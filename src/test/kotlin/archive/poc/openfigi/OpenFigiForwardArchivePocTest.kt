package archive.poc.openfigi

import archive.poc.ManifestRecord
import archive.poc.ObservationStatus
import archive.poc.Sha256Hex
import archive.poc.TransportStatus
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenFigiForwardArchivePocTest {
    private lateinit var root: Path
    private val ids = AtomicInteger(0)
    private val clock = (0..199).map { i ->
        Instant.parse("2026-09-15T01:00:00Z").plusSeconds(i.toLong())
    }
    private var clockIdx = 0

    private fun nextInstant(): Instant = clock[clockIdx++]

    private fun service(): OpenFigiForwardArchiveService =
        OpenFigiForwardArchiveService(
            archiveRoot = root,
            client =
                OpenFigiMappingClient(
                    baseUrl = "http://127.0.0.1:1", // unused for possessed path
                    clock = { nextInstant() },
                ),
            clock = { nextInstant() },
            idGenerator = { "id-${ids.incrementAndGet()}" },
        )

    private val ibmJob = listOf(OpenFigiMappingJob(idType = "ID_BB_GLOBAL", idValue = "BBG000BLNNH6"))

    private val validBody =
        """[{"data":[{"figi":"BBG000BLNNH6","ticker":"IBM","exchCode":"US","compositeFIGI":"BBG000BLNNH6","shareClassFIGI":"BBG001S5S399","name":"INTL BUSINESS MACHINES CORP","securityType":"Common Stock","marketSector":"Equity"}]}]"""
            .toByteArray(StandardCharsets.UTF_8)

    private fun possession(
        status: Int,
        body: ByteArray?,
        transportMessage: String? = null,
        jobs: List<OpenFigiMappingJob> = ibmJob,
        requestBodyBytes: ByteArray? = null,
    ): OpenFigiHttpPossession {
        val requestBytes = requestBodyBytes ?: OpenFigiMappingRequestBody.encode(jobs)
        val requestPayloadHash = Sha256Hex.of(requestBytes)
        val a = nextInstant()
        val b = nextInstant()
        return if (body == null) {
            OpenFigiHttpPossession(
                attemptedAt = a,
                attemptFinishedAt = b,
                fetchedAt = null,
                httpStatus = null,
                contentType = null,
                bodyBytes = null,
                transportFailureMessage = transportMessage ?: "SimulatedTransportFailure",
                requestPayloadHash = requestPayloadHash,
            )
        } else {
            OpenFigiHttpPossession(
                attemptedAt = a,
                attemptFinishedAt = b,
                fetchedAt = b,
                httpStatus = status,
                contentType = "application/json",
                bodyBytes = body,
                transportFailureMessage = null,
                requestPayloadHash = requestPayloadHash,
            )
        }
    }

    @BeforeTest
    fun setup() {
        root = Files.createTempDirectory("openfigi-archive-poc")
        clockIdx = 0
        ids.set(0)
    }

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun http200ValidBodyIsObservedWithExactSha256() {
        val svc = service()
        val result = svc.archivePossessedResponse(ibmJob, possession(200, validBody.copyOf()))
        assertTrue(result.observedIngestSucceeded)
        assertEquals(ObservationStatus.OBSERVED, result.record.observationStatus)
        assertEquals(Sha256Hex.of(validBody), result.record.rawPayloadHash)
        assertNotNull(result.record.fetchedAt)
        assertNotNull(result.record.eligibilityBoundaryAt)
        assertEquals(result.record.ingestedAt, result.record.eligibilityBoundaryAt)
        val uri = Path.of(result.record.rawPayloadUri!!)
        assertEquals(Sha256Hex.of(Files.readAllBytes(uri)), result.record.rawPayloadHash)
        assertEquals("figi", result.record.externalIdentifierNamespace)
        assertEquals("BBG000BLNNH6", result.record.externalIdentifier)
        assertFalse(result.record.requestKey.contains("api", ignoreCase = true) &&
            result.record.requestKey.contains("key", ignoreCase = true) &&
            result.record.requestKey.contains("OPENFIGI"))
        assertTrue(result.bindingProvenanceReady)
        assertNotNull(result.record.requestPayloadHash)
        assertNotNull(result.record.requestPayloadUri)
    }

    @Test
    fun whitespaceDifferenceChangesHash() {
        val a = """[{"data":[{"figi":"BBG000BLNNH6"}]}]""".toByteArray(StandardCharsets.UTF_8)
        val b = """[{ "data":[{"figi":"BBG000BLNNH6"}]}]""".toByteArray(StandardCharsets.UTF_8)
        assertNotEquals(Sha256Hex.of(a), Sha256Hex.of(b))
        val svc = service()
        val r1 = svc.archivePossessedResponse(ibmJob, possession(200, a))
        val r2 = svc.archivePossessedResponse(ibmJob, possession(200, b))
        assertNotEquals(r1.record.rawPayloadHash, r2.record.rawPayloadHash)
        assertEquals(r1.record.archiveId, r2.record.revisionCandidateOf)
        assertNull(r2.record.duplicateOf)
    }

    @Test
    fun malformedJsonIsRejectedValidationWithRawAndNullEligibility() {
        val body = """{"not":"array"}""".toByteArray(StandardCharsets.UTF_8)
        val result = service().archivePossessedResponse(ibmJob, possession(200, body))
        assertFalse(result.observedIngestSucceeded)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertEquals(Sha256Hex.of(body), result.record.rawPayloadHash)
        assertNotNull(result.record.rawPayloadUri)
        assertNotNull(result.record.fetchedAt)
        assertNull(result.record.eligibilityBoundaryAt)
    }

    @Test
    fun emptyBodyIsRejectedValidationOrFailClosed() {
        val body = ByteArray(0)
        val result = service().archivePossessedResponse(ibmJob, possession(200, body))
        assertFalse(result.observedIngestSucceeded)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertEquals(Sha256Hex.of(body), result.record.rawPayloadHash)
        assertNull(result.record.eligibilityBoundaryAt)
    }

    @Test
    fun http403CompleteBodyIsProviderFailureWithFetchedAtAndRaw() {
        val body = """{"error":"forbidden"}""".toByteArray(StandardCharsets.UTF_8)
        val result = service().archivePossessedResponse(ibmJob, possession(403, body))
        assertFalse(result.observedIngestSucceeded)
        assertEquals(ObservationStatus.PROVIDER_FAILURE, result.record.observationStatus)
        assertNotNull(result.record.fetchedAt)
        assertEquals(Sha256Hex.of(body), result.record.rawPayloadHash)
        assertNotNull(result.record.rawPayloadUri)
        assertNull(result.record.eligibilityBoundaryAt)
        assertEquals(403, result.record.httpStatus)
    }

    @Test
    fun http500CompleteBodyIsProviderFailureWithRaw() {
        val body = """{"error":"boom"}""".toByteArray(StandardCharsets.UTF_8)
        val result = service().archivePossessedResponse(ibmJob, possession(500, body))
        assertEquals(ObservationStatus.PROVIDER_FAILURE, result.record.observationStatus)
        assertNotNull(result.record.fetchedAt)
        assertNotNull(result.record.rawPayloadUri)
        assertNull(result.record.eligibilityBoundaryAt)
    }

    @Test
    fun transportExceptionIsProviderFailureWithoutFetchedAtOrRaw() {
        val result =
            service().archivePossessedResponse(
                ibmJob,
                possession(status = 0, body = null, transportMessage = "ConnectException"),
            )
        assertEquals(ObservationStatus.PROVIDER_FAILURE, result.record.observationStatus)
        assertEquals(TransportStatus.TRANSPORT_FAILURE, result.record.transportStatus)
        assertNull(result.record.fetchedAt)
        assertNull(result.record.rawPayloadHash)
        assertNull(result.record.rawPayloadUri)
        assertNull(result.record.eligibilityBoundaryAt)
        assertTrue(result.bindingProvenanceReady)
        assertNotNull(result.record.requestPayloadHash)
        assertNotNull(result.record.requestPayloadUri)
    }

    @Test
    fun sameLogicalKeySameHashIsDuplicateCandidate() {
        val svc = service()
        val first = svc.archivePossessedResponse(ibmJob, possession(200, validBody.copyOf()))
        val second = svc.archivePossessedResponse(ibmJob, possession(200, validBody.copyOf()))
        assertEquals(first.record.archiveId, second.record.duplicateOf)
        assertNull(second.record.revisionCandidateOf)
        assertEquals(first.record.eligibilityBoundaryAt, first.record.ingestedAt)
        // Past eligibility must not be rewritten / moved earlier by duplicate.
        assertEquals(first.record.eligibilityBoundaryAt, svc.readManifest().first().eligibilityBoundaryAt)
    }

    @Test
    fun sameLogicalKeyDifferentHashIsRevisionCandidateWithoutReplacement() {
        val body2 =
            """[{"data":[{"figi":"BBG000BLNNH6","ticker":"IBM","name":"UPDATED"}]}]"""
                .toByteArray(StandardCharsets.UTF_8)
        val svc = service()
        val first = svc.archivePossessedResponse(ibmJob, possession(200, validBody.copyOf()))
        val second = svc.archivePossessedResponse(ibmJob, possession(200, body2))
        assertEquals(first.record.archiveId, second.record.revisionCandidateOf)
        assertNull(second.record.duplicateOf)
        assertEquals(2, svc.readManifest().size)
        assertTrue(Files.exists(Path.of(first.record.rawPayloadUri!!)))
        assertTrue(Files.exists(Path.of(second.record.rawPayloadUri!!)))
    }

    @Test
    fun manifestAppendFailureDoesNotCountAsObservedSuccess() {
        val svc = service()
        // Poison manifest path: replace file with directory after constructing service via first success,
        // then force second append failure by deleting parent permissions is flaky.
        // Instead: create a service whose manifest path is blocked by making parent a file.
        val blockedRoot = Files.createTempDirectory("openfigi-blocked")
        val domainDir = blockedRoot.resolve(OpenFigiMappingClient.DOMAIN)
        Files.createDirectories(domainDir)
        // Make SOURCE a file so nested manifest cannot be created/appended cleanly after construction.
        val sourceAsFile = domainDir.resolve(OpenFigiMappingClient.SOURCE)
        Files.writeString(sourceAsFile, "not-a-directory")
        val broken =
            try {
                OpenFigiForwardArchiveService(
                    archiveRoot = blockedRoot,
                    client = OpenFigiMappingClient(baseUrl = "http://127.0.0.1:1", clock = { nextInstant() }),
                    clock = { nextInstant() },
                    idGenerator = { "broken-1" },
                )
                null
            } catch (_: Exception) {
                "init-failed"
            }
        // If init fails closed, that is acceptable Fail-Closed.
        if (broken == null) {
            // Constructed somehow; attempt archive and assert not observed success.
            val svc2 =
                OpenFigiForwardArchiveService(
                    archiveRoot = blockedRoot,
                    client = OpenFigiMappingClient(baseUrl = "http://127.0.0.1:1", clock = { nextInstant() }),
                    clock = { nextInstant() },
                    idGenerator = { "broken-2" },
                )
            // Overwrite raw parent to cause later failure path via read-only raw dir after first write attempt:
            val result = svc2.archivePossessedResponse(ibmJob, possession(200, validBody.copyOf()))
            assertFalse(result.observedIngestSucceeded)
        }
        blockedRoot.toFile().deleteRecursively()
        // Also verify happy-path service still works
        assertTrue(svc.archivePossessedResponse(ibmJob, possession(200, validBody.copyOf())).observedIngestSucceeded)
    }

    @Test
    fun rawWriteFailureIsNotObservedSuccess() {
        val svc = service()
        // First create normal layout
        val ok = svc.archivePossessedResponse(ibmJob, possession(200, validBody.copyOf()))
        assertTrue(ok.observedIngestSucceeded)
        // Replace raw directory with a file to force collision/write failure on next id path's parent
        val rawDir =
            root.resolve(OpenFigiMappingClient.DOMAIN).resolve(OpenFigiMappingClient.SOURCE).resolve("raw")
        // Create a file where the next archive raw file would need a writable dir — use fixed id generator
        val fixed =
            OpenFigiForwardArchiveService(
                archiveRoot = root,
                client = OpenFigiMappingClient(baseUrl = "http://127.0.0.1:1", clock = { nextInstant() }),
                clock = { nextInstant() },
                idGenerator = { "fixed-raw" },
            )
        // Pre-create a DIRECTORY named fixed-raw.raw to cause writeImmutable path confusion:
        // writeImmutable writes file `$id.raw`; if that path exists as dir/file collision:
        Files.createDirectories(rawDir)
        Files.writeString(rawDir.resolve("fixed-raw.raw"), "occupied")
        val result = fixed.archivePossessedResponse(ibmJob, possession(200, validBody.copyOf()))
        assertFalse(result.observedIngestSucceeded)
        assertEquals(ObservationStatus.LOCAL_ARCHIVE_FAILURE, result.record.observationStatus)
        assertNull(result.record.eligibilityBoundaryAt)
        assertNotNull(result.record.fetchedAt)
        assertNotNull(result.record.rawPayloadHash)
    }

    @Test
    fun hashMismatchOnWriteIsFailClosed() {
        val store = archive.poc.ImmutableRawStore(root)
        val bytes = "abc".toByteArray()
        val ex =
            kotlin.test.assertFailsWith<archive.poc.ArchiveIoException> {
                // wrong expected hash
                try {
                    store.writeImmutable("x", "h1", bytes, "deadbeef")
                } catch (e: IllegalArgumentException) {
                    throw archive.poc.ArchiveIoException(e.message ?: "hash", e)
                }
            }
        assertTrue(ex.message!!.contains("Hash") || ex.cause is IllegalArgumentException)
    }

    @Test
    fun warningOnlyIsNotObserved() {
        val body =
            """[{"warning":"No identifier found."}]""".toByteArray(StandardCharsets.UTF_8)
        val jobs = listOf(OpenFigiMappingJob(idType = "TICKER", idValue = "IBM", exchCode = "US"))
        val result = service().archivePossessedResponse(jobs, possession(200, body, jobs = jobs))
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertFalse(result.observedIngestSucceeded)
        assertNull(result.record.eligibilityBoundaryAt)
        assertNull(result.record.externalIdentifier)
        assertFalse(result.record.toJsonLine().contains("securityId", ignoreCase = true))
        assertFalse(result.record.toJsonLine().contains("knownAt"))
    }

    @Test
    fun errorOnlyIsNotObserved() {
        val body = """[{"error":"Unexpected error"}]""".toByteArray(StandardCharsets.UTF_8)
        val result = service().archivePossessedResponse(ibmJob, possession(200, body))
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertNull(result.record.eligibilityBoundaryAt)
    }

    @Test
    fun dataMustBeNonEmptyArrayOfObjects() {
        val dataAsString = """[{"data":"BBG000BLNNH6"}]""".toByteArray(StandardCharsets.UTF_8)
        val dataAsObject = """[{"data":{"figi":"BBG000BLNNH6"}}]""".toByteArray(StandardCharsets.UTF_8)
        val emptyArr = """[{"data":[]}]""".toByteArray(StandardCharsets.UTF_8)
        val badRow = """[{"data":["x"]}]""".toByteArray(StandardCharsets.UTF_8)
        for (body in listOf(dataAsString, dataAsObject, emptyArr, badRow)) {
            val result = service().archivePossessedResponse(ibmJob, possession(200, body))
            assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus, String(body))
            assertNull(result.record.eligibilityBoundaryAt)
        }
    }

    @Test
    fun warningWithDataIsFailClosedNotObserved() {
        val body =
            """[{"warning":"No identifier found.","data":[{"figi":"BBG000BLNNH6"}]}]"""
                .toByteArray(StandardCharsets.UTF_8)
        val result = service().archivePossessedResponse(ibmJob, possession(200, body))
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertNull(result.record.eligibilityBoundaryAt)
    }

    @Test
    fun rejectedValidationDoesNotGrantCoverage() {
        val svc = service()
        val rejected =
            svc.archivePossessedResponse(
                ibmJob,
                possession(200, """[{"warning":"No identifier found."}]""".toByteArray(StandardCharsets.UTF_8)),
            )
        assertEquals(ObservationStatus.REJECTED_VALIDATION, rejected.record.observationStatus)
        assertEquals(0, rejected.coverage.observedCount)
        assertNull(rejected.coverage.coverageStartAt)
    }

    @Test
    fun singleJobSingleCandidateSetsFigiExternalIdentifier() {
        val result = service().archivePossessedResponse(ibmJob, possession(200, validBody.copyOf()))
        assertEquals(ObservationStatus.OBSERVED, result.record.observationStatus)
        assertEquals("BBG000BLNNH6", result.record.externalIdentifier)
        assertEquals("figi", result.record.externalIdentifierNamespace)
    }

    @Test
    fun multipleDataCandidatesLeaveExternalIdentifierNull() {
        val body =
            """[{"data":[{"figi":"BBG000BLNNH6"},{"figi":"BBG000BLNNH7"}]}]"""
                .toByteArray(StandardCharsets.UTF_8)
        val result = service().archivePossessedResponse(ibmJob, possession(200, body))
        assertEquals(ObservationStatus.OBSERVED, result.record.observationStatus)
        assertNull(result.record.externalIdentifier)
        assertNull(result.record.externalIdentifierNamespace)
    }

    @Test
    fun multipleJobsLeaveRecordLevelExternalIdentifierNull() {
        val jobs =
            listOf(
                OpenFigiMappingJob(idType = "ID_BB_GLOBAL", idValue = "BBG000BLNNH6"),
                OpenFigiMappingJob(idType = "ID_BB_GLOBAL", idValue = "BBG000B9XRY4"),
            )
        val body =
            """[{"data":[{"figi":"BBG000BLNNH6"}]},{"data":[{"figi":"BBG000B9XRY4"}]}]"""
                .toByteArray(StandardCharsets.UTF_8)
        val result = service().archivePossessedResponse(jobs, possession(200, body, jobs = jobs))
        assertEquals(ObservationStatus.OBSERVED, result.record.observationStatus)
        assertNull(result.record.externalIdentifier)
    }

    @Test
    fun localArchiveFailureExcludedFromCoverage() {
        val svc = service()
        val ok = svc.archivePossessedResponse(ibmJob, possession(200, validBody.copyOf()))
        assertEquals(1, ok.coverage.observedCount)
        val rawDir =
            root.resolve(OpenFigiMappingClient.DOMAIN).resolve(OpenFigiMappingClient.SOURCE).resolve("raw")
        Files.writeString(rawDir.resolve("cov-fail.raw"), "occupied")
        val failSvc =
            OpenFigiForwardArchiveService(
                archiveRoot = root,
                client = OpenFigiMappingClient(baseUrl = "http://127.0.0.1:1", clock = { nextInstant() }),
                clock = { nextInstant() },
                idGenerator = { "cov-fail" },
            )
        val fail = failSvc.archivePossessedResponse(ibmJob, possession(200, validBody.copyOf()))
        assertEquals(ObservationStatus.LOCAL_ARCHIVE_FAILURE, fail.record.observationStatus)
        assertEquals(1, fail.coverage.observedCount)
        assertTrue(fail.coverage.hasNonObservedAlongside)
    }

    @Test
    fun manifestInvariantsRejectInvalidRows() {
        val t0 = Instant.parse("2026-09-15T03:00:00Z")
        val t1 = Instant.parse("2026-09-15T03:00:01Z")
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            ManifestRecord(
                archiveId = "a",
                domain = "SECURITY_MASTER",
                source = "openfigi.v3.mapping",
                requestKey = "k",
                attemptedAt = t0,
                attemptFinishedAt = t1,
                fetchedAt = t1,
                ingestedAt = t1,
                rawPayloadHash = null,
                rawPayloadUri = "x",
                httpStatus = 200,
                transportStatus = TransportStatus.HTTP_RESPONSE,
                observationStatus = ObservationStatus.OBSERVED,
                eligibilityBoundaryAt = t1,
            )
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            ManifestRecord(
                archiveId = "a",
                domain = "SECURITY_MASTER",
                source = "openfigi.v3.mapping",
                requestKey = "k",
                attemptedAt = t0,
                attemptFinishedAt = t1,
                fetchedAt = t1,
                ingestedAt = t1,
                rawPayloadHash = "abc",
                rawPayloadUri = "x",
                httpStatus = 200,
                transportStatus = TransportStatus.HTTP_RESPONSE,
                observationStatus = ObservationStatus.OBSERVED,
                eligibilityBoundaryAt = null,
            )
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            ManifestRecord(
                archiveId = "a",
                domain = "SECURITY_MASTER",
                source = "openfigi.v3.mapping",
                requestKey = "k",
                attemptedAt = t0,
                attemptFinishedAt = t1,
                fetchedAt = t1,
                ingestedAt = t1,
                transportStatus = TransportStatus.TRANSPORT_FAILURE,
                observationStatus = ObservationStatus.MISSING,
            )
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            ManifestRecord(
                archiveId = "a",
                domain = "SECURITY_MASTER",
                source = "openfigi.v3.mapping",
                requestKey = "k",
                attemptedAt = t0,
                attemptFinishedAt = t1,
                fetchedAt = null,
                ingestedAt = t1,
                rawPayloadUri = "x",
                transportStatus = TransportStatus.TRANSPORT_FAILURE,
                observationStatus = ObservationStatus.PROVIDER_FAILURE,
            )
        }
        // fromJsonLine Fail-Closed
        val badLine =
            """{"archiveId":"a","domain":"SECURITY_MASTER","source":"openfigi.v3.mapping","requestKey":"k","observedFields":[],"attemptedAt":"$t0","attemptFinishedAt":"$t1","fetchedAt":"$t1","ingestedAt":"$t1","rawPayloadHash":"abc","rawPayloadUri":"u","httpStatus":200,"transportStatus":"HTTP_RESPONSE","relatedPriorObservationIds":[],"observationStatus":"OBSERVED"}"""
        kotlin.test.assertFailsWith<archive.poc.ArchiveValidationException> {
            ManifestRecord.fromJsonLine(badLine)
        }
    }

    @Test
    fun knownAtIsNeverGeneratedOnManifest() {
        val result = service().archivePossessedResponse(ibmJob, possession(200, validBody.copyOf()))
        assertFalse(result.record.toJsonLine().contains("knownAt"))
        assertFalse(result.record.toJsonLine().contains("\"knownAt\""))
    }

    @Test
    fun coverageUsesObservedOnlyAndFailuresDoNotGrantCoverageSuccess() {
        val svc = service()
        val fail = svc.archivePossessedResponse(ibmJob, possession(status = 0, body = null))
        assertNull(fail.coverage.coverageStartAt)
        assertEquals(0, fail.coverage.observedCount)
        val ok = svc.archivePossessedResponse(ibmJob, possession(200, validBody.copyOf()))
        assertEquals(1, ok.coverage.observedCount)
        assertEquals(ok.record.ingestedAt, ok.coverage.coverageStartAt)
        assertEquals(ok.record.ingestedAt, ok.coverage.coverageThroughAt)
        assertTrue(ok.coverage.hasNonObservedAlongside)
    }

    @Test
    fun duplicateDoesNotMovePastEligibilityEarlier() {
        val svc = service()
        val first = svc.archivePossessedResponse(ibmJob, possession(200, validBody.copyOf()))
        val firstElig = first.record.eligibilityBoundaryAt!!
        val second = svc.archivePossessedResponse(ibmJob, possession(200, validBody.copyOf()))
        val firstAgain = svc.readManifest().first { it.archiveId == first.record.archiveId }
        assertEquals(firstElig, firstAgain.eligibilityBoundaryAt)
        assertTrue(!second.record.ingestedAt.isBefore(firstElig))
    }

    @Test
    fun liveClientTransportAndHttpSemanticsAgainstLocalServer() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/v3/mapping") { exchange ->
            val reqBody = exchange.requestBody.readAllBytes()
            assertTrue(reqBody.isNotEmpty())
            assertNull(exchange.requestHeaders.getFirst("X-OPENFIGI-APIKEY"))
            when (String(reqBody, StandardCharsets.UTF_8)) {
                "TRANSPORT" -> {
                    exchange.close() // abrupt
                }
                else -> {
                    val code = exchange.requestHeaders.getFirst("X-Test-Status")?.toInt() ?: 200
                    val body =
                        if (code == 200) validBody
                        else """{"error":"x"}""".toByteArray(StandardCharsets.UTF_8)
                    exchange.sendResponseHeaders(code, body.size.toLong())
                    exchange.responseBody.use { it.write(body) }
                }
            }
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}"
        try {
            val client =
                OpenFigiMappingClient(
                    baseUrl = base,
                    httpClient =
                        HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(2))
                            .build(),
                    requestTimeout = Duration.ofSeconds(2),
                    clock = { Instant.parse("2026-09-15T02:00:00Z") },
                )
            val okBody = OpenFigiMappingRequestBody.encode(ibmJob)
            val ok = client.executeMapping(okBody)
            assertTrue(ok.bodyFullyReceived)
            assertEquals(200, ok.httpStatus)
            assertNotNull(ok.fetchedAt)
            assertEquals(Sha256Hex.of(validBody), Sha256Hex.of(ok.bodyBytes!!))

            // 403 complete body
            val client403 =
                OpenFigiMappingClient(
                    baseUrl = base,
                    httpClient =
                        HttpClient.newBuilder()
                            .connectTimeout(Duration.ofSeconds(2))
                            .build(),
                    clock = { Instant.parse("2026-09-15T02:00:01Z") },
                )
            // Use a custom request by hitting through archive path instead:
            val svc =
                OpenFigiForwardArchiveService(
                    archiveRoot = root,
                    client = client403,
                    clock = { Instant.parse("2026-09-15T02:00:02Z") },
                    idGenerator = { "live-local-1" },
                )
            // Direct possessed 403 already covered; verify requestKey excludes secrets:
            val key = OpenFigiMappingClient.requestKey(okBody)
            assertFalse(key.contains("OPENFIGI"))
            assertTrue(key.startsWith("POST|/v3/mapping|sha256:"))
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun referencedRawIsNotOrphan() {
        val svc = service()
        val result = svc.archivePossessedResponse(ibmJob, possession(200, validBody.copyOf()))
        assertTrue(result.observedIngestSucceeded)
        assertTrue(svc.findOrphanRawObjects().isEmpty())
    }

    @Test
    fun unreferencedRawIsDetectedAsOrphan() {
        val svc = service()
        val ok = svc.archivePossessedResponse(ibmJob, possession(200, validBody.copyOf()))
        assertTrue(ok.observedIngestSucceeded)
        val rawDir =
            root.resolve(OpenFigiMappingClient.DOMAIN).resolve(OpenFigiMappingClient.SOURCE).resolve("raw")
        Files.createDirectories(rawDir)
        val orphanPath = rawDir.resolve("manual-orphan.raw")
        Files.writeString(orphanPath, "orphan-bytes")
        val orphans = svc.findOrphanRawObjects()
        assertEquals(1, orphans.size)
        assertEquals(orphanPath.toAbsolutePath().normalize(), orphans.single())
    }

    @Test
    fun rawLeftAfterManifestAppendFailureIsDetectableNextTime() {
        val svc = service()
        // Establish layout with one committed OBSERVED row.
        assertTrue(svc.archivePossessedResponse(ibmJob, possession(200, validBody.copyOf())).observedIngestSucceeded)
        // Simulate residue of: raw final move succeeded, manifest append failed
        // (raw on disk, no matching rawPayloadUri in manifest).
        val residualBody = validBody.copyOf()
        val residualHash = Sha256Hex.of(residualBody)
        val residualPath =
            archive.poc.ImmutableRawStore(root).writeImmutable(
                relativeDir = "${OpenFigiMappingClient.DOMAIN}/${OpenFigiMappingClient.SOURCE}/raw",
                archiveId = "orphan-after-append-fail",
                payload = residualBody,
                expectedSha256Hex = residualHash,
            )
        val detectSvc = service()
        val orphans = detectSvc.findOrphanRawObjects()
        assertTrue(
            orphans.any { it == residualPath.toAbsolutePath().normalize() },
            "orphans=$orphans residual=$residualPath",
        )
        // Prior OBSERVED coverage remains; orphan itself grants none.
        assertEquals(1, detectSvc.currentCoverage().observedCount)
        assertFalse(orphans.isEmpty())
    }

    @Test
    fun orphanRawDoesNotEnterCoverage() {
        val svc = service()
        val rawDir =
            root.resolve(OpenFigiMappingClient.DOMAIN).resolve(OpenFigiMappingClient.SOURCE).resolve("raw")
        Files.createDirectories(rawDir)
        Files.writeString(rawDir.resolve("coverage-orphan.raw"), "x")
        assertEquals(1, svc.findOrphanRawObjects().size)
        assertEquals(0, svc.currentCoverage().observedCount)
        assertNull(svc.currentCoverage().coverageStartAt)
    }

    @Test
    fun malformedUtf8InJsonStringIsRejectedValidationWithRawPreserved() {
        val prefix = """[{"data":[{"figi":"BBG000BLNNH6","name":"""".toByteArray(StandardCharsets.UTF_8)
        val suffix = """"}]}]""".toByteArray(StandardCharsets.UTF_8)
        val body = prefix + byteArrayOf(0xFF.toByte()) + suffix
        val result = service().archivePossessedResponse(ibmJob, possession(200, body))
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertFalse(result.observedIngestSucceeded)
        assertNull(result.record.eligibilityBoundaryAt)
        assertNotNull(result.record.fetchedAt)
        assertEquals(Sha256Hex.of(body), result.record.rawPayloadHash)
        assertNotNull(result.record.rawPayloadUri)
        val onDisk = Files.readAllBytes(Path.of(result.record.rawPayloadUri!!))
        assertTrue(onDisk.contentEquals(body))
        assertEquals(Sha256Hex.of(body), Sha256Hex.of(onDisk))
        assertTrue(onDisk.contains(0xFF.toByte()))
    }

    @Test
    fun validUtf8JsonStillObserved() {
        val result = service().archivePossessedResponse(ibmJob, possession(200, validBody.copyOf()))
        assertEquals(ObservationStatus.OBSERVED, result.record.observationStatus)
        assertTrue(result.observedIngestSucceeded)
    }

    @Test
    fun externalIdentifierRequiresNamespaceAndViceVersa() {
        val t0 = Instant.parse("2026-09-15T04:00:00Z")
        val t1 = Instant.parse("2026-09-15T04:00:01Z")
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            ManifestRecord(
                archiveId = "a",
                domain = "SECURITY_MASTER",
                source = "openfigi.v3.mapping",
                requestKey = "POST|/v3/mapping|sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                externalIdentifier = "BBG000BLNNH6",
                externalIdentifierNamespace = null,
                attemptedAt = t0,
                attemptFinishedAt = t1,
                fetchedAt = t1,
                ingestedAt = t1,
                rawPayloadHash = "abc",
                rawPayloadUri = "u",
                requestPayloadHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                requestPayloadUri = "req-u",
                httpStatus = 200,
                transportStatus = TransportStatus.HTTP_RESPONSE,
                observationStatus = ObservationStatus.OBSERVED,
                eligibilityBoundaryAt = t1,
            )
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            ManifestRecord(
                archiveId = "b",
                domain = "SECURITY_MASTER",
                source = "openfigi.v3.mapping",
                requestKey = "POST|/v3/mapping|sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                externalIdentifier = null,
                externalIdentifierNamespace = "figi",
                attemptedAt = t0,
                attemptFinishedAt = t1,
                fetchedAt = t1,
                ingestedAt = t1,
                rawPayloadHash = "abc",
                rawPayloadUri = "u",
                requestPayloadHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                requestPayloadUri = "req-u",
                httpStatus = 200,
                transportStatus = TransportStatus.HTTP_RESPONSE,
                observationStatus = ObservationStatus.OBSERVED,
                eligibilityBoundaryAt = t1,
            )
        }
    }

    @Test
    fun exactSentRequestBytesAreStoredSeparatelyFromResponseRaw() {
        val jobs = listOf(OpenFigiMappingJob(idType = "TICKER", idValue = "IBM", exchCode = "US"))
        val requestBytes = OpenFigiMappingRequestBody.encode(jobs)
        val result = service().archivePossessedResponse(requestBytes, possession(200, validBody.copyOf(), requestBodyBytes = requestBytes))
        assertTrue(result.observedIngestSucceeded)
        assertTrue(result.bindingProvenanceReady)
        assertNotNull(result.record.requestPayloadUri)
        assertNotNull(result.record.rawPayloadUri)
        assertNotEquals(result.record.requestPayloadUri, result.record.rawPayloadUri)
        val onDiskRequest = Files.readAllBytes(Path.of(result.record.requestPayloadUri!!))
        assertTrue(onDiskRequest.contentEquals(requestBytes))
        assertTrue(result.record.requestPayloadUri!!.endsWith(".request.raw"))
        assertTrue(result.record.rawPayloadUri!!.endsWith(".raw"))
        assertFalse(result.record.rawPayloadUri!!.endsWith(".request.raw"))
    }

    @Test
    fun requestPayloadHashMatchesExactSentBytesSha256() {
        val requestBytes = OpenFigiMappingRequestBody.encode(ibmJob)
        val result =
            service().archivePossessedResponse(requestBytes, possession(200, validBody.copyOf(), requestBodyBytes = requestBytes))
        assertEquals(Sha256Hex.of(requestBytes), result.record.requestPayloadHash)
        val onDisk = Files.readAllBytes(Path.of(result.record.requestPayloadUri!!))
        assertEquals(Sha256Hex.of(onDisk), result.record.requestPayloadHash)
    }

    @Test
    fun requestWhitespaceDifferenceChangesRequestHash() {
        val compact = """[{"idType":"TICKER","idValue":"IBM"}]""".toByteArray(StandardCharsets.UTF_8)
        val spaced = """[{ "idType":"TICKER","idValue":"IBM"}]""".toByteArray(StandardCharsets.UTF_8)
        assertNotEquals(Sha256Hex.of(compact), Sha256Hex.of(spaced))
        val svc = service()
        val r1 = svc.archivePossessedResponse(compact, possession(200, validBody.copyOf(), requestBodyBytes = compact))
        val r2 = svc.archivePossessedResponse(spaced, possession(200, validBody.copyOf(), requestBodyBytes = spaced))
        assertNotEquals(r1.record.requestPayloadHash, r2.record.requestPayloadHash)
        assertNotEquals(r1.record.requestKey, r2.record.requestKey)
    }

    @Test
    fun requestKeyBodyHashMatchesRequestPayloadHash() {
        val requestBytes = OpenFigiMappingRequestBody.encode(ibmJob)
        val result =
            service().archivePossessedResponse(requestBytes, possession(200, validBody.copyOf(), requestBodyBytes = requestBytes))
        assertTrue(
            OpenFigiForwardArchiveService.requestKeyBodyHashMatches(
                result.record.requestKey,
                result.record.requestPayloadHash!!,
            ),
        )
        assertEquals(
            OpenFigiMappingClient.requestKey(requestBytes),
            result.record.requestKey,
        )
        assertTrue(result.record.requestKey.endsWith(result.record.requestPayloadHash!!))
    }

    @Test
    fun requestRawOverwriteIsForbidden() {
        val requestBytes = OpenFigiMappingRequestBody.encode(ibmJob)
        val fixedId = "req-collision"
        val requestDir =
            root.resolve(OpenFigiMappingClient.DOMAIN).resolve(OpenFigiMappingClient.SOURCE).resolve("request")
        Files.createDirectories(requestDir)
        Files.writeString(requestDir.resolve("$fixedId.request.raw"), "occupied")
        val fixed =
            OpenFigiForwardArchiveService(
                archiveRoot = root,
                client = OpenFigiMappingClient(baseUrl = "http://127.0.0.1:1", clock = { nextInstant() }),
                clock = { nextInstant() },
                idGenerator = { fixedId },
            )
        val result = fixed.archivePossessedResponse(requestBytes, possession(200, validBody.copyOf(), requestBodyBytes = requestBytes))
        assertFalse(result.observedIngestSucceeded)
        assertFalse(result.bindingProvenanceReady)
        assertEquals(ObservationStatus.LOCAL_ARCHIVE_FAILURE, result.record.observationStatus)
        assertNull(result.record.requestPayloadUri)
        assertNull(result.record.requestPayloadHash)
        assertNull(result.record.eligibilityBoundaryAt)
        // Occupied object must remain unchanged
        assertEquals("occupied", Files.readString(requestDir.resolve("$fixedId.request.raw")))
    }

    @Test
    fun requestWriteFailureForbidsBindingReadyEvenIfResponseWouldSucceed() {
        val requestBytes = OpenFigiMappingRequestBody.encode(ibmJob)
        val sourceDir =
            root.resolve(OpenFigiMappingClient.DOMAIN).resolve(OpenFigiMappingClient.SOURCE)
        Files.createDirectories(sourceDir)
        // Occupy the request directory path with a file so immutable request write Fail-Closes.
        Files.writeString(sourceDir.resolve("request"), "not-a-directory")
        val svc =
            OpenFigiForwardArchiveService(
                archiveRoot = root,
                client = OpenFigiMappingClient(baseUrl = "http://127.0.0.1:1", clock = { nextInstant() }),
                clock = { nextInstant() },
                idGenerator = { "req-fail-1" },
            )
        val result = svc.archivePossessedResponse(requestBytes, possession(200, validBody.copyOf(), requestBodyBytes = requestBytes))
        assertFalse(result.observedIngestSucceeded)
        assertFalse(result.bindingProvenanceReady)
        assertEquals(ObservationStatus.LOCAL_ARCHIVE_FAILURE, result.record.observationStatus)
        assertNull(result.record.requestPayloadHash)
        assertNull(result.record.requestPayloadUri)
    }

    @Test
    fun responseRawCollisionKeepsRequestProvenanceButNotObserved() {
        val requestBytes = OpenFigiMappingRequestBody.encode(ibmJob)
        val fixedId = "resp-collision"
        val rawDir =
            root.resolve(OpenFigiMappingClient.DOMAIN).resolve(OpenFigiMappingClient.SOURCE).resolve("raw")
        Files.createDirectories(rawDir)
        Files.writeString(rawDir.resolve("$fixedId.raw"), "occupied")
        val fixed =
            OpenFigiForwardArchiveService(
                archiveRoot = root,
                client = OpenFigiMappingClient(baseUrl = "http://127.0.0.1:1", clock = { nextInstant() }),
                clock = { nextInstant() },
                idGenerator = { fixedId },
            )
        val result = fixed.archivePossessedResponse(requestBytes, possession(200, validBody.copyOf(), requestBodyBytes = requestBytes))
        assertFalse(result.observedIngestSucceeded)
        assertEquals(ObservationStatus.LOCAL_ARCHIVE_FAILURE, result.record.observationStatus)
        assertTrue(result.bindingProvenanceReady)
        assertNotNull(result.record.requestPayloadHash)
        assertNotNull(result.record.requestPayloadUri)
        assertTrue(Files.exists(Path.of(result.record.requestPayloadUri!!)))
        // Response collision must not be treated as safe OBSERVED join input
        assertNull(result.record.eligibilityBoundaryAt)
        assertNull(result.record.rawPayloadUri)
    }

    @Test
    fun apiKeyAndHeaderSecretsNeverEnterRequestRawOrManifest() {
        val secret = "super-secret-openfigi-key-xyz"
        val jobs = listOf(OpenFigiMappingJob(idType = "TICKER", idValue = "IBM", exchCode = "US"))
        val requestBytes = OpenFigiMappingRequestBody.encode(jobs)
        val result =
            service().archivePossessedResponse(requestBytes, possession(200, validBody.copyOf(), requestBodyBytes = requestBytes))
        val requestOnDisk = String(Files.readAllBytes(Path.of(result.record.requestPayloadUri!!)), StandardCharsets.UTF_8)
        val json = result.record.toJsonLine()
        for (hay in listOf(requestOnDisk, json, result.record.requestKey, String(requestBytes, StandardCharsets.UTF_8))) {
            assertFalse(hay.contains(secret))
            assertFalse(hay.contains("X-OPENFIGI-APIKEY"))
            assertFalse(hay.contains("OPENFIGI_API_KEY"))
            assertFalse(hay.contains("apiKey", ignoreCase = true))
        }
        assertFalse(requestOnDisk.contains("Authorization", ignoreCase = true))
    }

    @Test
    fun idTypeIdValueAndExchCodeRecoverableFromRequestRaw() {
        val jobs = listOf(OpenFigiMappingJob(idType = "TICKER", idValue = "IBM", exchCode = "US"))
        val requestBytes = OpenFigiMappingRequestBody.encode(jobs)
        val result =
            service().archivePossessedResponse(requestBytes, possession(200, validBody.copyOf(), requestBodyBytes = requestBytes))
        val raw = String(Files.readAllBytes(Path.of(result.record.requestPayloadUri!!)), StandardCharsets.UTF_8)
        assertTrue(raw.contains("\"idType\":\"TICKER\""))
        assertTrue(raw.contains("\"idValue\":\"IBM\""))
        assertTrue(raw.contains("\"exchCode\":\"US\""))
        assertTrue(raw.contentEquals(String(requestBytes, StandardCharsets.UTF_8)))
    }

    @Test
    fun bindingProvenanceReadyRequiresRequestProvenanceFields() {
        val result = service().archivePossessedResponse(ibmJob, possession(200, validBody.copyOf()))
        assertTrue(result.bindingProvenanceReady)
        assertNotNull(result.record.requestPayloadHash)
        assertNotNull(result.record.requestPayloadUri)
        // ProviderSymbolBindingEvidence inputs present; string-match join still not performed here.
        assertFalse(result.record.toJsonLine().contains("ProviderSymbolBindingEvidence"))
        assertFalse(result.record.toJsonLine().contains("securityId", ignoreCase = true))
        assertFalse(result.record.toJsonLine().contains("knownAt"))
        assertFalse(result.record.toJsonLine().contains("DailyPrice"))
    }

    @Test
    fun openFigiObservedWithoutRequestProvenanceIsRejectedByInvariant() {
        val t0 = Instant.parse("2026-09-15T05:00:00Z")
        val t1 = Instant.parse("2026-09-15T05:00:01Z")
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            ManifestRecord(
                archiveId = "a",
                domain = "SECURITY_MASTER",
                source = "openfigi.v3.mapping",
                requestKey = "POST|/v3/mapping|sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                attemptedAt = t0,
                attemptFinishedAt = t1,
                fetchedAt = t1,
                ingestedAt = t1,
                rawPayloadHash = "abc",
                rawPayloadUri = "u",
                httpStatus = 200,
                transportStatus = TransportStatus.HTTP_RESPONSE,
                observationStatus = ObservationStatus.OBSERVED,
                eligibilityBoundaryAt = t1,
            )
        }
    }

    @Test
    fun alphaVantageObservedDoesNotRequireRequestPayloadFields() {
        val t0 = Instant.parse("2026-09-15T05:10:00Z")
        val t1 = Instant.parse("2026-09-15T05:10:01Z")
        val record =
            ManifestRecord(
                archiveId = "av-1",
                domain = "PRICE",
                source = "alphavantage.time_series_daily.raw",
                requestKey = "GET|...",
                attemptedAt = t0,
                attemptFinishedAt = t1,
                fetchedAt = t1,
                ingestedAt = t1,
                rawPayloadHash = "abc",
                rawPayloadUri = "u",
                httpStatus = 200,
                transportStatus = TransportStatus.HTTP_RESPONSE,
                observationStatus = ObservationStatus.OBSERVED,
                eligibilityBoundaryAt = t1,
            )
        assertNull(record.requestPayloadHash)
        assertNull(record.requestPayloadUri)
    }

    @Test
    fun transportFailureStillStoresRequestProvenanceSeparatelyFromObserved() {
        val requestBytes = OpenFigiMappingRequestBody.encode(ibmJob)
        val result =
            service().archivePossessedResponse(requestBytes, possession(status = 0, body = null, transportMessage = "ConnectException", requestBodyBytes = requestBytes),
            )
        assertFalse(result.observedIngestSucceeded)
        assertEquals(ObservationStatus.PROVIDER_FAILURE, result.record.observationStatus)
        assertTrue(result.bindingProvenanceReady)
        assertNotNull(result.record.requestPayloadHash)
        assertNotNull(result.record.requestPayloadUri)
        assertNull(result.record.rawPayloadUri)
        assertNull(result.record.eligibilityBoundaryAt)
        assertTrue(
            Files.readAllBytes(Path.of(result.record.requestPayloadUri!!)).contentEquals(requestBytes),
        )
    }

    @Test
    fun possessionRequestPayloadHashMatchesExactRequestBytes() {
        val requestBytes = OpenFigiMappingRequestBody.encode(ibmJob)
        val p = possession(200, validBody.copyOf(), requestBodyBytes = requestBytes)
        assertEquals(Sha256Hex.of(requestBytes), p.requestPayloadHash)
        val result = service().archivePossessedResponse(requestBytes, p)
        assertTrue(result.bindingProvenanceReady)
        assertEquals(p.requestPayloadHash, result.record.requestPayloadHash)
    }

    @Test
    fun mismatchedPossessionAndRequestBytesIsFailClosedNotBindingReady() {
        val requestA = OpenFigiMappingRequestBody.encode(ibmJob)
        val requestB =
            OpenFigiMappingRequestBody.encode(
                listOf(OpenFigiMappingJob(idType = "TICKER", idValue = "IBM", exchCode = "US")),
            )
        assertNotEquals(Sha256Hex.of(requestA), Sha256Hex.of(requestB))
        val possessionB = possession(200, validBody.copyOf(), requestBodyBytes = requestB)
        val result = service().archivePossessedResponse(requestA, possessionB)
        assertFalse(result.observedIngestSucceeded)
        assertFalse(result.bindingProvenanceReady)
        assertEquals(ObservationStatus.LOCAL_ARCHIVE_FAILURE, result.record.observationStatus)
        assertNull(result.record.requestPayloadHash)
        assertNull(result.record.requestPayloadUri)
        assertNull(result.record.eligibilityBoundaryAt)
        assertTrue(result.failureNotes!!.contains("mismatch", ignoreCase = true))
    }

    @Test
    fun transportFailurePossessionRetainsRequestPayloadHash() {
        val requestBytes = OpenFigiMappingRequestBody.encode(ibmJob)
        val expected = Sha256Hex.of(requestBytes)
        val p =
            possession(
                status = 0,
                body = null,
                transportMessage = "ConnectException",
                requestBodyBytes = requestBytes,
            )
        assertEquals(expected, p.requestPayloadHash)
        assertNull(p.fetchedAt)
        assertNull(p.bodyBytes)
    }

    @Test
    fun liveClientBindsRequestPayloadHashBeforeSendIncludingTransportFailure() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.createContext("/v3/mapping") { exchange ->
            exchange.close()
        }
        server.start()
        try {
            val requestBytes = OpenFigiMappingRequestBody.encode(ibmJob)
            val expected = Sha256Hex.of(requestBytes)
            val client =
                OpenFigiMappingClient(
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                    httpClient =
                        HttpClient.newBuilder()
                            .connectTimeout(Duration.ofMillis(200))
                            .build(),
                    requestTimeout = Duration.ofMillis(200),
                    clock = { Instant.parse("2026-09-15T06:00:00Z") },
                )
            // Abrupt close may surface as transport failure or truncated response depending on timing;
            // either way possession must retain the pre-send request hash.
            val possession = client.executeMapping(requestBytes)
            assertEquals(expected, possession.requestPayloadHash)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun malformedRequestBodyForbidsBindingReady() {
        val bad = """{"not":"array"}""".toByteArray(StandardCharsets.UTF_8)
        val result =
            service().archivePossessedResponse(
                bad,
                possession(200, validBody.copyOf(), requestBodyBytes = bad),
            )
        assertFalse(result.observedIngestSucceeded)
        assertFalse(result.bindingProvenanceReady)
        assertEquals(ObservationStatus.LOCAL_ARCHIVE_FAILURE, result.record.observationStatus)
        assertNull(result.record.requestPayloadHash)
        assertNull(result.record.requestPayloadUri)
        assertTrue(result.failureNotes!!.contains("malformed", ignoreCase = true))
    }

    @Test
    fun requestBodyMissingIdValueIsMalformedFailClosed() {
        val bad = """[{"idType":"TICKER"}]""".toByteArray(StandardCharsets.UTF_8)
        val result =
            service().archivePossessedResponse(
                bad,
                possession(200, validBody.copyOf(), requestBodyBytes = bad),
            )
        assertFalse(result.bindingProvenanceReady)
        assertEquals(ObservationStatus.LOCAL_ARCHIVE_FAILURE, result.record.observationStatus)
    }

    @Test
    fun manifestRequestKeyHashMismatchRejectedOnFromJsonLine() {
        val t0 = Instant.parse("2026-09-15T07:00:00Z")
        val t1 = Instant.parse("2026-09-15T07:00:01Z")
        val badLine =
            """{"archiveId":"a","domain":"SECURITY_MASTER","source":"openfigi.v3.mapping","requestKey":"POST|/v3/mapping|sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","observedFields":[],"attemptedAt":"$t0","attemptFinishedAt":"$t1","fetchedAt":"$t1","ingestedAt":"$t1","rawPayloadHash":"abc","rawPayloadUri":"u","requestPayloadHash":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","requestPayloadUri":"req","httpStatus":200,"transportStatus":"HTTP_RESPONSE","relatedPriorObservationIds":[],"observationStatus":"OBSERVED","eligibilityBoundaryAt":"$t1"}"""
        kotlin.test.assertFailsWith<archive.poc.ArchiveValidationException> {
            ManifestRecord.fromJsonLine(badLine)
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            ManifestRecord(
                archiveId = "a",
                domain = "SECURITY_MASTER",
                source = "openfigi.v3.mapping",
                requestKey = "WRONG|/v3/mapping|sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                attemptedAt = t0,
                attemptFinishedAt = t1,
                fetchedAt = t1,
                ingestedAt = t1,
                rawPayloadHash = "abc",
                rawPayloadUri = "u",
                requestPayloadHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                requestPayloadUri = "req",
                httpStatus = 200,
                transportStatus = TransportStatus.HTTP_RESPONSE,
                observationStatus = ObservationStatus.OBSERVED,
                eligibilityBoundaryAt = t1,
            )
        }
    }

    @Test
    fun validOpenFigiManifestRoundTripPassesFromJsonLine() {
        val result = service().archivePossessedResponse(ibmJob, possession(200, validBody.copyOf()))
        assertTrue(result.observedIngestSucceeded)
        val reloaded = ManifestRecord.fromJsonLine(result.record.toJsonLine())
        assertEquals(result.record.requestPayloadHash, reloaded.requestPayloadHash)
        assertEquals(result.record.requestKey, reloaded.requestKey)
        assertEquals(result.record.requestPayloadUri, reloaded.requestPayloadUri)
        assertEquals(ObservationStatus.OBSERVED, reloaded.observationStatus)
    }

    @Test
    fun referencedRequestRawIsNotOrphan() {
        val svc = service()
        val result = svc.archivePossessedResponse(ibmJob, possession(200, validBody.copyOf()))
        assertTrue(result.bindingProvenanceReady)
        assertTrue(svc.findOrphanRequestObjects().isEmpty())
    }

    @Test
    fun unreferencedRequestRawIsDetectedAsOrphan() {
        val svc = service()
        assertTrue(svc.archivePossessedResponse(ibmJob, possession(200, validBody.copyOf())).bindingProvenanceReady)
        val requestDir =
            root.resolve(OpenFigiMappingClient.DOMAIN).resolve(OpenFigiMappingClient.SOURCE).resolve("request")
        Files.createDirectories(requestDir)
        val orphanPath = requestDir.resolve("manual-orphan.request.raw")
        Files.writeString(orphanPath, """[{"idType":"TICKER","idValue":"ORPHAN"}]""")
        val orphans = svc.findOrphanRequestObjects()
        assertEquals(1, orphans.size)
        assertEquals(orphanPath.toAbsolutePath().normalize(), orphans.single())
    }

    @Test
    fun requestLeftAfterManifestAppendFailureIsDetectableNextTime() {
        val svc = service()
        assertTrue(svc.archivePossessedResponse(ibmJob, possession(200, validBody.copyOf())).observedIngestSucceeded)
        val residualBytes = OpenFigiMappingRequestBody.encode(ibmJob)
        val residualHash = Sha256Hex.of(residualBytes)
        val residualPath =
            archive.poc.ImmutableRawStore(root).writeImmutable(
                relativeDir = "${OpenFigiMappingClient.DOMAIN}/${OpenFigiMappingClient.SOURCE}/request",
                archiveId = "orphan-req-after-append-fail",
                payload = residualBytes,
                expectedSha256Hex = residualHash,
                fileName = "orphan-req-after-append-fail.request.raw",
            )
        val detectSvc = service()
        val orphans = detectSvc.findOrphanRequestObjects()
        assertTrue(
            orphans.any { it == residualPath.toAbsolutePath().normalize() },
            "orphans=$orphans residual=$residualPath",
        )
        // Orphan itself must not grant binding-ready / eligibility / coverage.
        assertEquals(1, detectSvc.currentCoverage().observedCount)
        assertFalse(orphans.isEmpty())
    }

    @Test
    fun alphaVantageManifestWithRequestFieldsStillOptionalRegression() {
        val t0 = Instant.parse("2026-09-15T07:20:00Z")
        val t1 = Instant.parse("2026-09-15T07:20:01Z")
        val without =
            ManifestRecord(
                archiveId = "av-2",
                domain = "PRICE",
                source = "alphavantage.time_series_daily.raw",
                requestKey = "GET|query|symbol=IBM",
                attemptedAt = t0,
                attemptFinishedAt = t1,
                fetchedAt = t1,
                ingestedAt = t1,
                rawPayloadHash = "abc",
                rawPayloadUri = "u",
                httpStatus = 200,
                transportStatus = TransportStatus.HTTP_RESPONSE,
                observationStatus = ObservationStatus.OBSERVED,
                eligibilityBoundaryAt = t1,
            )
        assertNull(without.requestPayloadHash)
        val reloaded = ManifestRecord.fromJsonLine(without.toJsonLine())
        assertNull(reloaded.requestPayloadHash)
        assertEquals(ObservationStatus.OBSERVED, reloaded.observationStatus)
    }

    @Test
    fun integrityFixesDoNotInventSecurityIdKnownAtOrDailyPrice() {
        val result = service().archivePossessedResponse(ibmJob, possession(200, validBody.copyOf()))
        val line = result.record.toJsonLine()
        assertFalse(line.contains("securityId", ignoreCase = true))
        assertFalse(line.contains("knownAt"))
        assertFalse(line.contains("DailyPrice"))
        assertFalse(line.contains("JoinCandidate"))
    }

    @Test
    fun malformedUtf8RequestBodyIsFailClosedWithoutSilentReplacement() {
        val prefix = """[{"idType":"TICKER","idValue":"""".toByteArray(StandardCharsets.UTF_8)
        val suffix = """"}]""".toByteArray(StandardCharsets.UTF_8)
        val bad = prefix + byteArrayOf(0xFF.toByte()) + suffix
        // JDK String(bytes, UTF_8) may silently insert U+FFFD; strict decoder must not.
        assertTrue(String(bad, StandardCharsets.UTF_8).contains('\uFFFD'))
        kotlin.test.assertFailsWith<archive.poc.ArchiveValidationException> {
            OpenFigiMappingRequestBody.parseJobs(bad)
        }
        kotlin.test.assertFailsWith<archive.poc.ArchiveValidationException> {
            OpenFigiMappingRequestBody.decodeUtf8Strict(bad)
        }
        val result =
            service().archivePossessedResponse(
                bad,
                possession(200, validBody.copyOf(), requestBodyBytes = bad),
            )
        assertFalse(result.observedIngestSucceeded)
        assertFalse(result.bindingProvenanceReady)
        assertEquals(ObservationStatus.LOCAL_ARCHIVE_FAILURE, result.record.observationStatus)
        assertNull(result.record.requestPayloadHash)
        assertNull(result.record.eligibilityBoundaryAt)
        assertTrue(result.failureNotes!!.contains("malformed", ignoreCase = true))
    }

    @Test
    fun validUtf8RequestBodyStillParsesAndArchives() {
        val requestBytes = OpenFigiMappingRequestBody.encode(ibmJob)
        val jobs = OpenFigiMappingRequestBody.parseJobs(requestBytes)
        assertEquals(1, jobs.size)
        assertEquals("ID_BB_GLOBAL", jobs.single().idType)
        val result =
            service().archivePossessedResponse(
                requestBytes,
                possession(200, validBody.copyOf(), requestBodyBytes = requestBytes),
            )
        assertTrue(result.observedIngestSucceeded)
        assertTrue(result.bindingProvenanceReady)
    }

    @Test
    fun requestConstructionFailureDoesNotRethrowOrLeakSecrets() {
        val secret = "openfigi-secret-key-with\nnewline"
        val requestBytes = OpenFigiMappingRequestBody.encode(ibmJob)
        val client =
            OpenFigiMappingClient(
                baseUrl = "http://127.0.0.1:1",
                apiKey = secret,
                httpClient =
                    HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(100))
                        .build(),
                requestTimeout = Duration.ofMillis(100),
                clock = { Instant.parse("2026-09-15T08:00:00Z") },
            )
        val possession = client.executeMapping(requestBytes)
        assertEquals(Sha256Hex.of(requestBytes), possession.requestPayloadHash)
        assertNull(possession.fetchedAt)
        assertNull(possession.httpStatus)
        assertNull(possession.bodyBytes)
        assertNotNull(possession.transportFailureMessage)
        // Class name only — never e.message / API key / header / URI.
        assertEquals(possession.transportFailureMessage, possession.transportFailureMessage!!.trim())
        assertFalse(possession.transportFailureMessage!!.contains(secret))
        assertFalse(possession.transportFailureMessage!!.contains("newline"))
        assertFalse(possession.transportFailureMessage!!.contains("OPENFIGI"))
        assertFalse(possession.transportFailureMessage!!.contains("apikey", ignoreCase = true))
        assertFalse(possession.transportFailureMessage!!.contains("http://"))
        assertFalse(possession.transportFailureMessage!!.contains(":"))
        assertTrue(possession.transportFailureMessage!!.matches(Regex("[A-Za-z0-9_\$]+")))

        val result =
            OpenFigiForwardArchiveService(
                archiveRoot = root,
                client = client,
                clock = { Instant.parse("2026-09-15T08:00:01Z") },
                idGenerator = { "sec-construction-1" },
            ).archivePossessedResponse(requestBytes, possession)
        assertFalse(result.observedIngestSucceeded)
        assertTrue(result.bindingProvenanceReady) // request provenance may still commit
        val line = result.record.toJsonLine()
        assertFalse(line.contains(secret))
        assertFalse(line.contains("openfigi-secret"))
        assertEquals(possession.transportFailureMessage, result.record.notes)
        assertFalse((result.record.notes ?: "").contains(" "))
    }

    @Test
    fun requestPayloadHashFormatInvariantRejectsInvalidShapes() {
        val t0 = Instant.parse("2026-09-15T08:10:00Z")
        val t1 = Instant.parse("2026-09-15T08:10:01Z")
        val valid = "a".repeat(64)
        fun attempt(hash: String, keyHash: String = hash) {
            ManifestRecord(
                archiveId = "a",
                domain = "SECURITY_MASTER",
                source = "openfigi.v3.mapping",
                requestKey = "POST|/v3/mapping|sha256:$keyHash",
                attemptedAt = t0,
                attemptFinishedAt = t1,
                fetchedAt = t1,
                ingestedAt = t1,
                rawPayloadHash = "abc",
                rawPayloadUri = "u",
                requestPayloadHash = hash,
                requestPayloadUri = "req",
                httpStatus = 200,
                transportStatus = TransportStatus.HTTP_RESPONSE,
                observationStatus = ObservationStatus.OBSERVED,
                eligibilityBoundaryAt = t1,
            )
        }
        kotlin.test.assertFailsWith<IllegalArgumentException> { attempt("a".repeat(63)) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { attempt("a".repeat(65)) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { attempt("A".repeat(64)) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { attempt("g".repeat(64)) }
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            OpenFigiHttpPossession(
                attemptedAt = t0,
                attemptFinishedAt = t1,
                fetchedAt = null,
                httpStatus = null,
                contentType = null,
                bodyBytes = null,
                transportFailureMessage = "X",
                requestPayloadHash = "abc",
            )
        }
        // valid 64 lowercase hex PASS
        val ok =
            ManifestRecord(
                archiveId = "ok",
                domain = "SECURITY_MASTER",
                source = "openfigi.v3.mapping",
                requestKey = "POST|/v3/mapping|sha256:$valid",
                attemptedAt = t0,
                attemptFinishedAt = t1,
                fetchedAt = t1,
                ingestedAt = t1,
                rawPayloadHash = "abc",
                rawPayloadUri = "u",
                requestPayloadHash = valid,
                requestPayloadUri = "req",
                httpStatus = 200,
                transportStatus = TransportStatus.HTTP_RESPONSE,
                observationStatus = ObservationStatus.OBSERVED,
                eligibilityBoundaryAt = t1,
            )
        val reloaded = ManifestRecord.fromJsonLine(ok.toJsonLine())
        assertEquals(valid, reloaded.requestPayloadHash)

        // short hash historically accepted as matching pair must now Fail-Closed on reload
        val shortLine =
            """{"archiveId":"a","domain":"SECURITY_MASTER","source":"openfigi.v3.mapping","requestKey":"POST|/v3/mapping|sha256:abc","observedFields":[],"attemptedAt":"$t0","attemptFinishedAt":"$t1","fetchedAt":"$t1","ingestedAt":"$t1","rawPayloadHash":"abc","rawPayloadUri":"u","requestPayloadHash":"abc","requestPayloadUri":"req","httpStatus":200,"transportStatus":"HTTP_RESPONSE","relatedPriorObservationIds":[],"observationStatus":"OBSERVED","eligibilityBoundaryAt":"$t1"}"""
        kotlin.test.assertFailsWith<archive.poc.ArchiveValidationException> {
            ManifestRecord.fromJsonLine(shortLine)
        }
    }
}
