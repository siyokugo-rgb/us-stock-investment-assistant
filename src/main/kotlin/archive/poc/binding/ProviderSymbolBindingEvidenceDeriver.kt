package archive.poc.binding

import archive.poc.ArchiveValidationException
import archive.poc.ManifestRecord
import archive.poc.ObservationStatus
import archive.poc.Sha256Hex
import archive.poc.alphavantage.AlphaVantageDailyArchiveClient
import archive.poc.alphavantage.AlphaVantageDailyRequestKey
import archive.poc.openfigi.OpenFigiForwardArchiveService
import archive.poc.openfigi.OpenFigiMappingClient
import archive.poc.openfigi.OpenFigiMappingRequestBody
import archive.poc.openfigi.OpenFigiMappingValidator
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Derives [ProviderSymbolBindingEvidence] from PRICE + OpenFIGI OBSERVED archives.
 *
 * Forbidden: caller-supplied free-form providerSymbol, ticker-string-only join,
 * SecurityId / currency / DailyPrice / knownAt invention, first-FIGI selection,
 * current-mapping past backfill, preferring manifest over response revalidation.
 */
object ProviderSymbolBindingEvidenceDeriver {
    const val PRICE_PROVIDER_ALPHAVANTAGE = "alphavantage"
    const val FIGI_NAMESPACE = "figi"

