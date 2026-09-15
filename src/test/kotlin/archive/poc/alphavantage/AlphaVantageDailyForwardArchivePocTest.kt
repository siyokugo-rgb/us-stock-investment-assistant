package archive.poc.alphavantage

import archive.poc.ArchiveIoException
import archive.poc.ImmutableRawStore
import archive.poc.ManifestRecord
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
 * Synthetic QA for PRICE / alphavantage.time_series_daily.raw forward archive.
 * Live network is not required. Fixtures are sanitized / synthetic.
 */
class AlphaVantageDailyForwardArchivePocTest {
    private lateinit var root: Path
    private val ids = AtomicInteger(0)
    private val clock =
        mutableListOf(
            Instant.parse("2026-09-15T10:00:00Z"),
            Instant.parse("2026-09-15T10:00:01Z"),
            Instant.parse("2026-09-15T10:00:02Z"),
            Instant.parse("2026-09-15T10:00:03Z"),
            Instant.parse("2026-09-15T10:00:04Z"),
            Instant.parse("2026-09-15T10:00:05Z"),
            Instant.parse("2026-09-15T10:00:06Z"),
            Instant.parse("2026-09-15T10:00:07Z"),
            Instant.parse("2026-09-15T10:00:08Z"),
            Instant.parse("2026-09-15T10:00:09Z"),
            Instant.parse("2026-09-15T10:00:10Z"),
            Instant.parse("2026-09-15T10:00:11Z"),
            Instant.parse("2026-09-15T10:00:12Z"),
            Instant.parse("2026-09-15T10:00:13Z"),
            Instant.parse("2026-09-15T10:00:14Z"),
            Instant.parse("2026-09-15T10:00:15Z"),
            Instant.parse("2026-09-15T10:00:16Z"),
            Instant.parse("2026-09-15T10:00:17Z"),
            Instant.parse("2026-09-15T10:00:18Z"),
            Instant.parse("2026-09-15T10:00:19Z"),
            Instant.parse("2026-09-15T10:00:20Z"),
            Instant.parse("2026-09-15T10:00:21Z"),
            Instant.parse("2026-09-15T10:00:22Z"),
            Instant.parse("2026-09-15T10:00:23Z"),
            Instant.parse("2026-09-15T10:00:24Z"),
            Instant.parse("2026-09-15T10:00:25Z"),
            Instant.parse("2026-09-15T10:00:26Z"),
            Instant.parse("2026-09-15T10:00:27Z"),
            Instant.parse("2026-09-15T10:00:28Z"),
            Instant.parse("2026-09-15T10:00:29Z"),
            Instant.parse("2026-09-15T10:00:30Z"),
        )
    private var clockIdx = 0

    private fun nextInstant(): Instant = clock[clockIdx++]

    private fun service(): AlphaVantageDailyForwardArchiveService =
        AlphaVantageDailyForwardArchiveService(
            archiveRoot = root,
            client =
                AlphaVantageDailyArchiveClient(
                    baseUrl = "http://127.0.0.1:1", // unused for possessed path
                    clock = { nextInstant() },
                ),
            clock = { nextInstant() },
            idGenerator = { "id-${ids.incrementAndGet()}" },
        )

    private val symbol = "IBM"

    private val validBody: ByteArray =
        this::class.java.getResourceAsStream(
            "/archive/poc/alphavantage/av-daily-ibm-sanitized.json",
        )!!.readBytes()

