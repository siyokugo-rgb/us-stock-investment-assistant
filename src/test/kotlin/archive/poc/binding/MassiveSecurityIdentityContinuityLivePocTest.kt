package archive.poc.binding

import archive.poc.ObservationStatus
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Offline classification QA for [MassiveSecurityIdentityContinuityLivePoc].
 * No network. Does not claim real multi-as-of LIVE_VERIFIED.
 */
class MassiveSecurityIdentityContinuityLivePocTest {
    @Test
    fun withoutApiKeyIsLiveUnverified() {
        assertEquals(
            MassiveSecurityIdentityContinuityLivePoc.LiveClassification.LIVE_UNVERIFIED,
            MassiveSecurityIdentityContinuityLivePoc.classifyWithoutApiKey(),
        )
    }

    @Test
    fun twoUsableObservedPairsIsLiveVerified() {
        val outcomes =
            listOf(
                usable("AAPL"),
                usable("MSFT"),
                notObserved("GOOGL"),
            )
        assertEquals(
            MassiveSecurityIdentityContinuityLivePoc.LiveClassification.LIVE_VERIFIED,
            MassiveSecurityIdentityContinuityLivePoc.classifyOverall(outcomes),
        )
    }

    @Test
    fun singleUsablePairIsLivePartial() {
        val outcomes = listOf(usable("AAPL"), notObserved("MSFT"), notObserved("GOOGL"))
        assertEquals(
            MassiveSecurityIdentityContinuityLivePoc.LiveClassification.LIVE_PARTIAL,
            MassiveSecurityIdentityContinuityLivePoc.classifyOverall(outcomes),
        )
    }

    @Test
    fun integrityFailIsLiveFailEvenWithTwoUsableLookingPairs() {
        val bad =
            usable("AAPL").copy(
                integrityFail = true,
                integrityFailNotes = "expected CONTINUITY_CANDIDATE but got UNRESOLVED",
            )
        val outcomes = listOf(bad, usable("MSFT"))
        assertEquals(
            MassiveSecurityIdentityContinuityLivePoc.LiveClassification.LIVE_FAIL,
            MassiveSecurityIdentityContinuityLivePoc.classifyOverall(outcomes),
        )
    }

    @Test
    fun defaultDatesAreOrderedAndTickersAreNonEmpty() {
        assertTrue(
            MassiveSecurityIdentityContinuityLivePoc.DEFAULT_T1 <
                MassiveSecurityIdentityContinuityLivePoc.DEFAULT_T2,
        )
        assertEquals(3, MassiveSecurityIdentityContinuityLivePoc.DEFAULT_TICKERS.size)
        assertFalse(MassiveSecurityIdentityContinuityLivePoc.DEFAULT_TICKERS.any { it.isBlank() })
    }

    private fun usable(ticker: String) =
        MassiveSecurityIdentityContinuityLivePoc.TickerPairOutcome(
            ticker = ticker,
            t1 = MassiveSecurityIdentityContinuityLivePoc.DEFAULT_T1,
            t2 = MassiveSecurityIdentityContinuityLivePoc.DEFAULT_T2,
            snapshotT1 =
                MassiveSecurityIdentityContinuityLivePoc.SnapshotAudit(
                    label = "T1",
                    date = MassiveSecurityIdentityContinuityLivePoc.DEFAULT_T1,
                    archiveId = "$ticker-t1",
                    observationStatus = ObservationStatus.OBSERVED,
                    requestKey = "GET|/v3/reference/tickers/$ticker|date=2021-01-04",
                    rawPayloadHash = "a".repeat(64),
                    eligibilityBoundaryAt = Instant.parse("2026-09-18T04:00:00Z"),
                    notes = null,
                ),
            snapshotT2 =
                MassiveSecurityIdentityContinuityLivePoc.SnapshotAudit(
                    label = "T2",
                    date = MassiveSecurityIdentityContinuityLivePoc.DEFAULT_T2,
                    archiveId = "$ticker-t2",
                    observationStatus = ObservationStatus.OBSERVED,
                    requestKey = "GET|/v3/reference/tickers/$ticker|date=2024-06-03",
                    rawPayloadHash = "b".repeat(64),
                    eligibilityBoundaryAt = Instant.parse("2026-09-18T04:01:00Z"),
                    notes = null,
                ),
            bothObserved = true,
            deriverCompleted = true,
            derivedStatus = SecurityIdentityContinuityStatus.CONTINUITY_CANDIDATE,
            derivedReason = null,
            firstShareClassFigi = "BBGSHARE",
            secondShareClassFigi = "BBGSHARE",
            firstCompositeFigi = "BBGCOMP",
            secondCompositeFigi = "BBGCOMP",
            firstPrimaryExchange = "XNAS",
            secondPrimaryExchange = "XNAS",
            evidenceEligibleAt = Instant.parse("2026-09-18T04:01:00Z"),
            expectedContinuityWhenEligible = true,
            integrityFail = false,
            integrityFailNotes = null,
        )

    private fun notObserved(ticker: String) =
        usable(ticker).copy(
            bothObserved = false,
            deriverCompleted = true,
            derivedStatus = SecurityIdentityContinuityStatus.UNRESOLVED,
            derivedReason = SecurityIdentityContinuityReason.INPUT_NOT_OBSERVED,
            expectedContinuityWhenEligible = false,
            snapshotT1 =
                usable(ticker).snapshotT1.copy(
                    observationStatus = ObservationStatus.PROVIDER_FAILURE,
                    rawPayloadHash = null,
                    eligibilityBoundaryAt = null,
                ),
            snapshotT2 =
                usable(ticker).snapshotT2.copy(
                    observationStatus = ObservationStatus.PROVIDER_FAILURE,
                    rawPayloadHash = null,
                    eligibilityBoundaryAt = null,
                ),
        )
}
