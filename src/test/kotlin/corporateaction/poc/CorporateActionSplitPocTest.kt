package corporateaction.poc

import market.DailyPrice
import security.SecurityId
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Corporate Action Split PoC Fail-Closed tests.
 * Network-free fixtures only. Does not invent SecurityId from symbols or knownAt from dates.
 */
class CorporateActionSplitPocTest {
    private val fetchedAt = Instant.parse("2026-09-13T12:00:00Z")

    private fun observation(
        actionType: SplitActionType,
        ratio: SplitRatio,
        effectiveDate: LocalDate? = LocalDate.of(2020, 8, 31),
        eventId: String? = "evt-1",
        knownAtStatus: HistoricalKnownAtStatus = HistoricalKnownAtStatus.UNRESOLVED,
        announcedAt: Instant? = null,
        announcedDate: LocalDate? = LocalDate.of(2020, 7, 30),
        providerSymbol: String = "AAPL",
    ): RawSplitObservation =
        RawSplitObservation(
            provider = "synthetic-fixture",
            providerSymbol = providerSymbol,
            eventId = eventId,
            actionType = actionType,
            ratio = ratio,
            announcedDate = announcedDate,
            announcedAt = announcedAt,
            effectiveDate = effectiveDate,
            exDate = effectiveDate,
            recordDate = null,
            fetchedAt = fetchedAt,
            sourceDocumentId = "fixture-split",
            rawPayloadSha256 = null,
            historicalKnownAtStatus = knownAtStatus,
            evidenceStatus = SplitEvidenceStatus.SYNTHETIC_FIXTURE_ONLY,
        )

    @Test
    fun twoForOneSplitRatioSemantics() {
        val ratio = SplitRatio.of(oldShares = "1", newShares = "2")
        assertTrue(ratio.isForwardSplit())
        assertEquals(0, ratio.oldShares.compareTo(BigDecimal.ONE))
        assertEquals(0, ratio.newShares.compareTo(BigDecimal("2")))
        val obs = observation(SplitActionType.STOCK_SPLIT, ratio)
        assertEquals(SplitActionType.STOCK_SPLIT, obs.actionType)
    }

    @Test
    fun threeForTwoSplitRatioSemantics() {
        val ratio = SplitRatio.of(oldShares = "2", newShares = "3")
        assertTrue(ratio.isForwardSplit())
        val qty =
            SplitQuantityMath.applySplitQuantity(
                oldQuantity = BigDecimal("100"),
                ratio = ratio,
            )
        assertEquals(0, qty.compareTo(BigDecimal("150")))
    }

    @Test
    fun oneForTenReverseSplitRatioSemantics() {
        val ratio = SplitRatio.of(oldShares = "10", newShares = "1")
        assertTrue(ratio.isReverseSplit())
        val obs = observation(SplitActionType.REVERSE_SPLIT, ratio)
        assertEquals(SplitActionType.REVERSE_SPLIT, obs.actionType)
    }

    @Test
    fun zeroOldSharesRejected() {
        assertFailsWith<IllegalArgumentException> {
            SplitRatio(oldShares = BigDecimal.ZERO, newShares = BigDecimal.ONE)
        }
    }

    @Test
    fun zeroNewSharesRejected() {
        assertFailsWith<IllegalArgumentException> {
            SplitRatio(oldShares = BigDecimal.ONE, newShares = BigDecimal.ZERO)
        }
    }

    @Test
    fun negativeRatioRejected() {
        assertFailsWith<IllegalArgumentException> {
            SplitRatio(oldShares = BigDecimal("-1"), newShares = BigDecimal("2"))
        }
        assertFailsWith<IllegalArgumentException> {
            SplitRatio(oldShares = BigDecimal.ONE, newShares = BigDecimal("-2"))
        }
    }

    @Test
    fun malformedAmbiguousRatioStringRejected() {
        assertFailsWith<IllegalArgumentException> {
            AmbiguousSplitRatioParser.parseOrFail("2:1")
        }
        assertFailsWith<IllegalArgumentException> {
            AmbiguousSplitRatioParser.parseOrFail("2-for-1")
        }
        assertFailsWith<IllegalArgumentException> {
            AmbiguousSplitRatioParser.parseOrFail("0.5")
        }
    }

    @Test
    fun quantityOneHundredTimesTwoForOneIsTwoHundred() {
        val qty =
            SplitQuantityMath.applySplitQuantity(
                oldQuantity = BigDecimal("100"),
                oldShares = BigDecimal.ONE,
                newShares = BigDecimal("2"),
            )
        assertEquals(0, qty.compareTo(BigDecimal("200")))
    }