    private fun possession(
        status: Int,
        body: ByteArray?,
        transportMessage: String? = null,
    ): AlphaVantageHttpPossession {
        val a = nextInstant()
        val b = nextInstant()
        return if (body == null) {
            AlphaVantageHttpPossession(
                attemptedAt = a,
                attemptFinishedAt = b,
                fetchedAt = null,
                httpStatus = null,
                contentType = null,
                bodyBytes = null,
                transportFailureMessage = transportMessage ?: "SimulatedTransportFailure",
            )
        } else {
            AlphaVantageHttpPossession(
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

    private fun resourceBytes(name: String): ByteArray =
        this::class.java.getResourceAsStream("/archive/poc/alphavantage/$name")!!.readBytes()

    @BeforeTest
    fun setup() {
        root = Files.createTempDirectory("av-price-archive-poc")
        clockIdx = 0
        ids.set(0)
    }

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun http200ValidSeriesIsObservedWithExactSha256() {
        val result = service().archivePossessedResponse(symbol, possession(200, validBody.copyOf()))
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
        assertNull(result.record.externalIdentifier)
        assertEquals(
            AlphaVantageDailyArchiveClient.requestKey(symbol),
            result.record.requestKey,
        )
        assertFalse(result.record.requestKey.contains("apikey", ignoreCase = true))
        assertEquals(1, result.coverage.observedCount)
        assertEquals(result.record.ingestedAt, result.coverage.coverageStartAt)
    }

    @Test
    fun whitespaceDifferenceChangesHash() {
        val a = """{"Meta Data":{"2. Symbol":"IBM"},"Time Series (Daily)":{"2024-01-25":{"1. open":"1","2. high":"1","3. low":"1","4. close":"1","5. volume":"1"}}}""".toByteArray()
        val b = """{ "Meta Data":{"2. Symbol":"IBM"},"Time Series (Daily)":{"2024-01-25":{"1. open":"1","2. high":"1","3. low":"1","4. close":"1","5. volume":"1"}}}""".toByteArray()
        assertNotEquals(Sha256Hex.of(a), Sha256Hex.of(b))
        // Neither is fully valid vs fixture structure with all OHLCV rules for IBM match —
        // hash difference itself is the contract under test.
        assertNotEquals(Sha256Hex.of(validBody), Sha256Hex.of(validBody + " ".toByteArray()))
    }

    @Test
    fun informationEnvelopeIsRejectedNotObserved() {
        val body = resourceBytes("av-information-envelope.json")
        val result = service().archivePossessedResponse(symbol, possession(200, body))
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertFalse(result.observedIngestSucceeded)
        assertNull(result.record.eligibilityBoundaryAt)
        assertNotNull(result.record.rawPayloadUri)
        assertEquals(Sha256Hex.of(body), result.record.rawPayloadHash)
        assertEquals(0, result.coverage.observedCount)
    }

    @Test
    fun noteEnvelopeIsRejectedNotObserved() {
        val body = resourceBytes("av-note-envelope.json")
        val result = service().archivePossessedResponse(symbol, possession(200, body))
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertNull(result.record.eligibilityBoundaryAt)
        assertNotNull(result.record.rawPayloadUri)
    }

    @Test
    fun errorMessageEnvelopeIsRejectedNotObserved() {
        val body = resourceBytes("av-error-envelope.json")
        val result = service().archivePossessedResponse(symbol, possession(200, body))
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertNull(result.record.eligibilityBoundaryAt)
        assertNotNull(result.record.rawPayloadUri)
    }

    @Test
    fun http403CompleteBodyIsProviderFailureWithRaw() {
        val body = """{"error":"forbidden"}""".toByteArray(StandardCharsets.UTF_8)
        val result = service().archivePossessedResponse(symbol, possession(403, body))
        assertEquals(ObservationStatus.PROVIDER_FAILURE, result.record.observationStatus)
        assertNotNull(result.record.fetchedAt)
        assertNotNull(result.record.rawPayloadUri)
        assertEquals(Sha256Hex.of(body), result.record.rawPayloadHash)
        assertNull(result.record.eligibilityBoundaryAt)
        assertEquals(0, result.coverage.observedCount)
    }

    @Test
    fun http500CompleteBodyIsProviderFailureWithRaw() {
        val body = """{"error":"boom"}""".toByteArray(StandardCharsets.UTF_8)
        val result = service().archivePossessedResponse(symbol, possession(500, body))
        assertEquals(ObservationStatus.PROVIDER_FAILURE, result.record.observationStatus)
        assertNotNull(result.record.rawPayloadUri)
        assertNull(result.record.eligibilityBoundaryAt)
    }

    @Test
    fun transportFailureHasNoFetchedAtOrRaw() {
        val result =
            service().archivePossessedResponse(
                symbol,
                possession(status = 0, body = null, transportMessage = "ConnectException"),
            )
        assertEquals(ObservationStatus.PROVIDER_FAILURE, result.record.observationStatus)
        assertEquals(TransportStatus.TRANSPORT_FAILURE, result.record.transportStatus)
        assertNull(result.record.fetchedAt)
        assertNull(result.record.rawPayloadHash)
        assertNull(result.record.rawPayloadUri)
        assertNull(result.record.eligibilityBoundaryAt)
    }

    @Test
    fun invalidUtf8IsRejectedWithExactRawBytesPreserved() {
        val prefix = """{"Meta Data":{"2. Symbol":"IBM","name":"""".toByteArray(StandardCharsets.UTF_8)
        val suffix = """"},"Time Series (Daily)":{}}""".toByteArray(StandardCharsets.UTF_8)
        val body = prefix + byteArrayOf(0xFF.toByte()) + suffix
        val result = service().archivePossessedResponse(symbol, possession(200, body))
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertNull(result.record.eligibilityBoundaryAt)
        assertNotNull(result.record.fetchedAt)
        assertEquals(Sha256Hex.of(body), result.record.rawPayloadHash)
        val onDisk = Files.readAllBytes(Path.of(result.record.rawPayloadUri!!))
        assertTrue(onDisk.contentEquals(body))
        assertTrue(onDisk.contains(0xFF.toByte()))
    }

    @Test
    fun malformedJsonIsRejected() {
        val body = """{"Meta Data":""".toByteArray(StandardCharsets.UTF_8)
        val result = service().archivePossessedResponse(symbol, possession(200, body))
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertNull(result.record.eligibilityBoundaryAt)
        assertNotNull(result.record.rawPayloadUri)
    }

    @Test
    fun missingMetaDataIsRejected() {
        val body =
            """{"Time Series (Daily)":{"2024-01-25":{"1. open":"1","2. high":"1","3. low":"1","4. close":"1","5. volume":"1"}}}"""
                .toByteArray(StandardCharsets.UTF_8)
        val result = service().archivePossessedResponse(symbol, possession(200, body))
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
    }

    @Test
    fun missingTimeSeriesIsRejected() {
        val body =
            """{"Meta Data":{"1. Information":"x","2. Symbol":"IBM","3. Last Refreshed":"2024-01-25","4. Output Size":"Compact","5. Time Zone":"US/Eastern"}}"""
                .toByteArray(StandardCharsets.UTF_8)
        val result = service().archivePossessedResponse(symbol, possession(200, body))
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
    }

    @Test
    fun emptySeriesIsRejected() {
        val body =
            """{"Meta Data":{"1. Information":"x","2. Symbol":"IBM","3. Last Refreshed":"2024-01-25","4. Output Size":"Compact","5. Time Zone":"US/Eastern"},"Time Series (Daily)":{}}"""
                .toByteArray(StandardCharsets.UTF_8)
        val result = service().archivePossessedResponse(symbol, possession(200, body))
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
    }

    @Test
    fun malformedDateKeyIsRejected() {
        val body =
            """{"Meta Data":{"1. Information":"x","2. Symbol":"IBM","3. Last Refreshed":"2024-01-25","4. Output Size":"Compact","5. Time Zone":"US/Eastern"},"Time Series (Daily)":{"25-01-2024":{"1. open":"1","2. high":"1","3. low":"1","4. close":"1","5. volume":"1"}}}"""
                .toByteArray(StandardCharsets.UTF_8)
        val result = service().archivePossessedResponse(symbol, possession(200, body))
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
    }

    @Test
    fun missingOhlcvFieldIsRejected() {
        val body =
            """{"Meta Data":{"1. Information":"x","2. Symbol":"IBM","3. Last Refreshed":"2024-01-25","4. Output Size":"Compact","5. Time Zone":"US/Eastern"},"Time Series (Daily)":{"2024-01-25":{"1. open":"1","2. high":"1","3. low":"1","5. volume":"1"}}}"""
                .toByteArray(StandardCharsets.UTF_8)
        val result = service().archivePossessedResponse(symbol, possession(200, body))
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
    }

    @Test
    fun negativePriceIsRejected() {
        val body =
            """{"Meta Data":{"1. Information":"x","2. Symbol":"IBM","3. Last Refreshed":"2024-01-25","4. Output Size":"Compact","5. Time Zone":"US/Eastern"},"Time Series (Daily)":{"2024-01-25":{"1. open":"-1.0","2. high":"1","3. low":"1","4. close":"1","5. volume":"1"}}}"""
                .toByteArray(StandardCharsets.UTF_8)
        val result = service().archivePossessedResponse(symbol, possession(200, body))
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
    }

    @Test
    fun highLessThanLowIsRejected() {
        val body =
            """{"Meta Data":{"1. Information":"x","2. Symbol":"IBM","3. Last Refreshed":"2024-01-25","4. Output Size":"Compact","5. Time Zone":"US/Eastern"},"Time Series (Daily)":{"2024-01-25":{"1. open":"1","2. high":"1","3. low":"2","4. close":"1","5. volume":"1"}}}"""
                .toByteArray(StandardCharsets.UTF_8)
        val result = service().archivePossessedResponse(symbol, possession(200, body))
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
    }

    @Test
    fun symbolMismatchIsRejected() {
        val result = service().archivePossessedResponse("AAPL", possession(200, validBody.copyOf()))
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertNull(result.record.eligibilityBoundaryAt)
    }

    @Test
    fun sameRequestKeySameHashIsDuplicateCandidate() {
        val svc = service()
        val first = svc.archivePossessedResponse(symbol, possession(200, validBody.copyOf()))
        val second = svc.archivePossessedResponse(symbol, possession(200, validBody.copyOf()))
        assertEquals(first.record.archiveId, second.record.duplicateOf)
        assertNull(second.record.revisionCandidateOf)
        assertEquals(
            first.record.eligibilityBoundaryAt,
            svc.readManifest().first().eligibilityBoundaryAt,
        )
    }

    @Test
    fun sameRequestKeyDifferentHashIsRevisionCandidateNotAuthoritativeCorrection() {
        // Sanitized alternate body: same structure, one close digit changed.
        val alt =
            String(validBody, StandardCharsets.UTF_8)
                .replace("100.7500", "100.7600")
                .toByteArray(StandardCharsets.UTF_8)
        assertNotEquals(Sha256Hex.of(validBody), Sha256Hex.of(alt))
        val svc = service()
        val first = svc.archivePossessedResponse(symbol, possession(200, validBody.copyOf()))
        val second = svc.archivePossessedResponse(symbol, possession(200, alt))
        assertEquals(ObservationStatus.OBSERVED, second.record.observationStatus)
        assertEquals(first.record.archiveId, second.record.revisionCandidateOf)
        assertNull(second.record.duplicateOf)
        assertEquals(2, svc.readManifest().size)
        // Hash difference is revision candidate only — not correction certainty.
        assertTrue(second.record.notes?.contains("DailyPrice") == true || second.record.notes != null)
    }

    @Test
    fun rawWriteFailureIsLocalArchiveFailure() {
        val svc = service()
        assertTrue(svc.archivePossessedResponse(symbol, possession(200, validBody.copyOf())).observedIngestSucceeded)
        val rawDir =
            root.resolve(AlphaVantageDailyArchiveClient.DOMAIN)
                .resolve(AlphaVantageDailyArchiveClient.SOURCE)
                .resolve("raw")
        Files.createDirectories(rawDir)
        Files.writeString(rawDir.resolve("fixed-raw.raw"), "occupied")
        val fixed =
            AlphaVantageDailyForwardArchiveService(
                archiveRoot = root,
                client = AlphaVantageDailyArchiveClient(baseUrl = "http://127.0.0.1:1", clock = { nextInstant() }),
                clock = { nextInstant() },
                idGenerator = { "fixed-raw" },
            )
        val result = fixed.archivePossessedResponse(symbol, possession(200, validBody.copyOf()))
        assertEquals(ObservationStatus.LOCAL_ARCHIVE_FAILURE, result.record.observationStatus)
        assertNull(result.record.eligibilityBoundaryAt)
        assertFalse(result.observedIngestSucceeded)
    }

    @Test
    fun orphanRawIsDetectableAndExcludedFromCoverage() {
        val svc = service()
        assertTrue(svc.archivePossessedResponse(symbol, possession(200, validBody.copyOf())).observedIngestSucceeded)
        assertTrue(svc.findOrphanRawObjects().isEmpty())
        val residual =
            ImmutableRawStore(root).writeImmutable(
                relativeDir = "${AlphaVantageDailyArchiveClient.DOMAIN}/${AlphaVantageDailyArchiveClient.SOURCE}/raw",
                archiveId = "orphan-after-append-fail",
                payload = validBody,
                expectedSha256Hex = Sha256Hex.of(validBody),
            )
        val orphans = service().findOrphanRawObjects()
        assertTrue(orphans.any { it == residual.toAbsolutePath().normalize() })
        assertEquals(1, service().currentCoverage().observedCount)
    }

    @Test
    fun coverageUsesObservedIngestedAtNotPayloadTradingDates() {
        val svc = service()
        val rejected =
            svc.archivePossessedResponse(
                symbol,
                possession(200, resourceBytes("av-information-envelope.json")),
            )
        assertEquals(0, rejected.coverage.observedCount)
        assertNull(rejected.coverage.coverageStartAt)
        val ok = svc.archivePossessedResponse(symbol, possession(200, validBody.copyOf()))
        assertEquals(1, ok.coverage.observedCount)
        assertEquals(ok.record.ingestedAt, ok.coverage.coverageStartAt)
        assertEquals(ok.record.ingestedAt, ok.coverage.coverageThroughAt)
        // Payload contains 2024-01-23..25 — must NOT become coverageStartAt.
        assertFalse(ok.coverage.coverageStartAt.toString().startsWith("2024-01"))
    }

    @Test
    fun requestKeyNeverContainsApiKey() {
        val key = AlphaVantageDailyArchiveClient.requestKey("IBM")
        assertEquals(
            "GET|/query|function=TIME_SERIES_DAILY|symbol=IBM|outputsize=compact",
            key,
        )
        assertFalse(key.contains("apikey", ignoreCase = true))
        assertFalse(key.contains("demo", ignoreCase = true))
    }

    @Test
    fun hashMismatchOnWriteIsFailClosed() {
        val store = ImmutableRawStore(root)
        val bytes = "abc".toByteArray()
        val ex =
            kotlin.test.assertFailsWith<ArchiveIoException> {
                try {
                    store.writeImmutable("x", "h1", bytes, "deadbeef")
                } catch (e: IllegalArgumentException) {
                    throw ArchiveIoException(e.message ?: "hash", e)
                }
            }
        assertTrue(ex.message!!.contains("Hash") || ex.cause is IllegalArgumentException)
    }

    @Test
    fun noDailyPriceMapperSecurityIdOrKnownAtOnManifest() {
        val result = service().archivePossessedResponse(symbol, possession(200, validBody.copyOf()))
        val line = result.record.toJsonLine()
        assertFalse(line.contains("\"knownAt\""))
        assertFalse(line.contains("\"securityId\"", ignoreCase = true))
        assertFalse(line.contains("\"currency\""))
        assertNull(result.record.externalIdentifier)
        assertNull(result.record.externalIdentifierNamespace)
    }
}
