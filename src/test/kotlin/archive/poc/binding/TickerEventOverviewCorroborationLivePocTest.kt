package archive.poc.binding

import archive.poc.ObservationStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Offline classification QA for [TickerEventOverviewCorroborationLivePoc].
 * No network. No live MASSIVE_API_KEY required.
 */
class TickerEventOverviewCorroborationLivePocTest {
    @Test
    fun withoutApiKeyIsLiveUnverifiedWithZeroRequests() {
        assertEquals(
            TickerEventOverviewCorroborationLivePoc.LiveClassification.LIVE_UNVERIFIED,
            TickerEventOverviewCorroborationLivePoc.classifyWithoutApiKey(),
        )
        val outcome = TickerEventOverviewCorroborationLivePoc.outcomeWithoutApiKey()
        assertEquals(0, outcome.requestCount)
        assertFalse(outcome.apiKeyPresent)
        assertEquals(
            TickerEventOverviewCorroborationLivePoc.LiveClassification.LIVE_UNVERIFIED,
            outcome.liveClassification,
        )
    }

    @Test
    fun threeObservedTickerChangeCorroboratedIsLiveCorroborated() {
        val outcome = TickerEventOverviewCorroborationLivePoc.classified()
        assertEquals(
            TickerEventOverviewCorroborationLivePoc.LiveClassification.LIVE_CORROBORATED,
            outcome.liveClassification,
        )
    }

    @Test
    fun corroborationUnresolvedIsLiveUnresolved() {
        val outcome =
            TickerEventOverviewCorroborationLivePoc.classified(
                corroborationStatus = TickerEventOverviewCorroborationStatus.UNRESOLVED,
                corroborationReason =
                    TickerEventOverviewCorroborationReason.NO_EVENT_WINDOW_MATCH,
            )
        assertEquals(
            TickerEventOverviewCorroborationLivePoc.LiveClassification.LIVE_UNRESOLVED,
            outcome.liveClassification,
        )
    }

    @Test
    fun overviewProviderFailureIsLiveProviderFailure() {
        val outcome =
            TickerEventOverviewCorroborationLivePoc.classified(
                t1Status = ObservationStatus.PROVIDER_FAILURE,
                t1Ingest = false,
                continuityStatus = null,
                corroborationStatus = null,
            )
        assertEquals(
            TickerEventOverviewCorroborationLivePoc.LiveClassification.LIVE_PROVIDER_FAILURE,
            outcome.liveClassification,
        )
    }

    @Test
    fun eventsProviderFailureIsLiveProviderFailure() {
        val outcome =
            TickerEventOverviewCorroborationLivePoc.classified(
                eventsStatus = ObservationStatus.PROVIDER_FAILURE,
                eventsIngest = false,
                continuityStatus = SecurityIdentityContinuityStatus.TICKER_CHANGE_CANDIDATE,
                corroborationStatus = null,
            )
        assertEquals(
            TickerEventOverviewCorroborationLivePoc.LiveClassification.LIVE_PROVIDER_FAILURE,
            outcome.liveClassification,
        )
    }

    @Test
    fun validationRejectedIsLiveValidationRejected() {
        val outcome =
            TickerEventOverviewCorroborationLivePoc.classified(
                eventsStatus = ObservationStatus.REJECTED_VALIDATION,
                eventsIngest = false,
                continuityStatus = SecurityIdentityContinuityStatus.TICKER_CHANGE_CANDIDATE,
                corroborationStatus = null,
            )
        assertEquals(
            TickerEventOverviewCorroborationLivePoc.LiveClassification.LIVE_VALIDATION_REJECTED,
            outcome.liveClassification,
        )
    }

    @Test
    fun localFailureIsLiveLocalFailure() {
        val outcome =
            TickerEventOverviewCorroborationLivePoc.classified(
                t2LocalException = "ArchiveValidationException: hash mismatch",
                continuityStatus = null,
                corroborationStatus = null,
            )
        assertEquals(
            TickerEventOverviewCorroborationLivePoc.LiveClassification.LIVE_LOCAL_FAILURE,
            outcome.liveClassification,
        )
    }

    @Test
    fun outputDoesNotContainApiKeyValueOrRawBody() {
        val outcome = TickerEventOverviewCorroborationLivePoc.outcomeWithoutApiKey()
        val text = TickerEventOverviewCorroborationLivePoc.formatSecretFree(outcome)
        assertTrue(text.contains("MASSIVE_API_KEY=NOT SET"))
        assertFalse(text.contains("apiKey="))
        assertFalse(text.contains("\"results\""))
        assertFalse(text.contains("Bearer "))
        assertFalse(text.contains("rawBody"))
        assertTrue(text.contains("liveClassification=LIVE_UNVERIFIED"))

        val corroborated = TickerEventOverviewCorroborationLivePoc.classified()
        val text2 = TickerEventOverviewCorroborationLivePoc.formatSecretFree(corroborated)
        assertTrue(text2.contains("MASSIVE_API_KEY=SET"))
        assertFalse(text2.contains("sk_"))
        assertFalse(text2.contains("\"ticker_change\""))
    }

    @Test
    fun defaultsAreFixedFreeTiercope() {
        assertEquals("SQ", TickerEventOverviewCorroborationLivePoc.DEFAULT_T1_TICKER)
        assertEquals("2025-01-17", TickerEventOverviewCorroborationLivePoc.DEFAULT_T1_DATE)
        assertEquals("XYZ", TickerEventOverviewCorroborationLivePoc.DEFAULT_T2_TICKER)
        assertEquals("2025-01-22", TickerEventOverviewCorroborationLivePoc.DEFAULT_T2_DATE)
        assertEquals("XYZ", TickerEventOverviewCorroborationLivePoc.DEFAULT_EVENTS_LOOKUP)
        assertTrue(
            TickerEventOverviewCorroborationLivePoc.DEFAULT_T1_DATE <
                TickerEventOverviewCorroborationLivePoc.DEFAULT_T2_DATE,
        )
    }
}
