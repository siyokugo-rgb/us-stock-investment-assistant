package archive.poc.massive

import archive.poc.ImmutableRawStore
import archive.poc.ObservationStatus
import archive.poc.Sha256Hex
import archive.poc.TransportStatus
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
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

/**
 * Synthetic QA for PRICE / massive.stocks.aggs_1d.unadjusted forward archive.
 * Live network is not required. Fixtures are sanitized / synthetic.
 */
class MassiveDailyAggsForwardArchivePocTest {
    private lateinit var root: Path
    private val ids = AtomicInteger(0)
    private val clock =
        mutableListOf(
            Instant.parse("2026-09-16T10:00:00Z"),
            Instant.parse("2026-09-16T10:00:01Z"),
            Instant.parse("2026-09-16T10:00:02Z"),
            Instant.parse("2026-09-16T10:00:03Z"),
            Instant.parse("2026-09-16T10:00:04Z"),
            Instant.parse("2026-09-16T10:00:05Z"),
            Instant.parse("2026-09-16T10:00:06Z"),
            Instant.parse("2026-09-16T10:00:07Z"),
            Instant.parse("2026-09-16T10:00:08Z"),
            Instant.parse("2026-09-16T10:00:09Z"),
            Instant.parse("2026-09-16T10:00:10Z"),
            Instant.parse("2026-09-16T10:00:11Z"),
            Instant.parse("2026-09-16T10:00:12Z"),
            Instant.parse("2026-09-16T10:00:13Z"),
            Instant.parse("2026-09-16T10:00:14Z"),
            Instant.parse("2026-09-16T10:00:15Z"),
            Instant.parse("2026-09-16T10:00:16Z"),
            Instant.parse("2026-09-16T10:00:17Z"),
            Instant.parse("2026-09-16T10:00:18Z"),
            Instant.parse("2026-09-16T10:00:19Z"),
            Instant.parse("2026-09-16T10:00:20Z"),
            Instant.parse("2026-09-16T10:00:21Z"),
            Instant.parse("2026-09-16T10:00:22Z"),
            Instant.parse("2026-09-16T10:00:23Z"),
            Instant.parse("2026-09-16T10:00:24Z"),
            Instant.parse("2026-09-16T10:00:25Z"),
            Instant.parse("2026-09-16T10:00:26Z"),
            Instant.parse("2026-09-16T10:00:27Z"),
            Instant.parse("2026-09-16T10:00:28Z"),
            Instant.parse("2026-09-16T10:00:29Z"),
            Instant.parse("2026-09-16T10:00:30Z"),
            Instant.parse("2026-09-16T10:00:31Z"),
            Instant.parse("2026-09-16T10:00:32Z"),
            Instant.parse("2026-09-16T10:00:33Z"),
            Instant.parse("2026-09-16T10:00:34Z"),
            Instant.parse("2026-09-16T10:00:35Z"),
            Instant.parse("2026-09-16T10:00:36Z"),
            Instant.parse("2026-09-16T10:00:37Z"),
            Instant.parse("2026-09-16T10:00:38Z"),
            Instant.parse("2026-09-16T10:00:39Z"),
            Instant.parse("2026-09-16T10:00:40Z"),
        )
    private var clockIdx = 0

    private fun nextInstant(): Instant = clock[clockIdx++]

    private val ticker = "AAPL"
    private val from = "2024-01-02"
    private val to = "2024-01-10"
    private val limit = MassiveDailyAggsArchiveClient.DEFAULT_LIMIT

    private fun service(limitOverride: Int = limit): MassiveDailyAggsForwardArchiveService =
        MassiveDailyAggsForwardArchiveService(
            archiveRoot = root,
            client =
                MassiveDailyAggsArchiveClient(
                    baseUrl = "http://127.0.0.1:1",
                    limit = limitOverride,
                    clock = { nextInstant() },
                ),
            clock = { nextInstant() },
            idGenerator = { "id-${ids.incrementAndGet()}" },
        )

    private val validBody: ByteArray =
        this::class.java.getResourceAsStream(
            "/archive/poc/massive/massive-aapl-unadjusted-sanitized.json",
        )!!.readBytes()

    private fun requestKey(
        t: String = ticker,
        f: String = from,
        tt: String = to,
        lim: Int = limit,
    ): String = MassiveDailyAggsArchiveClient.requestKey(t, f, tt, lim)

