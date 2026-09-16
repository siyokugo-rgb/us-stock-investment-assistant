package archive.poc.binding

import archive.poc.ArchiveValidationException
import archive.poc.ManifestRecord
import archive.poc.ObservationStatus
import archive.poc.Sha256Hex
import archive.poc.massive.MassiveDailyAggsArchiveClient
import archive.poc.massive.MassiveDailyAggsRequestKey
import archive.poc.massive.MassiveTickerOverviewArchiveClient
import archive.poc.massive.MassiveTickerOverviewArchiveValidator
import archive.poc.massive.MassiveTickerOverviewRequestKey
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Derives [MassivePriceReferenceBindingEvidence] from Massive PRICE + Overview OBSERVED archives.
 *
 * Archive-level same-vendor provenance only.
 * Forbidden: caller free-form ticker/date, SecurityId, DailyPrice, currency/MIC adoption,
 * bar-level attribution, past backfill, latest-wins, OpenFIGI model reuse.
 */
object MassivePriceReferenceBindingEvidenceDeriver {
    /**
     * Pair-wise derive. Tickers and Overview date come **only** from canonical requestKeys.
     *
     * Fail-Closed throws [ArchiveValidationException] on:
     * - malformed PRICE requestKey (cannot derive providerTicker)
     * - Overview raw missing / unreadable / hash mismatch
     */
    fun derive(
        priceRecord: ManifestRecord,
        overviewRecord: ManifestRecord,
        overviewResponseBytes: ByteArray? = null,
    ): MassivePriceReferenceBindingEvidence {
        if (
            priceRecord.domain != MassiveDailyAggsArchiveClient.DOMAIN ||
                priceRecord.source != MassiveDailyAggsArchiveClient.SOURCE
        ) {
            val tickerGuess =
                runCatching {
                    MassiveDailyAggsRequestKey.parseOrThrow(priceRecord.requestKey).ticker
                }.getOrDefault("UNKNOWN")
            return shellIneligible(
                priceRecord,
                overviewRecord,
                tickerGuess,
                MassivePriceReferenceBindingReason.SOURCE_MISMATCH,
            )
        }

        val priceTicker =
            try {
                MassiveDailyAggsRequestKey.parseOrThrow(priceRecord.requestKey).ticker
            } catch (e: IllegalArgumentException) {
                throw ArchiveValidationException(
                    "PRICE requestKey malformed; Massive binding Fail-Closed: ${e.message}",
                    e,
                )
            }

        fun ineligible(
            reason: MassivePriceReferenceBindingReason,
            overviewRequestDate: String? = null,
            currencyName: String? = null,
            primaryExchange: String? = null,
            compositeFigi: String? = null,
            shareClassFigi: String? = null,
            active: Boolean? = null,
            bindingAt: Instant? = null,
        ): MassivePriceReferenceBindingEvidence =
            MassivePriceReferenceBindingEvidence(
                priceArchiveId = priceRecord.archiveId,
                overviewArchiveId = overviewRecord.archiveId,
                provider = MassivePriceReferenceBindingEvidence.PROVIDER_MASSIVE,
                providerTicker = priceTicker,
                priceEligibilityBoundaryAt = priceRecord.eligibilityBoundaryAt,
                overviewEligibilityBoundaryAt = overviewRecord.eligibilityBoundaryAt,
                bindingEligibleAt = bindingAt,
                overviewRequestDate = overviewRequestDate,
                currencyName = currencyName,
                primaryExchange = primaryExchange,
                compositeFigi = compositeFigi,
                shareClassFigi = shareClassFigi,
                active = active,
                referenceTemporalApplicability =
                    MassiveReferenceTemporalApplicability.UNRESOLVED,
                status = MassivePriceReferenceBindingStatus.INELIGIBLE,
                reason = reason,
            )

        if (
            overviewRecord.domain != MassiveTickerOverviewArchiveClient.DOMAIN ||
                overviewRecord.source != MassiveTickerOverviewArchiveClient.SOURCE
        ) {
            return ineligible(MassivePriceReferenceBindingReason.SOURCE_MISMATCH)
        }

        val overviewParsed =
            try {
                MassiveTickerOverviewRequestKey.parseOrThrow(overviewRecord.requestKey)
            } catch (_: IllegalArgumentException) {
                return ineligible(MassivePriceReferenceBindingReason.OVERVIEW_REQUEST_KEY_INVALID)
            }

        if (priceRecord.observationStatus != ObservationStatus.OBSERVED) {
            return ineligible(
                MassivePriceReferenceBindingReason.PRICE_NOT_OBSERVED,
                overviewRequestDate = overviewParsed.date,
            )
        }
        if (priceRecord.eligibilityBoundaryAt == null) {
            return ineligible(
                MassivePriceReferenceBindingReason.PRICE_ELIGIBILITY_MISSING,
                overviewRequestDate = overviewParsed.date,
            )
        }
        if (overviewRecord.observationStatus != ObservationStatus.OBSERVED) {
            return ineligible(
                MassivePriceReferenceBindingReason.OVERVIEW_NOT_OBSERVED,
                overviewRequestDate = overviewParsed.date,
            )
        }
        if (overviewRecord.eligibilityBoundaryAt == null) {
            return ineligible(
                MassivePriceReferenceBindingReason.OVERVIEW_ELIGIBILITY_MISSING,
                overviewRequestDate = overviewParsed.date,
            )
        }

        if (overviewParsed.ticker != priceTicker) {
            return ineligible(
                MassivePriceReferenceBindingReason.TICKER_MISMATCH,
                overviewRequestDate = overviewParsed.date,
            )
        }

        val rawUri = overviewRecord.rawPayloadUri
        val rawHash = overviewRecord.rawPayloadHash
        if (rawUri.isNullOrBlank() || rawHash.isNullOrBlank()) {
            return ineligible(
                MassivePriceReferenceBindingReason.OVERVIEW_RAW_INVALID,
                overviewRequestDate = overviewParsed.date,
            )
        }

        val bodyBytes =
            overviewResponseBytes
                ?: readBytesOrThrow(rawUri, "Massive Overview response raw")
        val actualHash = Sha256Hex.of(bodyBytes)
        if (actualHash != rawHash) {
            throw ArchiveValidationException(
                "Massive Overview raw hash mismatch: onDisk=$actualHash manifest=$rawHash",
            )
        }

        val httpStatus =
            overviewRecord.httpStatus
                ?: return ineligible(
                    MassivePriceReferenceBindingReason.OVERVIEW_RAW_INVALID,
                    overviewRequestDate = overviewParsed.date,
                )

        val validation =
            MassiveTickerOverviewArchiveValidator.validate(
                httpStatus = httpStatus,
                bodyBytes = bodyBytes,
                requestedTicker = priceTicker,
            )
        if (!validation.okForObserved) {
            return ineligible(
                MassivePriceReferenceBindingReason.OVERVIEW_RAW_INVALID,
                overviewRequestDate = overviewParsed.date,
            )
        }
        if (validation.validatedTicker != priceTicker) {
            return ineligible(
                MassivePriceReferenceBindingReason.TICKER_MISMATCH,
                overviewRequestDate = overviewParsed.date,
            )
        }

        val priceElig = priceRecord.eligibilityBoundaryAt!!
        val overviewElig = overviewRecord.eligibilityBoundaryAt!!
        val bindingAt =
            MassivePriceReferenceBindingEvidence.maxInstant(priceElig, overviewElig)

        return MassivePriceReferenceBindingEvidence(
            priceArchiveId = priceRecord.archiveId,
            overviewArchiveId = overviewRecord.archiveId,
            provider = MassivePriceReferenceBindingEvidence.PROVIDER_MASSIVE,
            providerTicker = priceTicker,
            priceEligibilityBoundaryAt = priceElig,
            overviewEligibilityBoundaryAt = overviewElig,
            bindingEligibleAt = bindingAt,
            overviewRequestDate = overviewParsed.date,
            currencyName = validation.currencyName,
            primaryExchange = validation.primaryExchange,
            compositeFigi = validation.compositeFigi,
            shareClassFigi = validation.shareClassFigi,
            active = validation.active,
            referenceTemporalApplicability =
                MassiveReferenceTemporalApplicability.UNRESOLVED,
            status = MassivePriceReferenceBindingStatus.CANDIDATE,
            reason = null,
        )
    }

