package archive.poc.binding

import archive.poc.ArchiveValidationException
import archive.poc.ManifestRecord
import archive.poc.ObservationStatus
import archive.poc.Sha256Hex
import archive.poc.TransportStatus
import archive.poc.massive.MassiveAllTickersArchiveClient
import archive.poc.massive.MassiveTickerEventsArchiveClient
import archive.poc.massive.MassiveTickerOverviewArchiveClient
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.Test
import kotlin.test.BeforeTest
import kotlin.test.AfterTest
/**
 * Synthetic-first QA for [TickerEventOverviewCorroborationDeriver].
 * No live API. No SecurityId / knownAt / continuity rule changes.
 */
class TickerEventOverviewCorroborationEvidenceTest {
    private lateinit var root: Path

    private val eligOv1 = Instant.parse("2026-09-10T10:00:00Z")
    private val eligOv2 = Instant.parse("2026-09-12T12:00:00Z")
    private val eligEvents = Instant.parse("2026-09-18T07:50:58Z")
    private val share = "BBGSHARE_SQ_XYZ"

    @BeforeTest
    fun setup() {
        root = Files.createTempDirectory("ticker-events-overview-corroboration")
    }

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun sqToXyzWithEventInsideWindowIsCorroborated() {
        val t1 = overview("ov-sq", "SQ", "2025-01-17", eligOv1)
        val t2 = overview("ov-xyz", "XYZ", "2025-01-22", eligOv2)
        val events =
            eventsRecord(
                lookupId = "XYZ",
                events =
                    listOf(
                        Ev("ticker_change", "2025-01-21", "XYZ"),
                        Ev("ticker_change", "2015-11-18", "SQ"),
                    ),
            )
        val evidence =
            TickerEventOverviewCorroborationDeriver.derive(t1, t2, events)
        assertEquals(
            TickerEventOverviewCorroborationStatus.CORROBORATED_TICKER_CHANGE_CANDIDATE,
            evidence.status,
        )
        assertNull(evidence.reason)
        assertEquals("SQ", evidence.firstProviderTicker)
        assertEquals("XYZ", evidence.secondProviderTicker)
        assertEquals("ticker_change", evidence.matchedEventType)
        assertEquals("2025-01-21", evidence.matchedEventDate)
        assertEquals("XYZ", evidence.matchedEventTicker)
        assertEquals(eligEvents, evidence.corroborationEligibleAt)
    }

    @Test
    fun overviewArgumentReverseYieldsSameCorroboration() {
        val t1 = overview("ov-sq", "SQ", "2025-01-17", eligOv1)
        val t2 = overview("ov-xyz", "XYZ", "2025-01-22", eligOv2)
        val events =
            eventsRecord(
                lookupId = "XYZ",
                events =
                    listOf(
                        Ev("ticker_change", "2025-01-21", "XYZ"),
                        Ev("ticker_change", "2015-11-18", "SQ"),
                    ),
            )
        val ab = TickerEventOverviewCorroborationDeriver.derive(t1, t2, events)
        val ba = TickerEventOverviewCorroborationDeriver.derive(t2, t1, events)
        assertEquals(ab.status, ba.status)
        assertEquals(ab.firstProviderTicker, ba.firstProviderTicker)
        assertEquals(ab.secondProviderTicker, ba.secondProviderTicker)
        assertEquals(ab.matchedEventDate, ba.matchedEventDate)
        assertEquals(ab.matchedEventTicker, ba.matchedEventTicker)
    }

    @Test
    fun sameTickerContinuityCandidateIsUnresolved() {
        val t1 = overview("ov-a", "AAPL", "2025-01-17", eligOv1)
        val t2 = overview("ov-b", "AAPL", "2025-01-22", eligOv2)
        val events =
            eventsRecord(
                lookupId = "AAPL",
                events = listOf(Ev("ticker_change", "2025-01-20", "AAPL")),
            )
        val evidence = TickerEventOverviewCorroborationDeriver.derive(t1, t2, events)
        assertEquals(TickerEventOverviewCorroborationStatus.UNRESOLVED, evidence.status)
        assertEquals(
            TickerEventOverviewCorroborationReason.CONTINUITY_NOT_TICKER_CHANGE_CANDIDATE,
            evidence.reason,
        )
        assertEquals(
            SecurityIdentityContinuityStatus.CONTINUITY_CANDIDATE,
            evidence.continuityStatus,
        )
    }

