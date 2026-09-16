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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Synthetic QA for SECURITY_MASTER / massive.stocks.all_tickers forward archive.
 * Live network is not required. Fixtures are sanitized / synthetic.
 */
class MassiveAllTickersForwardArchivePocTest {
    private lateinit var root: Path
    private val ids = AtomicInteger(0)
    private val clock =
        mutableListOf(
            Instant.parse("2026-09-16T14:00:00Z"),
            Instant.parse("2026-09-16T14:00:01Z"),
            Instant.parse("2026-09-16T14:00:02Z"),
            Instant.parse("2026-09-16T14:00:03Z"),
            Instant.parse("2026-09-16T14:00:04Z"),
            Instant.parse("2026-09-16T14:00:05Z"),
            Instant.parse("2026-09-16T14:00:06Z"),
            Instant.parse("2026-09-16T14:00:07Z"),
            Instant.parse("2026-09-16T14:00:08Z"),
            Instant.parse("2026-09-16T14:00:09Z"),
            Instant.parse("2026-09-16T14:00:10Z"),
            Instant.parse("2026-09-16T14:00:11Z"),
            Instant.parse("2026-09-16T14:00:12Z"),
            Instant.parse("2026-09-16T14:00:13Z"),
            Instant.parse("2026-09-16T14:00:14Z"),
            Instant.parse("2026-09-16T14:00:15Z"),
            Instant.parse("2026-09-16T14:00:16Z"),
            Instant.parse("2026-09-16T14:00:17Z"),
            Instant.parse("2026-09-16T14:00:18Z"),
            Instant.parse("2026-09-16T14:00:19Z"),
            Instant.parse("2026-09-16T14:00:20Z"),
            Instant.parse("2026-09-16T14:00:21Z"),
            Instant.parse("2026-09-16T14:00:22Z"),
            Instant.parse("2026-09-16T14:00:23Z"),
            Instant.parse("2026-09-16T14:00:24Z"),
            Instant.parse("2026-09-16T14:00:25Z"),
            Instant.parse("2026-09-16T14:00:26Z"),
            Instant.parse("2026-09-16T14:00:27Z"),
            Instant.parse("2026-09-16T14:00:28Z"),
            Instant.parse("2026-09-16T14:00:29Z"),
            Instant.parse("2026-09-16T14:00:30Z"),
            Instant.parse("2026-09-16T14:00:31Z"),
            Instant.parse("2026-09-16T14:00:32Z"),
            Instant.parse("2026-09-16T14:00:33Z"),
            Instant.parse("2026-09-16T14:00:34Z"),
            Instant.parse("2026-09-16T14:00:35Z"),
            Instant.parse("2026-09-16T14:00:36Z"),
            Instant.parse("2026-09-16T14:00:37Z"),
            Instant.parse("2026-09-16T14:00:38Z"),
            Instant.parse("2026-09-16T14:00:39Z"),
            Instant.parse("2026-09-16T14:00:40Z"),
            Instant.parse("2026-09-16T14:00:41Z"),
            Instant.parse("2026-09-16T14:00:42Z"),
            Instant.parse("2026-09-16T14:00:43Z"),
            Instant.parse("2026-09-16T14:00:44Z"),
            Instant.parse("2026-09-16T14:00:45Z"),
            Instant.parse("2026-09-16T14:00:46Z"),
            Instant.parse("2026-09-16T14:00:47Z"),
            Instant.parse("2026-09-16T14:00:48Z"),
            Instant.parse("2026-09-16T14:00:49Z"),
            Instant.parse("2026-09-16T14:00:50Z"),
        )
    private var clockIdx = 0

    private fun nextInstant(): Instant = clock[clockIdx++]

    private val ticker = "AAPL"

    private fun service(
        limit: Int = MassiveAllTickersArchiveClient.DEFAULT_LIMIT,
    ): MassiveAllTickersForwardArchiveService =
        MassiveAllTickersForwardArchiveService(
            archiveRoot = root,
            client =
                MassiveAllTickersArchiveClient(
                    baseUrl = "http://127.0.0.1:1",
                    limit = limit,
                    clock = { nextInstant() },
                ),
            clock = { nextInstant() },
            idGenerator = { "id-${ids.incrementAndGet()}" },
        )

