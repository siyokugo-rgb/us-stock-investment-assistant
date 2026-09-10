package pit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import support.Fixtures
import support.Fixtures.DECISION_AFTER
import support.Fixtures.DECISION_BEFORE
import support.Fixtures.DECISION_EQUAL
import support.Fixtures.INGESTED_AT
import support.Fixtures.INGESTED_LATER
import support.Fixtures.KNOWN_AT
import support.Fixtures.SEC_A
import support.Fixtures.SEC_B

class PitAvailabilityTest {
    @Test
    fun decisionAtBeforeKnownAtIsUnavailable() {
        val price = Fixtures.price()
        assertFalse(price.isAvailableAt(DECISION_BEFORE))
        assertFalse(Pit.isAvailableAt(KNOWN_AT, DECISION_BEFORE))
    }

    @Test
    fun decisionAtEqualToKnownAtIsAvailable() {
        val price = Fixtures.price()
        assertTrue(price.isAvailableAt(DECISION_EQUAL))
        assertTrue(Pit.isAvailableAt(KNOWN_AT, DECISION_EQUAL))
    }

    @Test
    fun decisionAtAfterKnownAtIsAvailable() {
        val price = Fixtures.price()
        assertTrue(price.isAvailableAt(DECISION_AFTER))
        assertTrue(Pit.isAvailableAt(KNOWN_AT, DECISION_AFTER))
    }

    @Test
    fun knowledgePitDoesNotUseIngestedAt() {
        val price = Fixtures.price(ingestedAt = INGESTED_LATER)
        assertTrue(price.isAvailableAt(DECISION_EQUAL))
        assertFalse(price.wasHeldBySystemAt(DECISION_EQUAL))
        assertTrue(price.wasHeldBySystemAt(INGESTED_LATER))
    }

    @Test
    fun heldBySystemRequiresIngestedAtOnOrBeforeDecisionAt() {
        assertFalse(Pit.wasHeldBySystemAt(INGESTED_AT, DECISION_EQUAL))
        assertTrue(Pit.wasHeldBySystemAt(INGESTED_AT, INGESTED_AT))
        assertTrue(Pit.wasHeldBySystemAt(INGESTED_AT, INGESTED_LATER))
    }

    @Test
    fun queryReturnsAllMatchingRecordsWithoutPickingLatest() {
        val older = Fixtures.price(close = "10.00", knownAt = KNOWN_AT, source = "source-a")
        val newer = Fixtures.price(
            high = "13.00",
            close = "12.00",
            knownAt = DECISION_AFTER,
            ingestedAt = INGESTED_LATER,
            source = "source-b",
        )
        val available = PitQuery.availableAt(
            records = listOf(older, newer),
            decisionAt = DECISION_AFTER,
            knownAt = { it.knownAt },
        )
        assertEquals(listOf(older, newer), available)
    }

    @Test
    fun queryDoesNotJoinDifferentSecurityIds() {
        val priceA = Fixtures.price(securityId = SEC_A)
        val priceB = Fixtures.price(securityId = SEC_B, open = "3.00", high = "3.00", low = "3.00", close = "3.00")
        val forA = PitQuery.forSecurity(
            records = listOf(priceA, priceB),
            securityId = SEC_A,
            idOf = { it.securityId },
        )
        assertEquals(listOf(priceA), forA)
        assertTrue(forA.none { it.securityId == SEC_B })
    }

    @Test
    fun dividendAndFundamentalUseKnownAtNotEffectiveDate() {
        val dividend = Fixtures.dividend()
        assertFalse(dividend.isAvailableAt(DECISION_BEFORE))
        assertTrue(dividend.isAvailableAt(DECISION_EQUAL))

        val snapshot = Fixtures.fundamental()
        val decisionDuringFiscalPeriod = java.time.Instant.parse("2026-01-15T15:00:00Z")
        assertFalse(snapshot.isAvailableAt(decisionDuringFiscalPeriod))
        assertTrue(snapshot.isAvailableAt(snapshot.knownAt))
    }
}
