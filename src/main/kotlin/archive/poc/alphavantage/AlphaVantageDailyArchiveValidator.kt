package archive.poc.alphavantage

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import market.poc.AlphaVantageDailyParser
import market.poc.AlphaVantagePocException

data class AlphaVantageDailyValidationOutcome(
    val okForObserved: Boolean,
    val observedFields: List<String>,
    val notes: String?,
)

/**
 * Fail-Closed validation for Alpha Vantage TIME_SERIES_DAILY possession.
 *
 * HTTP 200 alone is not OBSERVED. Provider envelopes (Error Message / Information / Note)
 * and invalid series structure must not be promoted.
 *
 * Does not assign SecurityId. Does not invent knownAt / currency.
 * Does not map to DailyPrice.
 * Reuses [AlphaVantageDailyParser] OHLCV / envelope rules from the Price PoC.
 */
object AlphaVantageDailyArchiveValidator {
    fun validate(
        httpStatus: Int,
        bodyBytes: ByteArray,
        requestedSymbol: String,
    ): AlphaVantageDailyValidationOutcome {
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

        // Fast envelope detection (also enforced by parser). Explicit for archive notes.
        if (containsTopLevelKey(text, "Error Message")) {
            return reject("provider Error Message envelope; OBSERVED forbidden")
        }
        if (containsTopLevelKey(text, "Information")) {
            return reject("provider Information envelope; OBSERVED forbidden")
        }
        if (containsTopLevelKey(text, "Note")) {
            return reject("provider Note envelope; OBSERVED forbidden")
        }

        val series =
            try {
                AlphaVantageDailyParser.parse(text, requestedSymbol = requestedSymbol)
            } catch (e: AlphaVantagePocException) {
                return reject(e.message ?: "Alpha Vantage parse rejected")
            } catch (e: IllegalArgumentException) {
                // OHLCV invariant failures from AlphaVantageDailyRawBar
                return reject(e.message ?: "OHLCV invariant rejected")
            } catch (e: Exception) {
                return reject("malformed Alpha Vantage payload: ${e.message}")
            }

        val fields =
            linkedSetOf(
                "Meta Data",
                "Time Series (Daily)",
                "open",
                "high",
                "low",
                "close",
                "volume",
            )
        series.timeZoneRaw?.let { fields += "timeZone" }
        series.lastRefreshedRaw?.let { fields += "lastRefreshed" }

        return AlphaVantageDailyValidationOutcome(
            okForObserved = true,
            observedFields = fields.toList().sorted(),
            notes =
                "TIME_SERIES_DAILY structure ok; currency=UNRESOLVED_FROM_TIME_SERIES_DAILY; " +
                    "historicalKnownAt=UNRESOLVED_UNUSABLE; no DailyPrice mapping; " +
                    "providerSymbol retained as provenance only (not externalIdentifier)",
        )
    }

    /**
     * Top-level JSON key presence for AV provider envelopes.
     * Meta Data nested "1. Information" is not a top-level "Information" key
     * (quote before Information is absent there).
     */
    private fun containsTopLevelKey(
        jsonText: String,
        key: String,
    ): Boolean = jsonText.contains("\"$key\"")

    private fun decodeUtf8Strict(bytes: ByteArray): String {
        val decoder =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(ByteBuffer.wrap(bytes)).toString()
    }

    private fun reject(notes: String) =
        AlphaVantageDailyValidationOutcome(
            okForObserved = false,
            observedFields = emptyList(),
            notes = notes,
        )
}
