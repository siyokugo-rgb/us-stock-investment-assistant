package archive.poc.massive

import archive.poc.ArchiveIoException
import archive.poc.CoverageCalculator
import archive.poc.CoverageWindow
import archive.poc.ImmutableRawStore
import archive.poc.ManifestRecord
import archive.poc.ManifestStore
import archive.poc.ObservationStatus
import archive.poc.Sha256Hex
import archive.poc.TransportStatus
import java.nio.file.Path
import java.time.Instant
import java.util.UUID

data class MassiveTickerEventsArchiveResult(
    val record: ManifestRecord,
    val coverage: CoverageWindow,
    /** True only when status==OBSERVED and raw+manifest fully committed. */
    val observedIngestSucceeded: Boolean,
    val failureNotes: String? = null,
    /** Raw validation snapshot; never Security continuity / SecurityId. */
    val validation: MassiveTickerEventsValidationOutcome? = null,
)

/**
 * Massive Stocks Ticker Events → immutable raw archive + append-only manifest.
 *
 * Forbidden: SecurityId, SecurityIdentifier, knownAt, validFrom/validTo,
 * old/new invention, continuity inference, DailyPrice, MIC/venue, live-required path.
 *
 * Reuses archive.poc common store/manifest/coverage primitives unchanged.
 */
