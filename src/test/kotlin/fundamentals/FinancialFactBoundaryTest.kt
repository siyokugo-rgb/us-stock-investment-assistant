package fundamentals

import issuer.IssuerId
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FinancialFactBoundaryTest {
    private val issuer = IssuerId("issuer-0001")

    private fun fact(
        concept: String = "NetIncomeLoss",
        taxonomy: String = "us-gaap",
        unit: String = "USD",
        value: String = "100.00",
        start: LocalDate? = LocalDate.of(2024, 10, 1),
        end: LocalDate = LocalDate.of(2025, 9, 30),
        fy: Int? = 2025,
        fp: String? = "FY",
        form: String = "10-K",
        filed: LocalDate = LocalDate.of(2025, 11, 1),
        frame: String? = "CY2025",
        accession: String = "0000320193-25-000079",
        issuerId: IssuerId = issuer,
    ): RawFinancialFact =
        RawFinancialFact(
            issuerId = issuerId,
            taxonomy = taxonomy,
            concept = concept,
            unit = unit,
            value = BigDecimal(value),
            start = start,
            end = end,
            fy = fy,
            fp = fp,
            form = form,
            filed = filed,
            frame = frame,
            accessionNumber = accession,
        )

    @Test
    fun taxonomyAndConceptArePreserved() {
        val raw = fact(taxonomy = "us-gaap", concept = "Assets")
        assertEquals("us-gaap", raw.taxonomy)
        assertEquals("Assets", raw.concept)
    }

    @Test
    fun unitIsPreserved() {
        assertEquals("USD/shares", fact(unit = "USD/shares").unit)
    }

    @Test
    fun valueIsBigDecimal() {
        val raw = fact(value = "1234567890.12")
        assertEquals(BigDecimal("1234567890.12"), raw.value)
        assertEquals(0, raw.value.compareTo(BigDecimal("1234567890.12")))
    }

    @Test
    fun instantPeriodIsIdentifiedByEndOnly() {
        val raw = fact(start = null, end = LocalDate.of(2025, 9, 30), concept = "Assets")
        val period = raw.period()
        assertTrue(period is FactPeriod.InstantPoint)
        assertEquals(LocalDate.of(2025, 9, 30), period.end)
        assertEquals(
            PeriodComparisonKey(PeriodKind.INSTANT, start = null, end = LocalDate.of(2025, 9, 30)),
            period.comparisonKey(),
        )
    }

    @Test
    fun durationPeriodIsIdentifiedByStartAndEnd() {
        val start = LocalDate.of(2024, 10, 1)
        val end = LocalDate.of(2025, 9, 30)
        val period = fact(start = start, end = end).period()
        assertTrue(period is FactPeriod.Duration)
        period as FactPeriod.Duration
        assertEquals(start, period.start)
        assertEquals(end, period.end)
        assertEquals(
            PeriodComparisonKey(PeriodKind.DURATION, start = start, end = end),
            period.comparisonKey(),
        )
    }

    @Test
    fun fyFpFrameAreNotPeriodIdentity() {
        val a =
            fact(
                fy = 2024,
                fp = "FY",
                frame = "CY2024",
                start = LocalDate.of(2023, 10, 1),
                end = LocalDate.of(2024, 9, 30),
            )
        val b =
            fact(
                fy = 2025,
                fp = "Q4",
                frame = null,
                start = LocalDate.of(2023, 10, 1),
                end = LocalDate.of(2024, 9, 30),
                accession = "0000320193-25-000099",
            )
        assertEquals(a.period().comparisonKey(), b.period().comparisonKey())
        assertNotEquals(a.fy, b.fy)
        assertNotEquals(a.fp, b.fp)
        assertNotEquals(a.frame, b.frame)
    }

    @Test
    fun samePeriodDifferentAccessionAreBothKept() {
        val set =
            FinancialFactCandidateSet(
                listOf(
                    fact(accession = "0000320193-25-000079", value = "10"),
                    fact(accession = "0000320193-25-000099", value = "11"),
                ),
            )
        assertEquals(2, set.candidates.size)
        assertEquals(
            setOf("0000320193-25-000079", "0000320193-25-000099"),
            set.candidates.map { it.fact.accessionNumber }.toSet(),
        )
    }

    @Test
    fun sameValueDifferentAccessionIsRepeated() {
        val set =
            FinancialFactCandidateSet(
                listOf(
                    fact(accession = "0000320193-25-000079", value = "42.00"),
                    fact(accession = "0000320193-25-000099", value = "42.00"),
                ),
            )
        assertEquals(2, set.candidates.size)
        assertTrue(set.candidates.all { it.classification == FactVersionClassification.REPEATED })
    }

    @Test
    fun mixedSameAndDifferentValuesInBucketPreferUnresolvedOverRepeated() {
        val a = fact(form = "10-K", accession = "0000320193-25-000001", value = "100")
        val b = fact(form = "10-K", accession = "0000320193-25-000002", value = "100")
        val c = fact(form = "10-K", accession = "0000320193-25-000003", value = "90")
        val set = FinancialFactCandidateSet(listOf(a, b, c))
        assertEquals(3, set.candidates.size)
        // Value-conflict peers exist for every candidate; do not treat 100/100 as safe REPEATED.
        assertTrue(set.candidates.all { it.classification == FactVersionClassification.UNRESOLVED })
        assertTrue(set.candidates.none { it.classification == FactVersionClassification.REPEATED })
        assertTrue(set.candidates.none { it.classification == FactVersionClassification.RESTATEMENT_CANDIDATE })
        val hundredSide =
            set.candidates.filter { it.fact.value.compareTo(BigDecimal("100")) == 0 }
        assertEquals(2, hundredSide.size)
        assertTrue(hundredSide.all { it.classification == FactVersionClassification.UNRESOLVED })
    }

    @Test
    fun nonAmendmentValueDifferenceIsUnresolvedNotRestatementCandidate() {
        val a = fact(form = "10-K", accession = "0000320193-25-000079", value = "100")
        val b = fact(form = "10-K", accession = "0000320193-25-000099", value = "90")
        val set = FinancialFactCandidateSet(listOf(a, b))
        assertEquals(2, set.candidates.size)
        assertTrue(set.candidates.all { it.classification == FactVersionClassification.UNRESOLVED })
        assertTrue(set.candidates.none { it.classification == FactVersionClassification.RESTATEMENT_CANDIDATE })
        assertNotEquals(0, a.value.compareTo(b.value))
    }

    @Test
    fun differentUnitsAreNotMerged() {
        val set =
            FinancialFactCandidateSet(
                listOf(
                    fact(unit = "USD", accession = "0000320193-25-000079"),
                    fact(unit = "shares", accession = "0000320193-25-000080", value = "1"),
                ),
            )
        assertEquals(setOf("USD", "shares"), set.candidates.map { it.fact.unit }.toSet())
    }

    @Test
    fun differentFramesArePreserved() {
        val set =
            FinancialFactCandidateSet(
                listOf(
                    fact(frame = "CY2025", accession = "0000320193-25-000079"),
                    fact(frame = null, accession = "0000320193-25-000099"),
                ),
            )
        assertEquals(setOf("CY2025", null), set.candidates.map { it.fact.frame }.toSet())
    }

    @Test
    fun unknownConceptIsNotAutoMapped() {
        val raw = fact(concept = "SomeObscureExtensionMetric")
        assertNull(UsGaapConceptNormalizer.normalize(raw.taxonomy, raw.concept))
        assertNull(FinancialFactVersionCandidate.from(raw, peers = listOf(raw)).normalizedConcept)
    }

    @Test
    fun extensionTaxonomyIsNotMappedToStandard() {
        assertNull(UsGaapConceptNormalizer.normalize("aapl", "NetIncomeLoss"))
        assertNull(UsGaapConceptNormalizer.normalize("us-gaap", "CompanySpecificRevenueExtension"))
    }

    @Test
    fun amendmentAloneIsAmendment() {
        val amendment = fact(form = "10-K/A", accession = "0000320193-25-000099")
        assertEquals(
            FactVersionClassification.AMENDMENT,
            FactVersionClassifier.classify(amendment, peers = listOf(amendment)),
        )
    }

    @Test
    fun originalPlusAmendmentValueDifferenceKeepsAmendmentAsAmendmentWithoutOverwrite() {
        val original = fact(form = "10-K", accession = "0000320193-25-000079", value = "100")
        val amendment = fact(form = "10-K/A", accession = "0000320193-25-000099", value = "95")
        val set = FinancialFactCandidateSet(listOf(original, amendment))
        assertEquals(2, set.candidates.size)
        val byAccn = set.candidates.associateBy { it.fact.accessionNumber }
        assertEquals(
            FactVersionClassification.UNRESOLVED,
            byAccn.getValue(original.accessionNumber).classification,
        )
        assertEquals(
            FactVersionClassification.AMENDMENT,
            byAccn.getValue(amendment.accessionNumber).classification,
        )
        assertEquals(BigDecimal("100"), byAccn.getValue(original.accessionNumber).fact.value)
        assertEquals(BigDecimal("95"), byAccn.getValue(amendment.accessionNumber).fact.value)
        assertTrue(set.candidates.none { it.classification == FactVersionClassification.RESTATEMENT_CANDIDATE })
    }

    @Test
    fun valueDifferenceAloneDoesNotAuthoritativelySelect() {
        val older =
            fact(accession = "0000320193-25-000001", filed = LocalDate.of(2025, 10, 1), value = "1")
        val newer =
            fact(accession = "0000320193-25-000999", filed = LocalDate.of(2025, 12, 1), value = "2")
        val set = FinancialFactCandidateSet(listOf(older, newer))
        val matched =
            set.candidatesFor(
                issuerId = issuer,
                normalizedConcept = NormalizedFinancialConcept.NET_INCOME,
                periodKey = older.period().comparisonKey(),
            )
        assertEquals(2, matched.size)
        assertEquals(
            setOf("0000320193-25-000001", "0000320193-25-000999"),
            matched.map { it.fact.accessionNumber }.toSet(),
        )
        assertTrue(
            FinancialFactCandidateSet::class.java.methods.none {
                it.name.contains("require", ignoreCase = true)
            },
        )
    }

    @Test
    fun candidatesForReturnsCandidateSetOnly() {
        val only = fact(concept = "Assets", start = null, end = LocalDate.of(2025, 9, 30))
        val set = FinancialFactCandidateSet(listOf(only))
        val matched =
            set.candidatesFor(
                issuerId = issuer,
                normalizedConcept = NormalizedFinancialConcept.ASSETS,
                periodKey = only.period().comparisonKey(),
            )
        assertEquals(1, matched.size)
        assertEquals(only.accessionNumber, matched.single().fact.accessionNumber)
        assertTrue(
            FinancialFactCandidateSet::class.java.declaredMethods.none {
                it.name == "requireSingleDeterminedCandidate"
            },
        )
    }

    @Test
    fun filedIsNotConvertedToKnownAt() {
        val raw = fact(filed = LocalDate.of(2025, 11, 1))
        assertEquals(LocalDate.of(2025, 11, 1), raw.filed)
        val names = RawFinancialFact::class.java.methods.map { it.name }.toSet()
        assertFalse(names.any { it.contains("knownAt", ignoreCase = true) })
    }

    @Test
    fun issuerIdIsRetained() {
        assertEquals(IssuerId("issuer-apple"), fact(issuerId = IssuerId("issuer-apple")).issuerId)
    }

    @Test
    fun securityIdIsNotAttachedToRawFact() {
        val fieldNames = RawFinancialFact::class.java.declaredFields.map { it.name }.toSet()
        assertTrue("issuerId" in fieldNames)
        assertFalse(fieldNames.any { it.equals("securityId", ignoreCase = true) })
    }

    @Test
    fun explicitUsGaapMappingsCoverObservedTagsOnly() {
        assertEquals(
            NormalizedFinancialConcept.REVENUE,
            UsGaapConceptNormalizer.normalize(
                "us-gaap",
                "RevenueFromContractWithCustomerExcludingAssessedTax",
            ),
        )
        assertEquals(
            NormalizedFinancialConcept.REVENUE,
            UsGaapConceptNormalizer.normalize("us-gaap", "Revenues"),
        )
        assertEquals(
            NormalizedFinancialConcept.NET_INCOME,
            UsGaapConceptNormalizer.normalize("us-gaap", "NetIncomeLoss"),
        )
        assertEquals(
            NormalizedFinancialConcept.ASSETS,
            UsGaapConceptNormalizer.normalize("us-gaap", "Assets"),
        )
        assertEquals(
            NormalizedFinancialConcept.LIABILITIES,
            UsGaapConceptNormalizer.normalize("us-gaap", "Liabilities"),
        )
        assertEquals(
            NormalizedFinancialConcept.CASH_AND_CASH_EQUIVALENTS,
            UsGaapConceptNormalizer.normalize("us-gaap", "CashAndCashEquivalentsAtCarryingValue"),
        )
    }

    @Test
    fun canonicalAccessionIsAccepted() {
        val raw = fact(accession = "0000320193-25-000079")
        assertEquals("0000320193-25-000079", raw.accessionNumber)
        assertTrue(AccessionNumberFormat.isCanonical(raw.accessionNumber))
        assertEquals("0000320193-25-000079", AccessionNumberFormat.requireCanonical(raw.accessionNumber))
    }

    @Test
    fun leadingWhitespaceAccessionIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            fact(accession = " 0000320193-25-000079")
        }
        assertFailsWith<IllegalArgumentException> {
            AccessionNumberFormat.requireCanonical("\t0000320193-25-000079")
        }
    }

    @Test
    fun trailingWhitespaceAccessionIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            fact(accession = "0000320193-25-000079 ")
        }
        assertFailsWith<IllegalArgumentException> {
            AccessionNumberFormat.requireCanonical("0000320193-25-000079\n")
        }
    }

    @Test
    fun blankAccessionIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            fact(accession = " ")
        }
        assertFailsWith<IllegalArgumentException> {
            AccessionNumberFormat.requireCanonical("")
        }
    }

    @Test
    fun accessionWithoutDashesIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            fact(accession = "000032019325000079")
        }
    }

    @Test
    fun accessionWithWrongDigitCountsIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            fact(accession = "000320193-25-000079")
        }
        assertFailsWith<IllegalArgumentException> {
            fact(accession = "0000320193-5-000079")
        }
        assertFailsWith<IllegalArgumentException> {
            fact(accession = "0000320193-25-00079")
        }
    }

    @Test
    fun accessionWithNonDigitsIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            fact(accession = "000032019X-25-000079")
        }
    }
}
