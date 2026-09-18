package archive.poc.binding

import archive.poc.ArchiveValidationException
import archive.poc.ManifestRecord
import archive.poc.ObservationStatus
import archive.poc.Sha256Hex
import archive.poc.massive.MassiveAllTickersArchiveClient
import archive.poc.massive.MassiveAllTickersArchiveValidator
import archive.poc.massive.MassiveAllTickersRequestKey
import archive.poc.massive.MassiveTickerOverviewArchiveClient
import archive.poc.massive.MassiveTickerOverviewArchiveValidator
import archive.poc.massive.MassiveTickerOverviewRequestKey
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Derives [SecurityIdentityContinuityEvidence] from two Massive archives.
 *
 * Cross-time candidates normalize **first = earlier provider as-of**, **second = later**
 * using requestKey `date=` only — never ingest/fetch/eligibility.
 *
 * When provider as-of cannot establish a cross-time order (missing date, same as-of,
 * unsupported/mismatch shells), first/second are **deterministic presentation slots**
 * (by archiveId) and do **not** claim temporal earlier/later.
 *
 * Cross-source Overview↔All Tickers compare is rejected
 * ([SecurityIdentityContinuityReason.SOURCE_PAIR_MISMATCH]); both sources are retained.
 *
 * Forbidden: SecurityId / SecurityIdentifier / knownAt / validFrom/validTo generation,
 * caller free-form dates, caller-supplied raw bytes, latest-wins, MIC resolution.
 */
object SecurityIdentityContinuityEvidenceDeriver {
    fun derive(
        left: ManifestRecord,
        right: ManifestRecord,
    ): SecurityIdentityContinuityEvidence {
        val leftKind = sourceKind(left)
        val rightKind = sourceKind(right)
        if (leftKind == null || rightKind == null) {
            return unresolvedShell(
                left,
                right,
                SecurityIdentityContinuityReason.SOURCE_UNSUPPORTED,
            )
        }
        if (leftKind != rightKind) {
            return unresolvedShell(
                left,
                right,
                SecurityIdentityContinuityReason.SOURCE_PAIR_MISMATCH,
            )
        }

        val leftSnap = loadSnapshot(left, leftKind)
        val rightSnap = loadSnapshot(right, rightKind)

        val da = leftSnap.providerAsOfDate
        val db = rightSnap.providerAsOfDate
        if (da.isNullOrBlank() || db.isNullOrBlank()) {
            // Prefer snapshot-level unresolved (e.g. non-OBSERVED never parsed date)
            // over PROVIDER_AS_OF_MISSING when both apply.
            val reason =
                leftSnap.unresolvedReason
                    ?: rightSnap.unresolvedReason
                    ?: SecurityIdentityContinuityReason.PROVIDER_AS_OF_MISSING
            val (first, second) = presentationSlots(leftSnap, rightSnap)
            return buildUnresolved(first, second, reason)
        }

        if (da == db) {
            val (first, second) = presentationSlots(leftSnap, rightSnap)
            return classifySameAsOf(first, second)
        }

        // Distinct provider as-of: normalize first=earlier, second=later.
        val (earlier, later) =
            if (da < db) leftSnap to rightSnap else rightSnap to leftSnap
        return classifyCrossTime(earlier, later)
    }

    private enum class SourceKind {
        OVERVIEW,
        ALL_TICKERS,
    }

    private data class Snapshot(
        val record: ManifestRecord,
        val kind: SourceKind,
        val providerTicker: String?,
        val providerAsOfDate: String?,
        val shareClassFigi: String?,
        val compositeFigi: String?,
        val primaryExchange: String?,
        val unresolvedReason: SecurityIdentityContinuityReason? = null,
    )

    private fun sourceKind(record: ManifestRecord): SourceKind? {
        if (record.domain != MassiveTickerOverviewArchiveClient.DOMAIN) return null
        return when (record.source) {
            MassiveTickerOverviewArchiveClient.SOURCE -> SourceKind.OVERVIEW
            MassiveAllTickersArchiveClient.SOURCE -> SourceKind.ALL_TICKERS
            else -> null
        }
    }

