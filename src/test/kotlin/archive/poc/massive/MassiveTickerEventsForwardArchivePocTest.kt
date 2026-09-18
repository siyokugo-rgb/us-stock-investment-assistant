package archive.poc.massive

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
 * Synthetic / offline QA for SECURITY_MASTER / massive.stocks.ticker_events forward archive.
 *
 * Fixtures are sanitized / synthetic — not live provider evidence.
 * Live network is not required and must not be used in this PoC revision.
 */
class MassiveTickerEventsForwardArchivePocTest {
    private lateinit var root: Path
    private val ids = AtomicInteger(0)
    private val clock =
        mutableListOf(
            Instant.parse("2026-09-18T12:00:00Z"),
            Instant.parse("2026-09-18T12:00:01Z"),
            Instant.parse("2026-09-18T12:00:02Z"),
            Instant.parse("2026-09-18T12:00:03Z"),
            Instant.parse("2026-09-18T12:00:04Z"),
            Instant.parse("2026-09-18T12:00:05Z"),
            Instant.parse("2026-09-18T12:00:06Z"),
            Instant.parse("2026-09-18T12:00:07Z"),
            Instant.parse("2026-09-18T12:00:08Z"),
            Instant.parse("2026-09-18T12:00:09Z"),
            Instant.parse("2026-09-18T12:00:10Z"),
            Instant.parse("2026-09-18T12:00:11Z"),
            Instant.parse("2026-09-18T12:00:12Z"),
            Instant.parse("2026-09-18T12:00:13Z"),
            Instant.parse("2026-09-18T12:00:14Z"),
            Instant.parse("2026-09-18T12:00:15Z"),
            Instant.parse("2026-09-18T12:00:16Z"),
            Instant.parse("2026-09-18T12:00:17Z"),
            Instant.parse("2026-09-18T12:00:18Z"),
            Instant.parse("2026-09-18T12:00:19Z"),
            Instant.parse("2026-09-18T12:00:20Z"),
            Instant.parse("2026-09-18T12:00:21Z"),
            Instant.parse("2026-09-18T12:00:22Z"),
            Instant.parse("2026-09-18T12:00:23Z"),
            Instant.parse("2026-09-18T12:00:24Z"),
            Instant.parse("2026-09-18T12:00:25Z"),
            Instant.parse("2026-09-18T12:00:26Z"),
            Instant.parse("2026-09-18T12:00:27Z"),
            Instant.parse("2026-09-18T12:00:28Z"),
            Instant.parse("2026-09-18T12:00:29Z"),
            Instant.parse("2026-09-18T12:00:30Z"),
            Instant.parse("2026-09-18T12:00:31Z"),
            Instant.parse("2026-09-18T12:00:32Z"),
            Instant.parse("2026-09-18T12:00:33Z"),
            Instant.parse("2026-09-18T12:00:34Z"),
            Instant.parse("2026-09-18T12:00:35Z"),
            Instant.parse("2026-09-18T12:00:36Z"),
            Instant.parse("2026-09-18T12:00:37Z"),
            Instant.parse("2026-09-18T12:00:38Z"),
            Instant.parse("2026-09-18T12:00:39Z"),
            Instant.parse("2026-09-18T12:00:40Z"),
            Instant.parse("2026-09-18T12:00:41Z"),
            Instant.parse("2026-09-18T12:00:42Z"),
            Instant.parse("2026-09-18T12:00:43Z"),
            Instant.parse("2026-09-18T12:00:44Z"),
            Instant.parse("2026-09-18T12:00:45Z"),
            Instant.parse("2026-09-18T12:00:46Z"),
            Instant.parse("2026-09-18T12:00:47Z"),
            Instant.parse("2026-09-18T12:00:48Z"),
            Instant.parse("2026-09-18T12:00:49Z"),
            Instant.parse("2026-09-18T12:00:50Z"),
            Instant.parse("2026-09-18T12:00:51Z"),
            Instant.parse("2026-09-18T12:00:52Z"),
            Instant.parse("2026-09-18T12:00:53Z"),
            Instant.parse("2026-09-18T12:00:54Z"),
            Instant.parse("2026-09-18T12:00:55Z"),
            Instant.parse("2026-09-18T12:00:56Z"),
            Instant.parse("2026-09-18T12:00:57Z"),
            Instant.parse("2026-09-18T12:00:58Z"),
            Instant.parse("2026-09-18T12:00:59Z"),
            Instant.parse("2026-09-18T12:01:00Z"),
            Instant.parse("2026-09-18T12:01:01Z"),
            Instant.parse("2026-09-18T12:01:02Z"),
            Instant.parse("2026-09-18T12:01:03Z"),
            Instant.parse("2026-09-18T12:01:04Z"),
            Instant.parse("2026-09-18T12:01:05Z"),
            Instant.parse("2026-09-18T12:01:06Z"),
            Instant.parse("2026-09-18T12:01:07Z"),
            Instant.parse("2026-09-18T12:01:08Z"),
            Instant.parse("2026-09-18T12:01:09Z"),
            Instant.parse("2026-09-18T12:01:10Z"),
        )
    private var clockIdx = 0