    @Test
    fun differentTickerDifferentShareClassIsUnresolved() {
        val t1 =
            overview("ov-a", "AAA", "2025-01-17", eligOv1, shareClass = "BBG_A", composite = "C1")
        val t2 =
            overview("ov-b", "BBB", "2025-01-22", eligOv2, shareClass = "BBG_B", composite = "C2")
        val events =
            eventsRecord(
                lookupId = "BBB",
                events = listOf(Ev("ticker_change", "2025-01-20", "BBB")),
            )
        val evidence = TickerEventOverviewCorroborationDeriver.derive(t1, t2, events)
        assertEquals(TickerEventOverviewCorroborationStatus.UNRESOLVED, evidence.status)
        assertEquals(
            TickerEventOverviewCorroborationReason.CONTINUITY_NOT_TICKER_CHANGE_CANDIDATE,
            evidence.reason,
        )
    }

    @Test
    fun shareClassMissingIsUnresolved() {
        val t1 = overview("ov-a", "SQ", "2025-01-17", eligOv1)
        val t2 =
            overview(
                "ov-b",
                "XYZ",
                "2025-01-22",
                eligOv2,
                omitShareClass = true,
            )
        val events =
            eventsRecord(
                lookupId = "XYZ",
                events = listOf(Ev("ticker_change", "2025-01-21", "XYZ")),
            )
        val evidence = TickerEventOverviewCorroborationDeriver.derive(t1, t2, events)
        assertEquals(TickerEventOverviewCorroborationStatus.UNRESOLVED, evidence.status)
        assertEquals(
            TickerEventOverviewCorroborationReason.CONTINUITY_NOT_TICKER_CHANGE_CANDIDATE,
            evidence.reason,
        )
    }

    @Test
    fun tickerEventsNonObservedIsUnresolved() {
        val t1 = overview("ov-sq", "SQ", "2025-01-17", eligOv1)
        val t2 = overview("ov-xyz", "XYZ", "2025-01-22", eligOv2)
        val events =
            eventsRecord(
                lookupId = "XYZ",
                events = listOf(Ev("ticker_change", "2025-01-21", "XYZ")),
                status = ObservationStatus.REJECTED_VALIDATION,
            )
        val evidence = TickerEventOverviewCorroborationDeriver.derive(t1, t2, events)
        assertEquals(TickerEventOverviewCorroborationStatus.UNRESOLVED, evidence.status)
        assertEquals(
            TickerEventOverviewCorroborationReason.EVENT_INPUT_NOT_OBSERVED,
            evidence.reason,
        )
    }

    @Test
    fun tickerEventsEmptyIsUnresolved() {
        val t1 = overview("ov-sq", "SQ", "2025-01-17", eligOv1)
        val t2 = overview("ov-xyz", "XYZ", "2025-01-22", eligOv2)
        val events = eventsRecord(lookupId = "XYZ", events = emptyList())
        val evidence = TickerEventOverviewCorroborationDeriver.derive(t1, t2, events)
        assertEquals(TickerEventOverviewCorroborationStatus.UNRESOLVED, evidence.status)
        assertEquals(TickerEventOverviewCorroborationReason.EVENTS_EMPTY, evidence.reason)
    }

    @Test
    fun eventDateEqualFirstDateIsNoWindowMatch() {
        val t1 = overview("ov-sq", "SQ", "2025-01-17", eligOv1)
        val t2 = overview("ov-xyz", "XYZ", "2025-01-22", eligOv2)
        val events =
            eventsRecord(
                lookupId = "XYZ",
                events = listOf(Ev("ticker_change", "2025-01-17", "XYZ")),
            )
        val evidence = TickerEventOverviewCorroborationDeriver.derive(t1, t2, events)
        assertEquals(
            TickerEventOverviewCorroborationReason.NO_EVENT_WINDOW_MATCH,
            evidence.reason,
        )
    }

