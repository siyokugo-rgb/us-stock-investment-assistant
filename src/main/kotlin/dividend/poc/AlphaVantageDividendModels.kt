package dividend.poc

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Alpha Vantage DIVIDENDS の raw 行（PoC専用）。
 *
 * - provider symbol を保持する。SecurityId は持たない／生成しない。
 * - historical knownAt は証明できないためフィールドを置かない（declarationDate からの生成禁止）。
 * - currency / dividendType は DIVIDENDS 応答に無い。USD / REGULAR 等を推測・補完しない。
 * - [dividend.DividendEvent] への mapping は行わない。
 */
data class AlphaVantageDividendRawEvent(
    val providerSymbol: String,
    val exDividendDate: LocalDate,
    val declarationDate: LocalDate?,
    val recordDate: LocalDate?,
    val paymentDate: LocalDate?,
    val amount: BigDecimal,
    /** provider が返した optional 日付の生文字列（"None" 等の監査用。推測補完しない）。 */
    val declarationDateRaw: String?,
    val recordDateRaw: String?,
    val paymentDateRaw: String?,
    val amountRaw: String,
) {
    init {
        require(providerSymbol.isNotBlank()) { "providerSymbol must not be blank" }
        require(amount.signum() >= 0) { "amount must not be negative: $amount" }
        require(amountRaw.isNotBlank()) { "amountRaw must not be blank" }
    }
}

enum class HistoricalKnownAtStatus {
    /** row 単位の historical publication timestamp が証明できない */
    UNRESOLVED_UNUSABLE,
}

/**
 * DIVIDENDS 単独では価格通貨を取得できない。
 * ticker / 「US株らしい」見た目 / exchange 推測からの USD 補完は禁止。
 */
enum class CurrencyResolutionStatus {
    UNRESOLVED_FROM_DIVIDENDS,
}

/**
 * DIVIDENDS 応答に dividend type フィールドが無い。
 * REGULAR デフォルト推測は禁止。「情報が無い」≠ Provider が UNKNOWN と明示した。
 */
enum class DividendTypeResolutionStatus {
    UNRESOLVED_FROM_DIVIDENDS,
}

/**
 * SecurityId は provider symbol から生成しない。
 */
enum class SecurityIdMappingStatus {
    FORBIDDEN_FROM_PROVIDER_SYMBOL,
}

/**
 * DIVIDENDS 応答の PoC 表現。
 *
 * historicalKnownAt / currency / dividendType / SecurityId はいずれも
 * [dividend.DividendEvent] mapping の独立 blocker。
 */
data class AlphaVantageDividendRawSeries(
    val providerSymbol: String,
    val events: List<AlphaVantageDividendRawEvent>,
    val historicalKnownAtStatus: HistoricalKnownAtStatus =
        HistoricalKnownAtStatus.UNRESOLVED_UNUSABLE,
    val currencyResolutionStatus: CurrencyResolutionStatus =
        CurrencyResolutionStatus.UNRESOLVED_FROM_DIVIDENDS,
    val dividendTypeResolutionStatus: DividendTypeResolutionStatus =
        DividendTypeResolutionStatus.UNRESOLVED_FROM_DIVIDENDS,
    val securityIdMappingStatus: SecurityIdMappingStatus =
        SecurityIdMappingStatus.FORBIDDEN_FROM_PROVIDER_SYMBOL,
)

enum class ApiKeySource {
    ENVIRONMENT,
    DEMO,
}

/**
 * [fetchedAt] は保有PIT相当。historical knownAt ではない。
 */
data class AlphaVantageDividendFetchEvidence(
    val endpoint: String,
    val function: String,
    val requestedSymbol: String,
    val httpStatus: Int,
    val fetchedAt: Instant,
    val payloadSha256: String,
    val payloadBytes: Int,
    val apiKeySource: ApiKeySource,
)

data class AlphaVantageDividendFetchResult(
    val evidence: AlphaVantageDividendFetchEvidence,
    val series: AlphaVantageDividendRawSeries,
)
