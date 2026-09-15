package archive.poc.alphavantage

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

data class AlphaVantageDailyArchiveResult(
    val record: ManifestRecord,
    val coverage: CoverageWindow,
    /** True only when status==OBSERVED and raw+manifest fully committed. */
    val observedIngestSucceeded: Boolean,
    val failureNotes: String? = null,
)

/**
 * Alpha Vantage TIME_SERIES_DAILY → immutable raw archive + append-only manifest.
 *
 * Forbidden: SecurityId generation, symbol→SecurityId, currency invention,
 * historical knownAt invention, DailyPrice mapping, adjusted-series merge,
 * decision use of PROVIDER_FAILURE / LOCAL_ARCHIVE_FAILURE bodies.
 *
 * Reuses archive.poc common store/manifest/coverage primitives (PR #19).
 */
class AlphaVantageDailyForwardArchiveService(
    archiveRoot: Path,
    private val client: AlphaVantageDailyArchiveClient,
    private val clock: () -> Instant = { Instant.now() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
    private val outputSize: String = AlphaVantageDailyArchiveClient.DEFAULT_OUTPUT_SIZE,
) {
    private val rawStore = ImmutableRawStore(archiveRoot)
    private val manifestStore =
        ManifestStore(
            archiveRoot
                .resolve(AlphaVantageDailyArchiveClient.DOMAIN)
                .resolve(AlphaVantageDailyArchiveClient.SOURCE)
                .resolve("manifest.jsonl"),
        )
    private val rawRelativeDir =
        "${AlphaVantageDailyArchiveClient.DOMAIN}/${AlphaVantageDailyArchiveClient.SOURCE}/raw"

    fun archiveDaily(symbol: String): AlphaVantageDailyArchiveResult =
        finalize(
            symbol = symbol.trim(),
            possession = client.executeDaily(symbol.trim()),
        )

    fun archivePossessedResponse(
        symbol: String,
        possession: AlphaVantageHttpPossession,
    ): AlphaVantageDailyArchiveResult =
        finalize(
            symbol = symbol.trim(),
            possession = possession,
        )

    private fun finalize(
        symbol: String,
        possession: AlphaVantageHttpPossession,
    ): AlphaVantageDailyArchiveResult {
        val requestKey = AlphaVantageDailyArchiveClient.requestKey(symbol, outputSize)
        val archiveId = idGenerator()
        val priors =
            manifestStore.findByRequestKey(
                AlphaVantageDailyArchiveClient.DOMAIN,
                AlphaVantageDailyArchiveClient.SOURCE,
                requestKey,
            )

        if (!possession.bodyFullyReceived) {
            val record =
                ManifestRecord(
                    archiveId = archiveId,
                    domain = AlphaVantageDailyArchiveClient.DOMAIN,
                    source = AlphaVantageDailyArchiveClient.SOURCE,
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
                        domain = AlphaVantageDailyArchiveClient.DOMAIN,
                        source = AlphaVantageDailyArchiveClient.SOURCE,
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
            AlphaVantageDailyArchiveValidator.validate(
                httpStatus = httpStatus,
                bodyBytes = body,
                requestedSymbol = symbol,
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
                        domain = AlphaVantageDailyArchiveClient.DOMAIN,
                        source = AlphaVantageDailyArchiveClient.SOURCE,
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
                domain = AlphaVantageDailyArchiveClient.DOMAIN,
                source = AlphaVantageDailyArchiveClient.SOURCE,
                requestKey = requestKey,
                // Provider symbol is NOT elevated to externalIdentifier / SecurityId.
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
    ): AlphaVantageDailyArchiveResult {
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
            return AlphaVantageDailyArchiveResult(
                record = localFailure,
                coverage =
                    CoverageCalculator.forDomainSource(
                        runCatching { manifestStore.readAll() }.getOrDefault(emptyList()),
                        AlphaVantageDailyArchiveClient.DOMAIN,
                        AlphaVantageDailyArchiveClient.SOURCE,
                    ),
                observedIngestSucceeded = false,
                failureNotes = "manifest append failed: ${e.message}",
            )
        }
        return AlphaVantageDailyArchiveResult(
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
            AlphaVantageDailyArchiveClient.DOMAIN,
            AlphaVantageDailyArchiveClient.SOURCE,
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