    @Test
    fun eventDateAfterSecondDateIsNoWindowMatch() {
        val t1 = overview("ov-sq", "SQ", "2025-01-17", eligOv1)
        val t2 = overview("ov-xyz", "XYZ", "2025-01-22", eligOv2)
        val events =
            eventsRecord(
                lookupId = "XYZ",
                events = listOf(Ev("ticker_change", "2025-01-23", "XYZ")),
            )
        val evidence = TickerEventOverviewCorroborationDeriver.derive(t1, t2, events)
        assertEquals(
            TickerEventOverviewCorroborationReason.NO_EVENT_WINDOW_MATCH,
            evidence.reason,
        )
    }

    @Test
    fun onlyEarlierTickerInWindowIsNoMatch() {
        val t1 = overview("ov-sq", "SQ", "2025-01-17", eligOv1)
        val t2 = overview("ov-xyz", "XYZ", "2025-01-22", eligOv2)
        val events =
            eventsRecord(
                lookupId = "XYZ",
                events = listOf(Ev("ticker_change", "2025-01-20", "SQ")),
            )
        val evidence = TickerEventOverviewCorroborationDeriver.derive(t1, t2, events)
        assertEquals(
            TickerEventOverviewCorroborationReason.NO_EVENT_WINDOW_MATCH,
            evidence.reason,
        )
    }

    @Test
    fun unrelatedTickerOnlyIsNoMatch() {
        val t1 = overview("ov-sq", "SQ", "2025-01-17", eligOv1)
        val t2 = overview("ov-xyz", "XYZ", "2025-01-22", eligOv2)
        val events =
            eventsRecord(
                lookupId = "XYZ",
                events = listOf(Ev("ticker_change", "2025-01-20", "ZZZ")),
            )
        val evidence = TickerEventOverviewCorroborationDeriver.derive(t1, t2, events)
        assertEquals(
            TickerEventOverviewCorroborationReason.NO_EVENT_WINDOW_MATCH,
            evidence.reason,
        )
    }

    @Test
    fun twoMatchingLaterTickerEventsAreAmbiguousWithoutDedupe() {
        val t1 = overview("ov-sq", "SQ", "2025-01-17", eligOv1)
        val t2 = overview("ov-xyz", "XYZ", "2025-01-22", eligOv2)
        val events =
            eventsRecord(
                lookupId = "XYZ",
                events =
                    listOf(
                        Ev("ticker_change", "2025-01-19", "XYZ"),
                        Ev("ticker_change", "2025-01-21", "XYZ"),
                    ),
            )
        val evidence = TickerEventOverviewCorroborationDeriver.derive(t1, t2, events)
        assertEquals(
            TickerEventOverviewCorroborationReason.EVENT_WINDOW_MATCH_AMBIGUOUS,
            evidence.reason,
        )
    }

    @Test
    fun rawOrderReversedStillMatchesSameSingleEvent() {
        val t1 = overview("ov-sq", "SQ", "2025-01-17", eligOv1)
        val t2 = overview("ov-xyz", "XYZ", "2025-01-22", eligOv2)
        val events =
            eventsRecord(
                lookupId = "XYZ",
                events =
                    listOf(
                        Ev("ticker_change", "2015-11-18", "SQ"),
                        Ev("ticker_change", "2025-01-21", "XYZ"),
                    ),
            )
        val evidence = TickerEventOverviewCorroborationDeriver.derive(t1, t2, events)
        assertEquals(
            TickerEventOverviewCorroborationStatus.CORROBORATED_TICKER_CHANGE_CANDIDATE,
            evidence.status,
        )
        assertEquals("2025-01-21", evidence.matchedEventDate)
        assertEquals("XYZ", evidence.matchedEventTicker)
    }

