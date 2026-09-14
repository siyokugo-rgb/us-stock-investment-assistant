package corporateaction.poc

import java.time.Instant
import java.time.LocalDate

/**
 * Provider / document 由来の raw split 観測（PoC）。
 *
 * - [providerSymbol] は external identifier。これから SecurityId を生成しない。
 * - SecurityId は持たない（ticker recycle / rename を無視した直結を避ける）。
 * - date / Instant フィールドは分離する。
 *
 * Instant 責務:
 * - [announcedAt]: issuer / exchange / provider が示す発表時刻（あっても knownAt ではない）
 * - [knownAt]: その split 情報を decision で利用可能だったことを根拠付きで確認できる最初の Instant
 * - [fetchedAt]: 本システム取得時刻
 *
 * [historicalKnownAtStatus] が UNRESOLVED のとき、知識PIT backtest 入力にしてはならない。
 * Provider が返していない field を想像で埋めない。
 */
data class RawSplitObservation(
    val provider: String,
    val providerSymbol: String,
    val eventId: String?,
    val actionType: SplitActionType,
    val ratio: SplitRatio,
    val announcedDate: LocalDate?,
    val announcedAt: Instant?,
    val effectiveDate: LocalDate?,
    val exDate: LocalDate?,
    val recordDate: LocalDate?,
    val fetchedAt: Instant,
    val knownAt: Instant?,
    val sourceDocumentId: String?,
    val rawPayloadSha256: String?,
    val historicalKnownAtStatus: HistoricalKnownAtStatus,
    val evidenceStatus: SplitEvidenceStatus,
) {
    init {
        require(provider.isNotBlank()) { "provider must not be blank" }
        require(providerSymbol.isNotBlank()) { "providerSymbol must not be blank" }
        when (actionType) {
            SplitActionType.STOCK_SPLIT ->
                require(ratio.isForwardSplit()) {
                    "STOCK_SPLIT requires newShares > oldShares (identity ratio rejected), got $ratio"
                }
            SplitActionType.REVERSE_SPLIT ->
                require(ratio.isReverseSplit()) {
                    "REVERSE_SPLIT requires newShares < oldShares (identity ratio rejected), got $ratio"
                }
        }
        when (historicalKnownAtStatus) {
            HistoricalKnownAtStatus.UNRESOLVED ->
                require(knownAt == null) {
                    "knownAt must be null when historicalKnownAtStatus is UNRESOLVED"
                }
            HistoricalKnownAtStatus.RESOLVED_WITH_EVIDENCE ->
                require(knownAt != null) {
                    "knownAt required when historicalKnownAtStatus is RESOLVED_WITH_EVIDENCE"
                }
        }
        // announcedAt / knownAt / fetchedAt are independent fields.
        // Do NOT auto-copy announcedAt → knownAt, or derive knownAt from dates / fetchedAt.
    }

    /** accounting application 用。effectiveDate が無ければ Fail-Closed。 */
    fun requireEffectiveDateForAccounting(): LocalDate =
        effectiveDate
            ?: throw IllegalStateException(
                "effectiveDate unknown; cannot apply split accounting " +
                    "(provider=$provider symbol=$providerSymbol eventId=$eventId)",
            )
}
