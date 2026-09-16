package archive.poc.massive

import archive.poc.ArchiveJson
import archive.poc.ArchiveValidationException
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class MassiveDailyAggsValidationOutcome(
    val okForObserved: Boolean,
    val observedFields: List<String>,
    val notes: String?,
)

/**
 * Fail-Closed validation for Massive Custom Bars possession (1 day, adjusted=false).
 *
 * HTTP 200 alone is not OBSERVED. Error envelopes, adjusted=true, empty results,
 * ticker mismatch, and OHLCV invariant failures must not be promoted.
 *
 * Does not assign SecurityId / currency / MIC / FIGI / knownAt.
 * Does not map to DailyPrice.
 * Does not treat `t` as historical knownAt or publication time.
 * Does not claim venue-specific or SIP volume in notes as solved.
 */
object MassiveDailyAggsArchiveValidator {
    fun validate(
        httpStatus: Int,
        bodyBytes: ByteArray,
        requestedTicker: String,
    ): MassiveDailyAggsValidationOutcome {
        if (bodyBytes.isEmpty()) return reject("empty body")
        if (httpStatus !in 200..299) {
            return reject("httpStatus=$httpStatus is not a success envelope for OBSERVED")
        }
        val text =
            try {
                decodeUtf8Strict(bodyBytes)
            } catch (_: Exception) {
                return reject("body is not valid UTF-8")
            }

        val root =
            try {
                ArchiveJson.parse(text).asObject("massive aggs")
            } catch (e: ArchiveValidationException) {
                return reject("malformed Massive JSON: ${e.message}")
            } catch (e: Exception) {
                return reject("malformed Massive JSON: ${e.message}")
            }

        val status =
            stringField(root, "status")
                ?: return reject("missing status; OBSERVED forbidden")
        if (status != "OK") {
            return reject("provider status=$status; OBSERVED forbidden")
        }

        val adjusted =
            boolField(root, "adjusted")
                ?: return reject("missing adjusted; OBSERVED forbidden")
        if (adjusted) {
            return reject("adjusted=true response; unadjusted archive OBSERVED forbidden")
        }

        val ticker =
            stringField(root, "ticker")
                ?: return reject("missing ticker; OBSERVED forbidden")
        if (ticker != requestedTicker) {
            return reject("ticker mismatch: response=$ticker requested=$requestedTicker")
        }

        // Pagination: PoC archives a single response only. next_url means incomplete coverage
        // of the requested range. Do not follow next_url; do not OBSERVE first page alone.
        when (val next = root.map["next_url"]) {
            null, is ArchiveJson.Null -> Unit
            is ArchiveJson.Str -> {
                // Blank and non-blank alike: presence of a string next_url is Fail-Closed.
                // Empty-string semantics are not guessed; incomplete single-response archive.
                return reject(
                    "next_url present; incomplete single-response archive " +
                        "(pagination required; next_url not followed)",
                )
            }
            else ->
                return reject(
                    "next_url present with unexpected type; incomplete single-response archive",
                )
        }

        val resultsNode =
            root.map["results"]
                ?: return reject("missing results; OBSERVED forbidden")
        val results =
            try {
                resultsNode.asArray("results")
            } catch (e: ArchiveValidationException) {
                return reject("results must be array: ${e.message}")
            }
        if (results.items.isEmpty()) {
            return reject("empty results; OBSERVED forbidden")
        }

        root.map["resultsCount"]?.let { node ->
            val count =
                intField(node)
                    ?: return reject("resultsCount must be integer")
            if (count != results.items.size) {
                return reject(
                    "resultsCount=$count does not match results.size=${results.items.size}",
                )
            }
        }

        val seenTs = linkedSetOf<Long>()
        var prevTs: Long? = null
        results.items.forEachIndexed { idx, item ->
            val row =
                try {
                    item.asObject("results[$idx]")
                } catch (e: ArchiveValidationException) {
                    return reject("results[$idx] must be object: ${e.message}")
                }
            val outcome = validateRow(row, idx, seenTs, prevTs)
            if (outcome != null) return outcome
            prevTs = longField(row, "t")!!
        }

        val fields =
            linkedSetOf(
                "status",
                "ticker",
                "adjusted",
                "results",
                "o",
                "h",
                "l",
                "c",
                "v",
                "t",
            )
        if (root.map.containsKey("queryCount")) fields += "queryCount"
        if (root.map.containsKey("resultsCount")) fields += "resultsCount"
        if (root.map.containsKey("request_id")) fields += "request_id"
        if (results.items.any { (it as? ArchiveJson.Obj)?.map?.containsKey("n") == true }) {
            fields += "n"
        }
        if (results.items.any { (it as? ArchiveJson.Obj)?.map?.containsKey("vw") == true }) {
            fields += "vw"
        }

        return MassiveDailyAggsValidationOutcome(
            okForObserved = true,
            observedFields = fields.toList().sorted(),
            notes =
                "massive Custom Bars 1d unadjusted aggregate ok; " +
                    "adjusted=false (split-unadjusted aggregate, not raw tape claim); " +
                    "t=windowStartMs≠knownAt; " +
                    "currency/MIC/FIGI/SecurityId/DailyPrice not generated; " +
                    "SIP/venue semantics remain CONDITIONAL/PARTIAL (not upgraded by archive PASS); " +
                    "providerTicker retained as provenance only (not externalIdentifier)",
        )
    }