    @Test
    fun badEventRawShaFailsClosed() {
        val t1 = overview("ov-sq", "SQ", "2025-01-17", eligOv1)
        val t2 = overview("ov-xyz", "XYZ", "2025-01-22", eligOv2)
        val events =
            eventsRecord(
                lookupId = "XYZ",
                events = listOf(Ev("ticker_change", "2025-01-21", "XYZ")),
                rawHashOverride = "0".repeat(64),
            )
        assertFailsWith<ArchiveValidationException> {
            TickerEventOverviewCorroborationDeriver.derive(t1, t2, events)
        }
    }

    @Test
    fun missingEventRawFailsClosed() {
        val t1 = overview("ov-sq", "SQ", "2025-01-17", eligOv1)
        val t2 = overview("ov-xyz", "XYZ", "2025-01-22", eligOv2)
        val events =
            eventsRecord(
                lookupId = "XYZ",
                events = listOf(Ev("ticker_change", "2025-01-21", "XYZ")),
                deleteRawAfterWrite = true,
            )
        assertFailsWith<ArchiveValidationException> {
            TickerEventOverviewCorroborationDeriver.derive(t1, t2, events)
        }
    }

    @Test
    fun observedButValidatorRejectIsUnresolved() {
        val t1 = overview("ov-sq", "SQ", "2025-01-17", eligOv1)
        val t2 = overview("ov-xyz", "XYZ", "2025-01-22", eligOv2)
        val badBody =
            """{"status":"ERROR","results":{"events":[]}}""".toByteArray(StandardCharsets.UTF_8)
        val events =
            eventsRecord(
                lookupId = "XYZ",
                bodyOverride = badBody,
            )
        val evidence = TickerEventOverviewCorroborationDeriver.derive(t1, t2, events)
        assertEquals(TickerEventOverviewCorroborationStatus.UNRESOLVED, evidence.status)
        assertEquals(
            TickerEventOverviewCorroborationReason.EVENT_RAW_VALIDATION_FAILED,
            evidence.reason,
        )
    }

    @Test
    fun wrongEventSourceIsUnresolved() {
        val t1 = overview("ov-sq", "SQ", "2025-01-17", eligOv1)
        val t2 = overview("ov-xyz", "XYZ", "2025-01-22", eligOv2)
        val events =
            eventsRecord(
                lookupId = "XYZ",
                events = listOf(Ev("ticker_change", "2025-01-21", "XYZ")),
            ).copy(source = MassiveTickerOverviewArchiveClient.SOURCE)
        val evidence = TickerEventOverviewCorroborationDeriver.derive(t1, t2, events)
        assertEquals(
            TickerEventOverviewCorroborationReason.EVENT_SOURCE_UNSUPPORTED,
            evidence.reason,
        )
    }

    @Test
    fun allTickersOverviewInputIsUnresolved() {
        val at =
            ManifestRecord(
                archiveId = "at-1",
                domain = MassiveAllTickersArchiveClient.DOMAIN,
                source = MassiveAllTickersArchiveClient.SOURCE,
                requestKey =
                    MassiveAllTickersArchiveClient.requestKey(
                        ticker = "SQ",
                        date = "2025-01-17",
                        limit = 1,
                    ),
                attemptedAt = Instant.parse("2026-09-10T09:00:00Z"),
                attemptFinishedAt = Instant.parse("2026-09-10T09:00:30Z"),
                fetchedAt = Instant.parse("2026-09-10T09:00:30Z"),
                ingestedAt = Instant.parse("2026-09-10T09:01:00Z"),
                httpStatus = 200,
                transportStatus = TransportStatus.HTTP_RESPONSE,
                observationStatus = ObservationStatus.OBSERVED,
                eligibilityBoundaryAt = eligOv1,
                rawPayloadHash = "a".repeat(64),
                rawPayloadUri = root.resolve("dummy.raw").also {
                    Files.write(it, byteArrayOf(1))
                }.toString(),
            )
        val t2 = overview("ov-xyz", "XYZ", "2025-01-22", eligOv2)
        val events =
            eventsRecord(
                lookupId = "XYZ",
                events = listOf(Ev("ticker_change", "2025-01-21", "XYZ")),
            )
        val evidence = TickerEventOverviewCorroborationDeriver.derive(at, t2, events)
        assertEquals(TickerEventOverviewCorroborationStatus.UNRESOLVED, evidence.status)
        assertEquals(
            TickerEventOverviewCorroborationReason.OVERVIEW_SOURCE_REQUIRED,
            evidence.reason,
        )
        assertEquals(SecurityIdentityContinuityStatus.UNRESOLVED, evidence.continuityStatus)
        assertNull(evidence.firstProviderTicker)
        assertNull(evidence.secondProviderTicker)
        assertNull(evidence.corroborationEligibleAt)
    }

