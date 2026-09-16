package archive.poc.binding

import java.time.Instant

/**
 * Forward-only provider-symbol ↔ OpenFIGI mapping binding evidence candidate.
 *
 * This is **not** Security identity resolution, SecurityId issuance, FIGI→Security,
 * DailyPrice mapping, currency inference, or historical knownAt.
 *
 * Pure derived PoC model — no DB / persistence framework.
 */
enum class ProviderSymbolBindingStatus {
    /** Safe to treat as a binding-evidence candidate (not Security resolved). */
    CANDIDATE,

    /** Multiple FIGI / mapping candidates — first-wins forbidden. */
    AMBIGUOUS,

    /** Missing or mismatched provenance; not usable as binding evidence. */
    INELIGIBLE,
}

enum class ProviderSymbolBindingReason {
    PRICE_NOT_OBSERVED,
    MAPPING_NOT_OBSERVED,
    REQUEST_PROVENANCE_INVALID,
    REQUEST_SYMBOL_MISMATCH,
    MULTIPLE_REQUEST_JOBS,
    MULTIPLE_MAPPING_CANDIDATES,
    RESPONSE_VALIDATION_FAILED,
    PRICE_ELIGIBILITY_MISSING,
    MAPPING_ELIGIBILITY_MISSING,
    CONFLICTING_CANDIDATES,
}

data class ProviderSymbolBindingEvidence(
    val priceArchiveId: String,
    val priceProvider: String,
    val providerSymbol: String,
    val mappingArchiveId: String,
    val mappingRequestKey: String,
    val mappingRequestPayloadHash: String?,
    val mappingRequestPayloadUri: String?,
    val mappingIdType: String?,
    val mappingIdValue: String?,
    val mappingExchCode: String?,
    val externalIdentifierNamespace: String?,
    val externalIdentifier: String?,
    val priceEligibilityBoundaryAt: Instant?,
    val mappingEligibilityBoundaryAt: Instant?,
    /** max(price, mapping) eligibility — forward-only; never historical knownAt / trading-date backfill. */
    val bindingEligibleAt: Instant?,
    val status: ProviderSymbolBindingStatus,
    val reason: ProviderSymbolBindingReason?,
) {
    init {
        require(priceArchiveId.isNotBlank())
        require(priceProvider.isNotBlank())
        require(providerSymbol.isNotBlank())
        require(mappingArchiveId.isNotBlank())
        require(mappingRequestKey.isNotBlank())
        when (status) {
            ProviderSymbolBindingStatus.CANDIDATE -> {
                require(reason == null) { "CANDIDATE must not carry an ineligibility reason" }
                require(bindingEligibleAt != null) { "CANDIDATE requires bindingEligibleAt" }
                require(mappingRequestPayloadHash != null && mappingRequestPayloadUri != null)
                require(mappingIdType != null && mappingIdValue != null)
            }
            ProviderSymbolBindingStatus.AMBIGUOUS,
            ProviderSymbolBindingStatus.INELIGIBLE,
            -> {
                require(reason != null) { "$status requires reason" }
            }
        }
    }
}