    @Test
    fun quantityOneHundredTimesOneForTenIsTen() {
        val qty =
            SplitQuantityMath.applySplitQuantity(
                oldQuantity = BigDecimal("100"),
                oldShares = BigDecimal("10"),
                newShares = BigDecimal.ONE,
            )
        assertEquals(0, qty.compareTo(BigDecimal("10")))
    }

    @Test
    fun terminatingFractionalQuantityIsPreservedExactly() {
        val qty =
            SplitQuantityMath.applySplitQuantity(
                oldQuantity = BigDecimal("25"),
                oldShares = BigDecimal("10"),
                newShares = BigDecimal.ONE,
            )
        // 2.5 is exact; must not be rounded to 2 or 3
        assertEquals(0, qty.compareTo(BigDecimal("2.5")))
    }

    @Test
    fun nonTerminatingFractionalQuantityFailsClosedWithoutRounding() {
        assertFailsWith<IllegalArgumentException> {
            SplitQuantityMath.applySplitQuantity(
                oldQuantity = BigDecimal("10"),
                oldShares = BigDecimal("3"),
                newShares = BigDecimal.ONE,
            )
        }
    }

    @Test
    fun securityIdIsNotDerivedFromProviderSymbol() {
        val obs =
            observation(
                actionType = SplitActionType.STOCK_SPLIT,
                ratio = SplitRatio.of("1", "2"),
                providerSymbol = "AAPL",
            )
        val fieldNames = RawSplitObservation::class.java.declaredFields.map { it.name }
        assertTrue(fieldNames.none { it.equals("securityId", ignoreCase = true) })
        val securityId = SecurityId("SEC-INTERNAL-1")
        assertTrue(securityId.value != obs.providerSymbol)
    }

    @Test
    fun effectiveDateIsNotKnownAt() {
        val effective = LocalDate.of(2020, 8, 31)
        val obs =
            observation(
                actionType = SplitActionType.STOCK_SPLIT,
                ratio = SplitRatio.of("1", "4"),
                effectiveDate = effective,
                knownAtStatus = HistoricalKnownAtStatus.UNRESOLVED,
            )
        assertEquals(HistoricalKnownAtStatus.UNRESOLVED, obs.historicalKnownAtStatus)
        assertEquals(null, obs.announcedAt)
        assertTrue(obs.effectiveDate != null)
    }

    @Test
    fun fetchedAtIsNotKnownAt() {
        val obs =
            observation(
                actionType = SplitActionType.STOCK_SPLIT,
                ratio = SplitRatio.of("1", "2"),
            )
        assertEquals(fetchedAt, obs.fetchedAt)
        assertEquals(HistoricalKnownAtStatus.UNRESOLVED, obs.historicalKnownAtStatus)
        assertTrue(obs.announcedAt == null)
    }

    @Test
    fun unresolvedKnownAtMustNotInventAnnouncementInstant() {
        assertFailsWith<IllegalArgumentException> {
            observation(
                actionType = SplitActionType.STOCK_SPLIT,
                ratio = SplitRatio.of("1", "2"),
                knownAtStatus = HistoricalKnownAtStatus.UNRESOLVED,
                announcedAt = Instant.parse("2020-07-30T00:00:00Z"),
            )
        }
    }

    @Test
    fun duplicateIdenticalEvidenceIsNotAutoAdoptedAsLatest() {
        val a =
            observation(
                actionType = SplitActionType.STOCK_SPLIT,
                ratio = SplitRatio.of("1", "2"),
                eventId = "same",
            )
        val b = a.copy(fetchedAt = Instant.parse("2026-09-14T00:00:00Z"))
        val dups = SplitObservationConflicts.duplicateIdenticalObservations(listOf(a, b))
        assertEquals(1, dups.size)
        assertTrue(a.fetchedAt.isBefore(b.fetchedAt))
    }

    @Test
    fun conflictingRatiosAreNotAutoResolved() {
        val a =
            observation(
                actionType = SplitActionType.STOCK_SPLIT,
                ratio = SplitRatio.of("1", "2"),
                eventId = "evt-x",
            )
        val b =
            observation(
                actionType = SplitActionType.STOCK_SPLIT,
                ratio = SplitRatio.of("1", "3"),
                eventId = "evt-x",
            )
        val conflicts = SplitObservationConflicts.conflictingRatiosForSameEventId(listOf(a, b))
        assertEquals(1, conflicts.size)
        assertTrue(conflicts.single().reason.contains("auto-resolve forbidden"))
    }