    private fun possession(
        status: Int,
        body: ByteArray?,
        transportMessage: String? = null,
        requestKey: String = requestKey(),
    ): MassiveHttpPossession {
        val a = nextInstant()
        val b = nextInstant()
        return if (body == null) {
            MassiveHttpPossession(
                requestKey = requestKey,
                attemptedAt = a,
                attemptFinishedAt = b,
                fetchedAt = null,
                httpStatus = null,
                contentType = null,
                bodyBytes = null,
                transportFailureMessage = transportMessage ?: "SimulatedTransportFailure",
            )
        } else {
            MassiveHttpPossession(
                requestKey = requestKey,
                attemptedAt = a,
                attemptFinishedAt = b,
                fetchedAt = b,
                httpStatus = status,
                contentType = "application/json",
                bodyBytes = body,
                transportFailureMessage = null,
            )
        }
    }

    private fun archive(
        svc: MassiveDailyAggsForwardArchiveService = service(),
        body: ByteArray,
        status: Int = 200,
        t: String = ticker,
        f: String = from,
        tt: String = to,
        lim: Int = limit,
    ) = svc.archivePossessedResponse(
        t,
        f,
        tt,
        possession(status, body, requestKey = requestKey(t, f, tt, lim)),
    )

    private fun mutateValid(transform: (String) -> String): ByteArray =
        transform(String(validBody, StandardCharsets.UTF_8)).toByteArray(StandardCharsets.UTF_8)

    @BeforeTest
    fun setup() {
        root = Files.createTempDirectory("massive-price-archive-poc")
        clockIdx = 0
        ids.set(0)
    }

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun http200ValidUnadjustedAggregateIsObservedWithExactSha256() {
        val result = archive(body = validBody.copyOf())
        assertTrue(result.observedIngestSucceeded)
        assertEquals(ObservationStatus.OBSERVED, result.record.observationStatus)
        assertEquals(Sha256Hex.of(validBody), result.record.rawPayloadHash)
        assertNotNull(result.record.fetchedAt)
        assertNotNull(result.record.eligibilityBoundaryAt)
        assertEquals(result.record.ingestedAt, result.record.eligibilityBoundaryAt)
        val onDisk = Files.readAllBytes(Path.of(result.record.rawPayloadUri!!))
        assertTrue(onDisk.contentEquals(validBody))
        assertNull(result.record.externalIdentifier)
        assertNull(result.record.externalIdentifierNamespace)
        val line = result.record.toJsonLine()
        assertFalse(line.contains("\"knownAt\""))
        assertFalse(line.contains("\"securityId\"", ignoreCase = true))
        assertFalse(line.contains("\"currency\""))
        assertFalse(line.contains("\"XNYS\""))
        assertFalse(line.contains("apiKey", ignoreCase = true))
        assertEquals(requestKey(), result.record.requestKey)
        assertEquals(1, result.coverage.observedCount)
        assertEquals(result.record.ingestedAt, result.coverage.coverageStartAt)
    }

    @Test
    fun whitespaceDifferenceChangesHash() {
        val a = validBody
        val b = (String(validBody) + " ").toByteArray(StandardCharsets.UTF_8)
        assertNotEquals(Sha256Hex.of(a), Sha256Hex.of(b))
    }

    @Test
    fun adjustedTrueResponseIsRejected() {
        val body = mutateValid { it.replace("\"adjusted\":false", "\"adjusted\":true") }
        val result = archive(body = body)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertNull(result.record.eligibilityBoundaryAt)
        assertNotNull(result.record.rawPayloadUri)
        assertTrue(result.record.notes!!.contains("adjusted=true"))
    }

    @Test
    fun tickerMismatchIsRejected() {
        val result =
            archive(
                body = validBody.copyOf(),
                t = "MSFT",
            )
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertNull(result.record.eligibilityBoundaryAt)
    }

    @Test
    fun missingResultsIsRejected() {
        val body =
            """{"ticker":"AAPL","adjusted":false,"status":"OK","queryCount":0,"resultsCount":0}"""
                .toByteArray(StandardCharsets.UTF_8)
        val result = archive(body = body)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
    }

    @Test
    fun emptyResultsIsRejected() {
        val body =
            """{"ticker":"AAPL","adjusted":false,"status":"OK","results":[],"resultsCount":0}"""
                .toByteArray(StandardCharsets.UTF_8)
        val result = archive(body = body)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertTrue(result.record.notes!!.contains("empty results"))
    }

    @Test
    fun malformedJsonIsRejected() {
        val body = """{"ticker":"AAPL","adjusted":false""".toByteArray(StandardCharsets.UTF_8)
        val result = archive(body = body)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertNotNull(result.record.rawPayloadUri)
    }

