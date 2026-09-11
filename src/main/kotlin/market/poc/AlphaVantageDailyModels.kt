package market.poc

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Alpha Vantage TIME_SERIES_DAILY の raw OHLCV 行（PoC専用）。
 *
 * - provider symbol を保持する。SecurityId は持たない／生成しない。
 * - historical knownAt は証明できないためフィールドを置かない（生成禁止）。
 */
data class AlphaVantageDailyRawBar(
    val providerSymbol: String,
    val tradingDate: LocalDate,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: Long,
) {
    init {
        require(providerSymbol.isNotBlank()) { "providerSymbol must not be blank" }
        require(volume >= 0L) { "volume must not be negative: $volume" }
        requireNonNegative("open", open)
        requireNonNegative("high", high)
        requireNonNegative("low", low)
        requireNonNegative("close", close)
        require(high.compareTo(low) >= 0) { "high ($high) must be >= low ($low)" }
        require(open.compareTo(low) >= 0 && open.compareTo(high) <= 0) {
            "open ($open) is outside low/high [$low, $high]"
        }
        require(close.compareTo(low) >= 0 && close.compareTo(high) <= 0) {
            "close ($close) is outside low/high [$low, $high]"
        }
    }
}

private fun requireNonNegative(
    name: String,
    value: BigDecimal,
) {
    require(value.signum() >= 0) { "$name must not be negative: $value" }
}

enum class HistoricalKnownAtStatus {
    /** row 単位の historical publication timestamp が証明できない */
    UNRESOLVED_UNUSABLE,
}

/**
 * TIME_SERIES_DAILY 応答の PoC 表現。
 *
 * historicalKnownAtStatus は常に [HistoricalKnownAtStatus.UNRESOLVED_UNUSABLE]
 * （row 単位の publication timestamp が response に存在しない）。
 */
data class AlphaVantageDailyRawSeries(
    val providerSymbol: String,
    val information: String?,
    val lastRefreshedRaw: String?,
    val outputSizeRaw: String?,
    val timeZoneRaw: String?,
    val bars: List<AlphaVantageDailyRawBar>,
    val historicalKnownAtStatus: HistoricalKnownAtStatus =
        HistoricalKnownAtStatus.UNRESOLVED_UNUSABLE,
)

enum class ApiKeySource {
    ENVIRONMENT,
    DEMO,
}

/**
 * [fetchedAt] は保有PIT相当。historical knownAt ではない。
 */
data class AlphaVantageFetchEvidence(
    val endpoint: String,
    val function: String,
    val requestedSymbol: String,
    val httpStatus: Int,
    val fetchedAt: Instant,
    val payloadSha256: String,
    val payloadBytes: Int,
    val apiKeySource: ApiKeySource,
)

data class AlphaVantageDailyFetchResult(
    val evidence: AlphaVantageFetchEvidence,
    val series: AlphaVantageDailyRawSeries,
)
