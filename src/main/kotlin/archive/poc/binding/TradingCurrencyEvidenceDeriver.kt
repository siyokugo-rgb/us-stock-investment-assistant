package archive.poc.binding

import archive.poc.ArchiveValidationException
import archive.poc.ManifestRecord
import archive.poc.ObservationStatus
import archive.poc.Sha256Hex
import archive.poc.massive.MassiveAllTickersArchiveClient
import archive.poc.massive.MassiveAllTickersArchiveValidator
import archive.poc.massive.MassiveAllTickersRequestKey
import archive.poc.massive.MassiveDailyAggsArchiveClient
import archive.poc.massive.MassiveDailyAggsRequestKey
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Derives [TradingCurrencyEvidence] from Massive PRICE + All Tickers OBSERVED archives.
 *
 * Archive-level Layer B trading currency candidate only.
 * Always reads archived [ManifestRecord.rawPayloadUri] bytes — never caller-supplied body
 * as a substitute for on-disk raw.
 *
 * Forbidden: caller free-form ticker, DailyPrice.currency, SecurityId, MIC/venue,
 * bar-level attribution, lowercase currency_symbol repair, Overview as required input,
 * latest-wins, past backfill.
 */
object TradingCurrencyEvidenceDeriver {
    /**
     * Pair-wise derive. Tickers and All Tickers date come **only** from canonical requestKeys.
     *
     * Fail-Closed throws [ArchiveValidationException] on:
     * - malformed PRICE requestKey (cannot derive providerTicker)
     * - PRICE/All Tickers archived raw missing, unreadable, or SHA-256 mismatch
     */
    fun derive(
        priceRecord: ManifestRecord,
        allTickersRecord: ManifestRecord,
    ): TradingCurrencyEvidence {
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
                allTickersRecord,
                tickerGuess,
                TradingCurrencyEvidenceReason.SOURCE_MISMATCH,
            )
        }

        val priceTicker =
            try {
                MassiveDailyAggsRequestKey.parseOrThrow(priceRecord.requestKey).ticker
            } catch (e: IllegalArgumentException) {
                throw ArchiveValidationException(
                    "PRICE requestKey malformed; TradingCurrencyEvidence Fail-Closed: ${e.message}",
                    e,
                )
            }

        fun ineligible(
            reason: TradingCurrencyEvidenceReason,
            allTickersRequestDate: String? = null,
            rawCurrencySymbol: String? = null,
            evidenceAt: Instant? = null,
        ): TradingCurrencyEvidence =
            TradingCurrencyEvidence(
                priceArchiveId = priceRecord.archiveId,
                allTickersArchiveId = allTickersRecord.archiveId,
                provider = TradingCurrencyEvidence.PROVIDER_MASSIVE,
                providerTicker = priceTicker,
                rawCurrencySymbol = rawCurrencySymbol,
                canonicalCurrencyCode = null,
                priceEligibilityBoundaryAt = priceRecord.eligibilityBoundaryAt,
                allTickersEligibilityBoundaryAt = allTickersRecord.eligibilityBoundaryAt,
                evidenceEligibleAt = evidenceAt,
                allTickersRequestDate = allTickersRequestDate,
                temporalApplicability = TradingCurrencyTemporalApplicability.UNRESOLVED,
                status = TradingCurrencyEvidenceStatus.INELIGIBLE,
                reason = reason,
            )

        if (
            allTickersRecord.domain != MassiveAllTickersArchiveClient.DOMAIN ||
                allTickersRecord.source != MassiveAllTickersArchiveClient.SOURCE
        ) {
            return ineligible(TradingCurrencyEvidenceReason.SOURCE_MISMATCH)
        }

        val allTickersParsed =
            try {
                MassiveAllTickersRequestKey.parseOrThrow(allTickersRecord.requestKey)
            } catch (_: IllegalArgumentException) {
                return ineligible(TradingCurrencyEvidenceReason.ALL_TICKERS_REQUEST_KEY_INVALID)
            }

        if (priceRecord.observationStatus != ObservationStatus.OBSERVED) {
            return ineligible(
                TradingCurrencyEvidenceReason.PRICE_NOT_OBSERVED,
                allTickersRequestDate = allTickersParsed.date,
            )
        }
        if (priceRecord.eligibilityBoundaryAt == null) {
            return ineligible(
                TradingCurrencyEvidenceReason.PRICE_ELIGIBILITY_MISSING,
                allTickersRequestDate = allTickersParsed.date,
            )
        }
        if (allTickersRecord.observationStatus != ObservationStatus.OBSERVED) {
            return ineligible(
                TradingCurrencyEvidenceReason.ALL_TICKERS_NOT_OBSERVED,
                allTickersRequestDate = allTickersParsed.date,
            )
        }
        if (allTickersRecord.eligibilityBoundaryAt == null) {
            return ineligible(
                TradingCurrencyEvidenceReason.ALL_TICKERS_ELIGIBILITY_MISSING,
                allTickersRequestDate = allTickersParsed.date,
            )
        }

        // Explicit ticker filter required — no multi-result universe page join.
        val allTickersTicker = allTickersParsed.ticker
        if (allTickersTicker.isNullOrBlank()) {
            return ineligible(
                TradingCurrencyEvidenceReason.SOURCE_MISMATCH,
                allTickersRequestDate = allTickersParsed.date,
            )
        }
        if (allTickersTicker != priceTicker) {
            return ineligible(
                TradingCurrencyEvidenceReason.TICKER_MISMATCH,
                allTickersRequestDate = allTickersParsed.date,
            )
        }

        requireArchivedRawIntegrity(
            record = priceRecord,
            label = "Massive PRICE response raw",
        )

        val allTickersBytes =
            requireArchivedRawIntegrity(
                record = allTickersRecord,
                label = "Massive All Tickers response raw",
            )

        val httpStatus =
            allTickersRecord.httpStatus
                ?: return ineligible(
                    TradingCurrencyEvidenceReason.RAW_INTEGRITY_INVALID,
                    allTickersRequestDate = allTickersParsed.date,
                )

        val validation =
            MassiveAllTickersArchiveValidator.validate(
                httpStatus = httpStatus,
                bodyBytes = allTickersBytes,
                requestedTicker = priceTicker,
                requestedActive = allTickersParsed.active,
            )
        if (!validation.okForObserved) {
            return ineligible(
                TradingCurrencyEvidenceReason.RAW_INTEGRITY_INVALID,
                allTickersRequestDate = allTickersParsed.date,
            )
        }
        if (validation.resultCount != 1) {
            return ineligible(
                TradingCurrencyEvidenceReason.SOURCE_MISMATCH,
                allTickersRequestDate = allTickersParsed.date,
            )
        }
        if (validation.tickers.singleOrNull() != priceTicker) {
            return ineligible(
                TradingCurrencyEvidenceReason.TICKER_MISMATCH,
                allTickersRequestDate = allTickersParsed.date,
            )
        }

        val rawSymbol = validation.currencySymbols.singleOrNull()
        if (rawSymbol.isNullOrBlank()) {
            return ineligible(
                TradingCurrencyEvidenceReason.CURRENCY_MISSING,
                allTickersRequestDate = allTickersParsed.date,
                rawCurrencySymbol = rawSymbol?.takeIf { it.isNotBlank() },
            )
        }

        if (!TradingCurrencyEvidence.CANONICAL_ISO_ALPHA.matches(rawSymbol)) {
            return ineligible(
                TradingCurrencyEvidenceReason.CURRENCY_CODE_INVALID,
                allTickersRequestDate = allTickersParsed.date,
                rawCurrencySymbol = rawSymbol,
            )
        }
        if (!Iso4217AlphabeticCodes.isAlphabeticMember(rawSymbol)) {
            return ineligible(
                TradingCurrencyEvidenceReason.CURRENCY_CODE_INVALID,
                allTickersRequestDate = allTickersParsed.date,
                rawCurrencySymbol = rawSymbol,
            )
        }

        val priceElig = priceRecord.eligibilityBoundaryAt!!
        val allTickersElig = allTickersRecord.eligibilityBoundaryAt!!
        val evidenceAt = TradingCurrencyEvidence.maxInstant(priceElig, allTickersElig)

        return TradingCurrencyEvidence(
            priceArchiveId = priceRecord.archiveId,
            allTickersArchiveId = allTickersRecord.archiveId,
            provider = TradingCurrencyEvidence.PROVIDER_MASSIVE,
            providerTicker = priceTicker,
            rawCurrencySymbol = rawSymbol,
            canonicalCurrencyCode = rawSymbol,
            priceEligibilityBoundaryAt = priceElig,
            allTickersEligibilityBoundaryAt = allTickersElig,
            evidenceEligibleAt = evidenceAt,
            allTickersRequestDate = allTickersParsed.date,
            temporalApplicability = TradingCurrencyTemporalApplicability.UNRESOLVED,
            status = TradingCurrencyEvidenceStatus.CANDIDATE,
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
        allTickersRecord: ManifestRecord,
        providerTicker: String,
        reason: TradingCurrencyEvidenceReason,
    ): TradingCurrencyEvidence =
        TradingCurrencyEvidence(
            priceArchiveId = priceRecord.archiveId,
            allTickersArchiveId = allTickersRecord.archiveId,
            provider = TradingCurrencyEvidence.PROVIDER_MASSIVE,
            providerTicker = providerTicker,
            rawCurrencySymbol = null,
            canonicalCurrencyCode = null,
            priceEligibilityBoundaryAt = priceRecord.eligibilityBoundaryAt,
            allTickersEligibilityBoundaryAt = allTickersRecord.eligibilityBoundaryAt,
            evidenceEligibleAt = null,
            allTickersRequestDate = null,
            temporalApplicability = TradingCurrencyTemporalApplicability.UNRESOLVED,
            status = TradingCurrencyEvidenceStatus.INELIGIBLE,
            reason = reason,
        )
}
