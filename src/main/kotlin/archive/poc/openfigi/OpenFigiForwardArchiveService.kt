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
    /**
     * True when secret-free request body was immutably stored and
     * [ManifestRecord.requestPayloadHash] matches the body hash embedded in [ManifestRecord.requestKey].
     * Independent of OBSERVED. Never invents SecurityId / knownAt / DailyPrice join.
     */
    val bindingProvenanceReady: Boolean,
    val failureNotes: String? = null,
)

/**
 * OpenFIGI mapping → immutable request+response raw archive + append-only manifest.
 *
 * Forbidden: SecurityId generation, ticker→SecurityId, knownAt invention,
 * first-FIGI auto-selection under ambiguity, authoritative replacement,
 * decision use of PROVIDER_FAILURE / LOCAL_ARCHIVE_FAILURE bodies,
 * ProviderSymbolBindingEvidence auto-join / symbol string-match join.
 */
class OpenFigiForwardArchiveService(
    private val archiveRoot: Path,
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
    private val requestRelativeDir =
        "${OpenFigiMappingClient.DOMAIN}/${OpenFigiMappingClient.SOURCE}/request"

    fun archiveMapping(jobs: List<OpenFigiMappingJob>): OpenFigiArchiveResult {
        val requestBody = OpenFigiMappingRequestBody.encode(jobs)
        return finalize(
            requestBodyBytes = requestBody,
            requestJobCount = jobs.size,
            possession = client.executeMapping(requestBody),
        )
    }

    /**
     * Synthetic / possessed-path ingest. [requestBodyBytes] must be the exact bytes that
     * correspond to [possession] / [requestJobCount] (no re-serialize).
     */
    fun archivePossessedResponse(
        requestBodyBytes: ByteArray,
        requestJobCount: Int,
        possession: OpenFigiHttpPossession,
    ): OpenFigiArchiveResult =
        finalize(
            requestBodyBytes = requestBodyBytes,
            requestJobCount = requestJobCount,
            possession = possession,
        )

    /** Convenience for tests that still hold jobs; encodes once and uses those exact bytes. */
    fun archivePossessedResponse(
        jobs: List<OpenFigiMappingJob>,
        possession: OpenFigiHttpPossession,
    ): OpenFigiArchiveResult {
        val requestBody = OpenFigiMappingRequestBody.encode(jobs)
        return archivePossessedResponse(
            requestBodyBytes = requestBody,
            requestJobCount = jobs.size,
            possession = possession,
        )
    }

    private fun finalize(
        requestBodyBytes: ByteArray,
        requestJobCount: Int,
        possession: OpenFigiHttpPossession,
    ): OpenFigiArchiveResult {
        val requestKey = OpenFigiMappingClient.requestKey(requestBodyBytes)
        val requestPayloadHash = Sha256Hex.of(requestBodyBytes)
        val archiveId = idGenerator()
        val priors =
            manifestStore.findByRequestKey(
                OpenFigiMappingClient.DOMAIN,
                OpenFigiMappingClient.SOURCE,
                requestKey,
            )

        if (!requestKeyBodyHashMatches(requestKey, requestPayloadHash)) {
            return commit(
                ManifestRecord(
                    archiveId = archiveId,
                    domain = OpenFigiMappingClient.DOMAIN,
                    source = OpenFigiMappingClient.SOURCE,
                    requestKey = requestKey,
                    attemptedAt = possession.attemptedAt,
                    attemptFinishedAt = possession.attemptFinishedAt,
                    fetchedAt = possession.fetchedAt,
                    ingestedAt = clock(),
                    rawPayloadHash = possession.bodyBytes?.let { Sha256Hex.of(it) },
                    rawPayloadUri = null,
                    contentType = possession.contentType,
                    httpStatus = possession.httpStatus,
                    transportStatus =
                        if (possession.bodyFullyReceived) {
                            TransportStatus.LOCAL_FAILURE
                        } else {
                            TransportStatus.TRANSPORT_FAILURE
                        },
                    observationStatus = ObservationStatus.LOCAL_ARCHIVE_FAILURE,
                    eligibilityBoundaryAt = null,
                    notes = "requestKey body hash mismatch vs requestPayloadHash; binding provenance forbidden",
                ),
                observedOk = false,
                bindingReady = false,
                failureNotes = "requestKey/requestPayloadHash mismatch",
            )
        }

        val requestPath =
            try {
                rawStore.writeImmutable(
                    relativeDir = requestRelativeDir,
                    archiveId = archiveId,
                    payload = requestBodyBytes,
                    expectedSha256Hex = requestPayloadHash,
                    fileName = "$archiveId.request.raw",
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
                        fetchedAt = possession.fetchedAt,
                        ingestedAt = clock(),
                        rawPayloadHash = possession.bodyBytes?.let { Sha256Hex.of(it) },
                        rawPayloadUri = null,
                        contentType = possession.contentType,
                        httpStatus = possession.httpStatus,
                        transportStatus = TransportStatus.LOCAL_FAILURE,
                        observationStatus = ObservationStatus.LOCAL_ARCHIVE_FAILURE,
                        eligibilityBoundaryAt = null,
                        notes = "request raw write failed: ${e.message}",
                    ),
                    observedOk = false,
                    bindingReady = false,
                    failureNotes = e.message,
                )
            }

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
                    requestPayloadHash = requestPayloadHash,
                    requestPayloadUri = requestPath.toString(),
                    notes = possession.transportFailureMessage ?: "transport failure",
                )
            return commit(record, observedOk = false, bindingReady = true)
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
                        observationStatus = ObservationStatus.LOCAL_ARCHIVE_FAILURE,
                        eligibilityBoundaryAt = null,
                        requestPayloadHash = requestPayloadHash,
                        requestPayloadUri = requestPath.toString(),
                        notes = "missing httpStatus despite body possession",
                    ),
                    observedOk = false,
                    bindingReady = true,
                    failureNotes = "missing httpStatus",
                )

        val validation =
            OpenFigiMappingValidator.validate(
                httpStatus = httpStatus,
                bodyBytes = body,
                requestJobCount = requestJobCount,
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
                        domain = OpenFigiMappingClient.DOMAIN,
                        source = OpenFigiMappingClient.SOURCE,
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
                        requestPayloadHash = requestPayloadHash,
                        requestPayloadUri = requestPath.toString(),
                        notes = "raw write failed: ${e.message}",
                        duplicateOf = duplicateOf,
                        revisionCandidateOf = revisionCandidateOf,
                        relatedPriorObservationIds = related,
                    ),
                    observedOk = false,
                    bindingReady = true,
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
                requestPayloadHash = requestPayloadHash,
                requestPayloadUri = requestPath.toString(),
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
            bindingReady = true,
        )
    }

    private fun commit(
        record: ManifestRecord,
        observedOk: Boolean,
        bindingReady: Boolean,
        failureNotes: String? = null,
    ): OpenFigiArchiveResult {
        try {
            manifestStore.append(record)
        } catch (e: ArchiveIoException) {
            val localFailure =
                record.copy(
                    observationStatus = ObservationStatus.LOCAL_ARCHIVE_FAILURE,
                    eligibilityBoundaryAt = null,
                    transportStatus = TransportStatus.LOCAL_FAILURE,
                    // Request provenance may exist on disk, but failed manifest means not
                    // safely binding-ready as an archived join input.
                    notes =
                        listOfNotNull(record.notes, "manifest append failed: ${e.message}")
                            .joinToString("; "),
                )
            return OpenFigiArchiveResult(
                record = localFailure,
                coverage =
                    CoverageCalculator.forDomainSource(
                        runCatching { manifestStore.readAll() }.getOrDefault(emptyList()),
                        OpenFigiMappingClient.DOMAIN,
                        OpenFigiMappingClient.SOURCE,
                    ),
                observedIngestSucceeded = false,
                bindingProvenanceReady = false,
                failureNotes = "manifest append failed: ${e.message}",
            )
        }
        // Binding-ready = secret-free request bytes immutably archived + hash aligns with requestKey.
        // Independent of OBSERVED / response raw success (those are separate gates).
        val bindingProvenanceReady =
            bindingReady &&
                record.requestPayloadHash != null &&
                record.requestPayloadUri != null &&
                requestKeyBodyHashMatches(record.requestKey, record.requestPayloadHash!!)

        return OpenFigiArchiveResult(
            record = record,
            coverage = currentCoverage(),
            observedIngestSucceeded =
                observedOk &&
                    record.observationStatus == ObservationStatus.OBSERVED &&
                    failureNotes == null,
            bindingProvenanceReady = bindingProvenanceReady,
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

    /**
     * Audit-only: raw `*.raw` files under response dir not referenced by any
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

    companion object {
        fun requestKeyBodyHashMatches(
            requestKey: String,
            requestPayloadHash: String,
        ): Boolean {
            val prefix = "POST|${OpenFigiMappingClient.MAPPING_PATH}|sha256:"
            if (!requestKey.startsWith(prefix)) return false
            return requestKey.removePrefix(prefix) == requestPayloadHash
        }
    }
}