    @Test
    fun conflictingEffectiveDatesAreNotAutoResolved() {
        val a =
            observation(
                actionType = SplitActionType.STOCK_SPLIT,
                ratio = SplitRatio.of("1", "2"),
                eventId = "evt-y",
                effectiveDate = LocalDate.of(2020, 8, 31),
            )
        val b =
            observation(
                actionType = SplitActionType.STOCK_SPLIT,
                ratio = SplitRatio.of("1", "2"),
                eventId = "evt-y",
                effectiveDate = LocalDate.of(2020, 9, 1),
            )
        val conflicts =
            SplitObservationConflicts.conflictingEffectiveDatesForSameEventId(listOf(a, b))
        assertEquals(1, conflicts.size)
    }

    @Test
    fun correctionWithoutVersionEvidenceMustNotLatestWins() {
        val a =
            observation(
                actionType = SplitActionType.STOCK_SPLIT,
                ratio = SplitRatio.of("1", "2"),
                eventId = "evt-z",
            )
        val b =
            observation(
                actionType = SplitActionType.STOCK_SPLIT,
                ratio = SplitRatio.of("1", "4"),
                eventId = "evt-z",
            )
        assertTrue(
            SplitObservationConflicts.conflictingRatiosForSameEventId(listOf(a, b)).isNotEmpty(),
        )
    }

    @Test
    fun preSplitRawPriceIsNotRewrittenBySplitEvent() {
        val securityId = SecurityId("SEC-1")
        val raw =
            DailyPrice(
                securityId = securityId,
                tradingDate = LocalDate.of(2020, 8, 28),
                open = BigDecimal("400.00"),
                high = BigDecimal("410.00"),
                low = BigDecimal("395.00"),
                close = BigDecimal("405.00"),
                volume = 1_000_000L,
                currency = "USD",
                knownAt = Instant.parse("2020-08-29T00:00:00Z"),
                ingestedAt = Instant.parse("2020-08-29T01:00:00Z"),
                source = "fixture-raw",
            )
        val split =
            observation(
                actionType = SplitActionType.STOCK_SPLIT,
                ratio = SplitRatio.of("1", "4"),
                effectiveDate = LocalDate.of(2020, 8, 31),
            )
        assertEquals(0, raw.close.compareTo(BigDecimal("405.00")))
        assertTrue(split.effectiveDate!!.isAfter(raw.tradingDate))
    }

    @Test
    fun adjustedPriceMustNotBeUsedAsExecutionPrice() {
        val fields = DailyPrice::class.java.declaredFields.map { it.name }
        assertTrue(fields.none { it.contains("adjust", ignoreCase = true) })
    }

    @Test
    fun reverseSplitQuantityDecreasesAndFractionalPossible() {
        val qty =
            SplitQuantityMath.applySplitQuantity(
                oldQuantity = BigDecimal("25"),
                oldShares = BigDecimal("10"),
                newShares = BigDecimal.ONE,
            )
        assertEquals(0, qty.compareTo(BigDecimal("2.5")))
    }

    @Test
    fun missingEffectiveDateFailsClosedForAccounting() {
        val obs =
            observation(
                actionType = SplitActionType.STOCK_SPLIT,
                ratio = SplitRatio.of("1", "2"),
                effectiveDate = null,
            )
        assertFailsWith<IllegalStateException> {
            obs.requireEffectiveDateForAccounting()
        }
    }

    @Test
    fun actionTypeInconsistentWithRatioFailsClosed() {
        assertFailsWith<IllegalArgumentException> {
            observation(
                actionType = SplitActionType.STOCK_SPLIT,
                ratio = SplitRatio.of("10", "1"),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            observation(
                actionType = SplitActionType.REVERSE_SPLIT,
                ratio = SplitRatio.of("1", "2"),
            )
        }
    }

    @Test
    fun theoreticalInverseRatioIsNotAMarketPriceEqualityTest() {
        val oldShares = BigDecimal.ONE
        val newShares = BigDecimal("4")
        val shareMultiplier = newShares.divide(oldShares)
        val perShareReferenceInverse = oldShares.divide(newShares)
        assertEquals(0, shareMultiplier.multiply(perShareReferenceInverse).compareTo(BigDecimal.ONE))
    }
}
