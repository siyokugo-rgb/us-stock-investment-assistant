package security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import support.Fixtures
import support.Fixtures.DECISION_AFTER
import support.Fixtures.DECISION_BEFORE
import support.Fixtures.DECISION_EQUAL
import support.Fixtures.SEC_A
import support.Fixtures.SEC_B
import java.time.LocalDate

class SecurityIdentifierTest {
    @Test
    fun unavailableBeforeValidFrom() {
        val identifier = Fixtures.identifier(
            validFrom = LocalDate.of(2024, 6, 10),
            validTo = LocalDate.of(2025, 1, 1),
        )
        assertFalse(identifier.isValidOn(LocalDate.of(2024, 6, 9)))
        assertTrue(identifier.isValidOn(LocalDate.of(2024, 6, 10)))
    }

    @Test
    fun unavailableOnAndAfterExclusiveValidTo() {
        val identifier = Fixtures.identifier(
            validFrom = LocalDate.of(2024, 1, 2),
            validTo = LocalDate.of(2024, 12, 31),
        )
        assertTrue(identifier.isValidOn(LocalDate.of(2024, 12, 30)))
        assertFalse(identifier.isValidOn(LocalDate.of(2024, 12, 31)))
        assertFalse(identifier.isValidOn(LocalDate.of(2025, 1, 1)))
    }

    @Test
    fun unavailableBeforeKnownAtEvenIfDateIsInValidityWindow() {
        val identifier = Fixtures.identifier()
        assertTrue(identifier.isValidOn(Fixtures.AS_OF))
        assertFalse(identifier.isAvailableAt(DECISION_BEFORE))
        assertTrue(identifier.isAvailableAt(DECISION_EQUAL))
        assertTrue(identifier.isAvailableAt(DECISION_AFTER))
    }

    @Test
    fun openEndedValidToRemainsValidOnLaterDates() {
        val identifier = Fixtures.identifier(validTo = null)
        assertTrue(identifier.isValidOn(LocalDate.of(2099, 12, 31)))
    }

    @Test
    fun emptyValidityWindowIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            Fixtures.identifier(
                validFrom = LocalDate.of(2024, 6, 10),
                validTo = LocalDate.of(2024, 6, 10),
            )
        }
    }

    @Test
    fun tickerChangeKeepsSameSecurityId() {
        val beforeChange = Fixtures.identifier(
            securityId = SEC_A,
            value = "OLD",
            validFrom = LocalDate.of(2020, 1, 2),
            validTo = LocalDate.of(2023, 6, 1),
        )
        val afterChange = Fixtures.identifier(
            securityId = SEC_A,
            value = "NEW",
            validFrom = LocalDate.of(2023, 6, 1),
            validTo = null,
        )
        assertEquals(beforeChange.securityId, afterChange.securityId)
        assertTrue(beforeChange.isValidOn(LocalDate.of(2023, 5, 31)))
        assertFalse(beforeChange.isValidOn(LocalDate.of(2023, 6, 1)))
        assertTrue(afterChange.isValidOn(LocalDate.of(2023, 6, 1)))

        val index = SecurityIdentifierIndex(listOf(beforeChange, afterChange))
        val onOldDate = index.candidatesForSecurity(
            securityId = SEC_A,
            decisionAt = DECISION_AFTER,
            asOfDate = LocalDate.of(2023, 5, 31),
        )
        val onNewDate = index.candidatesForSecurity(
            securityId = SEC_A,
            decisionAt = DECISION_AFTER,
            asOfDate = LocalDate.of(2023, 6, 1),
        )
        assertEquals(listOf("OLD"), onOldDate.map { it.value })
        assertEquals(listOf("NEW"), onNewDate.map { it.value })
        assertEquals(setOf(SEC_A), (onOldDate + onNewDate).map { it.securityId }.toSet())
    }

    @Test
    fun sameTickerStringWithDifferentSecurityIdsAreDistinctSecurities() {
        val recycledA = Fixtures.identifier(securityId = SEC_A, value = "XYZ")
        val recycledB = Fixtures.identifier(securityId = SEC_B, value = "XYZ")
        assertEquals("XYZ", recycledA.value)
        assertEquals("XYZ", recycledB.value)
        assertTrue(recycledA.securityId != recycledB.securityId)
    }

    @Test
    fun tickerRecycleIsNotMergedByStringAlone() {
        val firstUse = Fixtures.identifier(
            securityId = SEC_A,
            value = "RECY",
            validFrom = LocalDate.of(2010, 1, 4),
            validTo = LocalDate.of(2018, 1, 2),
        )
        val secondUse = Fixtures.identifier(
            securityId = SEC_B,
            value = "RECY",
            validFrom = LocalDate.of(2020, 1, 2),
            validTo = null,
        )
        val index = SecurityIdentifierIndex(listOf(firstUse, secondUse))

        val overlappingWindowIfNaive = index.candidatesByIdentifier(
            type = IdentifierType.TICKER,
            value = "RECY",
            decisionAt = DECISION_AFTER,
            asOfDate = LocalDate.of(2020, 6, 1),
        )
        assertEquals(listOf(SEC_B), overlappingWindowIfNaive.map { it.securityId })

        val bothIfValidityIgnoredWouldCollide = listOf(firstUse, secondUse).filter { it.value == "RECY" }
        assertEquals(2, bothIfValidityIgnoredWouldCollide.size)
        assertEquals(2, bothIfValidityIgnoredWouldCollide.map { it.securityId }.distinct().size)

        val conflictIfSameDateWereValid = SecurityIdentifierIndex(
            listOf(
                Fixtures.identifier(securityId = SEC_A, value = "DUP", validTo = null, source = "source-a"),
                Fixtures.identifier(securityId = SEC_B, value = "DUP", validTo = null, source = "source-b"),
            ),
        ).candidatesByIdentifier(
            type = IdentifierType.TICKER,
            value = "DUP",
            decisionAt = DECISION_AFTER,
            asOfDate = Fixtures.AS_OF,
        )
        assertEquals(2, conflictIfSameDateWereValid.size)
        assertEquals(setOf(SEC_A, SEC_B), conflictIfSameDateWereValid.map { it.securityId }.toSet())
    }

    @Test
    fun indexDoesNotAutoSelectAmongSourceConflictsForSameSecurity() {
        val fromVendorA = Fixtures.identifier(value = "AAA", source = "vendor-a", validTo = null)
        val fromVendorB = Fixtures.identifier(value = "AAA-ALT", source = "vendor-b", validTo = null)
        val index = SecurityIdentifierIndex(listOf(fromVendorA, fromVendorB))
        val candidates = index.candidatesForSecurity(
            securityId = SEC_A,
            decisionAt = DECISION_AFTER,
            asOfDate = Fixtures.AS_OF,
        )
        assertEquals(2, candidates.size)
    }

    @Test
    fun blankIdentifierValueIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            Fixtures.identifier(value = " ")
        }
    }

    @Test
    fun blankSecurityIdIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            SecurityId(" ")
        }
    }

    @Test
    fun securityIdentifierTypesAreTickerAndVendorPermanentIdOnly() {
        assertEquals(
            setOf(IdentifierType.TICKER, IdentifierType.VENDOR_PERMANENT_ID),
            IdentifierType.entries.toSet(),
        )
        val vendor =
            Fixtures.identifier(
                type = IdentifierType.VENDOR_PERMANENT_ID,
                value = "V-PERM-001",
                validTo = null,
            )
        assertEquals(IdentifierType.VENDOR_PERMANENT_ID, vendor.type)
        assertEquals(SEC_A, vendor.securityId)
    }
}
