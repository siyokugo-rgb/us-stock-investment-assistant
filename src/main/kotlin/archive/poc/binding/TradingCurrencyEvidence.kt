package archive.poc.binding

import java.time.Instant

/**
 * Archive-level Massive PRICE ↔ All Tickers Trading Currency evidence candidate.
 *
 * Pure derived PoC — not DailyPrice.currency, SecurityId, MIC/venue, bar-level currency,
 * or historical knownAt.
 *
 * Required sources: PRICE + All Tickers only.
 * Overview is out of scope for this deriver (optional corroboration deferred).
 *
 * [temporalApplicability] remains [TradingCurrencyTemporalApplicability.UNRESOLVED]
 * even for [TradingCurrencyEvidenceStatus.CANDIDATE]:
 * CANDIDATE ≠ bar-level / DailyPrice.currency usable.
 */
enum class TradingCurrencyEvidenceStatus {
    /** Archive-level validated ISO trading currency candidate (Layer B). */
    CANDIDATE,

    /** Missing / invalid provenance or currency code; not usable. */
    INELIGIBLE,

    /**
     * Reserved for same compatible as-of conflicting validated codes.
     * Pair-wise derive may not emit yet; latest-wins forbidden.
     */
    CONFLICT,
}

enum class TradingCurrencyEvidenceReason {
    PRICE_NOT_OBSERVED,
    ALL_TICKERS_NOT_OBSERVED,
    PRICE_ELIGIBILITY_MISSING,
    ALL_TICKERS_ELIGIBILITY_MISSING,
    PRICE_REQUEST_KEY_INVALID,
    ALL_TICKERS_REQUEST_KEY_INVALID,
    SOURCE_MISMATCH,
    TICKER_MISMATCH,
    RAW_INTEGRITY_INVALID,
    CURRENCY_MISSING,
    CURRENCY_CODE_INVALID,
    TEMPORAL_CONFLICT,
    CURRENCY_CONFLICT,
}

/**
 * Single-value temporal applicability for archive-level trading currency evidence.
 * Do not expand into a large enum in this PoC.
 */
enum class TradingCurrencyTemporalApplicability {
    /**
     * Evidence is an archive-level Forward candidate only.
     * Not attributable to PRICE bars / DailyPrice.currency without a later Gate.
     */
    UNRESOLVED,
}

data class TradingCurrencyEvidence(
    val priceArchiveId: String,
    val allTickersArchiveId: String,
    val provider: String,
    /** Canonical Massive ticker parsed from PRICE requestKey only — never caller free-form. */
    val providerTicker: String,
    /** Exact All Tickers `currency_symbol` raw (provider bytes decoded); never repaired. */
    val rawCurrencySymbol: String?,
    /**
     * Present only on CANDIDATE. Invariant: equals [rawCurrencySymbol]
     * (no lowercase→uppercase repair).
     */
    val canonicalCurrencyCode: String?,
    val priceEligibilityBoundaryAt: Instant?,
    val allTickersEligibilityBoundaryAt: Instant?,
    /**
     * max(price, allTickers) eligibility — evidence usable time.
     * ≠ allTickersRequestDate / knownAt / bar trading date.
     */
    val evidenceEligibleAt: Instant?,
    /** Provider as-of selector from All Tickers requestKey; null if date omitted. */
    val allTickersRequestDate: String?,
    val temporalApplicability: TradingCurrencyTemporalApplicability,
    val status: TradingCurrencyEvidenceStatus,
    val reason: TradingCurrencyEvidenceReason?,
) {
    init {
        require(priceArchiveId.isNotBlank())
        require(allTickersArchiveId.isNotBlank())
        require(provider.isNotBlank())
        require(providerTicker.isNotBlank())
        when (status) {
            TradingCurrencyEvidenceStatus.CANDIDATE -> {
                require(reason == null) { "CANDIDATE must not carry reason" }
                require(provider == PROVIDER_MASSIVE) { "CANDIDATE requires provider=Massive" }
                require(!rawCurrencySymbol.isNullOrBlank()) {
                    "CANDIDATE requires nonblank rawCurrencySymbol"
                }
                require(CANONICAL_ISO_ALPHA.matches(rawCurrencySymbol!!)) {
                    "CANDIDATE rawCurrencySymbol must match ^[A-Z]{3}$"
                }
                require(Iso4217AlphabeticCodes.isAlphabeticMember(rawCurrencySymbol)) {
                    "CANDIDATE rawCurrencySymbol must be an ISO 4217 alphabetic member " +
                        "(model invariant; not Deriver-only)"
                }
                require(canonicalCurrencyCode == rawCurrencySymbol) {
                    "CANDIDATE canonicalCurrencyCode must equal rawCurrencySymbol " +
                        "(no normalize/repair)"
                }
                require(priceEligibilityBoundaryAt != null) {
                    "CANDIDATE requires priceEligibilityBoundaryAt"
                }
                require(allTickersEligibilityBoundaryAt != null) {
                    "CANDIDATE requires allTickersEligibilityBoundaryAt"
                }
                require(evidenceEligibleAt != null) { "CANDIDATE requires evidenceEligibleAt" }
                val expectedMax =
                    maxInstant(priceEligibilityBoundaryAt!!, allTickersEligibilityBoundaryAt!!)
                require(evidenceEligibleAt == expectedMax) {
                    "CANDIDATE evidenceEligibleAt must equal max(price, allTickers) eligibility"
                }
                require(
                    temporalApplicability == TradingCurrencyTemporalApplicability.UNRESOLVED,
                ) {
                    "CANDIDATE must keep temporalApplicability=UNRESOLVED"
                }
            }
            TradingCurrencyEvidenceStatus.INELIGIBLE,
            TradingCurrencyEvidenceStatus.CONFLICT,
            -> {
                require(reason != null) { "$status requires reason" }
            }
        }
    }

    companion object {
        const val PROVIDER_MASSIVE = "Massive"
        val CANONICAL_ISO_ALPHA = Regex("^[A-Z]{3}$")

        fun maxInstant(
            a: Instant,
            b: Instant,
        ): Instant = if (a.isAfter(b)) a else b
    }
}
