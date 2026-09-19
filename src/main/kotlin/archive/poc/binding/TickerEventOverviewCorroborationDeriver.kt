package archive.poc.binding

import archive.poc.ArchiveValidationException
import archive.poc.ManifestRecord
import archive.poc.ObservationStatus
import archive.poc.Sha256Hex
import archive.poc.massive.MassiveTickerEventsArchiveClient
import archive.poc.massive.MassiveTickerEventsArchiveValidator
import archive.poc.massive.MassiveTickerEventsRequestKey
import archive.poc.massive.MassiveTickerOverviewArchiveClient
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Corroborates Overview TICKER_CHANGE_CANDIDATE with Massive Ticker Events raw evidence.
 *
 * Does not invent candidates from Events. Does not sort/dedupe events or invent OLD/NEW.
 * Lookup id is provenance only. Event date ≠ knownAt / validity.
 */
object TickerEventOverviewCorroborationDeriver {
    fun derive(
        overviewA: ManifestRecord,
        overviewB: ManifestRecord,
        tickerEvents: ManifestRecord,
    ): TickerEventOverviewCorroborationEvidence {
        if (!isOverviewSource(overviewA) || !isOverviewSource(overviewB)) {
            return unresolvedShell(
                overviewA,
                overviewB,
                tickerEvents,
                continuity =
                    SecurityIdentityContinuityEvidenceDeriver.derive(overviewA, overviewB),
                reason = TickerEventOverviewCorroborationReason.OVERVIEW_SOURCE_REQUIRED,
            )
        }

        val continuity =
            SecurityIdentityContinuityEvidenceDeriver.derive(overviewA, overviewB)
        if (continuity.status != SecurityIdentityContinuityStatus.TICKER_CHANGE_CANDIDATE) {
            return unresolvedFromContinuity(
                continuity,
                tickerEvents,
                TickerEventOverviewCorroborationReason.CONTINUITY_NOT_TICKER_CHANGE_CANDIDATE,
            )
        }

        if (!isTickerEventsSource(tickerEvents)) {
            return unresolvedFromContinuity(
                continuity,
                tickerEvents,
                TickerEventOverviewCorroborationReason.EVENT_SOURCE_UNSUPPORTED,
            )
        }

        if (tickerEvents.observationStatus != ObservationStatus.OBSERVED ||
            tickerEvents.eligibilityBoundaryAt == null
        ) {
            return unresolvedFromContinuity(
                continuity,
                tickerEvents,
                TickerEventOverviewCorroborationReason.EVENT_INPUT_NOT_OBSERVED,
            )
        }

        val lookupId =
            try {
                MassiveTickerEventsRequestKey.parseOrThrow(tickerEvents.requestKey).lookupId
            } catch (e: IllegalArgumentException) {
                throw ArchiveValidationException(
                    "Ticker Events requestKey malformed; corroboration Fail-Closed: ${e.message}",
                    e,
                )
            }

        val httpStatus =
            tickerEvents.httpStatus
                ?: return unresolvedFromContinuity(
                    continuity,
                    tickerEvents,
                    TickerEventOverviewCorroborationReason.EVENT_RAW_VALIDATION_FAILED,
                    lookupId = lookupId,
                )

        val bytes =
            requireArchivedRawIntegrity(
                tickerEvents,
                "Massive Ticker Events response raw",
            )

        val validation =
            MassiveTickerEventsArchiveValidator.validate(
                httpStatus = httpStatus,
                bodyBytes = bytes,
            )
        if (!validation.okForObserved) {
            return unresolvedFromContinuity(
                continuity,
                tickerEvents,
                TickerEventOverviewCorroborationReason.EVENT_RAW_VALIDATION_FAILED,
                lookupId = lookupId,
            )
        }

        if (validation.eventCount == 0 || validation.validatedEvents.isEmpty()) {
            return unresolvedFromContinuity(
                continuity,
                tickerEvents,
                TickerEventOverviewCorroborationReason.EVENTS_EMPTY,
                lookupId = lookupId,
            )
        }

        val firstDate = continuity.firstProviderAsOfDate!!
        val secondDate = continuity.secondProviderAsOfDate!!
        val secondTicker = continuity.secondProviderTicker!!

        val matches =
            validation.validatedEvents.filter { ev ->
                ev.type == MassiveTickerEventsArchiveClient.TYPES_TICKER_CHANGE &&
                    ev.ticker == secondTicker &&
                    firstDate < ev.date &&
                    ev.date <= secondDate
            }

        return when (matches.size) {
            0 ->
                unresolvedFromContinuity(
                    continuity,
                    tickerEvents,
                    TickerEventOverviewCorroborationReason.NO_EVENT_WINDOW_MATCH,
                    lookupId = lookupId,
                )
            1 -> {
                val match = matches.single()
                TickerEventOverviewCorroborationEvidence(
                    firstOverviewArchiveId = continuity.firstArchiveId,
                    secondOverviewArchiveId = continuity.secondArchiveId,
                    tickerEventsArchiveId = tickerEvents.archiveId,
                    firstProviderTicker = continuity.firstProviderTicker,
                    secondProviderTicker = continuity.secondProviderTicker,
                    firstProviderAsOfDate = continuity.firstProviderAsOfDate,
                    secondProviderAsOfDate = continuity.secondProviderAsOfDate,
                    firstShareClassFigi = continuity.firstShareClassFigi,
                    secondShareClassFigi = continuity.secondShareClassFigi,
                    continuityStatus = continuity.status,
                    tickerEventsLookupId = lookupId,
                    matchedEventType = match.type,
                    matchedEventDate = match.date,
                    matchedEventTicker = match.ticker,
                    continuityEvidenceEligibleAt = continuity.evidenceEligibleAt,
                    tickerEventsEligibilityBoundaryAt = tickerEvents.eligibilityBoundaryAt,
                    corroborationEligibleAt =
                        maxElig(
                            continuity.evidenceEligibleAt,
                            tickerEvents.eligibilityBoundaryAt,
                        ),
                    status =
                        TickerEventOverviewCorroborationStatus
                            .CORROBORATED_TICKER_CHANGE_CANDIDATE,
                    reason = null,
                )
            }
            else ->
                unresolvedFromContinuity(
                    continuity,
                    tickerEvents,
                    TickerEventOverviewCorroborationReason.EVENT_WINDOW_MATCH_AMBIGUOUS,
                    lookupId = lookupId,
                )
        }
    }