    @Test
    fun bothAllTickersWithoutRawIsUnresolvedOverviewSourceRequired() {
        val at1 = allTickersShell("at-1", "SQ", "2025-01-17")
        val at2 = allTickersShell("at-2", "XYZ", "2025-01-22")
        val events =
            eventsRecord(
                lookupId = "XYZ",
                events = listOf(Ev("ticker_change", "2025-01-21", "XYZ")),
            )
        // Must not invoke continuity deriver / parse raw — exception forbidden.
        val evidence = TickerEventOverviewCorroborationDeriver.derive(at1, at2, events)
        assertEquals(TickerEventOverviewCorroborationStatus.UNRESOLVED, evidence.status)
        assertEquals(
            TickerEventOverviewCorroborationReason.OVERVIEW_SOURCE_REQUIRED,
            evidence.reason,
        )
        assertEquals(SecurityIdentityContinuityStatus.UNRESOLVED, evidence.continuityStatus)
        assertNull(evidence.firstProviderTicker)
        assertNull(evidence.secondProviderTicker)
        assertNull(evidence.firstProviderAsOfDate)
        assertNull(evidence.secondProviderAsOfDate)
        assertNull(evidence.continuityEvidenceEligibleAt)
        assertNull(evidence.corroborationEligibleAt)
    }

    @Test
    fun lookupIdEqualSecondTickerWithValidEventIsCorroborated() {
        val t1 = overview("ov-sq", "SQ", "2025-01-17", eligOv1)
        val t2 = overview("ov-xyz", "XYZ", "2025-01-22", eligOv2)
        val events =
            eventsRecord(
                lookupId = "XYZ",
                events = listOf(Ev("ticker_change", "2025-01-21", "XYZ")),
            )
        val evidence = TickerEventOverviewCorroborationDeriver.derive(t1, t2, events)
        assertEquals(
            TickerEventOverviewCorroborationStatus.CORROBORATED_TICKER_CHANGE_CANDIDATE,
            evidence.status,
        )
        assertEquals("XYZ", evidence.tickerEventsLookupId)
        assertEquals(evidence.secondProviderTicker, evidence.tickerEventsLookupId)
    }

    @Test
    fun compositeFigiLookupEvenWithMatchingEventIsUnresolved() {
        val t1 = overview("ov-sq", "SQ", "2025-01-17", eligOv1)
        val t2 = overview("ov-xyz", "XYZ", "2025-01-22", eligOv2)
        val events =
            eventsRecord(
                lookupId = "BBG000BLNNH6",
                events =
                    listOf(
                        Ev("ticker_change", "2025-01-21", "XYZ"),
                        Ev("ticker_change", "2015-11-18", "SQ"),
                    ),
            )
        val evidence = TickerEventOverviewCorroborationDeriver.derive(t1, t2, events)
        assertEquals(TickerEventOverviewCorroborationStatus.UNRESOLVED, evidence.status)
        assertEquals(
            TickerEventOverviewCorroborationReason.EVENT_LOOKUP_NOT_LATER_TICKER,
            evidence.reason,
        )
        assertEquals("BBG000BLNNH6", evidence.tickerEventsLookupId)
        assertEquals("XYZ", evidence.secondProviderTicker)
    }

