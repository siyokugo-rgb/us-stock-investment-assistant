package archive.poc.massive

import archive.poc.ArchiveJson
import archive.poc.ArchiveValidationException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

data class MassiveAllTickersValidationOutcome(
    val okForObserved: Boolean,
    val observedFields: List<String>,
    val notes: String?,
    /** Present only when [okForObserved]; raw reference evidence — never domain adoption. */
    val resultCount: Int? = null,
    val tickers: List<String> = emptyList(),
    val currencyNames: List<String> = emptyList(),
    val currencySymbols: List<String> = emptyList(),
    val primaryExchanges: List<String> = emptyList(),
    val compositeFigis: List<String> = emptyList(),
    val shareClassFigis: List<String> = emptyList(),
    val actives: List<Boolean> = emptyList(),
    val delistedUtcs: List<String> = emptyList(),
    val lastUpdatedUtcs: List<String> = emptyList(),
)

/**
 * Fail-Closed validation for Massive Stocks All Tickers possession.
 *
 * HTTP 200 alone is not OBSERVED. Empty results, error envelopes, ticker mismatch,
 * wrong field types, and any string `next_url` must not be promoted.
 *
 * Empty `results` with complete HTTP body → REJECTED_VALIDATION (not MISSING):
 * design contract reserves MISSING for no-body possession; PRICE empty-results
 * uses the same REJECTED path.
 *
 * Does not assign SecurityId / DailyPrice.currency / MIC / venue / IssuerId / knownAt.
 * Does not normalize currency_symbol to uppercase ISO forms.
 * Does not map delisted_utc / last_updated_utc / request date to eligibility or knownAt.
 */