    private fun nextInstant(): Instant = clock[clockIdx++]

    private val lookupId = "META"

    private fun service(): MassiveTickerEventsForwardArchiveService =
        MassiveTickerEventsForwardArchiveService(
            archiveRoot = root,
            client =
                MassiveTickerEventsArchiveClient(
                    baseUrl = "http://127.0.0.1:1",
                    clock = { nextInstant() },
                ),
            clock = { nextInstant() },
            idGenerator = { "id-${ids.incrementAndGet()}" },
        )

    private val validBody: ByteArray =
        this::class.java.getResourceAsStream(
            "/archive/poc/massive/massive-ticker-events-meta-sanitized.json",
        )!!.readBytes()

    private fun requestKey(id: String = lookupId): String =
        MassiveTickerEventsArchiveClient.requestKey(id)

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
        svc: MassiveTickerEventsForwardArchiveService = service(),
        body: ByteArray,
        status: Int = 200,
        id: String = lookupId,
    ) = svc.archivePossessedResponse(
        id,
        possession(status, body, requestKey = requestKey(id)),
    )

    private fun mutateValid(transform: (String) -> String): ByteArray =
        transform(String(validBody, StandardCharsets.UTF_8)).toByteArray(StandardCharsets.UTF_8)

    private fun emptyEventsBody(): ByteArray =
        """
        {
          "request_id": "synthetic-empty-events",
          "results": { "events": [], "name": "Synthetic Empty" },
          "status": "OK"
        }
        """.trimIndent().toByteArray(StandardCharsets.UTF_8)

    @BeforeTest
    fun setup() {
        root = Files.createTempDirectory("massive-ticker-events-archive-poc")
        clockIdx = 0
        ids.set(0)
    }

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun http200ValidNonemptyEventsIsObservedWithExactSha256() {
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
        assertEquals(2, result.validation!!.eventCount)
        assertEquals(2, result.validation!!.validatedEvents.size)
        assertEquals("META", result.validation!!.validatedEvents[0].ticker)
        assertEquals("FB", result.validation!!.validatedEvents[1].ticker)
        assertEquals(requestKey(), result.record.requestKey)
        assertEquals(MassiveTickerEventsArchiveClient.DOMAIN, result.record.domain)
        assertEquals(MassiveTickerEventsArchiveClient.SOURCE, result.record.source)
        val line = result.record.toJsonLine()
        assertFalse(line.contains("\"knownAt\""))
        assertFalse(line.contains("\"securityId\"", ignoreCase = true))
        assertFalse(line.contains("apiKey", ignoreCase = true))
        assertFalse(line.contains("\"validFrom\""))
        assertFalse(line.contains("\"validTo\""))
        assertTrue(result.record.notes!!.contains("raw Ticker Events evidence only"))
        assertTrue(result.record.notes!!.contains("event date≠knownAt"))
    }

    @Test
    fun statusMissingIsRejected() {
        val body =
            """
            {
              "request_id": "synthetic-no-status",
              "results": {
                "events": [
                  {"type":"ticker_change","date":"2022-06-09","ticker_change":{"ticker":"META"}}
                ]
              }
            }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
        assertFalse(String(body).contains("\"status\""))
        val result = archive(body = body)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertNotNull(result.record.rawPayloadUri)
        assertNull(result.record.eligibilityBoundaryAt)
        assertTrue(result.record.notes!!.contains("missing status"))
    }

    @Test
    fun statusNotOkIsRejected() {
        val body =
            this::class.java.getResourceAsStream(
                "/archive/poc/massive/massive-ticker-events-error-envelope.json",
            )!!.readBytes()
        val result = archive(body = body)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertNull(result.record.eligibilityBoundaryAt)
        assertNotNull(result.record.rawPayloadUri)
    }

    @Test
    fun resultsMissingIsRejected() {
        val body =
            """{"status":"OK","request_id":"x"}""".toByteArray(StandardCharsets.UTF_8)
        val result = archive(body = body)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertTrue(result.record.notes!!.contains("missing results"))
    }

    @Test
    fun resultsNullIsRejected() {
        val body =
            """{"status":"OK","results":null}""".toByteArray(StandardCharsets.UTF_8)
        val result = archive(body = body)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertTrue(result.record.notes!!.contains("results null"))
    }

    @Test
    fun eventsMissingIsRejected() {
        val body =
            """{"status":"OK","results":{"name":"X"}}""".toByteArray(StandardCharsets.UTF_8)
        val result = archive(body = body)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertTrue(result.record.notes!!.contains("missing results.events"))
    }

    @Test
    fun eventsNullIsRejected() {
        val body =
            """{"status":"OK","results":{"events":null}}""".toByteArray(StandardCharsets.UTF_8)
        val result = archive(body = body)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertTrue(result.record.notes!!.contains("results.events null"))
    }

    @Test
    fun eventsWrongTypeIsRejected() {
        val body =
            """{"status":"OK","results":{"events":{}}}""".toByteArray(StandardCharsets.UTF_8)
        val result = archive(body = body)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertTrue(result.record.notes!!.contains("results.events must be array"))
    }

    @Test
    fun emptyEventsIsObservedWithNegativeProofBanNotes() {
        val body = emptyEventsBody()
        val result = archive(body = body)
        assertEquals(ObservationStatus.OBSERVED, result.record.observationStatus)
        assertTrue(result.observedIngestSucceeded)
        assertEquals(0, result.validation!!.eventCount)
        assertTrue(result.validation!!.validatedEvents.isEmpty())
        val notes = result.record.notes!!
        assertTrue(notes.contains("empty events observed"))
        assertTrue(notes.contains("no-event meaning UNKNOWN"))
        assertTrue(notes.contains("absence≠no ticker change") || notes.contains("negative proof禁止"))
        assertNotNull(result.record.eligibilityBoundaryAt)
        assertEquals(result.record.ingestedAt, result.record.eligibilityBoundaryAt)
    }

    @Test
    fun eventTypeMissingIsRejected() {
        val body =
            mutateValid {
                it.replace("\"type\": \"ticker_change\"", "\"typ\": \"ticker_change\"")
            }
        val result = archive(body = body)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertTrue(result.record.notes!!.contains("type"))
    }

    @Test
    fun eventTypeNotTickerChangeIsRejected() {
        val body =
            mutateValid {
                it.replaceFirst("\"type\": \"ticker_change\"", "\"type\": \"split\"")
            }
        val result = archive(body = body)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertTrue(result.record.notes!!.contains("ticker_change"))
    }

    @Test
    fun eventDateMissingIsRejected() {
        val body =
            mutateValid {
                it.replaceFirst("\"date\": \"2022-06-09\",", "")
            }
        val result = archive(body = body)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertTrue(result.record.notes!!.contains("date"))
    }

    @Test
    fun invalidCalendarDateIsRejected() {
        val body =
            mutateValid {
                it.replace("\"date\": \"2022-06-09\"", "\"date\": \"2026-02-30\"")
            }
        val result = archive(body = body)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertTrue(result.record.notes!!.contains("invalid calendar date"))
    }

    @Test
    fun tickerChangeMissingIsRejected() {
        val body =
            mutateValid {
                it.replace(
                    "\"ticker_change\": {\n          \"ticker\": \"META\"\n        },",
                    "",
                )
            }
        val result = archive(body = body)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertTrue(result.record.notes!!.contains("ticker_change"))
    }

    @Test
    fun tickerChangeTickerBlankIsRejected() {
        val body =
            mutateValid {
                it.replaceFirst("\"ticker\": \"META\"", "\"ticker\": \"\"")
            }
        val result = archive(body = body)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertTrue(result.record.notes!!.contains("ticker_change.ticker"))
    }

    @Test
    fun duplicateStructurallyValidEventsArePreservedNotDeduped() {
        val body =
            """
            {
              "status":"OK",
              "results":{
                "events":[
                  {"type":"ticker_change","date":"2022-06-09","ticker_change":{"ticker":"META"}},
                  {"type":"ticker_change","date":"2022-06-09","ticker_change":{"ticker":"META"}}
                ]
              }
            }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
        val result = archive(body = body)
        assertEquals(ObservationStatus.OBSERVED, result.record.observationStatus)
        assertEquals(2, result.validation!!.eventCount)
        assertTrue(result.record.notes!!.contains("potential duplicate/conflict; preserved"))
    }

    @Test
    fun rawEventOrderIsPreservedWithoutSort() {
        val result = archive(body = validBody.copyOf())
        val events = result.validation!!.validatedEvents
        assertEquals(listOf("2022-06-09", "2012-05-18"), events.map { it.date })
        assertEquals(listOf("META", "FB"), events.map { it.ticker })
        assertTrue(result.record.notes!!.contains("raw event order preserved"))
    }

    @Test
    fun unknownExtraFieldsArePreservedWhenKnownSchemaSafe() {
        val body =
            mutateValid {
                it.replace(
                    "\"status\": \"OK\"",
                    "\"extra_root\": 1, \"status\": \"OK\"",
                ).replace(
                    "\"type\": \"ticker_change\"",
                    "\"extra_event\": \"x\", \"type\": \"ticker_change\"",
                )
            }
        val result = archive(body = body)
        assertEquals(ObservationStatus.OBSERVED, result.record.observationStatus)
        assertEquals(2, result.validation!!.eventCount)
        // Unknown fields must not become observedFields business keys beyond audited set.
        assertFalse(result.record.observedFields.contains("extra_root"))
        assertFalse(result.record.observedFields.contains("extra_event"))
    }

    @Test
    fun nextUrlPresentIsRejected() {
        val body =
            mutateValid {
                it.replace(
                    "\"status\": \"OK\"",
                    "\"next_url\": \"https://example.invalid/next\", \"status\": \"OK\"",
                )
            }
        val result = archive(body = body)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertTrue(result.record.notes!!.contains("next_url present"))
        assertNotNull(result.record.rawPayloadUri)
        assertNull(result.record.eligibilityBoundaryAt)
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
                lookupId,
                possession(status = 0, body = null, transportMessage = "ConnectException"),
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
    fun invalidUtf8IsRejectedWithExactRawBytesPreserved() {
        val prefix = """{"status":"OK","results":{"events":[{"type":"""".toByteArray()
        val suffix = """"}]}}""".toByteArray()
        val body = prefix + byteArrayOf(0xFF.toByte()) + suffix
        val result = archive(body = body)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertNull(result.record.eligibilityBoundaryAt)
        assertEquals(Sha256Hex.of(body), result.record.rawPayloadHash)
        val onDisk = Files.readAllBytes(Path.of(result.record.rawPayloadUri!!))
        assertTrue(onDisk.contentEquals(body))
    }

    @Test
    fun malformedJsonIsRejected() {
        val body = """{"status":"OK","results":{""".toByteArray(StandardCharsets.UTF_8)
        val result = archive(body = body)
        assertEquals(ObservationStatus.REJECTED_VALIDATION, result.record.observationStatus)
        assertNotNull(result.record.rawPayloadUri)
        assertNull(result.record.eligibilityBoundaryAt)
    }

    @Test
    fun missingApiKeyFailsFastWithoutProviderFailurePossession() {
        val client =
            MassiveTickerEventsArchiveClient(
                baseUrl = "http://127.0.0.1:1",
                apiKey = null,
                clock = { nextInstant() },
            )
        val thrown =
            assertFailsWith<IllegalStateException> {
                client.executeTickerEvents(lookupId)
            }
        assertTrue(thrown.message!!.contains("MASSIVE_API_KEY"))
        assertTrue(thrown.message!!.contains("local configuration"))
        assertFalse(thrown.message!!.contains("apiKey="))

        val svc =
            MassiveTickerEventsForwardArchiveService(
                archiveRoot = root,
                client = client,
                clock = { nextInstant() },
                idGenerator = { "id-${ids.incrementAndGet()}" },
            )
        assertFailsWith<IllegalStateException> {
            svc.archiveTickerEvents(lookupId)
        }
        assertTrue(svc.readManifest().isEmpty())
        assertEquals(0, svc.currentCoverage().observedCount)
    }

    @Test
    fun secretNeverEntersRequestKeyManifestOrNotes() {
        val secret = "massive-events-secret-do-not-leak"
        val client =
            MassiveTickerEventsArchiveClient(
                baseUrl = "not a uri!!!",
                apiKey = secret,
                clock = { nextInstant() },
            )
        val possession = client.executeTickerEvents(lookupId)
        assertNull(possession.fetchedAt)
        assertNull(possession.bodyBytes)
        assertNotNull(possession.transportFailureMessage)
        assertTrue(possession.transportFailureMessage!!.matches(Regex("[A-Za-z][A-Za-z0-9_]*")))
        assertFalse(possession.transportFailureMessage!!.contains(secret))
        assertFalse(possession.transportFailureMessage!!.contains("apiKey", ignoreCase = true))
        assertFalse(possession.requestKey.contains(secret))
        assertFalse(possession.requestKey.contains("apiKey", ignoreCase = true))

        val result = archive(body = validBody.copyOf())
        assertFalse(result.record.requestKey.contains("apiKey", ignoreCase = true))
        assertFalse(result.record.toJsonLine().contains(secret))
        assertFalse(result.record.notes!!.contains("apiKey", ignoreCase = true))
    }

    @Test
    fun canonicalRequestKeyFixesTypesTickerChange() {
        val key = requestKey()
        assertEquals("GET|/vX/reference/tickers/META/events|types=ticker_change", key)
        assertFalse(key.contains("apiKey", ignoreCase = true))
        val parsed = MassiveTickerEventsRequestKey.parseOrThrow(key)
        assertEquals("META", parsed.lookupId)
    }

    @Test
    fun possessionRequestKeyMismatchIsRefused() {
        val bad = possession(200, validBody.copyOf(), requestKey = requestKey(id = "FB"))
        val thrown =
            assertFailsWith<IllegalArgumentException> {
                service().archivePossessedResponse(lookupId, bad)
            }
        assertTrue(thrown.message!!.contains("possession.requestKey mismatch"))
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
        val alt = mutateValid { it.replace("Synthetic Meta", "Synthetic Meta Revised") }
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
    fun eligibilityBoundaryAtIsIngestedAtNotEventDate() {
        val result = archive(body = validBody.copyOf())
        assertEquals(ObservationStatus.OBSERVED, result.record.observationStatus)
        val eligibility = result.record.eligibilityBoundaryAt
        assertNotNull(eligibility)
        assertEquals(result.record.ingestedAt, eligibility)
        assertTrue(eligibility.toString().startsWith("2026-09-18"))
        assertFalse(eligibility.toString().contains("2022-06-09"))
        assertFalse(eligibility.toString().contains("2012-05-18"))
        assertFalse(result.record.toJsonLine().contains("\"knownAt\""))
    }

    @Test
    fun manifestDoesNotGenerateIdentityOrValidityFields() {
        val result = archive(body = validBody.copyOf())
        assertNull(result.record.externalIdentifier)
        assertNull(result.record.externalIdentifierNamespace)
        val line = result.record.toJsonLine()
        assertFalse(line.contains("\"knownAt\""))
        assertFalse(line.contains("\"securityId\"", ignoreCase = true))
        assertFalse(line.contains("\"validFrom\""))
        assertFalse(line.contains("\"validTo\""))
        assertFalse(line.contains("\"securityIdentifier\"", ignoreCase = true))
        assertTrue(result.record.notes!!.contains("lookup id≠SecurityId"))
        assertTrue(result.record.notes!!.contains("ticker_change.ticker≠SecurityId"))
        assertTrue(result.record.notes!!.contains("no continuity inference"))
    }

    @Test
    fun lookupIdWithPathBreakingCharsIsRejected() {
        for (bad in listOf("A|B", "A/B", "A?B", "A#B", "A&B", "  ")) {
            assertFailsWith<IllegalArgumentException> {
                MassiveTickerEventsArchiveClient.requestKey(bad)
            }
        }
    }

    @Test
    fun requestKeyParserRejectsApiKeySegmentAndWrongTypes() {
        assertFailsWith<IllegalArgumentException> {
            MassiveTickerEventsRequestKey.parseOrThrow(
                "GET|/vX/reference/tickers/META/events|types=ticker_change|apiKey=x",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MassiveTickerEventsRequestKey.parseOrThrow(
                "GET|/vX/reference/tickers/META/events|types=all",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            MassiveTickerEventsRequestKey.parseOrThrow(
                "POST|/vX/reference/tickers/META/events|types=ticker_change",
            )
        }
    }

    @Test
    fun fixtureIsMarkedSyntheticNotLiveEvidence() {
        val text = String(validBody, StandardCharsets.UTF_8)
        assertTrue(text.contains("synthetic-ticker-events-request-id"))
        assertTrue(text.contains("Synthetic Meta"))
        assertFalse(text.contains("apiKey", ignoreCase = true))
    }
}