class MassiveTickerEventsForwardArchiveService(
    archiveRoot: Path,
    private val client: MassiveTickerEventsArchiveClient,
    private val clock: () -> Instant = { Instant.now() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private val rawStore = ImmutableRawStore(archiveRoot)
    private val manifestStore =
        ManifestStore(
            archiveRoot
                .resolve(MassiveTickerEventsArchiveClient.DOMAIN)
                .resolve(MassiveTickerEventsArchiveClient.SOURCE)
                .resolve("manifest.jsonl"),
        )
    private val rawRelativeDir =
        "${MassiveTickerEventsArchiveClient.DOMAIN}/" +
            "${MassiveTickerEventsArchiveClient.SOURCE}/raw"

    fun archiveTickerEvents(lookupId: String): MassiveTickerEventsArchiveResult =
        finalize(
            lookupId = MassiveTickerEventsArchiveClient.normalizeLookupIdOrThrow(lookupId),
            possession = client.executeTickerEvents(lookupId),
        )

    fun archivePossessedResponse(
        lookupId: String,
        possession: MassiveHttpPossession,
    ): MassiveTickerEventsArchiveResult {
        val id = MassiveTickerEventsArchiveClient.normalizeLookupIdOrThrow(lookupId)
        val expectedKey = client.requestKeyFor(id)
        require(possession.requestKey == expectedKey) {
            "possession.requestKey mismatch: possession=${possession.requestKey} expected=$expectedKey " +
                "(refusing to archive with incorrect request provenance)"
        }
        return finalize(lookupId = id, possession = possession)
    }

    private fun finalize(
        @Suppress("UNUSED_PARAMETER") lookupId: String,
        possession: MassiveHttpPossession,
    ): MassiveTickerEventsArchiveResult {
        val requestKey = possession.requestKey
        // Fail-Closed provenance check (canonical types=ticker_change).
        MassiveTickerEventsRequestKey.parseOrThrow(requestKey)
        val archiveId = idGenerator()
        val priors =
            manifestStore.findByRequestKey(
                MassiveTickerEventsArchiveClient.DOMAIN,
                MassiveTickerEventsArchiveClient.SOURCE,
                requestKey,
            )

        if (!possession.bodyFullyReceived) {
            val record =
                ManifestRecord(
                    archiveId = archiveId,
                    domain = MassiveTickerEventsArchiveClient.DOMAIN,
                    source = MassiveTickerEventsArchiveClient.SOURCE,
                    requestKey = requestKey,
                    attemptedAt = possession.attemptedAt,
                    attemptFinishedAt = possession.attemptFinishedAt,
                    fetchedAt = null,
                    ingestedAt = clock(),
                    httpStatus = possession.httpStatus,
                    transportStatus = TransportStatus.TRANSPORT_FAILURE,
                    observationStatus = ObservationStatus.PROVIDER_FAILURE,
                    eligibilityBoundaryAt = null,
                    notes = possession.transportFailureMessage ?: "transport failure",
                )
            return commit(record, observedOk = false)
        }

        val body = possession.bodyBytes!!
        val fetchedAt = possession.fetchedAt!!
        val hash = Sha256Hex.of(body)
        val httpStatus =
            possession.httpStatus
                ?: return commit(
                    ManifestRecord(
                        archiveId = archiveId,
                        domain = MassiveTickerEventsArchiveClient.DOMAIN,
                        source = MassiveTickerEventsArchiveClient.SOURCE,
                        requestKey = requestKey,
                        attemptedAt = possession.attemptedAt,
                        attemptFinishedAt = possession.attemptFinishedAt,
                        fetchedAt = fetchedAt,
                        ingestedAt = clock(),
                        rawPayloadHash = hash,
                        contentType = possession.contentType,
                        transportStatus = TransportStatus.LOCAL_FAILURE,
                        observationStatus = ObservationStatus.LOCAL_ARCHIVE_FAILURE,
                        eligibilityBoundaryAt = null,
                        notes = "missing httpStatus despite body possession",
                    ),
                    observedOk = false,
                    failureNotes = "missing httpStatus",
                )

        val validation =
            MassiveTickerEventsArchiveValidator.validate(
                httpStatus = httpStatus,
                bodyBytes = body,
            )
        val intendedStatus =
            when {
                httpStatus !in 200..299 -> ObservationStatus.PROVIDER_FAILURE
                !validation.okForObserved -> ObservationStatus.REJECTED_VALIDATION
                else -> ObservationStatus.OBSERVED
            }

        val duplicateOf =
            priors.firstOrNull { it.rawPayloadHash != null && it.rawPayloadHash == hash }?.archiveId
        val revisionCandidateOf =
            if (duplicateOf == null) {
                priors.lastOrNull { it.rawPayloadHash != null && it.rawPayloadHash != hash }?.archiveId
            } else {
                null
            }
        val related =
            if (duplicateOf != null || revisionCandidateOf != null) {
                priors.map { it.archiveId }.distinct()
            } else {
                emptyList()
            }

        val rawPath =
            try {
                rawStore.writeImmutable(
                    relativeDir = rawRelativeDir,
                    archiveId = archiveId,
                    payload = body,
                    expectedSha256Hex = hash,
                )
            } catch (e: ArchiveIoException) {
                return commit(
                    ManifestRecord(
                        archiveId = archiveId,
                        domain = MassiveTickerEventsArchiveClient.DOMAIN,
                        source = MassiveTickerEventsArchiveClient.SOURCE,
                        requestKey = requestKey,
                        attemptedAt = possession.attemptedAt,
                        attemptFinishedAt = possession.attemptFinishedAt,
                        fetchedAt = fetchedAt,
                        ingestedAt = clock(),
                        rawPayloadHash = hash,
                        rawPayloadUri = null,
                        contentType = possession.contentType,
                        httpStatus = httpStatus,
                        transportStatus = TransportStatus.LOCAL_FAILURE,
                        observationStatus = ObservationStatus.LOCAL_ARCHIVE_FAILURE,
                        eligibilityBoundaryAt = null,
                        notes = "raw write failed: ${e.message}",
                        duplicateOf = duplicateOf,
                        revisionCandidateOf = revisionCandidateOf,
                        relatedPriorObservationIds = related,
                    ),
                    observedOk = false,
                    failureNotes = e.message,
                    validation = validation,
                )
            }

        val ingestedAt = clock()
        val record =
            ManifestRecord(
                archiveId = archiveId,
                domain = MassiveTickerEventsArchiveClient.DOMAIN,
                source = MassiveTickerEventsArchiveClient.SOURCE,
                requestKey = requestKey,
                // Never auto-set from lookup id / ticker_change.ticker / CUSIP / Composite FIGI.
                externalIdentifier = null,
                externalIdentifierNamespace = null,
                observedFields = validation.observedFields,
                attemptedAt = possession.attemptedAt,
                attemptFinishedAt = possession.attemptFinishedAt,
                fetchedAt = fetchedAt,
                ingestedAt = ingestedAt,
                rawPayloadHash = hash,
                rawPayloadUri = rawPath.toString(),
                contentType = possession.contentType,
                httpStatus = httpStatus,
                transportStatus = TransportStatus.HTTP_RESPONSE,
                revisionCandidateOf = revisionCandidateOf,
                relatedPriorObservationIds = related,
                duplicateOf = duplicateOf,
                observationStatus = intendedStatus,
                eligibilityBoundaryAt =
                    if (intendedStatus == ObservationStatus.OBSERVED) ingestedAt else null,
                notes = validation.notes,
            )
        return commit(
            record,
            observedOk = intendedStatus == ObservationStatus.OBSERVED,
            validation = validation,
        )
    }

    private fun commit(
        record: ManifestRecord,
        observedOk: Boolean,
        failureNotes: String? = null,
        validation: MassiveTickerEventsValidationOutcome? = null,
    ): MassiveTickerEventsArchiveResult {
        try {
            manifestStore.append(record)
        } catch (e: ArchiveIoException) {
            val localFailure =
                record.copy(
                    observationStatus = ObservationStatus.LOCAL_ARCHIVE_FAILURE,
                    eligibilityBoundaryAt = null,
                    transportStatus = TransportStatus.LOCAL_FAILURE,
                    notes =
                        listOfNotNull(record.notes, "manifest append failed: ${e.message}")
                            .joinToString("; "),
                )
            return MassiveTickerEventsArchiveResult(
                record = localFailure,
                coverage =
                    CoverageCalculator.forDomainSource(
                        runCatching { manifestStore.readAll() }.getOrDefault(emptyList()),
                        MassiveTickerEventsArchiveClient.DOMAIN,
                        MassiveTickerEventsArchiveClient.SOURCE,
                    ),
                observedIngestSucceeded = false,
                failureNotes = "manifest append failed: ${e.message}",
                validation = validation,
            )
        }
        return MassiveTickerEventsArchiveResult(
            record = record,
            coverage = currentCoverage(),
            observedIngestSucceeded =
                observedOk &&
                    record.observationStatus == ObservationStatus.OBSERVED &&
                    failureNotes == null,
            failureNotes = failureNotes,
            validation = validation,
        )
    }

    fun currentCoverage(): CoverageWindow =
        CoverageCalculator.forDomainSource(
            manifestStore.readAll(),
            MassiveTickerEventsArchiveClient.DOMAIN,
            MassiveTickerEventsArchiveClient.SOURCE,
        )

    fun readManifest(): List<ManifestRecord> = manifestStore.readAll()

    /**
     * Audit-only: raw `*.raw` files under this domain/source not referenced by any
     * manifest `rawPayloadUri`. Never deletes, never promotes to OBSERVED.
     */
    fun findOrphanRawObjects(): List<Path> {
        val rawFiles = rawStore.listRawObjects(rawRelativeDir)
        val referenced =
            manifestStore
                .readAll()
                .mapNotNull { it.rawPayloadUri }
                .map { Path.of(it).normalize().toAbsolutePath() }
                .toSet()
        return rawFiles
            .map { it.toAbsolutePath().normalize() }
            .filter { it !in referenced }
            .sorted()
    }
}
