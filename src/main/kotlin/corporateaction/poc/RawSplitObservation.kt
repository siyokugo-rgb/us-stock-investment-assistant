package corporateaction.poc

import java.time.Instant
import java.time.LocalDate

/**
 * Provider / document 由来の raw split 観測（PoC）。
 *
 * - [providerSymbol] は external identifier。これから SecurityId を生成しない。
 * - [SecurityId] は持たない（ticker recycle / rename を無視した直結を避ける）。
 * - date フィールドは分離。effectiveDate を knownAt にしない。
 * - [historicalKnownAtStatus] が UNRESOLVED のとき、知識PIT backtest 入力にしてはならない。
 * - Provider が返していない field を想像で埋めない。
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
                require(ratio.isForwardSplit() || ratio.isIdentityRatio()) {
                    "STOCK_SPLIT requires newShares >= oldShares, got $ratio"
                }
            SplitActionType.REVERSE_SPLIT ->
                require(ratio.isReverseSplit()) {
                    "REVERSE_SPLIT requires newShares < oldShares, got $ratio"
                }
        }
        when (historicalKnownAtStatus) {
            HistoricalKnownAtStatus.UNRESOLVED ->
                require(announcedAt == null) {
                    "announcedAt must be null when historicalKnownAtStatus is UNRESOLVED " +
                        "(do not invent Instant from LocalDate)"
                }
            HistoricalKnownAtStatus.RESOLVED_WITH_EVIDENCE ->
                require(announcedAt != null) {
                    "announcedAt required when historicalKnownAtStatus is RESOLVED_WITH_EVIDENCE"
                }
        }
    }

    /** accounting application 用。effectiveDate が無ければ Fail-Closed。 */
    fun requireEffectiveDateForAccounting(): LocalDate =
        effectiveDate
            ?: throw IllegalStateException(
                "effectiveDate unknown; cannot apply split accounting " +
                    "(provider=$provider symbol=$providerSymbol eventId=$eventId)",
            )
}
