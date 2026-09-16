package archive.poc.massive

import archive.poc.ArchiveJson
import archive.poc.ArchiveValidationException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class MassiveTickerOverviewValidationOutcome(
    val okForObserved: Boolean,
    val observedFields: List<String>,
    val notes: String?,
    /** Present only when [okForObserved]; raw reference evidence — never domain adoption. */
    val validatedTicker: String? = null,
    val currencyName: String? = null,
    val primaryExchange: String? = null,
    val compositeFigi: String? = null,
    val shareClassFigi: String? = null,
    val active: Boolean? = null,
)

/**
 * Fail-Closed validation for Massive Stocks Ticker Overview possession.
 *
 * HTTP 200 alone is not OBSERVED. Error envelopes, ticker mismatch, wrong field types,
 * and unexpected pagination indicators must not be promoted.
 *
 * Does not assign SecurityId / DailyPrice.currency / MIC / venue / IssuerId / knownAt.
 * Does not elevate currency_name / primary_exchange / FIGI / CIK to internal identity.
 * Does not map list_date / delisted_utc / last_updated_utc to validity or knownAt.
 * Request query `date` (provider as-of selector) is not interpreted as knowledge-PIT or eligibility.
 */
object MassiveTickerOverviewArchiveValidator {
    fun validate(
        httpStatus: Int,
        bodyBytes: ByteArray,
        requestedTicker: String,
    ): MassiveTickerOverviewValidationOutcome {
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
                ArchiveJson.parse(text).asObject("massive ticker overview")
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

        // Official docs: single-object endpoint; no pagination. If next_url appears, Fail-Closed.
        when (val next = root.map["next_url"]) {
            null, is ArchiveJson.Null -> Unit
            is ArchiveJson.Str ->
                return reject(
                    "next_url present; incomplete single-response archive " +
                        "(pagination required; next_url not followed)",
                )
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
                resultsNode.asObject("results")
            } catch (e: ArchiveValidationException) {
                return reject("results must be object: ${e.message}")
            }

        val ticker =
            nonBlankString(results, "ticker")
                ?: return reject("results.ticker missing/blank; OBSERVED forbidden")
        if (ticker != requestedTicker) {
            return reject("ticker mismatch: response=$ticker requested=$requestedTicker")
        }

        val fields = linkedSetOf("status", "results", "ticker")

        // Optional audited fields: type-check only; never promote to domain identity.
        optionalNonBlankString(results, "name")?.let { fields += "name" }
            ?: optionalRejectIfWrongType(results, "name", expectString = true)?.let { return it }
        optionalNonBlankString(results, "market")?.let { fields += "market" }
            ?: optionalRejectIfWrongType(results, "market", expectString = true)?.let { return it }
        optionalNonBlankString(results, "locale")?.let { fields += "locale" }
            ?: optionalRejectIfWrongType(results, "locale", expectString = true)?.let { return it }
        optionalNonBlankString(results, "type")?.let { fields += "type" }
            ?: optionalRejectIfWrongType(results, "type", expectString = true)?.let { return it }

        when (val activeNode = results.map["active"]) {
            null, is ArchiveJson.Null -> Unit
            is ArchiveJson.Bool -> fields += "active"
            else -> return reject("results.active must be boolean when present")
        }

        val primaryExchange =
            optionalNonBlankString(results, "primary_exchange")?.also { fields += "primary_exchange" }
                ?: run {
                    optionalRejectIfWrongType(results, "primary_exchange", expectString = true)?.let {
                        return it
                    }
                    blankStringReject(results, "primary_exchange")?.let { return it }
                    null
                }

        val currencyName =
            optionalNonBlankString(results, "currency_name")?.also { fields += "currency_name" }
                ?: run {
                    optionalRejectIfWrongType(results, "currency_name", expectString = true)?.let {
                        return it
                    }
                    blankStringReject(results, "currency_name")?.let { return it }
                    null
                }

        val compositeFigi =
            optionalNonBlankString(results, "composite_figi")?.also { fields += "composite_figi" }
                ?: run {
                    optionalRejectIfWrongType(results, "composite_figi", expectString = true)?.let {
                        return it
                    }
                    blankStringReject(results, "composite_figi")?.let { return it }
                    null
                }

        val shareClassFigi =
            optionalNonBlankString(results, "share_class_figi")?.also { fields += "share_class_figi" }
                ?: run {
                    optionalRejectIfWrongType(results, "share_class_figi", expectString = true)?.let {
                        return it
                    }
                    blankStringReject(results, "share_class_figi")?.let { return it }
                    null
                }

        optionalNonBlankString(results, "cik")?.let { fields += "cik" }
            ?: optionalRejectIfWrongType(results, "cik", expectString = true)?.let { return it }
            ?: blankStringReject(results, "cik")?.let { return it }

        when (val listDate = results.map["list_date"]) {
            null, is ArchiveJson.Null -> Unit
            is ArchiveJson.Str -> {
                if (listDate.value.isBlank()) {
                    return reject("results.list_date blank")
                }
                if (!LIST_DATE.matches(listDate.value)) {
                    return reject("results.list_date must be YYYY-MM-DD when present")
                }
                fields += "list_date"
            }
            else -> return reject("results.list_date must be string when present")
        }

        for (tsField in listOf("delisted_utc", "last_updated_utc")) {
            when (val node = results.map[tsField]) {
                null, is ArchiveJson.Null -> Unit
                is ArchiveJson.Str -> {
                    if (node.value.isBlank()) {
                        return reject("results.$tsField blank")
                    }
                    // Format check only — never promote to knownAt / eligibility / validFrom/To.
                    if (!UTC_HINT.matches(node.value)) {
                        return reject("results.$tsField malformed timestamp string")
                    }
                    fields += tsField
                }
                else -> return reject("results.$tsField must be string when present")
            }
        }

        if (root.map.containsKey("request_id")) fields += "request_id"
        if (root.map.containsKey("count")) fields += "count"

        val activeValue =
            when (val a = results.map["active"]) {
                is ArchiveJson.Bool -> a.value
                else -> null
            }

        return MassiveTickerOverviewValidationOutcome(
            okForObserved = true,
            observedFields = fields.toList().sorted(),
            notes =
                "massive Ticker Overview ok; raw reference evidence only; " +
                    "currency_name≠DailyPrice.currency; " +
                    "primary_exchange≠venue resolved / MIC / SecurityId; " +
                    "composite_figi/share_class_figi≠SecurityId; " +
                    "cik≠IssuerId; ticker≠SecurityId; " +
                    "list_date/delisted_utc/last_updated_utc≠knownAt/eligibility; " +
                    "externalIdentifier not auto-set; " +
                    "PRICE↔Overview join not performed",
            validatedTicker = ticker,
            currencyName = currencyName,
            primaryExchange = primaryExchange,
            compositeFigi = compositeFigi,
            shareClassFigi = shareClassFigi,
            active = activeValue,
        )
    }