    /**
     * Pair-wise derive. Provider symbol is parsed **only** from the PRICE record's
     * canonical Alpha Vantage [ManifestRecord.requestKey].
     *
     * Fail-Closed (throws [ArchiveValidationException]):
     * - malformed PRICE requestKey
     * - OpenFIGI request/response raw missing / unreadable / hash mismatch
     * - manifest external id disagrees with revalidated response external id
     */
    fun derive(
        priceRecord: ManifestRecord,
        mappingRecord: ManifestRecord,
        mappingRequestBytes: ByteArray? = null,
        mappingResponseBytes: ByteArray? = null,
    ): ProviderSymbolBindingEvidence {
        requirePriceDomain(priceRecord)
        requireMappingDomain(mappingRecord)

        val providerSymbol =
            try {
                AlphaVantageDailyRequestKey.parseOrThrow(priceRecord.requestKey).symbol
            } catch (e: IllegalArgumentException) {
                throw ArchiveValidationException(
                    "PRICE requestKey malformed; binding Fail-Closed: ${e.message}",
                    e,
                )
            }

        fun ineligible(
            reason: ProviderSymbolBindingReason,
            idType: String? = null,
            idValue: String? = null,
            exch: String? = null,
            reqHash: String? = mappingRecord.requestPayloadHash,
            reqUri: String? = mappingRecord.requestPayloadUri,
            extNs: String? = null,
            extId: String? = null,
            bindingAt: Instant? = null,
        ): ProviderSymbolBindingEvidence =
            ProviderSymbolBindingEvidence(
                priceArchiveId = priceRecord.archiveId,
                priceProvider = PRICE_PROVIDER_ALPHAVANTAGE,
                providerSymbol = providerSymbol,
                mappingArchiveId = mappingRecord.archiveId,
                mappingRequestKey = mappingRecord.requestKey,
                mappingRequestPayloadHash = reqHash,
                mappingRequestPayloadUri = reqUri,
                mappingIdType = idType,
                mappingIdValue = idValue,
                mappingExchCode = exch,
                externalIdentifierNamespace = extNs,
                externalIdentifier = extId,
                priceEligibilityBoundaryAt = priceRecord.eligibilityBoundaryAt,
                mappingEligibilityBoundaryAt = mappingRecord.eligibilityBoundaryAt,
                bindingEligibleAt = bindingAt,
                status = ProviderSymbolBindingStatus.INELIGIBLE,
                reason = reason,
            )

        if (priceRecord.observationStatus != ObservationStatus.OBSERVED) {
            return ineligible(ProviderSymbolBindingReason.PRICE_NOT_OBSERVED)
        }
        if (priceRecord.eligibilityBoundaryAt == null) {
            return ineligible(ProviderSymbolBindingReason.PRICE_ELIGIBILITY_MISSING)
        }
        if (mappingRecord.observationStatus != ObservationStatus.OBSERVED) {
            return ineligible(ProviderSymbolBindingReason.MAPPING_NOT_OBSERVED)
        }
        if (mappingRecord.eligibilityBoundaryAt == null) {
            return ineligible(ProviderSymbolBindingReason.MAPPING_ELIGIBILITY_MISSING)
        }

        val reqHash = mappingRecord.requestPayloadHash
        val reqUri = mappingRecord.requestPayloadUri
        if (
            reqHash.isNullOrBlank() ||
                reqUri.isNullOrBlank() ||
                !OpenFigiForwardArchiveService.requestKeyBodyHashMatches(
                    mappingRecord.requestKey,
                    reqHash,
                )
        ) {
            return ineligible(ProviderSymbolBindingReason.REQUEST_PROVENANCE_INVALID)
        }

        val requestBytes =
            mappingRequestBytes
                ?: readBytesOrThrow(reqUri, "OpenFIGI request raw")
        val actualRequestHash = Sha256Hex.of(requestBytes)
        if (actualRequestHash != reqHash) {
            throw ArchiveValidationException(
                "OpenFIGI request raw hash mismatch: onDisk=$actualRequestHash manifest=$reqHash",
            )
        }

        val jobs =
            try {
                OpenFigiMappingRequestBody.parseJobs(requestBytes)
            } catch (_: Exception) {
                return ineligible(
                    ProviderSymbolBindingReason.REQUEST_PROVENANCE_INVALID,
                    reqHash = reqHash,
                    reqUri = reqUri,
                )
            }

        if (jobs.size > 1) {
            return ineligible(
                ProviderSymbolBindingReason.MULTIPLE_REQUEST_JOBS,
                reqHash = reqHash,
                reqUri = reqUri,
            )
        }
        val job = jobs.single()
        if (job.idType != "TICKER" || job.idValue != providerSymbol) {
            return ineligible(
                ProviderSymbolBindingReason.REQUEST_SYMBOL_MISMATCH,
                idType = job.idType,
                idValue = job.idValue,
                exch = job.exchCode,
                reqHash = reqHash,
                reqUri = reqUri,
            )
        }

        val responseBytes =
            mappingResponseBytes
                ?: mappingRecord.rawPayloadUri?.let { readBytesOrThrow(it, "OpenFIGI response raw") }
                ?: return ineligible(
                    ProviderSymbolBindingReason.RESPONSE_VALIDATION_FAILED,
                    idType = job.idType,
                    idValue = job.idValue,
                    exch = job.exchCode,
                    reqHash = reqHash,
                    reqUri = reqUri,
                )

        if (
            mappingRecord.rawPayloadHash != null &&
                Sha256Hex.of(responseBytes) != mappingRecord.rawPayloadHash
        ) {
            throw ArchiveValidationException(
                "OpenFIGI response raw hash mismatch vs manifest rawPayloadHash",
            )
        }

        val httpStatus =
            mappingRecord.httpStatus
                ?: return ineligible(
                    ProviderSymbolBindingReason.RESPONSE_VALIDATION_FAILED,
                    idType = job.idType,
                    idValue = job.idValue,
                    exch = job.exchCode,
                    reqHash = reqHash,
                    reqUri = reqUri,
                )
        val validation =
            OpenFigiMappingValidator.validate(
                httpStatus = httpStatus,
                bodyBytes = responseBytes,
                requestJobCount = 1,
            )
        if (!validation.okForObserved) {
            return ineligible(
                ProviderSymbolBindingReason.RESPONSE_VALIDATION_FAILED,
                idType = job.idType,
                idValue = job.idValue,
                exch = job.exchCode,
                reqHash = reqHash,
                reqUri = reqUri,
            )
        }

        val bindingAt =
            maxInstant(
                priceRecord.eligibilityBoundaryAt!!,
                mappingRecord.eligibilityBoundaryAt!!,
            )

        if (validation.figiCandidateCount > 1) {
            return ProviderSymbolBindingEvidence(
                priceArchiveId = priceRecord.archiveId,
                priceProvider = PRICE_PROVIDER_ALPHAVANTAGE,
                providerSymbol = providerSymbol,
                mappingArchiveId = mappingRecord.archiveId,
                mappingRequestKey = mappingRecord.requestKey,
                mappingRequestPayloadHash = reqHash,
                mappingRequestPayloadUri = reqUri,
                mappingIdType = job.idType,
                mappingIdValue = job.idValue,
                mappingExchCode = job.exchCode,
                externalIdentifierNamespace = null,
                externalIdentifier = null,
                priceEligibilityBoundaryAt = priceRecord.eligibilityBoundaryAt,
                mappingEligibilityBoundaryAt = mappingRecord.eligibilityBoundaryAt,
                bindingEligibleAt = bindingAt,
                status = ProviderSymbolBindingStatus.AMBIGUOUS,
                reason = ProviderSymbolBindingReason.MULTIPLE_MAPPING_CANDIDATES,
            )
        }

        if (
            validation.figiCandidateCount != 1 ||
                validation.externalIdentifier.isNullOrBlank() ||
                validation.externalIdentifierNamespace != FIGI_NAMESPACE
        ) {
            return ineligible(
                ProviderSymbolBindingReason.MISSING_EXTERNAL_IDENTIFIER,
                idType = job.idType,
                idValue = job.idValue,
                exch = job.exchCode,
                reqHash = reqHash,
                reqUri = reqUri,
                bindingAt = bindingAt,
            )
        }

        val manifestId = mappingRecord.externalIdentifier
        val manifestNs = mappingRecord.externalIdentifierNamespace
        if (manifestId.isNullOrBlank() || manifestNs.isNullOrBlank()) {
            // Unique FIGI in raw does not invent / promote a missing manifest external id.
            return ineligible(
                ProviderSymbolBindingReason.MISSING_EXTERNAL_IDENTIFIER,
                idType = job.idType,
                idValue = job.idValue,
                exch = job.exchCode,
                reqHash = reqHash,
                reqUri = reqUri,
                bindingAt = bindingAt,
            )
        }

        if (
            manifestId != validation.externalIdentifier ||
                manifestNs != validation.externalIdentifierNamespace
        ) {
            throw ArchiveValidationException(
                "MAPPING_MANIFEST_RESPONSE_MISMATCH: manifest external id/namespace " +
                    "disagrees with revalidated response " +
                    "(manifest=$manifestNs/$manifestId response=" +
                    "${validation.externalIdentifierNamespace}/${validation.externalIdentifier})",
            )
        }

        if (manifestNs != FIGI_NAMESPACE) {
            throw ArchiveValidationException(
                "MAPPING_MANIFEST_RESPONSE_MISMATCH: externalIdentifierNamespace must be $FIGI_NAMESPACE",
            )
        }

        return ProviderSymbolBindingEvidence(
            priceArchiveId = priceRecord.archiveId,
            priceProvider = PRICE_PROVIDER_ALPHAVANTAGE,
            providerSymbol = providerSymbol,
            mappingArchiveId = mappingRecord.archiveId,
            mappingRequestKey = mappingRecord.requestKey,
            mappingRequestPayloadHash = reqHash,
            mappingRequestPayloadUri = reqUri,
            mappingIdType = job.idType,
            mappingIdValue = job.idValue,
            mappingExchCode = job.exchCode,
            externalIdentifierNamespace = manifestNs,
            externalIdentifier = manifestId,
            priceEligibilityBoundaryAt = priceRecord.eligibilityBoundaryAt,
            mappingEligibilityBoundaryAt = mappingRecord.eligibilityBoundaryAt,
            bindingEligibleAt = bindingAt,
            status = ProviderSymbolBindingStatus.CANDIDATE,
            reason = null,
        )
    }

