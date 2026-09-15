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
 * Does not assign SecurityId. Does not invent knownAt.
 * Ticker-only does not become externalIdentifier.
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
        var figi: String? = null
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
            if (!(hasData || hasError || hasWarning)) {
                return reject("mapping[$idx] missing data|error|warning")
            }
            if (hasData) {
                fields += "data"
                val dataVal = obj.map["data"]
                if (dataVal is ArchiveJson.Arr) {
                    dataVal.items.forEach { row ->
                        val rowObj = row as? ArchiveJson.Obj ?: return@forEach
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
                        if (figi == null) {
                            val f = rowObj.map["figi"]
                            if (f is ArchiveJson.Str && f.value.isNotBlank()) {
                                figi = f.value
                            }
                        }
                    }
                }
            }
            if (hasError) fields += "error"
            if (hasWarning) fields += "warning"
        }

        return OpenFigiValidationOutcome(
            okForObserved = true,
            observedFields = fields.toList().sorted(),
            externalIdentifier = figi,
            externalIdentifierNamespace = figi?.let { "figi" },
            notes = null,
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