    /**
     * Deterministic non-temporal presentation order by archiveId.
     * Must not be described as provider as-of / temporal earlier-later.
     */
    private fun presentationSlots(
        a: Snapshot,
        b: Snapshot,
    ): Pair<Snapshot, Snapshot> =
        if (a.record.archiveId <= b.record.archiveId) a to b else b to a

    private fun presentationSlots(
        a: ManifestRecord,
        b: ManifestRecord,
    ): Pair<ManifestRecord, ManifestRecord> =
        if (a.archiveId <= b.archiveId) a to b else b to a

    private fun loadSnapshot(
        record: ManifestRecord,
        kind: SourceKind,
    ): Snapshot {
        if (record.observationStatus != ObservationStatus.OBSERVED ||
            record.eligibilityBoundaryAt == null
        ) {
            return Snapshot(
                record = record,
                kind = kind,
                providerTicker = null,
                providerAsOfDate = null,
                shareClassFigi = null,
                compositeFigi = null,
                primaryExchange = null,
                unresolvedReason = SecurityIdentityContinuityReason.INPUT_NOT_OBSERVED,
            )
        }

        val parsedTicker: String?
        val parsedDate: String?
        val requestedTickerForAllTickers: String?
        when (kind) {
            SourceKind.OVERVIEW -> {
                val parsed =
                    try {
                        MassiveTickerOverviewRequestKey.parseOrThrow(record.requestKey)
                    } catch (e: IllegalArgumentException) {
                        throw ArchiveValidationException(
                            "Overview requestKey malformed; continuity Fail-Closed: ${e.message}",
                            e,
                        )
                    }
                parsedTicker = parsed.ticker
                parsedDate = parsed.date
                requestedTickerForAllTickers = null
            }
            SourceKind.ALL_TICKERS -> {
                val parsed =
                    try {
                        MassiveAllTickersRequestKey.parseOrThrow(record.requestKey)
                    } catch (e: IllegalArgumentException) {
                        throw ArchiveValidationException(
                            "All Tickers requestKey malformed; continuity Fail-Closed: ${e.message}",
                            e,
                        )
                    }
                parsedDate = parsed.date
                val filter = parsed.ticker
                if (filter.isNullOrBlank()) {
                    return Snapshot(
                        record = record,
                        kind = kind,
                        providerTicker = null,
                        providerAsOfDate = parsedDate,
                        shareClassFigi = null,
                        compositeFigi = null,
                        primaryExchange = null,
                        unresolvedReason = SecurityIdentityContinuityReason.SNAPSHOT_AMBIGUOUS,
                    )
                }
                parsedTicker = filter
                requestedTickerForAllTickers = filter
            }
        }

        val httpStatus =
            record.httpStatus
                ?: return Snapshot(
                    record = record,
                    kind = kind,
                    providerTicker = parsedTicker,
                    providerAsOfDate = parsedDate,
                    shareClassFigi = null,
                    compositeFigi = null,
                    primaryExchange = null,
                    unresolvedReason = SecurityIdentityContinuityReason.RAW_VALIDATION_FAILED,
                )

        val bytes =
            requireArchivedRawIntegrity(
                record,
                label =
                    when (kind) {
                        SourceKind.OVERVIEW -> "Massive Ticker Overview response raw"
                        SourceKind.ALL_TICKERS -> "Massive All Tickers response raw"
                    },
            )

        return when (kind) {
            SourceKind.OVERVIEW -> {
                val outcome =
                    MassiveTickerOverviewArchiveValidator.validate(
                        httpStatus = httpStatus,
                        bodyBytes = bytes,
                        requestedTicker = parsedTicker!!,
                    )
                if (!outcome.okForObserved) {
                    Snapshot(
                        record = record,
                        kind = kind,
                        providerTicker = parsedTicker,
                        providerAsOfDate = parsedDate,
                        shareClassFigi = null,
                        compositeFigi = null,
                        primaryExchange = null,
                        unresolvedReason = SecurityIdentityContinuityReason.RAW_VALIDATION_FAILED,
                    )
                } else {
                    Snapshot(
                        record = record,
                        kind = kind,
                        providerTicker = outcome.validatedTicker ?: parsedTicker,
                        providerAsOfDate = parsedDate,
                        shareClassFigi = outcome.shareClassFigi,
                        compositeFigi = outcome.compositeFigi,
                        primaryExchange = outcome.primaryExchange,
                    )
                }
            }
            SourceKind.ALL_TICKERS -> {
                val parsedActive =
                    MassiveAllTickersRequestKey.parseOrThrow(record.requestKey).active
                val outcome =
                    MassiveAllTickersArchiveValidator.validate(
                        httpStatus = httpStatus,
                        bodyBytes = bytes,
                        requestedTicker = requestedTickerForAllTickers,
                        requestedActive = parsedActive,
                    )
                if (!outcome.okForObserved) {
                    return Snapshot(
                        record = record,
                        kind = kind,
                        providerTicker = parsedTicker,
                        providerAsOfDate = parsedDate,
                        shareClassFigi = null,
                        compositeFigi = null,
                        primaryExchange = null,
                        unresolvedReason = SecurityIdentityContinuityReason.RAW_VALIDATION_FAILED,
                    )
                }
                if (outcome.resultCount != 1) {
                    return Snapshot(
                        record = record,
                        kind = kind,
                        providerTicker = parsedTicker,
                        providerAsOfDate = parsedDate,
                        shareClassFigi = null,
                        compositeFigi = null,
                        primaryExchange = null,
                        unresolvedReason = SecurityIdentityContinuityReason.SNAPSHOT_AMBIGUOUS,
                    )
                }
                val rowTicker = outcome.tickers.singleOrNull()
                if (rowTicker == null || rowTicker != requestedTickerForAllTickers) {
                    return Snapshot(
                        record = record,
                        kind = kind,
                        providerTicker = parsedTicker,
                        providerAsOfDate = parsedDate,
                        shareClassFigi = null,
                        compositeFigi = null,
                        primaryExchange = null,
                        unresolvedReason = SecurityIdentityContinuityReason.SNAPSHOT_AMBIGUOUS,
                    )
                }
                Snapshot(
                    record = record,
                    kind = kind,
                    providerTicker = rowTicker,
                    providerAsOfDate = parsedDate,
                    shareClassFigi = outcome.shareClassFigis.singleOrNull(),
                    compositeFigi = outcome.compositeFigis.singleOrNull(),
                    primaryExchange = outcome.primaryExchanges.singleOrNull(),
                )
            }
        }
    }

