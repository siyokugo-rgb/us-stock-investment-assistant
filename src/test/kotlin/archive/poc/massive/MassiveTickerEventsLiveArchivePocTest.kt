package archive.poc.massive

import archive.poc.CoverageWindow
import archive.poc.ManifestRecord
import archive.poc.ObservationStatus
import archive.poc.Sha256Hex
import archive.poc.TransportStatus
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Offline QA for [MassiveTickerEventsLiveArchivePoc] classification / defaults.
 * No network. Does not claim live schema verification.
 */
class MassiveTickerEventsLiveArchivePocTest {
    private lateinit var tmpDir: java.nio.file.Path

    @BeforeTest
    fun setup() {
        tmpDir = Files.createTempDirectory("massive-ticker-events-live-poc")
    }

    @AfterTest
    fun cleanup() {
        tmpDir.toFile().deleteRecursively()
    }

    @Test
    fun withoutApiKeyIsLiveUnverified() {
        assertEquals(
            MassiveTickerEventsLiveArchivePoc.LiveClassification.LIVE_UNVERIFIED,
            MassiveTickerEventsLiveArchivePoc.classifyWithoutApiKey(),
        )
    }

    @Test
    fun defaultLookupIdIsXyz() {
        assertEquals("XYZ", MassiveTickerEventsLiveArchivePoc.DEFAULT_LOOKUP_ID)
        assertFalse(MassiveTickerEventsLiveArchivePoc.DEFAULT_LOOKUP_ID.isBlank())
    }