    /**
     * Conflict only when the same [priceArchiveId] has CANDIDATE rows with **distinct**
     * `(externalIdentifierNamespace, externalIdentifier)` identity pairs.
     *
     * Agreeing repeated mappings (same FIGI, different mappingArchiveId) stay CANDIDATE.
     * mappingArchiveId difference alone is never a conflict reason. latest-wins forbidden.
     */
    fun applyConflicts(
        evidences: List<ProviderSymbolBindingEvidence>,
    ): List<ProviderSymbolBindingEvidence> {
        val byPrice =
            evidences
                .filter { it.status == ProviderSymbolBindingStatus.CANDIDATE }
                .groupBy { it.priceArchiveId }
        val conflictedPrices =
            byPrice
                .filter { (_, list) ->
                    list
                        .map { it.externalIdentifierNamespace to it.externalIdentifier }
                        .distinct()
                        .size > 1
                }.keys
        if (conflictedPrices.isEmpty()) return evidences
        return evidences.map { ev ->
            if (
                ev.status == ProviderSymbolBindingStatus.CANDIDATE &&
                    ev.priceArchiveId in conflictedPrices
            ) {
                ev.copy(
                    status = ProviderSymbolBindingStatus.AMBIGUOUS,
                    reason = ProviderSymbolBindingReason.CONFLICTING_CANDIDATES,
                )
            } else {
                ev
            }
        }
    }

    private fun requirePriceDomain(record: ManifestRecord) {
        require(record.domain == AlphaVantageDailyArchiveClient.DOMAIN) {
            "priceRecord.domain must be ${AlphaVantageDailyArchiveClient.DOMAIN}"
        }
        require(record.source == AlphaVantageDailyArchiveClient.SOURCE) {
            "priceRecord.source must be ${AlphaVantageDailyArchiveClient.SOURCE}"
        }
    }

    private fun requireMappingDomain(record: ManifestRecord) {
        require(record.domain == OpenFigiMappingClient.DOMAIN) {
            "mappingRecord.domain must be ${OpenFigiMappingClient.DOMAIN}"
        }
        require(record.source == OpenFigiMappingClient.SOURCE) {
            "mappingRecord.source must be ${OpenFigiMappingClient.SOURCE}"
        }
    }

    private fun readBytesOrThrow(
        uri: String,
        label: String,
    ): ByteArray {
        val path = Path.of(uri)
        if (!Files.isRegularFile(path)) {
            throw ArchiveValidationException("$label missing or not a file: $uri")
        }
        return Files.readAllBytes(path)
    }

    private fun maxInstant(
        a: Instant,
        b: Instant,
    ): Instant = if (a.isAfter(b)) a else b
}