    @Test
    fun earlierTickerLookupIsUnresolved() {
        val t1 = overview("ov-sq", "SQ", "2025-01-17", eligOv1)
        val t2 = overview("ov-xyz", "XYZ", "2025-01-22", eligOv2)
        val events =
            eventsRecord(
                lookupId = "SQ",
                events = listOf(Ev("ticker_change", "2025-01-21", "XYZ")),
            )
        val evidence = TickerEventOverviewCorroborationDeriver.derive(t1, t2, events)
        assertEquals(TickerEventOverviewCorroborationStatus.UNRESOLVED, evidence.status)
        assertEquals(
            TickerEventOverviewCorroborationReason.EVENT_LOOKUP_NOT_LATER_TICKER,
            evidence.reason,
        )
        assertEquals("SQ", evidence.tickerEventsLookupId)
    }

    @Test
    fun corroborationEligibleAtIsMaxOfContinuityAndEvents() {
        val earlyElig = Instant.parse("2026-01-01T00:00:00Z")
        val midElig = Instant.parse("2026-06-01T00:00:00Z")
        val lateElig = Instant.parse("2026-09-01T00:00:00Z")
        val t1 = overview("ov-sq", "SQ", "2025-01-17", lateElig)
        val t2 = overview("ov-xyz", "XYZ", "2025-01-22", earlyElig)
        val events =
            eventsRecord(
                lookupId = "XYZ",
                events = listOf(Ev("ticker_change", "2025-01-21", "XYZ")),
                eligibility = midElig,
            )
        val evidence = TickerEventOverviewCorroborationDeriver.derive(t1, t2, events)
        assertEquals(
            TickerEventOverviewCorroborationStatus.CORROBORATED_TICKER_CHANGE_CANDIDATE,
            evidence.status,
        )
        // continuity evidenceEligibleAt = max(late, early) = late; max(late, mid) = late
        assertEquals(lateElig, evidence.continuityEvidenceEligibleAt)
        assertEquals(lateElig, evidence.corroborationEligibleAt)
    }

    @Test
    fun modelSurfaceHasNoSecurityIdKnownAtOrDailyPrice() {
        val names =
            TickerEventOverviewCorroborationEvidence::class.java.declaredFields
                .map { it.name }
                .filterNot { it == "Companion" }
        assertFalse(names.any { it.equals("securityId", ignoreCase = true) })
        assertFalse(names.any { it.contains("SecurityIdentifier", ignoreCase = true) })
        assertFalse(names.any { it.equals("knownAt", ignoreCase = true) })
        assertFalse(names.any { it.equals("validFrom", ignoreCase = true) })
        assertFalse(names.any { it.equals("validTo", ignoreCase = true) })
        assertFalse(names.any { it.contains("DailyPrice", ignoreCase = true) })
    }