    @Test
    fun invalidUtf8IsRejectedWithExactRawBytesPreserved() {
        val prefix = """{"ticker":"AAPL","adjusted":false,"status":"OK","name":"""".toByteArray()
        val suffix = """","results":[]}""".toByteArray()
        val body = prefix + byteArrayOf(0xFF.toByte()) + suffix
        val result = archive(body = body)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertNull(result.record.eligibilityBoundaryAt)
        assertEquals(Sha256Hex.of(body), result.record.rawPayloadHash)
        val onDisk = Files.readAllBytes(Path.of(result.record.rawPayloadUri!!))
        assertTrue(onDisk.contentEquals(body))
        assertTrue(onDisk.contains(0xFF.toByte()))
    }

    @Test
    fun negativeOhlcIsRejected() {
        val body =
            mutateValid {
                it.replace("\"o\":184.35", "\"o\":-1.0")
            }
        val result = archive(body = body)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
    }

    @Test
    fun negativeVolumeIsRejected() {
        val body = mutateValid { it.replace("\"v\":51234567", "\"v\":-1") }
        val result = archive(body = body)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
    }

    @Test
    fun highLessThanLowIsRejected() {
        val body =
            mutateValid {
                it.replace("\"h\":186.40", "\"h\":180.0").replace("\"l\":183.92", "\"l\":183.92")
            }
        // force h < l on first bar
        val forced =
            """{"ticker":"AAPL","adjusted":false,"status":"OK","resultsCount":1,"results":[{"o":1,"h":1,"l":2,"c":1,"v":1,"t":1704153600000}]}"""
                .toByteArray(StandardCharsets.UTF_8)
        val result = archive(body = forced)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertTrue(result.record.notes!!.contains("high < low"))
    }

    @Test
    fun openOutsideHighLowIsRejected() {
        val body =
            """{"ticker":"AAPL","adjusted":false,"status":"OK","resultsCount":1,"results":[{"o":10,"h":5,"l":1,"c":3,"v":1,"t":1704153600000}]}"""
                .toByteArray(StandardCharsets.UTF_8)
        // h>=l but open outside — actually h=5 l=1 o=10 outside
        val result = archive(body = body)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
    }

    @Test
    fun closeOutsideHighLowIsRejected() {
        val body =
            """{"ticker":"AAPL","adjusted":false,"status":"OK","resultsCount":1,"results":[{"o":2,"h":5,"l":1,"c":9,"v":1,"t":1704153600000}]}"""
                .toByteArray(StandardCharsets.UTF_8)
        val result = archive(body = body)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
    }

    @Test
    fun duplicateTimestampsAreRejected() {
        val body =
            """{"ticker":"AAPL","adjusted":false,"status":"OK","resultsCount":2,"results":[{"o":1,"h":1,"l":1,"c":1,"v":1,"t":1704153600000},{"o":1,"h":1,"l":1,"c":1,"v":1,"t":1704153600000}]}"""
                .toByteArray(StandardCharsets.UTF_8)
        val result = archive(body = body)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertTrue(result.record.notes!!.contains("duplicate timestamp"))
    }

    @Test
    fun malformedTimestampIsRejected() {
        val body =
            """{"ticker":"AAPL","adjusted":false,"status":"OK","resultsCount":1,"results":[{"o":1,"h":1,"l":1,"c":1,"v":1,"t":1704153600000.5}]}"""
                .toByteArray(StandardCharsets.UTF_8)
        val result = archive(body = body)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
    }

    @Test
    fun nonAscendingTimestampsAreRejected() {
        val body =
            """{"ticker":"AAPL","adjusted":false,"status":"OK","resultsCount":2,"results":[{"o":1,"h":1,"l":1,"c":1,"v":1,"t":1704240000000},{"o":1,"h":1,"l":1,"c":1,"v":1,"t":1704153600000}]}"""
                .toByteArray(StandardCharsets.UTF_8)
        val result = archive(body = body)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
    }

    @Test
    fun http403CompleteBodyIsProviderFailureWithRaw() {
        val body = """{"status":"ERROR","error":"forbidden"}""".toByteArray(StandardCharsets.UTF_8)
        val result = archive(body = body, status = 403)
        assertEquals(ObservationStatus.PROVIDER_FAILURE, result.record.observationStatus)
        assertNotNull(result.record.fetchedAt)
        assertNotNull(result.record.rawPayloadUri)
        assertEquals(Sha256Hex.of(body), result.record.rawPayloadHash)
        assertNull(result.record.eligibilityBoundaryAt)
        assertEquals(0, result.coverage.observedCount)
    }