    private fun validateRow(
        row: ArchiveJson.Obj,
        idx: Int,
        seenTs: MutableSet<Long>,
        prevTs: Long?,
    ): MassiveDailyAggsValidationOutcome? {
        val o = decimalField(row, "o") ?: return reject("results[$idx].o missing/invalid")
        val h = decimalField(row, "h") ?: return reject("results[$idx].h missing/invalid")
        val l = decimalField(row, "l") ?: return reject("results[$idx].l missing/invalid")
        val c = decimalField(row, "c") ?: return reject("results[$idx].c missing/invalid")
        val v = decimalField(row, "v") ?: return reject("results[$idx].v missing/invalid")
        val t = longField(row, "t") ?: return reject("results[$idx].t missing/invalid integer")

        if (!o.isFiniteNumber() || !h.isFiniteNumber() || !l.isFiniteNumber() ||
            !c.isFiniteNumber() || !v.isFiniteNumber()
        ) {
            return reject("results[$idx] non-finite OHLCV")
        }
        if (o.signum() < 0 || h.signum() < 0 || l.signum() < 0 || c.signum() < 0) {
            return reject("results[$idx] negative price")
        }
        if (v.signum() < 0) {
            return reject("results[$idx] negative volume")
        }
        if (h < l) {
            return reject("results[$idx] high < low")
        }
        if (o < l || o > h) {
            return reject("results[$idx] open outside high-low")
        }
        if (c < l || c > h) {
            return reject("results[$idx] close outside high-low")
        }
        if (t <= 0L) {
            return reject("results[$idx] t must be positive epoch millis")
        }
        if (!seenTs.add(t)) {
            return reject("results[$idx] duplicate timestamp t=$t")
        }
        if (prevTs != null && t <= prevTs) {
            return reject("results[$idx] timestamps not strictly ascending (sort=asc)")
        }
        // Optional n / vw: if present, must be finite and non-negative where numeric.
        row.map["n"]?.let { node ->
            val n =
                intField(node)
                    ?: return reject("results[$idx].n must be integer")
            if (n < 0) return reject("results[$idx].n negative")
        }
        row.map["vw"]?.let { node ->
            val vw =
                when (node) {
                    is ArchiveJson.Num -> decimalFromNum(node)
                    else -> null
                } ?: return reject("results[$idx].vw must be number")
            if (!vw.isFiniteNumber() || vw.signum() < 0) {
                return reject("results[$idx].vw invalid")
            }
        }
        return null
    }

    private fun stringField(
        obj: ArchiveJson.Obj,
        key: String,
    ): String? =
        when (val v = obj.map[key]) {
            is ArchiveJson.Str -> v.value
            else -> null
        }

    private fun boolField(
        obj: ArchiveJson.Obj,
        key: String,
    ): Boolean? =
        when (val v = obj.map[key]) {
            is ArchiveJson.Bool -> v.value
            else -> null
        }

    private fun decimalField(
        obj: ArchiveJson.Obj,
        key: String,
    ): BigDecimal? =
        when (val v = obj.map[key]) {
            is ArchiveJson.Num -> decimalFromNum(v)
            else -> null
        }

    private fun longField(
        obj: ArchiveJson.Obj,
        key: String,
    ): Long? =
        when (val v = obj.map[key]) {
            is ArchiveJson.Num -> {
                val raw = v.value
                if (raw.contains('.') || raw.contains('e', ignoreCase = true)) {
                    null
                } else {
                    raw.toLongOrNull()
                }
            }
            else -> null
        }

    private fun intField(node: ArchiveJson): Int? =
        when (node) {
            is ArchiveJson.Num -> {
                if (node.value.contains('.') || node.value.contains('e', ignoreCase = true)) {
                    null
                } else {
                    node.value.toIntOrNull()
                }
            }
            else -> null
        }

    private fun decimalFromNum(num: ArchiveJson.Num): BigDecimal? =
        try {
            BigDecimal(num.value)
        } catch (_: Exception) {
            null
        }

    private fun BigDecimal.isFiniteNumber(): Boolean =
        try {
            this.toDouble().let { !it.isNaN() && it.isFinite() }
        } catch (_: Exception) {
            false
        }

    private fun decodeUtf8Strict(bytes: ByteArray): String {
        val decoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun reject(notes: String) =
        MassiveDailyAggsValidationOutcome(
            okForObserved = false,
            observedFields = emptyList(),
            notes = notes,
        )
}