    private fun classifySameAsOf(
        first: Snapshot,
        second: Snapshot,
    ): SecurityIdentityContinuityEvidence {
        if (first.unresolvedReason != null || second.unresolvedReason != null) {
            return buildUnresolved(
                first,
                second,
                first.unresolvedReason ?: second.unresolvedReason!!,
            )
        }
        if (identityLayerConflict(first, second)) {
            return buildConflict(first, second)
        }
        val scA = first.shareClassFigi
        val scB = second.shareClassFigi
        if (!scA.isNullOrBlank() && !scB.isNullOrBlank() && scA != scB) {
            return buildConflict(first, second)
        }
        return buildUnresolved(
            first,
            second,
            SecurityIdentityContinuityReason.SAME_AS_OF_NOT_CROSS_TIME,
        )
    }

    private fun classifyCrossTime(
        earlier: Snapshot,
        later: Snapshot,
    ): SecurityIdentityContinuityEvidence {
        // Candidate / conflict / unresolved for distinct as-of: first=earlier, second=later.
        if (earlier.unresolvedReason != null || later.unresolvedReason != null) {
            return buildUnresolved(
                earlier,
                later,
                earlier.unresolvedReason ?: later.unresolvedReason!!,
            )
        }

        if (identityLayerConflict(earlier, later)) {
            return buildConflict(earlier, later)
        }

        val scA = earlier.shareClassFigi
        val scB = later.shareClassFigi
        if (scA.isNullOrBlank() || scB.isNullOrBlank()) {
            return buildUnresolved(
                earlier,
                later,
                SecurityIdentityContinuityReason.SHARE_CLASS_IDENTITY_MISSING,
            )
        }

        val tickerA = earlier.providerTicker
        val tickerB = later.providerTicker
        if (tickerA.isNullOrBlank() || tickerB.isNullOrBlank()) {
            return buildUnresolved(
                earlier,
                later,
                SecurityIdentityContinuityReason.SNAPSHOT_AMBIGUOUS,
            )
        }

        return when {
            tickerA == tickerB && scA == scB ->
                buildCandidate(
                    earlier,
                    later,
                    SecurityIdentityContinuityStatus.CONTINUITY_CANDIDATE,
                )
            tickerA != tickerB && scA == scB ->
                buildCandidate(
                    earlier,
                    later,
                    SecurityIdentityContinuityStatus.TICKER_CHANGE_CANDIDATE,
                )
            tickerA == tickerB && scA != scB ->
                buildCandidate(
                    earlier,
                    later,
                    SecurityIdentityContinuityStatus.RECYCLE_CANDIDATE,
                )
            else ->
                // Different ticker + different share_class: do not invent recycle/relationship.
                buildUnresolved(
                    earlier,
                    later,
                    SecurityIdentityContinuityReason.SNAPSHOT_AMBIGUOUS,
                )
        }
    }

