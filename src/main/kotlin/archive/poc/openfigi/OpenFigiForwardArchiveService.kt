package archive.poc.openfigi

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

data class OpenFigiArchiveResult(
    val record: ManifestRecord,
    val coverage: CoverageWindow,
    /** True only when status==OBSERVED and raw+manifest fully committed. */
    val observedIngestSucceeded: Boolean,
    val failureNotes: String? = null,
)

/**
 * OpenFIGI mapping → immutable raw archive + append-only manifest.
 *
 * Forbidden: SecurityId generation, ticker→SecurityId, knownAt invention,
 * authoritative replacement, decision use of PROVIDER_FAILURE bodies.
 */
class OpenFigiForwardArchiveService(
    archiveRoot: Path,
    private val client: OpenFigiMappingClient,
    private val clock: () -> Instant = { Instant.now() },
    private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
    private val rawStore = ImmutableRawStore(archiveRoot)
    private val manifestStore =
        ManifestStore(
            archiveRoot
                .resolve(OpenFigiMappingClient.DOMAIN)
                .resolve(OpenFigiMappingClient.SOURCE)
                .resolve("manifest.jsonl"),
        )
    private val rawRelativeDir =
        "${OpenFigiMappingClient.DOMAIN}/${OpenFigiMappingClient.SOURCE}/raw"

    fun archiveMapping(jobs: List<OpenFigiMappingJob>): OpenFigiArchiveResult {
        val requestBody = OpenFigiMappingRequestBody.encode(jobs)
        return finalize(
            requestKey = OpenFigiMappingClient.requestKey(requestBody),
            requestJobCount = jobs.size,
            possession = client.executeMapping(requestBody),
        )
    }

    fun archivePossessedResponse(
        jobs: List<OpenFigiMappingJob>,
        possession: OpenFigiHttpPossession,
    ): OpenFigiArchiveResult {
        val requestBody = OpenFigiMappingRequestBody.encode(jobs)
        return finalize(
            requestKey = OpenFigiMappingClient.requestKey(requestBody),
            requestJobCount = jobs.size,
            possession = possession,
        )
    }

    private fun finalize(
        requestKey: String,
        requestJobCount: Int,
        possession: OpenFigiHttpPossession,
    ): OpenFigiArchiveResult {
        val archiveId = idGenerator()
        val priors =
            manifestStore.findByRequestKey(
                OpenFigiMappingClient.DOMAIN,
                OpenFigiMappingClient.SOURCE,
                requestKey,
            )

        if (!possession.bodyFullyReceived) {
            val record =
                ManifestRecord(
                    archiveId = archiveId,
                    domain = OpenFigiMappingClient.DOMAIN,
                    source = OpenFigiMappingClient.SOURCE,
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
                        domain = OpenFigiMappingClient.DOMAIN,
                        source = OpenFigiMappingClient.SOURCE,
                        requestKey = requestKey,
                        attemptedAt = possession.attemptedAt,
                        attemptFinishedAt = possession.attemptFinishedAt,
                        fetchedAt = fetchedAt,
                        ingestedAt = clock(),
                        rawPayloadHash = hash,
                        contentType = possession.contentType,
                        transportStatus = TransportStatus.LOCAL_FAILURE,
                        observationStatus = ObservationStatus.PROVIDER_FAILURE,
                        eligibilityBoundaryAt = null,
                        notes = "missing httpStatus despite body possession",
                    ),
                    observedOk = false,
                    failureNotes = "missing httpStatus",
                )

        val validation =
            OpenFigiMappingValidator.validate(
                httpStatus = httpStatus,
                bodyBytes = body,
                requestJobCount = requestJobCount,
            )
        val status =
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
                        domain = OpenFigiMappingClient.DOMAIN,
                        source = OpenFigiMappingClient.SOURCE,
                        requestKey = requestKey,
                        attemptedAt = possession.attemptedAt,
                        attemptFinishedAt = possession.attemptFinishedAt,
                        fetchedAt = fetchedAt,
                        ingestedAt = clock(),
                        rawPayloadHash = hash,
                        contentType = possession.contentType,
                        httpStatus = httpStatus,
                        transportStatus = TransportStatus.LOCAL_FAILURE,
                        observationStatus = ObservationStatus.PROVIDER_FAILURE,
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
                domain = OpenFigiMappingClient.DOMAIN,
                source = OpenFigiMappingClient.SOURCE,
                requestKey = requestKey,
                externalIdentifier = validation.externalIdentifier,
                externalIdentifierNamespace = validation.externalIdentifierNamespace,
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
                observationStatus = status,
                eligibilityBoundaryAt =
                    if (status == ObservationStatus.OBSERVED) ingestedAt else null,
                notes = validation.notes,
            )
        return commit(record, observedOk = status == ObservationStatus.OBSERVED)
    }

    private fun commit(
        record: ManifestRecord,
        observedOk: Boolean,
        failureNotes: String? = null,
    ): OpenFigiArchiveResult {
        try {
            manifestStore.append(record)
        } catch (e: ArchiveIoException) {
            return OpenFigiArchiveResult(
                record = record,
                coverage =
                    CoverageCalculator.forDomainSource(
                        emptyList(),
                        OpenFigiMappingClient.DOMAIN,
                        OpenFigiMappingClient.SOURCE,
                    ),
                observedIngestSucceeded = false,
                failureNotes = "manifest append failed: ${e.message}",
            )
        }
        return OpenFigiArchiveResult(
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
            OpenFigiMappingClient.DOMAIN,
            OpenFigiMappingClient.SOURCE,
        )

    fun readManifest(): List<ManifestRecord> = manifestStore.readAll()
}
