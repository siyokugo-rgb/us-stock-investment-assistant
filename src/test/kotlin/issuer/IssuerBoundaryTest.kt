package issuer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import support.Fixtures
import support.Fixtures.DECISION_AFTER
import support.Fixtures.DECISION_BEFORE
import support.Fixtures.DECISION_EQUAL
import support.Fixtures.INGESTED_AT
import support.Fixtures.ISSUER_A
import support.Fixtures.ISSUER_B
import support.Fixtures.KNOWN_AT
import support.Fixtures.SEC_A
import support.Fixtures.SEC_B
import java.time.LocalDate

class IssuerBoundaryTest {
    @Test
    fun issuerIdBlankIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            IssuerId("  ")
        }
        assertFailsWith<IllegalArgumentException> {
            IssuerId("")
        }
    }

    @Test
    fun issuerIdIsNotDerivedFromCikOrTicker() {
        // IssuerId is an opaque internal id; CIK/ticker stay on IssuerIdentifier / SecurityIdentifier.
        val issuerId = IssuerId("issuer-0001")
        assertEquals("issuer-0001", issuerId.value)
        assertFalse(issuerId.value == Cik.normalize("320193"))
        assertFalse(issuerId.value == "AAPL")
        val cikId = IssuerIdentifier.cik(
            issuerId = issuerId,
            rawCik = "320193",
            knownAt = KNOWN_AT,
            ingestedAt = INGESTED_AT,
            source = "fixture-source",
        )
        assertEquals(issuerId, cikId.issuerId)
        assertEquals("0000320193", cikId.value)
        assertTrue(issuerId.value != cikId.value)
    }

    @Test
    fun sameIssuerCanRelateToMultipleSecurities() {
        val relA = Fixtures.issuerSecurityRelation(issuerId = ISSUER_A, securityId = SEC_A)
        val relB = Fixtures.issuerSecurityRelation(issuerId = ISSUER_A, securityId = SEC_B)
        val index = IssuerSecurityRelationIndex(listOf(relA, relB))
        val candidates = index.availableSecuritiesFor(ISSUER_A, Fixtures.AS_OF, DECISION_EQUAL)
        assertEquals(2, candidates.size)
        assertEquals(setOf(SEC_A, SEC_B), candidates.map { it.securityId }.toSet())
    }

    @Test
    fun multipleSecuritiesAreNotCollapsedToOne() {
        val relA = Fixtures.issuerSecurityRelation(securityId = SEC_A)
        val relB = Fixtures.issuerSecurityRelation(securityId = SEC_B)
        val candidates =
            IssuerSecurityRelationIndex(listOf(relA, relB))
                .availableSecuritiesFor(ISSUER_A, Fixtures.AS_OF, DECISION_EQUAL)
        // Ambiguity stays as a candidate set — no auto-pick of first/latest.
        assertEquals(listOf(relA, relB), candidates)
        assertTrue(candidates.size != 1)
    }

    @Test
    fun validFromIsInclusive() {
        val relation =
            Fixtures.issuerSecurityRelation(
                validFrom = LocalDate.of(2024, 6, 10),
                validTo = LocalDate.of(2025, 1, 1),
            )
        assertFalse(relation.isValidOn(LocalDate.of(2024, 6, 9)))
        assertTrue(relation.isValidOn(LocalDate.of(2024, 6, 10)))
    }

    @Test
    fun validToIsExclusive() {
        val relation =
            Fixtures.issuerSecurityRelation(
                validFrom = LocalDate.of(2024, 1, 2),
                validTo = LocalDate.of(2024, 12, 31),
            )
        assertTrue(relation.isValidOn(LocalDate.of(2024, 12, 30)))
        assertFalse(relation.isValidOn(LocalDate.of(2024, 12, 31)))
    }

    @Test
    fun nullValidToRemainsOpenEnded() {
        val relation = Fixtures.issuerSecurityRelation(validTo = null)
        assertTrue(relation.isValidOn(LocalDate.of(2099, 12, 31)))
    }

    @Test
    fun relationUnavailableBeforeKnownAt() {
        val relation = Fixtures.issuerSecurityRelation()
        assertTrue(relation.isValidOn(Fixtures.AS_OF))
        assertFalse(relation.isAvailableAt(DECISION_BEFORE))
        val candidates =
            IssuerSecurityRelationIndex(listOf(relation))
                .availableSecuritiesFor(ISSUER_A, Fixtures.AS_OF, DECISION_BEFORE)
        assertTrue(candidates.isEmpty())
    }

    @Test
    fun relationAvailableAtExactKnownAt() {
        val relation = Fixtures.issuerSecurityRelation()
        assertTrue(relation.isAvailableAt(DECISION_EQUAL))
        val candidates =
            IssuerSecurityRelationIndex(listOf(relation))
                .availableSecuritiesFor(ISSUER_A, Fixtures.AS_OF, DECISION_EQUAL)
        assertEquals(listOf(relation), candidates)
    }

    @Test
    fun knownAtAndIngestedAtAreStoredSeparately() {
        val relation =
            Fixtures.issuerSecurityRelation(
                knownAt = KNOWN_AT,
                ingestedAt = INGESTED_AT,
            )
        assertEquals(KNOWN_AT, relation.knownAt)
        assertEquals(INGESTED_AT, relation.ingestedAt)
        assertTrue(relation.ingestedAt.isAfter(relation.knownAt))
        // Knowledge PIT ignores ingestedAt.
        assertTrue(relation.isAvailableAt(DECISION_EQUAL))
        assertFalse(relation.wasHeldBySystemAt(DECISION_EQUAL))
    }

    @Test
    fun cikIdentifierBelongsToIssuerSide() {
        val identifier = Fixtures.issuerIdentifier(issuerId = ISSUER_A, rawCik = "789019")
        assertEquals(ISSUER_A, identifier.issuerId)
        assertEquals(IssuerIdentifierType.CIK, identifier.type)
        assertEquals("0000789019", identifier.value)
        // No SecurityId field on IssuerIdentifier.
        assertTrue(identifier.issuerId.value.startsWith("issuer-"))
    }

    @Test
    fun noApiConvertsCikStringIntoSecurityId() {
        val canonical = Cik.normalize("1652044")
        assertEquals("0001652044", canonical)
        // Cik helper returns canonical String only — never SecurityId.
        assertFalse(canonical == SEC_A.value)
        assertFalse(canonical == ISSUER_A.value)
        // Relation index is keyed by IssuerId, not CIK string; CIK alone yields no Security.
        val index = IssuerSecurityRelationIndex(emptyList())
        assertTrue(index.availableSecuritiesFor(ISSUER_A, Fixtures.AS_OF, DECISION_EQUAL).isEmpty())
        // Compiles/uses only Issuer-side APIs; there is no cik→SecurityId conversion entry point.
        val identifier = Fixtures.issuerIdentifier(rawCik = canonical)
        assertEquals(ISSUER_A, identifier.issuerId)
        assertEquals(canonical, identifier.value)
    }

    @Test
    fun missingRelationYieldsEmptyCandidates() {
        val index = IssuerSecurityRelationIndex(listOf(Fixtures.issuerSecurityRelation(issuerId = ISSUER_B)))
        assertTrue(index.availableSecuritiesFor(ISSUER_A, Fixtures.AS_OF, DECISION_EQUAL).isEmpty())
    }

    @Test
    fun asOfDateOutsideValidityExcludesRelation() {
        val relation =
            Fixtures.issuerSecurityRelation(
                validFrom = LocalDate.of(2024, 1, 2),
                validTo = LocalDate.of(2024, 6, 1),
            )
        assertFalse(relation.isValidOn(LocalDate.of(2024, 6, 10)))
        val candidates =
            IssuerSecurityRelationIndex(listOf(relation))
                .availableSecuritiesFor(ISSUER_A, LocalDate.of(2024, 6, 10), DECISION_AFTER)
        assertTrue(candidates.isEmpty())
    }

    @Test
    fun emptyValidityWindowIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            Fixtures.issuerSecurityRelation(
                validFrom = LocalDate.of(2024, 6, 10),
                validTo = LocalDate.of(2024, 6, 10),
            )
        }
    }

    @Test
    fun nonCanonicalCikIsRejectedOnIssuerIdentifier() {
        assertFailsWith<IllegalArgumentException> {
            IssuerIdentifier(
                issuerId = ISSUER_A,
                type = IssuerIdentifierType.CIK,
                value = "320193",
                knownAt = KNOWN_AT,
                ingestedAt = INGESTED_AT,
                source = "fixture-source",
            )
        }
    }
}