    @Test
    fun http429CompleteBodyIsProviderFailureWithRaw() {
        val body = """{"status":"ERROR","error":"rate limited"}""".toByteArray(StandardCharsets.UTF_8)
        val result = archive(body = body, status = 429)
        assertEquals(ObservationStatus.PROVIDER_FAILURE, result.record.observationStatus)
        assertNotNull(result.record.rawPayloadUri)
        assertNull(result.record.eligibilityBoundaryAt)
    }

    @Test
    fun http500CompleteBodyIsProviderFailureWithRaw() {
        val body = """{"status":"ERROR","error":"boom"}""".toByteArray(StandardCharsets.UTF_8)
        val result = archive(body = body, status = 500)
        assertEquals(ObservationStatus.PROVIDER_FAILURE, result.record.observationStatus)
        assertNotNull(result.record.rawPayloadUri)
        assertNull(result.record.eligibilityBoundaryAt)
    }

    @Test
    fun transportFailureHasNoFetchedAtOrRaw() {
        val result =
            service().archivePossessedResponse(
                ticker,
                from,
                to,
                possession(status = 0, body = null, transportMessage = "ConnectException"),
            )
        assertEquals(ObservationStatus.PROVIDER_FAILURE, result.record.observationStatus)
        assertEquals(TransportStatus.TRANSPORT_FAILURE, result.record.transportStatus)
        assertNull(result.record.fetchedAt)
        assertNull(result.record.rawPayloadHash)
        assertNull(result.record.rawPayloadUri)
        assertNull(result.record.eligibilityBoundaryAt)
        assertEquals("ConnectException", result.record.notes)
        assertFalse(result.record.notes!!.contains("apiKey", ignoreCase = true))
        assertFalse(result.record.notes!!.contains("http"))
    }

    @Test
    fun requestConstructionFailureStoresClassNameOnlyNotSecret() {
        val client =
            MassiveDailyAggsArchiveClient(
                baseUrl = "http://127.0.0.1:1",
                apiKey = "super-secret-massive-key",
                limit = 50,
                clock = { nextInstant() },
            )
        val possession = client.executeDailyAggs(ticker, from, to)
        assertNull(possession.fetchedAt)
        assertNull(possession.bodyBytes)
        assertNotNull(possession.transportFailureMessage)
        assertFalse(possession.transportFailureMessage!!.contains("super-secret"))
        assertFalse(possession.transportFailureMessage!!.contains("apiKey", ignoreCase = true))
        assertFalse(possession.requestKey.contains("apiKey", ignoreCase = true))
        assertFalse(possession.requestKey.contains("super-secret"))
        // Archive the transport failure
        val svc =
            MassiveDailyAggsForwardArchiveService(
                archiveRoot = root,
                client = client,
                clock = { nextInstant() },
                idGenerator = { "id-${ids.incrementAndGet()}" },
            )
        val result = svc.archivePossessedResponse(ticker, from, to, possession)
        assertEquals(ObservationStatus.PROVIDER_FAILURE, result.record.observationStatus)
        assertFalse(result.record.toJsonLine().contains("super-secret"))
        assertFalse(result.record.toJsonLine().contains("apiKey", ignoreCase = true))
    }

    @Test
    fun requestKeyNeverContainsApiKeyAndIsCanonical() {
        val key = requestKey()
        assertEquals(
            "GET|/v2/aggs/ticker/AAPL/range/1/day/2024-01-02/2024-01-10|adjusted=false|sort=asc|limit=5000",
            key,
        )
        assertFalse(key.contains("apiKey", ignoreCase = true))
        val parsed = MassiveDailyAggsRequestKey.parseOrThrow(key)
        assertEquals("AAPL", parsed.ticker)
        assertEquals(5000, parsed.limit)
    }

    @Test
    fun sameRequestKeySameHashIsDuplicateCandidate() {
        val svc = service()
        val first = archive(svc = svc, body = validBody.copyOf())
        val second = archive(svc = svc, body = validBody.copyOf())
        assertEquals(first.record.archiveId, second.record.duplicateOf)
        assertNull(second.record.revisionCandidateOf)
    }

    @Test
    fun sameRequestKeyDifferentHashIsRevisionCandidateNotAuthoritativeCorrection() {
        val alt = mutateValid { it.replace("185.64", "185.65") }
        assertNotEquals(Sha256Hex.of(validBody), Sha256Hex.of(alt))
        val svc = service()
        val first = archive(svc = svc, body = validBody.copyOf())
        val second = archive(svc = svc, body = alt)
        assertEquals(ObservationStatus.OBSERVED, second.record.observationStatus)
        assertEquals(first.record.archiveId, second.record.revisionCandidateOf)
        assertNull(second.record.duplicateOf)
        assertEquals(2, svc.readManifest().size)
    }