    private fun shellIneligible(
        priceRecord: ManifestRecord,
        overviewRecord: ManifestRecord,
        providerTicker: String,
        reason: MassivePriceReferenceBindingReason,
    ): MassivePriceReferenceBindingEvidence =
        MassivePriceReferenceBindingEvidence(
            priceArchiveId = priceRecord.archiveId,
            overviewArchiveId = overviewRecord.archiveId,
            provider = MassivePriceReferenceBindingEvidence.PROVIDER_MASSIVE,
            providerTicker = providerTicker,
            priceEligibilityBoundaryAt = priceRecord.eligibilityBoundaryAt,
            overviewEligibilityBoundaryAt = overviewRecord.eligibilityBoundaryAt,
            bindingEligibleAt = null,
            overviewRequestDate = null,
            currencyName = null,
            primaryExchange = null,
            compositeFigi = null,
            shareClassFigi = null,
            active = null,
            referenceTemporalApplicability =
                MassiveReferenceTemporalApplicability.UNRESOLVED,
            status = MassivePriceReferenceBindingStatus.INELIGIBLE,
            reason = reason,
        )

    private fun readBytesOrThrow(
        uri: String,
        label: String,
    ): ByteArray {
        val path = Path.of(uri)
        if (!Files.isRegularFile(path)) {
            throw ArchiveValidationException("$label missing or not a regular file: $uri")
        }
        return Files.readAllBytes(path)
    }
}
