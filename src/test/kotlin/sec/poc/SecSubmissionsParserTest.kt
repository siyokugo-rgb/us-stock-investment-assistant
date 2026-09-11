package sec.poc

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SecSubmissionsParserTest {
    @Test
    fun parsesAppleFixtureWithoutUsingClockAsKnownAt() {
        val json = readResource("sec/poc/aapl-submissions-sanitized.json")
        val doc = SecSubmissionsParser.parse(json)

        assertEquals(SecCik.parse("0000320193"), doc.cik)
        assertEquals("Apple Inc.", doc.name)
        assertEquals(listOf("AAPL"), doc.tickers)
        assertEquals(listOf("Nasdaq"), doc.exchanges)
        assertTrue(doc.recentFilings.isNotEmpty())
        assertTrue(doc.historyFiles.isNotEmpty())
        assertTrue(doc.observedRecentKeys.contains("acceptanceDateTime"))

        val tenK = doc.recentFilings.first { it.form == "10-K" }
        assertNotNull(tenK.filingDate)
        assertNotNull(tenK.acceptanceDateTime)
        assertTrue(tenK.acceptanceDateTime!!.isBefore(Instant.parse("2099-01-01T00:00:00Z")))
    }

    @Test
    fun alphabetFixtureShowsMultipleTickersUnderOneCik() {
        val doc = SecSubmissionsParser.parse(readResource("sec/poc/alphabet-submissions-sanitized.json"))
        assertEquals(SecCik.parse("0001652044"), doc.cik)
        assertTrue(doc.tickers.containsAll(listOf("GOOGL", "GOOG")))
        assertTrue(doc.tickers.size > 1)
        assertEquals(doc.tickers.size, doc.exchanges.size)
    }

    @Test
    fun treatsAmendmentAsSeparateFilingRow() {
        val json =
            """
            {
              "cik": "0000320193",
              "name": "Apple Inc.",
              "tickers": ["AAPL"],
              "exchanges": ["Nasdaq"],
              "formerNames": [],
              "filings": {
                "recent": {
                  "accessionNumber": ["0001140361-26-015711", "0001140361-26-035325"],
                  "filingDate": ["2026-04-20", "2026-09-01"],
                  "reportDate": ["2026-04-17", "2026-04-17"],
                  "acceptanceDateTime": ["2026-04-20T21:29:51.000Z", "2026-09-01T20:30:35.000Z"],
                  "form": ["8-K", "8-K/A"],
                  "primaryDocument": ["a.htm", "b.htm"],
                  "primaryDocDescription": ["8-K", "8-K/A"],
                  "isXBRL": [0, 0],
                  "isInlineXBRL": [0, 0]
                },
                "files": []
              }
            }
            """.trimIndent()
        val doc = SecSubmissionsParser.parse(json)
        assertEquals(2, doc.recentFilings.size)
        val original = doc.recentFilings[0]
        val amendment = doc.recentFilings[1]
        assertEquals("8-K", original.form)
        assertEquals("8-K/A", amendment.form)
        assertFalse(original.isAmendment)
        assertTrue(amendment.isAmendment)
        assertTrue(original.accessionNumber != amendment.accessionNumber)
        assertTrue(original.acceptanceDateTime != amendment.acceptanceDateTime)
    }

    @Test
    fun rejectsMissingRequiredCik() {
        assertFailsWith<SecEdgarPocException> {
            SecSubmissionsParser.parse(readResource("sec/poc/missing-cik.json"))
        }
    }

    @Test
    fun rejectsMalformedJson() {
        assertFailsWith<SecEdgarPocException> {
            SecSubmissionsParser.parse(readResource("sec/poc/malformed.json"))
        }
    }

    @Test
    fun rejectsInvalidCik() {
        assertFailsWith<SecEdgarPocException> { SecCik.parse("AAPL") }
        assertFailsWith<SecEdgarPocException> { SecCik.parse("") }
        assertFailsWith<SecEdgarPocException> { SecCik.parse("12345678901") }
    }

    @Test
    fun configFailsClosedWithoutUserAgent() {
        assertFailsWith<SecEdgarPocException> {
            SecEdgarPocConfig.fromEnvironment(emptyMap())
        }
        assertFailsWith<SecEdgarPocException> {
            SecEdgarPocConfig.fromEnvironment(mapOf("SEC_EDGAR_USER_AGENT" to "bot example.com"))
        }
    }

    @Test
    fun acceptanceDateTimeIsPartialLowerBoundNotKnownAtProxy() {
        val assessment =
            SecSubmissionsParser.assessTimestampFields()
                .first { it.field == "acceptanceDateTime" }
        assertEquals(PitEvidenceGrade.PARTIAL, assessment.gradeForHistoricalKnownAt)
        assertTrue(assessment.gradeForHistoricalKnownAt != PitEvidenceGrade.CONFIRMED)
        assertTrue(assessment.notes.contains("lower-bound"), assessment.notes)
        assertTrue(
            assessment.notes.contains("PIT unsafe") || assessment.notes.contains("insufficient"),
            assessment.notes,
        )
        assertTrue(assessment.notes.contains("conservative proxy とは呼ばない"), assessment.notes)
        assertTrue(!assessment.notes.contains("conservative proxy 候補"), assessment.notes)
    }

    private fun readResource(path: String): String {
        val stream =
            checkNotNull(javaClass.classLoader.getResourceAsStream(path)) {
                "Missing test resource: $path"
            }
        return stream.bufferedReader().readText()
    }
}
