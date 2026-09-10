package market

import pit.Pit
import security.SecurityId
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * 1取引日の OHLC。effectiveAt は tradingDate。
 *
 * Adjusted close は持たない。
 * 将来 adjusted close を実際の売買価格として使用してはならない。
 */
data class DailyPrice(
    val securityId: SecurityId,
    val tradingDate: LocalDate,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: Long,
    val currency: String,
    val knownAt: Instant,
    val ingestedAt: Instant,
    val source: String,
) {
    init {
        require(currency.isNotBlank()) { "currency must not be blank" }
        require(source.isNotBlank()) { "source must not be blank" }
        require(volume >= 0L) { "volume must not be negative: $volume" }
        requireNonNegativePrice("open", open)
        requireNonNegativePrice("high", high)
        requireNonNegativePrice("low", low)
        requireNonNegativePrice("close", close)
        require(high.compareTo(low) >= 0) { "high ($high) must be >= low ($low)" }
        require(open.compareTo(low) >= 0 && open.compareTo(high) <= 0) {
            "open ($open) is outside low/high [$low, $high]"
        }
        require(close.compareTo(low) >= 0 && close.compareTo(high) <= 0) {
            "close ($close) is outside low/high [$low, $high]"
        }
        Pit.requireKnownBeforeOrAtIngested(knownAt, ingestedAt)
    }

    fun isAvailableAt(decisionAt: Instant): Boolean = Pit.isAvailableAt(knownAt, decisionAt)

    fun wasHeldBySystemAt(decisionAt: Instant): Boolean = Pit.wasHeldBySystemAt(ingestedAt, decisionAt)
}

private fun requireNonNegativePrice(name: String, value: BigDecimal) {
    require(value.signum() >= 0) { "$name must not be negative: $value" }
}
