package archive.poc.openfigi

import archive.poc.ArchiveJson
import archive.poc.ArchiveValidationException
import java.nio.charset.StandardCharsets

data class OpenFigiValidationOutcome(
    val okForObserved: Boolean,
    val observedFields: List<String>,
    val externalIdentifier: String?,
    val externalIdentifierNamespace: String?,
    val notes: String?,
)

/**
 * Minimal Fail-Closed validation for OpenFIGI mapping possession.
 *
 * HTTP 200 alone is not OBSERVED. Provider-level mapping failure envelopes
 * (error / warning-only / absent-or-invalid data) must not be promoted.
 *
 * Does not assign SecurityId. Does not invent knownAt.
 * Does not auto-pick the first FIGI among candidates.
 */
object OpenFigiMappingValidator {
    fun validate(
        httpStatus: Int,
        bodyBytes: ByteArray,
        requestJobCount: Int,
    ): OpenFigiValidationOutcome {
        if (bodyBytes.isEmpty()) return reject("empty body")
        if (httpStatus !in 200..299) {
            return reject("httpStatus=$httpStatus is not a success envelope for OBSERVED")
        }
        val text =
            try {
                String(bodyBytes, StandardCharsets.UTF_8)
            } catch (_: Exception) {
                return reject("body is not UTF-8")
            }
        val root =
            try {
                ArchiveJson.parse(text)
            } catch (e: Exception) {
                return reject("malformed JSON: ${e.message}")
            }
        val arr =
            try {
                root.asArray("openfigi.mapping")
            } catch (e: ArchiveValidationException) {
                return reject(e.message ?: "expected array")
            }
        if (arr.items.size != requestJobCount) {
            return reject(
                "response job count ${arr.items.size} != request job count $requestJobCount",
            )
        }

        val fields = linkedSetOf<String>()
        val figis = mutableListOf<String>()
        var dataCandidateCount = 0

        arr.items.forEachIndexed { idx, item ->
            val obj =
                try {
                    item.asObject("openfigi.mapping[$idx]")
                } catch (e: ArchiveValidationException) {
                    return reject(e.message ?: "expected object")
                }
            val keys = obj.map.keys
            val hasData = "data" in keys
            val hasError = "error" in keys
            val hasWarning = "warning" in keys

            if (hasError) {
                fields += "error"
                return reject("mapping[$idx] contains error; OBSERVED forbidden")
            }
            if (!(hasData || hasWarning)) {
                return reject("mapping[$idx] missing data|error|warning")
            }
            if (hasWarning) fields += "warning"

            if (!hasData) {
                // warning-only / data absent
                return reject("mapping[$idx] data absent; OBSERVED forbidden")
            }
            fields += "data"
            val dataVal = obj.map["data"]
            if (dataVal !is ArchiveJson.Arr) {
                return reject("mapping[$idx].data must be array")
            }
            if (dataVal.items.isEmpty()) {
                return reject("mapping[$idx].data array is empty")
            }
            // Official docs: warning means no FIGI found. warning+data coexistence is not
            // confirmed safe → Fail-Closed.
            if (hasWarning) {
                return reject("mapping[$idx] has warning with data; Fail-Closed")
            }

            dataVal.items.forEachIndexed { rowIdx, row ->
                val rowObj =
                    row as? ArchiveJson.Obj
                        ?: return reject("mapping[$idx].data[$rowIdx] must be object")
                dataCandidateCount += 1
                listOf(
                    "figi",
                    "compositeFIGI",
                    "shareClassFIGI",
                    "ticker",
                    "exchCode",
                    "securityType",
                    "marketSector",
                    "name",
                ).forEach { k ->
                    if (k in rowObj.map) fields += k
                }
                val f = rowObj.map["figi"]
                if (f is ArchiveJson.Str && f.value.isNotBlank()) {
                    figis += f.value
                }
            }
        }

        val uniqueExternal =
            if (
                requestJobCount == 1 &&
                    arr.items.size == 1 &&
                    dataCandidateCount == 1 &&
                    figis.size == 1
            ) {
                figis.single()
            } else {
                null
            }

        return OpenFigiValidationOutcome(
            okForObserved = true,
            observedFields = fields.toList().sorted(),
            externalIdentifier = uniqueExternal,
            externalIdentifierNamespace = uniqueExternal?.let { "figi" },
            notes =
                if (uniqueExternal == null && figis.isNotEmpty()) {
                    "figi ambiguity or multi-candidate; externalIdentifier left null"
                } else {
                    null
                },
        )
    }

    private fun reject(notes: String) =
        OpenFigiValidationOutcome(
            okForObserved = false,
            observedFields = emptyList(),
            externalIdentifier = null,
            externalIdentifierNamespace = null,
            notes = notes,
        )
}
