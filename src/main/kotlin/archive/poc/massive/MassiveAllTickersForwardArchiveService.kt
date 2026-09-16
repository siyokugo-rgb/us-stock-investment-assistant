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

data class MassiveAllTickersArchiveResult(
    val record: ManifestRecord,
    val coverage: CoverageWindow,
    /** True only when status==OBSERVED and raw+manifest fully committed. */
    val observedIngestSucceeded: Boolean,
    val failureNotes: String? = null,
)

/**
 * Massive Stocks All Tickers → immutable raw archive + append-only manifest.
 *
 * Forbidden: SecurityId, DailyPrice.currency, MIC/venue resolution, IssuerId,
 * OpenFIGI auto-join, historical knownAt invention, Trading Currency SOLVED claim,
 * complete Security Master claim, pagination follow.
 *
 * Reuses archive.poc common store/manifest/coverage primitives.
 */
class MassiveAllTickersForwardArchiveService(
    archiveRoot: Path,
    private val client: MassiveAllTickersArchiveClient,
    private val clock: () -> Instant = { Instant.now() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private val rawStore = ImmutableRawStore(archiveRoot)
    private val manifestStore =
        ManifestStore(
            archiveRoot
                .resolve(MassiveAllTickersArchiveClient.DOMAIN)
                .resolve(MassiveAllTickersArchiveClient.SOURCE)
                .resolve("manifest.jsonl"),
        )
    private val rawRelativeDir =
        "${MassiveAllTickersArchiveClient.DOMAIN}/" +
            "${MassiveAllTickersArchiveClient.SOURCE}/raw"

    fun archiveAllTickers(
        ticker: String? = null,
        active: Boolean? = null,
        date: String? = null,
    ): MassiveAllTickersArchiveResult =
        finalize(
            ticker = ticker?.trim()?.takeIf { it.isNotEmpty() },
            active = active,
            date = date?.trim()?.takeIf { it.isNotEmpty() },
            possession = client.executeAllTickers(ticker, active, date),
        )

    fun archivePossessedResponse(
        possession: MassiveHttpPossession,
        ticker: String? = null,
        active: Boolean? = null,
        date: String? = null,
    ): MassiveAllTickersArchiveResult {
        val normalizedTicker = ticker?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedDate = date?.trim()?.takeIf { it.isNotEmpty() }
        val expectedKey =
            client.requestKeyFor(
                ticker = normalizedTicker,
                active = active,
                date = normalizedDate,
            )
        require(possession.requestKey == expectedKey) {
            "possession.requestKey mismatch: possession=${possession.requestKey} expected=$expectedKey " +
                "(refusing to archive with incorrect request provenance)"
        }
        return finalize(
            ticker = normalizedTicker,
            active = active,
            date = normalizedDate,
            possession = possession,
        )
    }

    private fun finalize(
        ticker: String?,
        @Suppress("UNUSED_PARAMETER") active: Boolean?,
        @Suppress("UNUSED_PARAMETER") date: String?,
        possession: MassiveHttpPossession,
    ): MassiveAllTickersArchiveResult {
        val requestKey = possession.requestKey
        val archiveId = idGenerator()
        val priors =
            manifestStore.findByRequestKey(
                MassiveAllTickersArchiveClient.DOMAIN,
                MassiveAllTickersArchiveClient.SOURCE,
                requestKey,
            )

        if (!possession.bodyFullyReceived) {
            val record =
                ManifestRecord(
                    archiveId = archiveId,
                    domain = MassiveAllTickersArchiveClient.DOMAIN,
                    source = MassiveAllTickersArchiveClient.SOURCE,
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
                        domain = MassiveAllTickersArchiveClient.DOMAIN,
                        source = MassiveAllTickersArchiveClient.SOURCE,
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
            MassiveAllTickersArchiveValidator.validate(
                httpStatus = httpStatus,
                bodyBytes = body,
                requestedTicker = ticker,
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
                        domain = MassiveAllTickersArchiveClient.DOMAIN,
                        source = MassiveAllTickersArchiveClient.SOURCE,
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
                )
            }

        val ingestedAt = clock()
        val record =
            ManifestRecord(
                archiveId = archiveId,
                domain = MassiveAllTickersArchiveClient.DOMAIN,
                source = MassiveAllTickersArchiveClient.SOURCE,
                requestKey = requestKey,
                // Never auto-set externalIdentifier from ticker/FIGI/CIK/currency.
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
        return commit(record, observedOk = intendedStatus == ObservationStatus.OBSERVED)
    }

    private fun commit(
        record: ManifestRecord,
        observedOk: Boolean,
        failureNotes: String? = null,
    ): MassiveAllTickersArchiveResult {
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
            return MassiveAllTickersArchiveResult(
                record = localFailure,
                coverage =
                    CoverageCalculator.forDomainSource(
                        runCatching { manifestStore.readAll() }.getOrDefault(emptyList()),
                        MassiveAllTickersArchiveClient.DOMAIN,
                        MassiveAllTickersArchiveClient.SOURCE,
                    ),
                observedIngestSucceeded = false,
                failureNotes = "manifest append failed: ${e.message}",
            )
        }
        return MassiveAllTickersArchiveResult(
            record = record,
            coverage = currentCoverage(),
            observedIngestSucceeded =
                observedOk &&
                    record.observationStatus == ObservationStatus.OBSERVED &&
                    failureNotes == null,
            failureNotes = failureNotes,
        )
    }

    fun currentCoverage(): CoverageWindow =
        CoverageCalculator.forDomainSource(
            manifestStore.readAll(),
            MassiveAllTickersArchiveClient.DOMAIN,
            MassiveAllTickersArchiveClient.SOURCE,
        )

    fun readManifest(): List<ManifestRecord> = manifestStore.readAll()

    /**
     * Audit-only: raw `*.raw` files under this domain/source not referenced by any
     * manifest `rawPayloadUri`. Never deletes, never promotes to OBSERVED, never invents eligibility.
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