    /** composite equal + both share_class nonblank and unequal → layer conflict. */
    private fun identityLayerConflict(
        a: Snapshot,
        b: Snapshot,
    ): Boolean {
        val ca = a.compositeFigi
        val cb = b.compositeFigi
        val sa = a.shareClassFigi
        val sb = b.shareClassFigi
        return !ca.isNullOrBlank() &&
            !cb.isNullOrBlank() &&
            ca == cb &&
            !sa.isNullOrBlank() &&
            !sb.isNullOrBlank() &&
            sa != sb
    }

    private fun maxElig(
        a: Instant?,
        b: Instant?,
    ): Instant? {
        if (a == null || b == null) return null
        return if (a.isAfter(b)) a else b
    }

    private fun buildCandidate(
        earlier: Snapshot,
        later: Snapshot,
        status: SecurityIdentityContinuityStatus,
    ): SecurityIdentityContinuityEvidence =
        SecurityIdentityContinuityEvidence(
            firstArchiveId = earlier.record.archiveId,
            secondArchiveId = later.record.archiveId,
            firstSource = earlier.record.source,
            secondSource = later.record.source,
            firstProviderTicker = earlier.providerTicker,
            secondProviderTicker = later.providerTicker,
            firstProviderAsOfDate = earlier.providerAsOfDate,
            secondProviderAsOfDate = later.providerAsOfDate,
            firstEligibilityBoundaryAt = earlier.record.eligibilityBoundaryAt,
            secondEligibilityBoundaryAt = later.record.eligibilityBoundaryAt,
            evidenceEligibleAt =
                maxElig(
                    earlier.record.eligibilityBoundaryAt,
                    later.record.eligibilityBoundaryAt,
                ),
            firstShareClassFigi = earlier.shareClassFigi,
            secondShareClassFigi = later.shareClassFigi,
            firstCompositeFigi = earlier.compositeFigi,
            secondCompositeFigi = later.compositeFigi,
            firstPrimaryExchange = earlier.primaryExchange,
            secondPrimaryExchange = later.primaryExchange,
            status = status,
            reason = null,
        )