    @Test
    fun coverageUsesObservedIngestedAtNotPayloadMarketDates() {
        val svc = service()
        val rejected =
            archive(
                svc = svc,
                body =
                    """{"ticker":"AAPL","adjusted":false,"status":"ERROR","error":"nope"}"""
                        .toByteArray(StandardCharsets.UTF_8),
            )
        // HTTP 200 ERROR status → rejected validation
        assertEquals(ObservationStatus.REJECTED_VALIDATION, rejected.record.observationStatus)
        assertEquals(0, rejected.coverage.observedCount)
        val ok = archive(svc = svc, body = validBody.copyOf())
        assertEquals(1, ok.coverage.observedCount)
        assertEquals(ok.record.ingestedAt, ok.coverage.coverageStartAt)
        assertEquals(ok.record.ingestedAt, ok.coverage.coverageThroughAt)
        assertFalse(ok.coverage.coverageStartAt.toString().startsWith("2024-01"))
    }

    @Test
    fun orphanRawIsDetectableAndExcludedFromCoverage() {
        val svc = service()
        assertTrue(archive(svc = svc, body = validBody.copyOf()).observedIngestSucceeded)
        assertTrue(svc.findOrphanRawObjects().isEmpty())
        val residual =
            ImmutableRawStore(root).writeImmutable(
                relativeDir =
                    "${MassiveDailyAggsArchiveClient.DOMAIN}/${MassiveDailyAggsArchiveClient.SOURCE}/raw",
                archiveId = "orphan-after-append-fail",
                payload = validBody,
                expectedSha256Hex = Sha256Hex.of(validBody),
            )
        val orphans = service().findOrphanRawObjects()
        assertTrue(orphans.any { it == residual.toAbsolutePath().normalize() })
        assertEquals(1, service().currentCoverage().observedCount)
    }

    @Test
    fun rawWriteFailureIsLocalArchiveFailure() {
        val svc = service()
        assertTrue(archive(svc = svc, body = validBody.copyOf()).observedIngestSucceeded)
        val rawDir =
            root.resolve(MassiveDailyAggsArchiveClient.DOMAIN)
                .resolve(MassiveDailyAggsArchiveClient.SOURCE)
                .resolve("raw")
        Files.createDirectories(rawDir)
        Files.writeString(rawDir.resolve("fixed-raw.raw"), "occupied")
        val fixed =
            MassiveDailyAggsForwardArchiveService(
                archiveRoot = root,
                client =
                    MassiveDailyAggsArchiveClient(
                        baseUrl = "http://127.0.0.1:1",
                        clock = { nextInstant() },
                    ),
                clock = { nextInstant() },
                idGenerator = { "fixed-raw" },
            )
        val result = archive(svc = fixed, body = validBody.copyOf())
        assertEquals(ObservationStatus.LOCAL_ARCHIVE_FAILURE, result.record.observationStatus)
        assertNull(result.record.eligibilityBoundaryAt)
        assertFalse(result.observedIngestSucceeded)
    }

    @Test
    fun providerErrorStatusUnderHttp200IsRejectedNotObserved() {
        val body =
            this::class.java.getResourceAsStream(
                "/archive/poc/massive/massive-error-envelope.json",
            )!!.readBytes()
        val result = archive(body = body)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertNull(result.record.eligibilityBoundaryAt)
        assertNotNull(result.record.rawPayloadUri)
    }

    @Test
    fun resultsCountMismatchIsRejected() {
        val body =
            mutateValid { it.replace("\"resultsCount\":2", "\"resultsCount\":9") }
        val result = archive(body = body)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
    }

    @Test
    fun noCurrencyMicFigiSecurityIdOrDailyPriceGenerated() {
        val result = archive(body = validBody.copyOf())
        assertNull(result.record.externalIdentifier)
        assertNull(result.record.externalIdentifierNamespace)
        val notes = result.record.notes!!
        assertTrue(notes.contains("currency/MIC/FIGI/SecurityId/DailyPrice not generated"))
        assertTrue(notes.contains("SIP/venue semantics remain CONDITIONAL/PARTIAL"))
        assertFalse(notes.contains("XNYS price"))
    }

    @Test
    fun possessionRequestKeyMismatchIsRefused() {
        val bad =
            possession(
                200,
                validBody.copyOf(),
                requestKey = requestKey(t = "MSFT"),
            )
        try {
            service().archivePossessedResponse(ticker, from, to, bad)
            throw AssertionError("expected require failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("possession.requestKey mismatch"))
        }
    }
}