    private val validBody: ByteArray =
        this::class.java.getResourceAsStream(
            "/archive/poc/massive/massive-all-tickers-aapl-sanitized.json",
        )!!.readBytes()

    private val inactiveBody: ByteArray =
        this::class.java.getResourceAsStream(
            "/archive/poc/massive/massive-all-tickers-inactive-sanitized.json",
        )!!.readBytes()

    private fun requestKey(
        t: String? = ticker,
        active: Boolean? = null,
        date: String? = null,
        limit: Int = MassiveAllTickersArchiveClient.DEFAULT_LIMIT,
    ): String =
        MassiveAllTickersArchiveClient.requestKey(
            ticker = t,
            active = active,
            date = date,
            limit = limit,
        )

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
        svc: MassiveAllTickersForwardArchiveService = service(),
        body: ByteArray,
        status: Int = 200,
        t: String? = ticker,
        active: Boolean? = null,
        date: String? = null,
    ) = svc.archivePossessedResponse(
        possession(
            status,
            body,
            requestKey = requestKey(t = t, active = active, date = date),
        ),
        ticker = t,
        active = active,
        date = date,
    )

    private fun mutateValid(transform: (String) -> String): ByteArray =
        transform(String(validBody, StandardCharsets.UTF_8)).toByteArray(StandardCharsets.UTF_8)

    @BeforeTest
    fun setup() {
        root = Files.createTempDirectory("massive-all-tickers-archive-poc")
        clockIdx = 0
        ids.set(0)
    }

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun http200ValidSingleResultIsObservedWithExactSha256() {
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
        assertTrue(result.record.observedFields.contains("currency_symbol"))
        assertTrue(result.record.observedFields.contains("currency_name"))
        val line = result.record.toJsonLine()
        assertFalse(line.contains("\"knownAt\""))
        assertFalse(line.contains("\"securityId\"", ignoreCase = true))
        assertFalse(line.contains("apiKey", ignoreCase = true))
        assertEquals(requestKey(), result.record.requestKey)
        assertEquals(MassiveAllTickersArchiveClient.DOMAIN, result.record.domain)
        assertEquals(MassiveAllTickersArchiveClient.SOURCE, result.record.source)
        assertEquals(1, result.coverage.observedCount)
    }

    @Test
    fun whitespaceDifferenceChangesHash() {
        val a = validBody
        val b = (String(validBody) + " ").toByteArray(StandardCharsets.UTF_8)
        assertNotEquals(Sha256Hex.of(a), Sha256Hex.of(b))
    }

    @Test
    fun malformedJsonIsRejected() {
        val body = """{"status":"OK","results":[""".toByteArray(StandardCharsets.UTF_8)
        val result = archive(body = body)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertNotNull(result.record.rawPayloadUri)
        assertNull(result.record.eligibilityBoundaryAt)
    }

    @Test
    fun invalidUtf8IsRejectedWithExactRawBytesPreserved() {
        val prefix =
            """{"status":"OK","results":[{"ticker":"AAPL","name":"""".toByteArray()
        val suffix = """"}]}""".toByteArray()
        val body = prefix + byteArrayOf(0xFF.toByte()) + suffix
        val result = archive(body = body)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertNull(result.record.eligibilityBoundaryAt)
        assertEquals(Sha256Hex.of(body), result.record.rawPayloadHash)
        val onDisk = Files.readAllBytes(Path.of(result.record.rawPayloadUri!!))
        assertTrue(onDisk.contentEquals(body))
    }

    @Test
    fun providerErrorEnvelopeIsNotObserved() {
        val body =
            this::class.java.getResourceAsStream(
                "/archive/poc/massive/massive-all-tickers-error-envelope.json",
            )!!.readBytes()
        val result = archive(body = body)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertNull(result.record.eligibilityBoundaryAt)
        assertNotNull(result.record.rawPayloadUri)
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
    }

    @Test
    fun http429CompleteBodyIsProviderFailureWithRaw() {
        val body = """{"status":"ERROR","error":"rate limited"}""".toByteArray(StandardCharsets.UTF_8)
        val result = archive(body = body, status = 429)
        assertEquals(ObservationStatus.PROVIDER_FAILURE, result.record.observationStatus)
        assertNotNull(result.record.rawPayloadUri)
    }

    @Test
    fun http500CompleteBodyIsProviderFailureWithRaw() {
        val body = """{"status":"ERROR","error":"boom"}""".toByteArray(StandardCharsets.UTF_8)
        val result = archive(body = body, status = 500)
        assertEquals(ObservationStatus.PROVIDER_FAILURE, result.record.observationStatus)
        assertNotNull(result.record.rawPayloadUri)
    }

    @Test
    fun transportFailureHasNoFetchedAtOrRaw() {
        val result =
            service().archivePossessedResponse(
                possession(status = 0, body = null, transportMessage = "ConnectException"),
                ticker = ticker,
            )
        assertEquals(ObservationStatus.PROVIDER_FAILURE, result.record.observationStatus)
        assertEquals(TransportStatus.TRANSPORT_FAILURE, result.record.transportStatus)
        assertNull(result.record.fetchedAt)
        assertNull(result.record.rawPayloadHash)
        assertNull(result.record.rawPayloadUri)
        assertEquals("ConnectException", result.record.notes)
        assertFalse(result.record.notes!!.contains("apiKey", ignoreCase = true))
    }

    @Test
    fun missingApiKeyFailsFastWithoutProviderFailurePossession() {
        val client =
            MassiveAllTickersArchiveClient(
                baseUrl = "http://127.0.0.1:1",
                apiKey = null,
                clock = { nextInstant() },
            )
        val thrown =
            assertFailsWith<IllegalStateException> {
                client.executeAllTickers(ticker = ticker)
            }
        assertTrue(thrown.message!!.contains("MASSIVE_API_KEY"))
        assertTrue(thrown.message!!.contains("local configuration"))
        assertFalse(thrown.message!!.contains("apiKey="))

        val svc =
            MassiveAllTickersForwardArchiveService(
                archiveRoot = root,
                client = client,
                clock = { nextInstant() },
                idGenerator = { "id-${ids.incrementAndGet()}" },
            )
        assertFailsWith<IllegalStateException> {
            svc.archiveAllTickers(ticker = ticker)
        }
        assertTrue(svc.readManifest().isEmpty())
        assertEquals(0, svc.currentCoverage().observedCount)
    }

    @Test
    fun requestConstructionFailureStoresClassNameOnlyNotSecret() {
        val secret = "massive-all-tickers-secret-do-not-leak"
        val client =
            MassiveAllTickersArchiveClient(
                baseUrl = "not a uri!!!",
                apiKey = secret,
                clock = { nextInstant() },
            )
        val possession = client.executeAllTickers(ticker = ticker)
        assertNull(possession.fetchedAt)
        assertNull(possession.bodyBytes)
        assertNotNull(possession.transportFailureMessage)
        assertTrue(possession.transportFailureMessage!!.matches(Regex("[A-Za-z][A-Za-z0-9_]*")))
        assertFalse(possession.transportFailureMessage!!.contains(secret))
        assertFalse(possession.transportFailureMessage!!.contains("apiKey", ignoreCase = true))
        assertFalse(possession.requestKey.contains(secret))
        assertFalse(possession.requestKey.contains("apiKey", ignoreCase = true))
    }

    @Test
    fun requestKeyNeverContainsApiKeyAndIsCanonical() {
        val key = requestKey()
        assertEquals(
            "GET|/v3/reference/tickers|market=stocks|ticker=AAPL|limit=1|sort=ticker|order=asc",
            key,
        )
        assertFalse(key.contains("apiKey", ignoreCase = true))
        val withDateActive =
            requestKey(t = "AAPL", active = false, date = "2024-06-01")
        assertEquals(
            "GET|/v3/reference/tickers|market=stocks|ticker=AAPL|active=false|" +
                "date=2024-06-01|limit=1|sort=ticker|order=asc",
            withDateActive,
        )
        val parsed = MassiveAllTickersRequestKey.parseOrThrow(withDateActive)
        assertEquals("AAPL", parsed.ticker)
        assertEquals(false, parsed.active)
        assertEquals("2024-06-01", parsed.date)
        assertEquals(1, parsed.limit)
    }

    @Test
    fun tickerMismatchIsRejected() {
        val result = archive(body = validBody.copyOf(), t = "MSFT")
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertNull(result.record.eligibilityBoundaryAt)
        assertTrue(result.record.notes!!.contains("ticker mismatch"))
    }

    @Test
    fun wrongCurrencySymbolTypeIsRejected() {
        val body =
            mutateValid {
                it.replace("\"currency_symbol\": \"USD\"", "\"currency_symbol\": 840")
            }
        val result = archive(body = body)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertTrue(result.record.notes!!.contains("currency_symbol"))
    }

    @Test
    fun blankCurrencySymbolIsRejected() {
        val body =
            mutateValid {
                it.replace("\"currency_symbol\": \"USD\"", "\"currency_symbol\": \"\"")
            }
        val result = archive(body = body)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertTrue(result.record.notes!!.contains("currency_symbol"))
    }

    @Test
    fun validCurrencySymbolIsPreservedAsRawEvidenceNotNormalized() {
        val body =
            mutateValid {
                it.replace("\"currency_symbol\": \"USD\"", "\"currency_symbol\": \"usd\"")
            }
        val result = archive(body = body)
        assertEquals(ObservationStatus.OBSERVED, result.record.observationStatus)
        assertTrue(result.record.observedFields.contains("currency_symbol"))
        // Provider raw kept; no uppercase normalize into domain.
        val onDisk = String(Files.readAllBytes(Path.of(result.record.rawPayloadUri!!)))
        assertTrue(onDisk.contains("\"currency_symbol\": \"usd\""))
        assertFalse(result.record.notes!!.contains("DailyPrice.currency generated"))
        assertTrue(result.record.notes!!.contains("currency_symbol≠DailyPrice.currency"))
    }

    @Test
    fun activeFalseAndDelistedUtcArePreserved() {
        val result =
            archive(
                body = inactiveBody.copyOf(),
                t = "DEAD",
                active = false,
            )
        assertEquals(ObservationStatus.OBSERVED, result.record.observationStatus)
        assertTrue(result.record.observedFields.contains("active"))
        assertTrue(result.record.observedFields.contains("delisted_utc"))
        assertTrue(result.record.observedFields.contains("last_updated_utc"))
        val onDisk = String(Files.readAllBytes(Path.of(result.record.rawPayloadUri!!)))
        assertTrue(onDisk.contains("\"active\": false"))
        assertTrue(onDisk.contains("\"delisted_utc\": \"2020-01-15T00:00:00Z\""))
        assertEquals(
            requestKey(t = "DEAD", active = false),
            result.record.requestKey,
        )
    }

    @Test
    fun dateQueryDoesNotBackdateEligibilityOrKnownAt() {
        val requestDate = "2024-06-01"
        val result = archive(body = validBody.copyOf(), date = requestDate)
        assertEquals(ObservationStatus.OBSERVED, result.record.observationStatus)
        assertEquals(
            requestKey(date = requestDate),
            result.record.requestKey,
        )
        val ingestedAt = result.record.ingestedAt
        val eligibility = result.record.eligibilityBoundaryAt
        assertNotNull(eligibility)
        assertEquals(ingestedAt, eligibility)
        assertTrue(ingestedAt.toString().startsWith("2026-09-16"))
        assertFalse(eligibility.toString().contains("2024-06-01"))
        assertFalse(eligibility.toString().contains("2026-09-15"))
        assertFalse(result.record.toJsonLine().contains("\"knownAt\""))
    }

    @Test
    fun nextUrlPresentIsRejectedAndNotCompleteCoverage() {
        val body =
            mutateValid {
                it.replace(
                    "\"status\": \"OK\"",
                    "\"next_url\": \"https://example.invalid/next\", \"status\": \"OK\"",
                )
            }
        val result = archive(body = body)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertNull(result.record.eligibilityBoundaryAt)
        assertNotNull(result.record.rawPayloadUri)
        assertTrue(result.record.notes!!.contains("next_url present"))
        assertTrue(result.record.notes!!.contains("first page is not complete coverage"))
        assertEquals(0, result.coverage.observedCount)
    }

    @Test
    fun emptyResultsIsRejectedNotMissing() {
        val body =
            """{"status":"OK","count":0,"results":[]}""".toByteArray(StandardCharsets.UTF_8)
        val result = archive(body = body)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertNotEquals(ObservationStatus.MISSING, result.record.observationStatus)
        assertTrue(result.record.notes!!.contains("empty results"))
        assertNotNull(result.record.rawPayloadUri)
        assertNull(result.record.eligibilityBoundaryAt)
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
        val alt = mutateValid { it.replace("Apple Inc.", "Apple Inc. Revised") }
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
    fun coverageUsesObservedIngestedAtOnlyNotProviderDates() {
        val svc = service()
        val rejected =
            archive(
                svc = svc,
                body =
                    """{"status":"ERROR","error":"nope"}""".toByteArray(StandardCharsets.UTF_8),
            )
        assertEquals(ObservationStatus.REJECTED_VALIDATION, rejected.record.observationStatus)
        assertEquals(0, rejected.coverage.observedCount)
        val ok = archive(svc = svc, body = validBody.copyOf())
        assertEquals(1, ok.coverage.observedCount)
        assertEquals(ok.record.ingestedAt, ok.coverage.coverageStartAt)
        assertFalse(ok.coverage.coverageStartAt.toString().startsWith("2020"))
        assertFalse(ok.coverage.coverageStartAt.toString().contains("2026-09-15"))
    }

    @Test
    fun doesNotGenerateDailyPriceCurrencySecurityIdKnownAtOrMic() {
        val result = archive(body = validBody.copyOf())
        assertNull(result.record.externalIdentifier)
        assertNull(result.record.externalIdentifierNamespace)
        val notes = result.record.notes!!
        assertTrue(notes.contains("currency_symbol≠DailyPrice.currency"))
        assertTrue(notes.contains("currency_name≠DailyPrice.currency"))
        assertTrue(notes.contains("primary_exchange≠venue resolved"))
        assertTrue(notes.contains("composite_figi/share_class_figi≠SecurityId"))
        assertTrue(notes.contains("cik≠IssuerId"))
        assertTrue(notes.contains("delisted_utc/last_updated_utc≠knownAt"))
        val line = result.record.toJsonLine()
        assertFalse(line.contains("\"knownAt\""))
        assertFalse(line.contains("\"securityId\"", ignoreCase = true))
        assertFalse(line.contains("\"issuerId\"", ignoreCase = true))
        assertFalse(line.contains("\"dailyPrice\"", ignoreCase = true))
        assertFalse(line.contains("\"mic\"", ignoreCase = true))
        assertNotNull(result.record.eligibilityBoundaryAt)
    }

    @Test
    fun orphanRawIsDetectableAndExcludedFromCoverage() {
        val svc = service()
        assertTrue(archive(svc = svc, body = validBody.copyOf()).observedIngestSucceeded)
        assertTrue(svc.findOrphanRawObjects().isEmpty())
        val residual =
            ImmutableRawStore(root).writeImmutable(
                relativeDir =
                    "${MassiveAllTickersArchiveClient.DOMAIN}/" +
                        "${MassiveAllTickersArchiveClient.SOURCE}/raw",
                archiveId = "orphan-after-append-fail",
                payload = validBody,
                expectedSha256Hex = Sha256Hex.of(validBody),
            )
        val orphans = service().findOrphanRawObjects()
        assertTrue(orphans.any { it == residual.toAbsolutePath().normalize() })
        assertEquals(1, service().currentCoverage().observedCount)
    }

    @Test
    fun possessionRequestKeyMismatchIsRefused() {
        val bad = possession(200, validBody.copyOf(), requestKey = requestKey(t = "MSFT"))
        try {
            service().archivePossessedResponse(bad, ticker = ticker)
            throw AssertionError("expected require failure")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("possession.requestKey mismatch"))
        }
    }

    @Test
    fun differentFiltersProduceDifferentRequestKeys() {
        val a = requestKey(t = "AAPL", active = null)
        val b = requestKey(t = "AAPL", active = true)
        val c = requestKey(t = "AAPL", active = false)
        val d = requestKey(t = null)
        assertNotEquals(a, b)
        assertNotEquals(b, c)
        assertNotEquals(a, d)
    }
}
