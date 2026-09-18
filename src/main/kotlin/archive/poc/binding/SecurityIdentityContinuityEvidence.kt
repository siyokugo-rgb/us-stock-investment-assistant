package archive.poc.binding

import java.time.Instant

/**
 * Cross-as-of Massive security/reference identity continuity evidence (synthetic-first PoC).
 *
 * Compares two Massive archives (same-source Overview↔Overview or All Tickers↔All Tickers
 * for candidates). Field names are **first / second** presentation slots — they do **not**
 * claim temporal earlier/later unless [status] is a candidate (where deriver normalizes
 * first = earlier provider as-of, second = later).
 *
 * Not Security Master. Does **not** emit [security.SecurityId], [security.SecurityIdentifier],
 * knownAt, validFrom/validTo, DailyPrice, MIC/venue resolution, or TradingCurrencyEvidence.
 *
 * [evidenceEligibleAt] is derived **evidence availability** only
 * (`max(first, second) eligibility`) — not identity validity / knownAt / ticker validity.
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
    val firstArchiveId: String,
    val secondArchiveId: String,
    /** Massive archive source for the first input (ticker_overview or all_tickers). */
    val firstSource: String,
    /** Massive archive source for the second input. */
    val secondSource: String,
    val firstProviderTicker: String?,
    val secondProviderTicker: String?,
    /** Provider as-of from requestKey `date=` only; null if omitted. */
    val firstProviderAsOfDate: String?,
    val secondProviderAsOfDate: String?,
    val firstEligibilityBoundaryAt: Instant?,
    val secondEligibilityBoundaryAt: Instant?,
    /**
     * max(first, second) eligibility when both present.
     * Evidence availability bound only — ≠ identity validFrom/validTo/knownAt.
     */
    val evidenceEligibleAt: Instant?,
    val firstShareClassFigi: String?,
    val secondShareClassFigi: String?,
    val firstCompositeFigi: String?,
    val secondCompositeFigi: String?,
    /** Provider-declared primary listing exchange ISO-code evidence; ≠ MIC resolved. */
    val firstPrimaryExchange: String?,
    val secondPrimaryExchange: String?,
    val status: SecurityIdentityContinuityStatus,
    val reason: SecurityIdentityContinuityReason?,
) {
    init {
        require(firstArchiveId.isNotBlank())
        require(secondArchiveId.isNotBlank())
        require(firstSource.isNotBlank())
        require(secondSource.isNotBlank())
        when (status) {
            SecurityIdentityContinuityStatus.CONTINUITY_CANDIDATE -> {
                requireCandidateCommon()
                require(!firstProviderTicker.isNullOrBlank() && !secondProviderTicker.isNullOrBlank()) {
                    "CONTINUITY_CANDIDATE requires nonblank tickers"
                }
                require(firstProviderTicker == secondProviderTicker) {
                    "CONTINUITY_CANDIDATE requires matching tickers"
                }
                require(firstShareClassFigi == secondShareClassFigi) {
                    "CONTINUITY_CANDIDATE requires matching share_class_figi"
                }
            }
            SecurityIdentityContinuityStatus.TICKER_CHANGE_CANDIDATE -> {
                requireCandidateCommon()
                require(!firstProviderTicker.isNullOrBlank() && !secondProviderTicker.isNullOrBlank()) {
                    "TICKER_CHANGE_CANDIDATE requires nonblank tickers"
                }
                require(firstProviderTicker != secondProviderTicker) {
                    "TICKER_CHANGE_CANDIDATE requires differing tickers"
                }
                require(firstShareClassFigi == secondShareClassFigi) {
                    "TICKER_CHANGE_CANDIDATE requires matching share_class_figi"
                }
            }
            SecurityIdentityContinuityStatus.RECYCLE_CANDIDATE -> {
                requireCandidateCommon()
                require(!firstProviderTicker.isNullOrBlank() && !secondProviderTicker.isNullOrBlank()) {
                    "RECYCLE_CANDIDATE requires nonblank tickers"
                }
                require(firstProviderTicker == secondProviderTicker) {
                    "RECYCLE_CANDIDATE requires matching tickers"
                }
                require(firstShareClassFigi != secondShareClassFigi) {
                    "RECYCLE_CANDIDATE requires differing share_class_figi"
                }
                // Gate: same nonblank composite + differing share_class → CONFLICT, not RECYCLE.
                val c1 = firstCompositeFigi
                val c2 = secondCompositeFigi
                require(
                    c1.isNullOrBlank() ||
                        c2.isNullOrBlank() ||
                        c1 != c2,
                ) {
                    "RECYCLE_CANDIDATE forbidden when both composite_figi nonblank and equal " +
                        "with differing share_class (CONFLICT)"
                }
            }
            SecurityIdentityContinuityStatus.UNRESOLVED,
            SecurityIdentityContinuityStatus.CONFLICT,
            -> {
                require(reason != null) { "$status requires reason" }
            }
        }
    }

    private fun requireCandidateCommon() {
        require(reason == null) { "$status must not carry reason" }
        require(firstArchiveId != secondArchiveId) {
            "$status forbids identical archiveId on both sides"
        }
        require(firstSource == secondSource) {
            "$status requires same source on both sides"
        }
        require(firstProviderAsOfDate != null && secondProviderAsOfDate != null) {
            "$status requires both provider as-of dates"
        }
        require(firstProviderAsOfDate!! < secondProviderAsOfDate!!) {
            "$status requires firstProviderAsOfDate < secondProviderAsOfDate " +
                "(first=earlier, second=later by provider as-of only)"
        }
        require(!firstShareClassFigi.isNullOrBlank() && !secondShareClassFigi.isNullOrBlank()) {
            "$status requires nonblank share_class_figi on both sides"
        }
        require(firstEligibilityBoundaryAt != null && secondEligibilityBoundaryAt != null) {
            "$status requires both eligibilityBoundaryAt"
        }
        require(evidenceEligibleAt != null) { "$status requires evidenceEligibleAt" }
        val expected =
            if (firstEligibilityBoundaryAt!!.isAfter(secondEligibilityBoundaryAt!!)) {
                firstEligibilityBoundaryAt
            } else {
                secondEligibilityBoundaryAt
            }
        require(evidenceEligibleAt == expected) {
            "evidenceEligibleAt must equal max(first, second) eligibility"
        }
    }
}
