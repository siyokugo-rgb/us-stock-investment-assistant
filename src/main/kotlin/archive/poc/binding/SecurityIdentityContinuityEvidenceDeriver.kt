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
 * Derives [SecurityIdentityContinuityEvidence] from two same-source Massive archives.
 *
 * Ordering uses requestKey provider as-of `date=` only — never ingest/fetch/eligibility.
 * Cross-source Overview↔All Tickers compare is rejected ([SecurityIdentityContinuityReason.SOURCE_PAIR_MISMATCH]).
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

        val earlierLater = orderByProviderAsOf(leftSnap, rightSnap)
        if (earlierLater == null) {
            // Prefer snapshot-level unresolved (e.g. non-OBSERVED never parsed date)
            // over PROVIDER_AS_OF_MISSING when both apply.
            val reason =
                leftSnap.unresolvedReason
                    ?: rightSnap.unresolvedReason
                    ?: SecurityIdentityContinuityReason.PROVIDER_AS_OF_MISSING
            return buildUnresolved(
                a = leftSnap,
                b = rightSnap,
                earlier = leftSnap,
                later = rightSnap,
                reason = reason,
            )
        }
        val (earlier, later) = earlierLater

        if (earlier.providerAsOfDate == later.providerAsOfDate) {
            return classifySameAsOf(earlier, later)
        }

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

    /**
     * Returns (earlier, later) by provider as-of date, or null if either date omitted.
     * Argument order does not matter.
     */
    private fun orderByProviderAsOf(
        a: Snapshot,
        b: Snapshot,
    ): Pair<Snapshot, Snapshot>? {
        val da = a.providerAsOfDate
        val db = b.providerAsOfDate
        if (da.isNullOrBlank() || db.isNullOrBlank()) return null
        return if (da <= db) a to b else b to a
    }

    private fun classifySameAsOf(
        earlier: Snapshot,
        later: Snapshot,
    ): SecurityIdentityContinuityEvidence {
        if (earlier.unresolvedReason != null || later.unresolvedReason != null) {
            return buildUnresolved(
                a = earlier,
                b = later,
                earlier = earlier,
                later = later,
                reason =
                    earlier.unresolvedReason
                        ?: later.unresolvedReason!!,
            )
        }
        if (identityLayerConflict(earlier, later)) {
            return buildConflict(earlier, later)
        }
        val scA = earlier.shareClassFigi
        val scB = later.shareClassFigi
        if (!scA.isNullOrBlank() && !scB.isNullOrBlank() && scA != scB) {
            return buildConflict(earlier, later)
        }
        return buildUnresolved(
            a = earlier,
            b = later,
            earlier = earlier,
            later = later,
            reason = SecurityIdentityContinuityReason.SAME_AS_OF_NOT_CROSS_TIME,
        )
    }

    private fun classifyCrossTime(
        earlier: Snapshot,
        later: Snapshot,
    ): SecurityIdentityContinuityEvidence {
        if (earlier.unresolvedReason != null || later.unresolvedReason != null) {
            return buildUnresolved(
                a = earlier,
                b = later,
                earlier = earlier,
                later = later,
                reason =
                    earlier.unresolvedReason
                        ?: later.unresolvedReason!!,
            )
        }

        if (identityLayerConflict(earlier, later)) {
            return buildConflict(earlier, later)
        }

        val scA = earlier.shareClassFigi
        val scB = later.shareClassFigi
        if (scA.isNullOrBlank() || scB.isNullOrBlank()) {
            return buildUnresolved(
                a = earlier,
                b = later,
                earlier = earlier,
                later = later,
                reason = SecurityIdentityContinuityReason.SHARE_CLASS_IDENTITY_MISSING,
            )
        }

        val tickerA = earlier.providerTicker
        val tickerB = later.providerTicker
        if (tickerA.isNullOrBlank() || tickerB.isNullOrBlank()) {
            return buildUnresolved(
                a = earlier,
                b = later,
                earlier = earlier,
                later = later,
                reason = SecurityIdentityContinuityReason.SNAPSHOT_AMBIGUOUS,
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
                    a = earlier,
                    b = later,
                    earlier = earlier,
                    later = later,
                    reason = SecurityIdentityContinuityReason.SNAPSHOT_AMBIGUOUS,
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
            earlierArchiveId = earlier.record.archiveId,
            laterArchiveId = later.record.archiveId,
            source = earlier.record.source,
            earlierProviderTicker = earlier.providerTicker,
            laterProviderTicker = later.providerTicker,
            earlierProviderAsOfDate = earlier.providerAsOfDate,
            laterProviderAsOfDate = later.providerAsOfDate,
            earlierEligibilityBoundaryAt = earlier.record.eligibilityBoundaryAt,
            laterEligibilityBoundaryAt = later.record.eligibilityBoundaryAt,
            evidenceEligibleAt =
                maxElig(
                    earlier.record.eligibilityBoundaryAt,
                    later.record.eligibilityBoundaryAt,
                ),
            earlierShareClassFigi = earlier.shareClassFigi,
            laterShareClassFigi = later.shareClassFigi,
            earlierCompositeFigi = earlier.compositeFigi,
            laterCompositeFigi = later.compositeFigi,
            earlierPrimaryExchange = earlier.primaryExchange,
            laterPrimaryExchange = later.primaryExchange,
            status = status,
            reason = null,
        )

    private fun buildConflict(
        earlier: Snapshot,
        later: Snapshot,
    ): SecurityIdentityContinuityEvidence =
        SecurityIdentityContinuityEvidence(
            earlierArchiveId = earlier.record.archiveId,
            laterArchiveId = later.record.archiveId,
            source = earlier.record.source,
            earlierProviderTicker = earlier.providerTicker,
            laterProviderTicker = later.providerTicker,
            earlierProviderAsOfDate = earlier.providerAsOfDate,
            laterProviderAsOfDate = later.providerAsOfDate,
            earlierEligibilityBoundaryAt = earlier.record.eligibilityBoundaryAt,
            laterEligibilityBoundaryAt = later.record.eligibilityBoundaryAt,
            evidenceEligibleAt =
                maxElig(
                    earlier.record.eligibilityBoundaryAt,
                    later.record.eligibilityBoundaryAt,
                ),
            earlierShareClassFigi = earlier.shareClassFigi,
            laterShareClassFigi = later.shareClassFigi,
            earlierCompositeFigi = earlier.compositeFigi,
            laterCompositeFigi = later.compositeFigi,
            earlierPrimaryExchange = earlier.primaryExchange,
            laterPrimaryExchange = later.primaryExchange,
            status = SecurityIdentityContinuityStatus.CONFLICT,
            reason = SecurityIdentityContinuityReason.IDENTITY_LAYER_CONFLICT,
        )

    private fun buildUnresolved(
        a: Snapshot,
        b: Snapshot,
        earlier: Snapshot,
        later: Snapshot,
        reason: SecurityIdentityContinuityReason,
    ): SecurityIdentityContinuityEvidence =
        SecurityIdentityContinuityEvidence(
            earlierArchiveId = earlier.record.archiveId,
            laterArchiveId = later.record.archiveId,
            source = a.record.source,
            earlierProviderTicker = earlier.providerTicker,
            laterProviderTicker = later.providerTicker,
            earlierProviderAsOfDate = earlier.providerAsOfDate,
            laterProviderAsOfDate = later.providerAsOfDate,
            earlierEligibilityBoundaryAt = earlier.record.eligibilityBoundaryAt,
            laterEligibilityBoundaryAt = later.record.eligibilityBoundaryAt,
            evidenceEligibleAt =
                maxElig(
                    earlier.record.eligibilityBoundaryAt,
                    later.record.eligibilityBoundaryAt,
                ),
            earlierShareClassFigi = earlier.shareClassFigi,
            laterShareClassFigi = later.shareClassFigi,
            earlierCompositeFigi = earlier.compositeFigi,
            laterCompositeFigi = later.compositeFigi,
            earlierPrimaryExchange = earlier.primaryExchange,
            laterPrimaryExchange = later.primaryExchange,
            status = SecurityIdentityContinuityStatus.UNRESOLVED,
            reason = reason,
        )

    private fun unresolvedShell(
        left: ManifestRecord,
        right: ManifestRecord,
        reason: SecurityIdentityContinuityReason,
    ): SecurityIdentityContinuityEvidence =
        SecurityIdentityContinuityEvidence(
            earlierArchiveId = left.archiveId,
            laterArchiveId = right.archiveId,
            source = left.source.ifBlank { right.source },
            earlierProviderTicker = null,
            laterProviderTicker = null,
            earlierProviderAsOfDate = null,
            laterProviderAsOfDate = null,
            earlierEligibilityBoundaryAt = left.eligibilityBoundaryAt,
            laterEligibilityBoundaryAt = right.eligibilityBoundaryAt,
            evidenceEligibleAt = maxElig(left.eligibilityBoundaryAt, right.eligibilityBoundaryAt),
            earlierShareClassFigi = null,
            laterShareClassFigi = null,
            earlierCompositeFigi = null,
            laterCompositeFigi = null,
            earlierPrimaryExchange = null,
            laterPrimaryExchange = null,
            status = SecurityIdentityContinuityStatus.UNRESOLVED,
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
