package archive.poc.binding

import java.time.Instant

/**
 * Archive-level Massive PRICE ↔ Ticker Overview same-vendor provenance binding evidence.
 *
 * Pure derived PoC — not SecurityId, DailyPrice, Trading Currency adoption, MIC/venue
 * resolution, bar-level historical identity, or historical knownAt.
 *
 * [referenceTemporalApplicability] remains [MassiveReferenceTemporalApplicability.UNRESOLVED]
 * even for [MassivePriceReferenceBindingStatus.CANDIDATE]: provenance relation ≠ reference
 * fields applicable to PRICE bars/semantics.
 */
enum class MassivePriceReferenceBindingStatus {
    /** Same Massive ticker namespace archive-level provenance candidate. */
    CANDIDATE,

    /** Missing / mismatched provenance; not usable. */
    INELIGIBLE,

    /** Explicit identity-bearing conflict (reserved; pair-wise derive may not emit yet). */
    CONFLICT,
}

enum class MassivePriceReferenceBindingReason {
    PRICE_NOT_OBSERVED,
    OVERVIEW_NOT_OBSERVED,
    PRICE_ELIGIBILITY_MISSING,
    OVERVIEW_ELIGIBILITY_MISSING,
    PRICE_REQUEST_KEY_INVALID,
    OVERVIEW_REQUEST_KEY_INVALID,
    TICKER_MISMATCH,
    OVERVIEW_RAW_INVALID,
    SOURCE_MISMATCH,
    TEMPORAL_CONFLICT,
}

/**
 * Single-value temporal applicability for carried Overview reference fields.
 * Do not expand into a large enum in this PoC.
 */
enum class MassiveReferenceTemporalApplicability {
    /**
     * Reference fields are raw evidence carried by the binding only.
     * Not applicable to PRICE bars / DailyPrice / venue resolution without a later Gate.
     */
    UNRESOLVED,
}

data class MassivePriceReferenceBindingEvidence(
    val priceArchiveId: String,
    val overviewArchiveId: String,
    val provider: String,
    /** Canonical Massive ticker parsed from PRICE requestKey only — never caller free-form. */
    val providerTicker: String,
    val priceEligibilityBoundaryAt: Instant?,
    val overviewEligibilityBoundaryAt: Instant?,
    /**
     * max(price, overview) eligibility — binding evidence usable time.
     * ≠ overviewRequestDate / knownAt / bar trading date.
     */
    val bindingEligibleAt: Instant?,
    /** Provider reference as-of selector from Overview requestKey; null if date omitted. */
    val overviewRequestDate: String?,
    val currencyName: String?,
    val primaryExchange: String?,
    val compositeFigi: String?,
    val shareClassFigi: String?,
    val active: Boolean?,
    val referenceTemporalApplicability: MassiveReferenceTemporalApplicability,
    val status: MassivePriceReferenceBindingStatus,
    val reason: MassivePriceReferenceBindingReason?,
) {
    init {
        require(priceArchiveId.isNotBlank())
        require(overviewArchiveId.isNotBlank())
        require(provider.isNotBlank())
        require(providerTicker.isNotBlank())
        when (status) {
            MassivePriceReferenceBindingStatus.CANDIDATE -> {
                require(reason == null) { "CANDIDATE must not carry reason" }
                require(provider == PROVIDER_MASSIVE) { "CANDIDATE requires provider=Massive" }
                require(priceEligibilityBoundaryAt != null) {
                    "CANDIDATE requires priceEligibilityBoundaryAt"
                }
                require(overviewEligibilityBoundaryAt != null) {
                    "CANDIDATE requires overviewEligibilityBoundaryAt"
                }
                require(bindingEligibleAt != null) { "CANDIDATE requires bindingEligibleAt" }
                val expectedMax =
                    maxInstant(priceEligibilityBoundaryAt!!, overviewEligibilityBoundaryAt!!)
                require(bindingEligibleAt == expectedMax) {
                    "CANDIDATE bindingEligibleAt must equal max(price, overview) eligibility"
                }
                require(
                    referenceTemporalApplicability ==
                        MassiveReferenceTemporalApplicability.UNRESOLVED,
                ) {
                    "CANDIDATE must keep referenceTemporalApplicability=UNRESOLVED"
                }
            }
            MassivePriceReferenceBindingStatus.INELIGIBLE,
            MassivePriceReferenceBindingStatus.CONFLICT,
            -> {
                require(reason != null) { "$status requires reason" }
            }
        }
    }

    companion object {
        const val PROVIDER_MASSIVE = "Massive"

        fun maxInstant(
            a: Instant,
            b: Instant,
        ): Instant = if (a.isAfter(b)) a else b
    }
}