    private fun buildConflict(
        first: Snapshot,
        second: Snapshot,
    ): SecurityIdentityContinuityEvidence =
        SecurityIdentityContinuityEvidence(
            firstArchiveId = first.record.archiveId,
            secondArchiveId = second.record.archiveId,
            firstSource = first.record.source,
            secondSource = second.record.source,
            firstProviderTicker = first.providerTicker,
            secondProviderTicker = second.providerTicker,
            firstProviderAsOfDate = first.providerAsOfDate,
            secondProviderAsOfDate = second.providerAsOfDate,
            firstEligibilityBoundaryAt = first.record.eligibilityBoundaryAt,
            secondEligibilityBoundaryAt = second.record.eligibilityBoundaryAt,
            evidenceEligibleAt =
                maxElig(
                    first.record.eligibilityBoundaryAt,
                    second.record.eligibilityBoundaryAt,
                ),
            firstShareClassFigi = first.shareClassFigi,
            secondShareClassFigi = second.shareClassFigi,
            firstCompositeFigi = first.compositeFigi,
            secondCompositeFigi = second.compositeFigi,
            firstPrimaryExchange = first.primaryExchange,
            secondPrimaryExchange = second.primaryExchange,
            status = SecurityIdentityContinuityStatus.CONFLICT,
            reason = SecurityIdentityContinuityReason.IDENTITY_LAYER_CONFLICT,
        )

    private fun buildUnresolved(
        first: Snapshot,
        second: Snapshot,
        reason: SecurityIdentityContinuityReason,
    ): SecurityIdentityContinuityEvidence =
        SecurityIdentityContinuityEvidence(
            firstArchiveId = first.record.archiveId,
            secondArchiveId = second.record.archiveId,
            firstSource = first.record.source,
            secondSource = second.record.source,
            firstProviderTicker = first.providerTicker,
            secondProviderTicker = second.providerTicker,
            firstProviderAsOfDate = first.providerAsOfDate,
            secondProviderAsOfDate = second.providerAsOfDate,
            firstEligibilityBoundaryAt = first.record.eligibilityBoundaryAt,
            secondEligibilityBoundaryAt = second.record.eligibilityBoundaryAt,
            evidenceEligibleAt =
                maxElig(
                    first.record.eligibilityBoundaryAt,
                    second.record.eligibilityBoundaryAt,
                ),
            firstShareClassFigi = first.shareClassFigi,
            secondShareClassFigi = second.shareClassFigi,
            firstCompositeFigi = first.compositeFigi,
            secondCompositeFigi = second.compositeFigi,
            firstPrimaryExchange = first.primaryExchange,
            secondPrimaryExchange = second.primaryExchange,
            status = SecurityIdentityContinuityStatus.UNRESOLVED,
            reason = reason,
        )

    private fun unresolvedShell(
        left: ManifestRecord,
        right: ManifestRecord,
        reason: SecurityIdentityContinuityReason,
    ): SecurityIdentityContinuityEvidence {
        val (first, second) = presentationSlots(left, right)
        return SecurityIdentityContinuityEvidence(
            firstArchiveId = first.archiveId,
            secondArchiveId = second.archiveId,
            firstSource = first.source.ifBlank { "unknown" },
            secondSource = second.source.ifBlank { "unknown" },
            firstProviderTicker = null,
            secondProviderTicker = null,
            firstProviderAsOfDate = null,
            secondProviderAsOfDate = null,
            firstEligibilityBoundaryAt = first.eligibilityBoundaryAt,
            secondEligibilityBoundaryAt = second.eligibilityBoundaryAt,
            evidenceEligibleAt = maxElig(first.eligibilityBoundaryAt, second.eligibilityBoundaryAt),
            firstShareClassFigi = null,
            secondShareClassFigi = null,
            firstCompositeFigi = null,
            secondCompositeFigi = null,
            firstPrimaryExchange = null,
            secondPrimaryExchange = null,
            status = SecurityIdentityContinuityStatus.UNRESOLVED,
            reason = reason,
        )
    }

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