object MassiveAllTickersArchiveValidator {
    fun validate(
        httpStatus: Int,
        bodyBytes: ByteArray,
        requestedTicker: String?,
        requestedMarket: String = MassiveAllTickersArchiveClient.MARKET_STOCKS,
    ): MassiveAllTickersValidationOutcome {
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
                ArchiveJson.parse(text).asObject("massive all tickers")
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

        // Paginated endpoint: single-response PoC must Fail-Close when next_url is present.
        when (val next = root.map["next_url"]) {
            null, is ArchiveJson.Null -> Unit
            is ArchiveJson.Str ->
                return reject(
                    "next_url present; incomplete single-response archive " +
                        "(pagination required; next_url not followed; " +
                        "first page is not complete coverage)",
                )
            else ->
                return reject(
                    "next_url present with unexpected type; incomplete single-response archive",
                )
        }

        val resultsNode =
            root.map["results"]
                ?: return reject("missing results; OBSERVED forbidden")
        val resultsArr =
            try {
                resultsNode.asArray("results")
            } catch (e: ArchiveValidationException) {
                return reject("results must be array: ${e.message}")
            }
        val results = resultsArr.items

        if (results.isEmpty()) {
            // Complete body received → not MISSING (design: MISSING has no raw).
            return reject("empty results; OBSERVED forbidden")
        }

        val fields = linkedSetOf("status", "results")
        val tickers = mutableListOf<String>()
        val currencyNames = mutableListOf<String>()
        val currencySymbols = mutableListOf<String>()
        val primaryExchanges = mutableListOf<String>()
        val compositeFigis = mutableListOf<String>()
        val shareClassFigis = mutableListOf<String>()
        val actives = mutableListOf<Boolean>()
        val delistedUtcs = mutableListOf<String>()
        val lastUpdatedUtcs = mutableListOf<String>()

        for ((idx, item) in results.withIndex()) {
            val obj =
                try {
                    item.asObject("results[$idx]")
                } catch (e: ArchiveValidationException) {
                    return reject("results[$idx] must be object: ${e.message}")
                }

            val ticker =
                nonBlankString(obj, "ticker")
                    ?: return reject("results[$idx].ticker missing/blank; OBSERVED forbidden")
            if (requestedTicker != null && ticker != requestedTicker) {
                return reject(
                    "ticker mismatch: response=$ticker requested=$requestedTicker " +
                        "at results[$idx]",
                )
            }
            tickers += ticker
            fields += "ticker"

            when (val marketNode = obj.map["market"]) {
                null, is ArchiveJson.Null -> Unit
                is ArchiveJson.Str -> {
                    if (marketNode.value.isBlank()) {
                        return reject("results[$idx].market blank")
                    }
                    if (requestedMarket.isNotBlank() && marketNode.value != requestedMarket) {
                        return reject(
                            "results[$idx].market=${marketNode.value} " +
                                "does not match requested market=$requestedMarket",
                        )
                    }
                    fields += "market"
                }
                else -> return reject("results[$idx].market must be string when present")
            }

            when (val activeNode = obj.map["active"]) {
                null, is ArchiveJson.Null -> Unit
                is ArchiveJson.Bool -> {
                    actives += activeNode.value
                    fields += "active"
                }
                else -> return reject("results[$idx].active must be boolean when present")
            }

            readOptionalNonBlank(obj, idx, "currency_name")?.let {
                currencyNames += it
                fields += "currency_name"
            } ?: optionalTypeOrBlankReject(obj, idx, "currency_name")?.let { return it }

            readOptionalNonBlank(obj, idx, "currency_symbol")?.let {
                // Official: ISO 4217 code — keep provider raw value; no uppercase normalize.
                currencySymbols += it
                fields += "currency_symbol"
            } ?: optionalTypeOrBlankReject(obj, idx, "currency_symbol")?.let { return it }

            readOptionalNonBlank(obj, idx, "primary_exchange")?.let {
                primaryExchanges += it
                fields += "primary_exchange"
            } ?: optionalTypeOrBlankReject(obj, idx, "primary_exchange")?.let { return it }

            readOptionalNonBlank(obj, idx, "composite_figi")?.let {
                compositeFigis += it
                fields += "composite_figi"
            } ?: optionalTypeOrBlankReject(obj, idx, "composite_figi")?.let { return it }

            readOptionalNonBlank(obj, idx, "share_class_figi")?.let {
                shareClassFigis += it
                fields += "share_class_figi"
            } ?: optionalTypeOrBlankReject(obj, idx, "share_class_figi")?.let { return it }

            readOptionalNonBlank(obj, idx, "name")?.let { fields += "name" }
                ?: optionalTypeOrBlankReject(obj, idx, "name")?.let { return it }
            readOptionalNonBlank(obj, idx, "locale")?.let { fields += "locale" }
                ?: optionalTypeOrBlankReject(obj, idx, "locale")?.let { return it }
            readOptionalNonBlank(obj, idx, "type")?.let { fields += "type" }
                ?: optionalTypeOrBlankReject(obj, idx, "type")?.let { return it }
            readOptionalNonBlank(obj, idx, "cik")?.let { fields += "cik" }
                ?: optionalTypeOrBlankReject(obj, idx, "cik")?.let { return it }

            when (val delisted = obj.map["delisted_utc"]) {
                null, is ArchiveJson.Null -> Unit
                is ArchiveJson.Str -> {
                    if (delisted.value.isBlank()) {
                        return reject("results[$idx].delisted_utc blank")
                    }
                    if (!UTC_HINT.matches(delisted.value)) {
                        return reject("results[$idx].delisted_utc malformed timestamp string")
                    }
                    delistedUtcs += delisted.value
                    fields += "delisted_utc"
                }
                else -> return reject("results[$idx].delisted_utc must be string when present")
            }

            when (val lastUpdated = obj.map["last_updated_utc"]) {
                null, is ArchiveJson.Null -> Unit
                is ArchiveJson.Str -> {
                    if (lastUpdated.value.isBlank()) {
                        return reject("results[$idx].last_updated_utc blank")
                    }
                    if (!UTC_HINT.matches(lastUpdated.value)) {
                        return reject(
                            "results[$idx].last_updated_utc malformed timestamp string",
                        )
                    }
                    lastUpdatedUtcs += lastUpdated.value
                    fields += "last_updated_utc"
                }
                else ->
                    return reject("results[$idx].last_updated_utc must be string when present")
            }
        }

        if (root.map.containsKey("request_id")) fields += "request_id"
        if (root.map.containsKey("count")) fields += "count"

        return MassiveAllTickersValidationOutcome(
            okForObserved = true,
            observedFields = fields.toList().sorted(),
            notes =
                "massive All Tickers ok; raw reference evidence only; " +
                    "currency_symbol≠DailyPrice.currency (ISO 4217 raw only; no normalize); " +
                    "currency_name≠DailyPrice.currency; " +
                    "primary_exchange≠venue resolved / MIC / SecurityId; " +
                    "composite_figi/share_class_figi≠SecurityId; " +
                    "cik≠IssuerId; ticker≠SecurityId; " +
                    "delisted_utc/last_updated_utc≠knownAt/eligibility; " +
                    "date query≠knownAt/eligibility; " +
                    "externalIdentifier not auto-set; " +
                    "pagination not followed; first page≠complete universe coverage; " +
                    "Trading Currency / DailyPrice / SecurityId not solved",
            resultCount = results.size,
            tickers = tickers,
            currencyNames = currencyNames,
            currencySymbols = currencySymbols,
            primaryExchanges = primaryExchanges,
            compositeFigis = compositeFigis,
            shareClassFigis = shareClassFigis,
            actives = actives,
            delistedUtcs = delistedUtcs,
            lastUpdatedUtcs = lastUpdatedUtcs,
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

    private fun readOptionalNonBlank(
        obj: ArchiveJson.Obj,
        idx: Int,
        key: String,
    ): String? =
        when (val v = obj.map[key]) {
            is ArchiveJson.Str -> v.value.takeIf { it.isNotBlank() }
            else -> null
        }

    private fun optionalTypeOrBlankReject(
        obj: ArchiveJson.Obj,
        idx: Int,
        key: String,
    ): MassiveAllTickersValidationOutcome? {
        return when (val v = obj.map[key]) {
            null, is ArchiveJson.Null -> null
            is ArchiveJson.Str ->
                if (v.value.isBlank()) {
                    reject("results[$idx].$key blank string; OBSERVED forbidden")
                } else {
                    null
                }
            else -> reject("results[$idx].$key must be string when present")
        }
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
        MassiveAllTickersValidationOutcome(
            okForObserved = false,
            observedFields = emptyList(),
            notes = notes,
        )

    private val UTC_HINT =
        Regex(
            "^\\d{4}-\\d{2}-\\d{2}([T ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:?\\d{2})?)?$",
        )
}