    @Test
    fun manualCorroboratedWithNullLookupIdIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            validCorroboratedBase().copy(tickerEventsLookupId = null)
        }
    }

    @Test
    fun manualCorroboratedWithLookupIdNotEqualSecondTickerIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            validCorroboratedBase().copy(tickerEventsLookupId = "BBG000BLNNH6")
        }
    }

    @Test
    fun manualCorroboratedWithWrongTickerMatchIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            validCorroboratedBase().copy(matchedEventTicker = "SQ")
        }
    }

    @Test
    fun manualCorroboratedWithEventOutsideWindowIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            validCorroboratedBase().copy(matchedEventDate = "2025-01-17")
        }
    }

    @Test
    fun manualCorroboratedWithWrongEligibilityMaxIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            validCorroboratedBase().copy(
                corroborationEligibleAt = Instant.parse("2020-01-01T00:00:00Z"),
            )
        }
    }

    @Test
    fun manualCorroboratedWithSameTickersIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            validCorroboratedBase().copy(
                firstProviderTicker = "XYZ",
                secondProviderTicker = "XYZ",
                matchedEventTicker = "XYZ",
            )
        }
    }

    // --- helpers ---

    private data class Ev(
        val type: String,
        val date: String,
        val ticker: String,
    )

    private fun validCorroboratedBase(): TickerEventOverviewCorroborationEvidence =
        TickerEventOverviewCorroborationEvidence(
            firstOverviewArchiveId = "ov-sq",
            secondOverviewArchiveId = "ov-xyz",
            tickerEventsArchiveId = "ev-1",
            firstProviderTicker = "SQ",
            secondProviderTicker = "XYZ",
            firstProviderAsOfDate = "2025-01-17",
            secondProviderAsOfDate = "2025-01-22",
            firstShareClassFigi = share,
            secondShareClassFigi = share,
            continuityStatus = SecurityIdentityContinuityStatus.TICKER_CHANGE_CANDIDATE,
            tickerEventsLookupId = "XYZ",
            matchedEventType = "ticker_change",
            matchedEventDate = "2025-01-21",
            matchedEventTicker = "XYZ",
            continuityEvidenceEligibleAt = eligOv2,
            tickerEventsEligibilityBoundaryAt = eligEvents,
            corroborationEligibleAt = eligEvents,
            status =
                TickerEventOverviewCorroborationStatus.CORROBORATED_TICKER_CHANGE_CANDIDATE,
            reason = null,
        )

    private fun overviewBody(
        ticker: String,
        shareClass: String?,
        composite: String?,
        omitShareClass: Boolean,
    ): ByteArray {
        val sc =
            if (omitShareClass || shareClass == null) {
                ""
            } else {
                """, "share_class_figi": "$shareClass""""
            }
        val comp =
            if (composite == null) {
                ""
            } else {
                """, "composite_figi": "$composite""""
            }
        return """
            {
              "request_id": "synthetic-overview",
              "results": {
                "active": true,
                "ticker": "$ticker",
                "name": "Synthetic",
                "market": "stocks",
                "locale": "us",
                "type": "CS",
                "currency_name": "usd",
                "primary_exchange": "XNAS"$comp$sc
              },
              "status": "OK"
            }
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
    }

    private fun eventsBody(events: List<Ev>): ByteArray {
        val rows =
            events.joinToString(",") { e ->
                """{"type":"${e.type}","date":"${e.date}","ticker_change":{"ticker":"${e.ticker}"}}"""
            }
        return """
            {"status":"OK","request_id":"synthetic-events","results":{"name":"Synthetic","events":[$rows]}}
            """.trimIndent().toByteArray(StandardCharsets.UTF_8)
    }

    private fun writeRaw(
        source: String,
        archiveId: String,
        body: ByteArray,
    ): Path {
        val dir = root.resolve("SECURITY_MASTER").resolve(source).resolve("raw")
        Files.createDirectories(dir)
        val path = dir.resolve("$archiveId.raw")
        Files.write(path, body)
        return path
    }

    private fun allTickersShell(
        archiveId: String,
        ticker: String,
        date: String,
    ): ManifestRecord {
        // OBSERVED requires hash/uri on ManifestRecord, but file is absent/broken on purpose.
        // Corroboration must not invoke continuity deriver or read this raw.
        val missingUri = root.resolve("missing-$archiveId.raw").toString()
        return ManifestRecord(
            archiveId = archiveId,
            domain = MassiveAllTickersArchiveClient.DOMAIN,
            source = MassiveAllTickersArchiveClient.SOURCE,
            requestKey =
                MassiveAllTickersArchiveClient.requestKey(
                    ticker = ticker,
                    date = date,
                    limit = 1,
                ),
            attemptedAt = Instant.parse("2026-09-10T09:00:00Z"),
            attemptFinishedAt = Instant.parse("2026-09-10T09:00:30Z"),
            fetchedAt = Instant.parse("2026-09-10T09:00:30Z"),
            ingestedAt = Instant.parse("2026-09-10T09:01:00Z"),
            httpStatus = 200,
            transportStatus = TransportStatus.HTTP_RESPONSE,
            observationStatus = ObservationStatus.OBSERVED,
            eligibilityBoundaryAt = eligOv1,
            rawPayloadHash = "b".repeat(64),
            rawPayloadUri = missingUri,
        )
    }

    private fun overview(
        archiveId: String,
        ticker: String,
        date: String,
        eligibility: Instant,
        shareClass: String? = share,
        composite: String? = "BBGCOMP1",
        omitShareClass: Boolean = false,
    ): ManifestRecord {
        val body = overviewBody(ticker, shareClass, composite, omitShareClass)
        val path = writeRaw(MassiveTickerOverviewArchiveClient.SOURCE, archiveId, body)
        return ManifestRecord(
            archiveId = archiveId,
            domain = MassiveTickerOverviewArchiveClient.DOMAIN,
            source = MassiveTickerOverviewArchiveClient.SOURCE,
            requestKey = MassiveTickerOverviewArchiveClient.requestKey(ticker, date),
            attemptedAt = Instant.parse("2026-09-10T09:00:00Z"),
            attemptFinishedAt = Instant.parse("2026-09-10T09:00:30Z"),
            fetchedAt = Instant.parse("2026-09-10T09:00:30Z"),
            ingestedAt = Instant.parse("2026-09-10T09:01:00Z"),
            rawPayloadHash = Sha256Hex.of(body),
            rawPayloadUri = path.toString(),
            httpStatus = 200,
            transportStatus = TransportStatus.HTTP_RESPONSE,
            observationStatus = ObservationStatus.OBSERVED,
            eligibilityBoundaryAt = eligibility,
        )
    }

    private fun eventsRecord(
        lookupId: String,
        events: List<Ev> = emptyList(),
        eligibility: Instant? = eligEvents,
        status: ObservationStatus = ObservationStatus.OBSERVED,
        bodyOverride: ByteArray? = null,
        rawHashOverride: String? = null,
        deleteRawAfterWrite: Boolean = false,
    ): ManifestRecord {
        val body = bodyOverride ?: eventsBody(events)
        val needsRaw =
            status == ObservationStatus.OBSERVED ||
                status == ObservationStatus.REJECTED_VALIDATION
        val path =
            if (needsRaw) {
                writeRaw(MassiveTickerEventsArchiveClient.SOURCE, "ev-$lookupId", body)
            } else {
                null
            }
        if (deleteRawAfterWrite && path != null) {
            Files.delete(path)
        }
        val hash =
            when {
                !needsRaw -> null
                rawHashOverride != null -> rawHashOverride
                else -> Sha256Hex.of(body)
            }
        val effectiveElig =
            when (status) {
                ObservationStatus.OBSERVED -> eligibility
                else -> null
            }
        return ManifestRecord(
            archiveId = "ev-$lookupId",
            domain = MassiveTickerEventsArchiveClient.DOMAIN,
            source = MassiveTickerEventsArchiveClient.SOURCE,
            requestKey = MassiveTickerEventsArchiveClient.requestKey(lookupId),
            attemptedAt = Instant.parse("2026-09-18T07:50:00Z"),
            attemptFinishedAt = Instant.parse("2026-09-18T07:50:30Z"),
            fetchedAt =
                when (status) {
                    ObservationStatus.PROVIDER_FAILURE -> null
                    else -> Instant.parse("2026-09-18T07:50:30Z")
                },
            ingestedAt = Instant.parse("2026-09-18T07:50:58Z"),
            rawPayloadHash = hash,
            rawPayloadUri = path?.toString(),
            httpStatus =
                when (status) {
                    ObservationStatus.PROVIDER_FAILURE -> null
                    else -> 200
                },
            transportStatus =
                when (status) {
                    ObservationStatus.PROVIDER_FAILURE -> TransportStatus.TRANSPORT_FAILURE
                    else -> TransportStatus.HTTP_RESPONSE
                },
            observationStatus = status,
            eligibilityBoundaryAt = effectiveElig,
        )
    }
}
