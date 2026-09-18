package archive.poc.binding

import java.time.Instant

/**
 * Cross-as-of Massive security/reference identity continuity evidence (synthetic-first PoC).
 *
 * Compares two same-source OBSERVED archives (Overview↔Overview or All Tickers↔All Tickers)
 * ordered by provider as-of `date=` from requestKey only.
 *
 * Not Security Master. Does **not** emit [security.SecurityId], [security.SecurityIdentifier],
 * knownAt, validFrom/validTo, DailyPrice, MIC/venue resolution, or TradingCurrencyEvidence.
 *
 * [evidenceEligibleAt] is derived **evidence availability** only
 * (`max(earlier, later) eligibility`) — not identity validity / knownAt / ticker validity.
 *
 * [SecurityIdentityContinuityStatus.CONTINUITY_CANDIDATE] ≠ proven Security continuity.
 * `share_class_figi` equality ≠ SecurityId.
 */
enum class SecurityIdentityContinuityStatus {
    CONTINUITY_CANDIDATE,
    TICKER_CHANGE_CANDIDATE,
    RECYCLE_CANDIDATE,
    UNRESOLVED,
    CONFLICT,
}

enum class SecurityIdentityContinuityReason {
    INPUT_NOT_OBSERVED,
    SOURCE_UNSUPPORTED,
    SOURCE_PAIR_MISMATCH,
    PROVIDER_AS_OF_MISSING,
    SAME_AS_OF_NOT_CROSS_TIME,
    SNAPSHOT_AMBIGUOUS,
    RAW_VALIDATION_FAILED,
    SHARE_CLASS_IDENTITY_MISSING,
    IDENTITY_LAYER_CONFLICT,
}

data class SecurityIdentityContinuityEvidence(
    val earlierArchiveId: String,
    val laterArchiveId: String,
    /** Massive archive source (ticker_overview or all_tickers). */
    val source: String,
    val earlierProviderTicker: String?,
    val laterProviderTicker: String?,
    /** Provider as-of from requestKey `date=` only; null if omitted. */
    val earlierProviderAsOfDate: String?,
    val laterProviderAsOfDate: String?,
    val earlierEligibilityBoundaryAt: Instant?,
    val laterEligibilityBoundaryAt: Instant?,
    /**
     * max(earlier, later) eligibility when both present.
     * Evidence availability bound only — ≠ identity validFrom/validTo/knownAt.
     */
    val evidenceEligibleAt: Instant?,
    val earlierShareClassFigi: String?,
    val laterShareClassFigi: String?,
    val earlierCompositeFigi: String?,
    val laterCompositeFigi: String?,
    /** Provider-declared primary listing exchange ISO-code evidence; ≠ MIC resolved. */
    val earlierPrimaryExchange: String?,
    val laterPrimaryExchange: String?,
    val status: SecurityIdentityContinuityStatus,
    val reason: SecurityIdentityContinuityReason?,
) {
    init {
        require(earlierArchiveId.isNotBlank())
        require(laterArchiveId.isNotBlank())
        require(source.isNotBlank())
        when (status) {
            SecurityIdentityContinuityStatus.CONTINUITY_CANDIDATE,
            SecurityIdentityContinuityStatus.TICKER_CHANGE_CANDIDATE,
            SecurityIdentityContinuityStatus.RECYCLE_CANDIDATE,
            -> {
                require(reason == null) { "$status must not carry reason" }
                require(earlierProviderAsOfDate != null && laterProviderAsOfDate != null)
                require(earlierProviderAsOfDate < laterProviderAsOfDate) {
                    "candidate requires earlierProviderAsOfDate < laterProviderAsOfDate"
                }
                require(earlierEligibilityBoundaryAt != null && laterEligibilityBoundaryAt != null)
                require(evidenceEligibleAt != null)
                val expected =
                    if (earlierEligibilityBoundaryAt!!.isAfter(laterEligibilityBoundaryAt!!)) {
                        earlierEligibilityBoundaryAt
                    } else {
                        laterEligibilityBoundaryAt
                    }
                require(evidenceEligibleAt == expected) {
                    "evidenceEligibleAt must equal max(earlier, later) eligibility"
                }
                require(!earlierShareClassFigi.isNullOrBlank())
                require(!laterShareClassFigi.isNullOrBlank())
            }
            SecurityIdentityContinuityStatus.UNRESOLVED,
            SecurityIdentityContinuityStatus.CONFLICT,
            -> {
                require(reason != null) { "$status requires reason" }
            }
        }
    }
}
