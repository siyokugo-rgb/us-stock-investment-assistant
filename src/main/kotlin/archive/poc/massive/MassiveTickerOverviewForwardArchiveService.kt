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

data class MassiveTickerOverviewArchiveResult(
    val record: ManifestRecord,
    val coverage: CoverageWindow,
    /** True only when status==OBSERVED and raw+manifest fully committed. */
    val observedIngestSucceeded: Boolean,
    val failureNotes: String? = null,
)

/**
 * Massive Stocks Ticker Overview → immutable raw archive + append-only manifest.
 *
 * Forbidden: SecurityId, DailyPrice.currency, MIC/venue resolution, IssuerId,
 * OpenFIGI auto-join, Massive PRICE auto-join, historical knownAt invention,
 * ProviderSymbolBindingEvidence changes, AV archive deletion.
 *
 * Reuses archive.poc common store/manifest/coverage primitives.
 */
class MassiveTickerOverviewForwardArchiveService(
    archiveRoot: Path,
    private val client: MassiveTickerOverviewArchiveClient,
    private val clock: () -> Instant = { Instant.now() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private val rawStore = ImmutableRawStore(archiveRoot)
    private val manifestStore =
        ManifestStore(
            archiveRoot
                .resolve(MassiveTickerOverviewArchiveClient.DOMAIN)
                .resolve(MassiveTickerOverviewArchiveClient.SOURCE)
                .resolve("manifest.jsonl"),
        )
    private val rawRelativeDir =
        "${MassiveTickerOverviewArchiveClient.DOMAIN}/" +
            "${MassiveTickerOverviewArchiveClient.SOURCE}/raw"

    fun archiveTickerOverview(
        ticker: String,
        date: String? = null,
    ): MassiveTickerOverviewArchiveResult =
        finalize(
            ticker = ticker.trim(),
            date = date?.trim()?.takeIf { it.isNotEmpty() },
            possession = client.executeTickerOverview(ticker.trim(), date),
        )

    fun archivePossessedResponse(
        ticker: String,
        possession: MassiveHttpPossession,
        date: String? = null,
    ): MassiveTickerOverviewArchiveResult {
        val trimmedTicker = ticker.trim()
        val normalizedDate = date?.trim()?.takeIf { it.isNotEmpty() }
        val expectedKey = client.requestKeyFor(trimmedTicker, normalizedDate)
        require(possession.requestKey == expectedKey) {
            "possession.requestKey mismatch: possession=${possession.requestKey} expected=$expectedKey " +
                "(refusing to archive with incorrect request provenance)"
        }
        return finalize(
            ticker = trimmedTicker,
            date = normalizedDate,
            possession = possession,
        )
    }

    private fun finalize(
        ticker: String,
        @Suppress("UNUSED_PARAMETER") date: String?,
        possession: MassiveHttpPossession,
    ): MassiveTickerOverviewArchiveResult {
        val requestKey = possession.requestKey
        val archiveId = idGenerator()
        val priors =
            manifestStore.findByRequestKey(
                MassiveTickerOverviewArchiveClient.DOMAIN,
                MassiveTickerOverviewArchiveClient.SOURCE,
                requestKey,
            )

        if (!possession.bodyFullyReceived) {
            val record =
                ManifestRecord(
                    archiveId = archiveId,
                    domain = MassiveTickerOverviewArchiveClient.DOMAIN,
                    source = MassiveTickerOverviewArchiveClient.SOURCE,
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
                        domain = MassiveTickerOverviewArchiveClient.DOMAIN,
                        source = MassiveTickerOverviewArchiveClient.SOURCE,
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
            MassiveTickerOverviewArchiveValidator.validate(
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
                        domain = MassiveTickerOverviewArchiveClient.DOMAIN,
                        source = MassiveTickerOverviewArchiveClient.SOURCE,
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
                domain = MassiveTickerOverviewArchiveClient.DOMAIN,
                source = MassiveTickerOverviewArchiveClient.SOURCE,
                requestKey = requestKey,
                // Never auto-set externalIdentifier from ticker/FIGI/CIK.
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
    ): MassiveTickerOverviewArchiveResult {
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
            return MassiveTickerOverviewArchiveResult(
                record = localFailure,
                coverage =
                    CoverageCalculator.forDomainSource(
                        runCatching { manifestStore.readAll() }.getOrDefault(emptyList()),
                        MassiveTickerOverviewArchiveClient.DOMAIN,
                        MassiveTickerOverviewArchiveClient.SOURCE,
                    ),
                observedIngestSucceeded = false,
                failureNotes = "manifest append failed: ${e.message}",
            )
        }
        return MassiveTickerOverviewArchiveResult(
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
            MassiveTickerOverviewArchiveClient.DOMAIN,
            MassiveTickerOverviewArchiveClient.SOURCE,
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