    private fun isOverviewSource(record: ManifestRecord): Boolean =
        record.domain == MassiveTickerOverviewArchiveClient.DOMAIN &&
            record.source == MassiveTickerOverviewArchiveClient.SOURCE

    private fun isTickerEventsSource(record: ManifestRecord): Boolean =
        record.domain == MassiveTickerEventsArchiveClient.DOMAIN &&
            record.source == MassiveTickerEventsArchiveClient.SOURCE

    private fun maxElig(
        a: Instant?,
        b: Instant?,
    ): Instant? {
        if (a == null || b == null) return null
        return if (a.isAfter(b)) a else b
    }

    private fun unresolvedShell(
        overviewA: ManifestRecord,
        overviewB: ManifestRecord,
        tickerEvents: ManifestRecord,
        continuity: SecurityIdentityContinuityEvidence,
        reason: TickerEventOverviewCorroborationReason,
    ): TickerEventOverviewCorroborationEvidence =
        unresolvedFromContinuity(continuity, tickerEvents, reason)

    private fun unresolvedFromContinuity(
        continuity: SecurityIdentityContinuityEvidence,
        tickerEvents: ManifestRecord,
        reason: TickerEventOverviewCorroborationReason,
        lookupId: String? = null,
    ): TickerEventOverviewCorroborationEvidence =
        TickerEventOverviewCorroborationEvidence(
            firstOverviewArchiveId = continuity.firstArchiveId,
            secondOverviewArchiveId = continuity.secondArchiveId,
            tickerEventsArchiveId = tickerEvents.archiveId,
            firstProviderTicker = continuity.firstProviderTicker,
            secondProviderTicker = continuity.secondProviderTicker,
            firstProviderAsOfDate = continuity.firstProviderAsOfDate,
            secondProviderAsOfDate = continuity.secondProviderAsOfDate,
            firstShareClassFigi = continuity.firstShareClassFigi,
            secondShareClassFigi = continuity.secondShareClassFigi,
            continuityStatus = continuity.status,
            tickerEventsLookupId = lookupId,
            matchedEventType = null,
            matchedEventDate = null,
            matchedEventTicker = null,
            continuityEvidenceEligibleAt = continuity.evidenceEligibleAt,
            tickerEventsEligibilityBoundaryAt = tickerEvents.eligibilityBoundaryAt,
            corroborationEligibleAt =
                maxElig(
                    continuity.evidenceEligibleAt,
                    tickerEvents.eligibilityBoundaryAt,
                ),
            status = TickerEventOverviewCorroborationStatus.UNRESOLVED,
            reason = reason,
        )

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
}