    @Test
    fun observedNonemptyWithIntegrityIsSchemaVerified() {
        val body =
            """
            {"status":"OK","results":{"events":[
              {"type":"ticker_change","date":"2025-01-21","ticker_change":{"ticker":"XYZ"}}
            ]}}
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
        val result = observedResult(body, eventCount = 1, ticker = "XYZ", date = "2025-01-21")
        assertEquals(
            MassiveTickerEventsLiveArchivePoc.LiveClassification.LIVE_SCHEMA_VERIFIED,
            MassiveTickerEventsLiveArchivePoc.classify(result),
        )
        assertTrue(MassiveTickerEventsLiveArchivePoc.isSchemaVerified(result))
    }

    @Test
    fun observedEmptyEventsIsLiveEmptyObserved() {
        val body =
            """{"status":"OK","results":{"events":[]}}""".toByteArray(StandardCharsets.UTF_8)
        val result = observedResult(body, eventCount = 0)
        assertEquals(
            MassiveTickerEventsLiveArchivePoc.LiveClassification.LIVE_EMPTY_OBSERVED,
            MassiveTickerEventsLiveArchivePoc.classify(result),
        )
        assertFalse(MassiveTickerEventsLiveArchivePoc.isSchemaVerified(result))
    }

    @Test
    fun rejectedValidationIsLiveSchemaRejected() {
        val result =
            baseResult(
                ObservationStatus.REJECTED_VALIDATION,
                observedOk = false,
                body = """{"status":"ERROR"}""".toByteArray(),
                validation =
                    MassiveTickerEventsValidationOutcome(
                        okForObserved = false,
                        observedFields = emptyList(),
                        notes = "missing status",
                    ),
            )
        assertEquals(
            MassiveTickerEventsLiveArchivePoc.LiveClassification.LIVE_SCHEMA_REJECTED,
            MassiveTickerEventsLiveArchivePoc.classify(result),
        )
    }

    @Test
    fun providerFailureIsLiveProviderFailure() {
        val result =
            MassiveTickerEventsArchiveResult(
                record =
                    ManifestRecord(
                        archiveId = "id-1",
                        domain = MassiveTickerEventsArchiveClient.DOMAIN,
                        source = MassiveTickerEventsArchiveClient.SOURCE,
                        requestKey = MassiveTickerEventsArchiveClient.requestKey("XYZ"),
                        attemptedAt = Instant.parse("2026-09-18T12:00:00Z"),
                        attemptFinishedAt = Instant.parse("2026-09-18T12:00:01Z"),
                        ingestedAt = Instant.parse("2026-09-18T12:00:02Z"),
                        httpStatus = 403,
                        transportStatus = TransportStatus.HTTP_RESPONSE,
                        observationStatus = ObservationStatus.PROVIDER_FAILURE,
                        notes = "provider",
                    ),
                coverage =
                    CoverageWindow(
                        domain = MassiveTickerEventsArchiveClient.DOMAIN,
                        source = MassiveTickerEventsArchiveClient.SOURCE,
                        coverageStartAt = null,
                        coverageThroughAt = null,
                        observedCount = 0,
                        hasNonObservedAlongside = false,
                    ),
                observedIngestSucceeded = false,
            )
        assertEquals(
            MassiveTickerEventsLiveArchivePoc.LiveClassification.LIVE_PROVIDER_FAILURE,
            MassiveTickerEventsLiveArchivePoc.classify(result),
        )
    }

    @Test
    fun localArchiveFailureIsLiveLocalFailure() {
        val result =
            MassiveTickerEventsArchiveResult(
                record =
                    ManifestRecord(
                        archiveId = "id-1",
                        domain = MassiveTickerEventsArchiveClient.DOMAIN,
                        source = MassiveTickerEventsArchiveClient.SOURCE,
                        requestKey = MassiveTickerEventsArchiveClient.requestKey("XYZ"),
                        attemptedAt = Instant.parse("2026-09-18T12:00:00Z"),
                        attemptFinishedAt = Instant.parse("2026-09-18T12:00:01Z"),
                        ingestedAt = Instant.parse("2026-09-18T12:00:02Z"),
                        transportStatus = TransportStatus.LOCAL_FAILURE,
                        observationStatus = ObservationStatus.LOCAL_ARCHIVE_FAILURE,
                        notes = "local",
                    ),
                coverage =
                    CoverageWindow(
                        domain = MassiveTickerEventsArchiveClient.DOMAIN,
                        source = MassiveTickerEventsArchiveClient.SOURCE,
                        coverageStartAt = null,
                        coverageThroughAt = null,
                        observedCount = 0,
                        hasNonObservedAlongside = false,
                    ),
                observedIngestSucceeded = false,
            )
        assertEquals(
            MassiveTickerEventsLiveArchivePoc.LiveClassification.LIVE_LOCAL_FAILURE,
            MassiveTickerEventsLiveArchivePoc.classify(result),
        )
    }

    @Test
    fun printResultDoesNotEmitApiKeyOrRawBody() {
        val body =
            """{"status":"OK","results":{"events":[]}}""".toByteArray(StandardCharsets.UTF_8)
        val result = observedResult(body, eventCount = 0)
        val baos = java.io.ByteArrayOutputStream()
        val original = System.out
        System.setOut(java.io.PrintStream(baos))
        try {
            MassiveTickerEventsLiveArchivePoc.printResult(
                result,
                MassiveTickerEventsLiveArchivePoc.LiveClassification.LIVE_EMPTY_OBSERVED,
            )
        } finally {
            System.setOut(original)
        }
        val out = baos.toString(StandardCharsets.UTF_8)
        assertFalse(out.contains("apiKey", ignoreCase = true))
        assertFalse(out.contains("MASSIVE_API_KEY="))
        assertFalse(out.contains("\"events\""))
        assertTrue(out.contains("liveClassification=LIVE_EMPTY_OBSERVED"))
        assertTrue(out.contains("eventCount=0"))
    }

    private fun observedResult(
        body: ByteArray,
        eventCount: Int,
        ticker: String = "XYZ",
        date: String = "2025-01-21",
    ): MassiveTickerEventsArchiveResult {
        val raw = tmpDir.resolve("raw-${System.nanoTime()}.raw")
        Files.write(raw, body)
        val hash = Sha256Hex.of(body)
        val events =
            if (eventCount == 0) {
                emptyList()
            } else {
                List(eventCount) {
                    MassiveTickerChangeRawEvidence(
                        type = "ticker_change",
                        date = date,
                        ticker = ticker,
                    )
                }
            }
        val ingested = Instant.parse("2026-09-18T12:00:03Z")
        return MassiveTickerEventsArchiveResult(
            record =
                ManifestRecord(
                    archiveId = "id-obs",
                    domain = MassiveTickerEventsArchiveClient.DOMAIN,
                    source = MassiveTickerEventsArchiveClient.SOURCE,
                    requestKey = MassiveTickerEventsArchiveClient.requestKey("XYZ"),
                    attemptedAt = Instant.parse("2026-09-18T12:00:00Z"),
                    attemptFinishedAt = Instant.parse("2026-09-18T12:00:01Z"),
                    fetchedAt = Instant.parse("2026-09-18T12:00:01Z"),
                    ingestedAt = ingested,
                    rawPayloadHash = hash,
                    rawPayloadUri = raw.toString(),
                    httpStatus = 200,
                    transportStatus = TransportStatus.HTTP_RESPONSE,
                    observationStatus = ObservationStatus.OBSERVED,
                    eligibilityBoundaryAt = ingested,
                    notes =
                        "massive Ticker Events ok; raw Ticker Events evidence only; " +
                            "empty events != negative proof",
                    observedFields = listOf("events", "results", "status"),
                ),
            coverage =
                CoverageWindow(
                    domain = MassiveTickerEventsArchiveClient.DOMAIN,
                    source = MassiveTickerEventsArchiveClient.SOURCE,
                    coverageStartAt = ingested,
                    coverageThroughAt = ingested,
                    observedCount = 1,
                    hasNonObservedAlongside = false,
                ),
            observedIngestSucceeded = true,
            validation =
                MassiveTickerEventsValidationOutcome(
                    okForObserved = true,
                    observedFields = listOf("events", "results", "status"),
                    notes = "ok",
                    eventCount = eventCount,
                    validatedEvents = events,
                ),
        )
    }

    private fun baseResult(
        status: ObservationStatus,
        observedOk: Boolean,
        body: ByteArray,
        validation: MassiveTickerEventsValidationOutcome?,
    ): MassiveTickerEventsArchiveResult {
        val raw = tmpDir.resolve("raw-${System.nanoTime()}.raw")
        Files.write(raw, body)
        return MassiveTickerEventsArchiveResult(
            record =
                ManifestRecord(
                    archiveId = "id-base",
                    domain = MassiveTickerEventsArchiveClient.DOMAIN,
                    source = MassiveTickerEventsArchiveClient.SOURCE,
                    requestKey = MassiveTickerEventsArchiveClient.requestKey("XYZ"),
                    attemptedAt = Instant.parse("2026-09-18T12:00:00Z"),
                    attemptFinishedAt = Instant.parse("2026-09-18T12:00:01Z"),
                    fetchedAt = Instant.parse("2026-09-18T12:00:01Z"),
                    ingestedAt = Instant.parse("2026-09-18T12:00:02Z"),
                    rawPayloadHash = Sha256Hex.of(body),
                    rawPayloadUri = raw.toString(),
                    httpStatus = 200,
                    transportStatus = TransportStatus.HTTP_RESPONSE,
                    observationStatus = status,
                    notes = validation?.notes,
                ),
            coverage =
                CoverageWindow(
                    domain = MassiveTickerEventsArchiveClient.DOMAIN,
                    source = MassiveTickerEventsArchiveClient.SOURCE,
                    coverageStartAt = null,
                    coverageThroughAt = null,
                    observedCount = 0,
                    hasNonObservedAlongside = false,
                ),
            observedIngestSucceeded = observedOk,
            validation = validation,
        )
    }
}