    private fun stringField(
        obj: ArchiveJson.Obj,
        key: String,
    ): String? =
        when (val v = obj.map[key]) {
            is ArchiveJson.Str -> v.value
            else -> null
        }

    private fun nonBlankString(
        obj: ArchiveJson.Obj,
        key: String,
    ): String? = stringField(obj, key)?.takeIf { it.isNotBlank() }

    private fun optionalNonBlankString(
        obj: ArchiveJson.Obj,
        key: String,
    ): String? =
        when (val v = obj.map[key]) {
            is ArchiveJson.Str -> v.value.takeIf { it.isNotBlank() }
            else -> null
        }

    private fun blankStringReject(
        obj: ArchiveJson.Obj,
        key: String,
    ): MassiveTickerOverviewValidationOutcome? =
        when (val v = obj.map[key]) {
            is ArchiveJson.Str ->
                if (v.value.isBlank()) {
                    reject("results.$key blank string; OBSERVED forbidden")
                } else {
                    null
                }
            else -> null
        }

    private fun optionalRejectIfWrongType(
        obj: ArchiveJson.Obj,
        key: String,
        expectString: Boolean,
    ): MassiveTickerOverviewValidationOutcome? {
        val v = obj.map[key] ?: return null
        if (v is ArchiveJson.Null) return null
        if (expectString && v !is ArchiveJson.Str) {
            return reject("results.$key must be string when present")
        }
        return null
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
        MassiveTickerOverviewValidationOutcome(
            okForObserved = false,
            observedFields = emptyList(),
            notes = notes,
        )

    private val LIST_DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    // Accept ISO-8601-ish UTC strings without inventing Instant/knownAt.
    private val UTC_HINT =
        Regex(
            "^\\d{4}-\\d{2}-\\d{2}([T ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:?\\d{2})?)?$",
        )
}
