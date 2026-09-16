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
 * Always reads Overview (and PRICE) bytes from archived [ManifestRecord.rawPayloadUri] —
 * never accepts caller-supplied response bytes as a substitute for on-disk raw.
 *
 * Forbidden: caller free-form ticker/date, SecurityId, DailyPrice, currency/MIC adoption,
 * bar-level attribution, past backfill, latest-wins, OpenFIGI model reuse.
 */
object MassivePriceReferenceBindingEvidenceDeriver {
    /**
     * Pair-wise derive. Tickers and Overview date come **only** from canonical requestKeys.
     *
     * Fail-Closed throws [ArchiveValidationException] on:
     * - malformed PRICE requestKey (cannot derive providerTicker)
     * - PRICE/Overview archived raw missing, unreadable, or SHA-256 mismatch
     */
    fun derive(
        priceRecord: ManifestRecord,
        overviewRecord: ManifestRecord,
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

        // Archive integrity: PRICE on-disk raw must exist and match manifest hash.
        // No full PRICE semantic re-parse here — OBSERVED contract is assumed separately.
        requireArchivedRawIntegrity(
            record = priceRecord,
            label = "Massive PRICE response raw",
        )

        val overviewUri = overviewRecord.rawPayloadUri
        val overviewHash = overviewRecord.rawPayloadHash
        if (overviewUri.isNullOrBlank() || overviewHash.isNullOrBlank()) {
            throw ArchiveValidationException(
                "Massive Overview OBSERVED archive missing rawPayloadUri/rawPayloadHash",
            )
        }

        val overviewBytes =
            requireArchivedRawIntegrity(
                record = overviewRecord,
                label = "Massive Overview response raw",
            )

        val httpStatus =
            overviewRecord.httpStatus
                ?: return ineligible(
                    MassivePriceReferenceBindingReason.OVERVIEW_RAW_INVALID,
                    overviewRequestDate = overviewParsed.date,
                )

        val validation =
            MassiveTickerOverviewArchiveValidator.validate(
                httpStatus = httpStatus,
                bodyBytes = overviewBytes,
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

    /**
     * Reads [ManifestRecord.rawPayloadUri] from disk and verifies SHA-256 == [ManifestRecord.rawPayloadHash].
     * Never substitutes caller-supplied bytes for the archived object.
     */
    private fun requireArchivedRawIntegrity(
        record: ManifestRecord,
        label: String,
    ): ByteArray {
        val uri = record.rawPayloadUri
        val expectedHash = record.rawPayloadHash
        if (uri.isNullOrBlank() || expectedHash.isNullOrBlank()) {
            throw ArchiveValidationException(
                "$label: OBSERVED archive missing rawPayloadUri/rawPayloadHash",
            )
        }
        val path = Path.of(uri)
        if (!Files.isRegularFile(path)) {
            throw ArchiveValidationException("$label missing or not a regular file: $uri")
        }
        val bytes = Files.readAllBytes(path)
        val actualHash = Sha256Hex.of(bytes)
        if (actualHash != expectedHash) {
            throw ArchiveValidationException(
                "$label hash mismatch: onDisk=$actualHash manifest=$expectedHash",
            )
        }
        return bytes
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
}
