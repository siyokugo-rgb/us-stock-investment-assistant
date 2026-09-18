package archive.poc.binding

import archive.poc.ArchiveValidationException
import archive.poc.ManifestRecord
import archive.poc.ObservationStatus
import archive.poc.Sha256Hex
import archive.poc.TransportStatus
import archive.poc.massive.MassiveAllTickersArchiveClient
import archive.poc.massive.MassiveTickerOverviewArchiveClient
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Synthetic-first QA for [SecurityIdentityContinuityEvidenceDeriver].
 * Does not claim real multi-as-of provider validation PASS.
 * No SecurityId / SecurityIdentifier / knownAt / validity generation.
 */
class SecurityIdentityContinuityEvidenceTest {
    private lateinit var root: Path

    private val eligT1 = Instant.parse("2026-09-10T10:00:00Z")
    private val eligT2 = Instant.parse("2026-09-12T12:00:00Z")

    @BeforeTest
    fun setup() {
        root = Files.createTempDirectory("security-identity-continuity")
    }

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun sameTickerSameShareClassDifferentAsOfIsContinuityCandidate() {
        val t1 =
            overviewRecord(
                archiveId = "ov-t1",
                ticker = "AAPL",
                date = "2024-01-02",
                eligibility = eligT1,
                shareClass = "BBG001S5N8V8",
                composite = "BBG000B9XRY4",
            )
        val t2 =
            overviewRecord(
                archiveId = "ov-t2",
                ticker = "AAPL",
                date = "2024-06-01",
                eligibility = eligT2,
                shareClass = "BBG001S5N8V8",
                composite = "BBG000B9XRY4",
            )
        val evidence = SecurityIdentityContinuityEvidenceDeriver.derive(t2, t1)
        assertEquals(SecurityIdentityContinuityStatus.CONTINUITY_CANDIDATE, evidence.status)
        assertNull(evidence.reason)
        assertEquals("ov-t1", evidence.earlierArchiveId)
        assertEquals("ov-t2", evidence.laterArchiveId)
        assertEquals("2024-01-02", evidence.earlierProviderAsOfDate)
        assertEquals("2024-06-01", evidence.laterProviderAsOfDate)
        assertEquals(eligT2, evidence.evidenceEligibleAt)
    }

    @Test
    fun tickerChangeSameShareClassIsTickerChangeCandidate() {
        val t1 =
            overviewRecord(
                archiveId = "ov-old",
                ticker = "OLD",
                date = "2023-01-01",
                eligibility = eligT1,
                shareClass = "BBGSHARE1",
                composite = "BBGCOMP1",
            )
        val t2 =
            overviewRecord(
                archiveId = "ov-new",
                ticker = "NEW",
                date = "2024-01-01",
                eligibility = eligT2,
                shareClass = "BBGSHARE1",
                composite = "BBGCOMP1",
            )
        val evidence = SecurityIdentityContinuityEvidenceDeriver.derive(t1, t2)
        assertEquals(SecurityIdentityContinuityStatus.TICKER_CHANGE_CANDIDATE, evidence.status)
        assertEquals("OLD", evidence.earlierProviderTicker)
        assertEquals("NEW", evidence.laterProviderTicker)
    }

    @Test
    fun sameTickerDifferentShareClassIsRecycleCandidate() {
        val t1 =
            overviewRecord(
                archiveId = "ov-a",
                ticker = "RECY",
                date = "2020-01-01",
                eligibility = eligT1,
                shareClass = "BBGSHARE_A",
                composite = "BBGCOMP_A",
            )
        val t2 =
            overviewRecord(
                archiveId = "ov-b",
                ticker = "RECY",
                date = "2024-01-01",
                eligibility = eligT2,
                shareClass = "BBGSHARE_B",
                composite = "BBGCOMP_B",
            )
        val evidence = SecurityIdentityContinuityEvidenceDeriver.derive(t1, t2)
        assertEquals(SecurityIdentityContinuityStatus.RECYCLE_CANDIDATE, evidence.status)
    }

    @Test
    fun shareClassMissingIsUnresolved() {
        val t1 =
            overviewRecord(
                archiveId = "ov-a",
                ticker = "AAPL",
                date = "2024-01-02",
                eligibility = eligT1,
                shareClass = "BBG001S5N8V8",
                omitShareClass = false,
            )
        val t2 =
            overviewRecord(
                archiveId = "ov-b",
                ticker = "AAPL",
                date = "2024-06-01",
                eligibility = eligT2,
                omitShareClass = true,
            )
        val evidence = SecurityIdentityContinuityEvidenceDeriver.derive(t1, t2)
        assertEquals(SecurityIdentityContinuityStatus.UNRESOLVED, evidence.status)
        assertEquals(
            SecurityIdentityContinuityReason.SHARE_CLASS_IDENTITY_MISSING,
            evidence.reason,
        )
    }

    @Test
    fun dateOmittedIsUnresolvedWithoutIngestOrdering() {
        val t1 =
            overviewRecord(
                archiveId = "ov-dated",
                ticker = "AAPL",
                date = "2024-01-02",
                eligibility = eligT1,
                shareClass = "BBG001S5N8V8",
            )
        val t2 =
            overviewRecord(
                archiveId = "ov-nodate",
                ticker = "AAPL",
                date = null,
                eligibility = eligT2,
                shareClass = "BBG001S5N8V8",
            )
        val evidence = SecurityIdentityContinuityEvidenceDeriver.derive(t1, t2)
        assertEquals(SecurityIdentityContinuityStatus.UNRESOLVED, evidence.status)
        assertEquals(
            SecurityIdentityContinuityReason.PROVIDER_AS_OF_MISSING,
            evidence.reason,
        )
    }

    @Test
    fun sameAsOfSameIdentityDoesNotElevateToContinuity() {
        val a =
            overviewRecord(
                archiveId = "ov-1",
                ticker = "AAPL",
                date = "2024-06-01",
                eligibility = eligT1,
                shareClass = "BBG001S5N8V8",
            )
        val b =
            overviewRecord(
                archiveId = "ov-2",
                ticker = "AAPL",
                date = "2024-06-01",
                eligibility = eligT2,
                shareClass = "BBG001S5N8V8",
            )
        val evidence = SecurityIdentityContinuityEvidenceDeriver.derive(a, b)
        assertEquals(SecurityIdentityContinuityStatus.UNRESOLVED, evidence.status)
        assertEquals(
            SecurityIdentityContinuityReason.SAME_AS_OF_NOT_CROSS_TIME,
            evidence.reason,
        )
    }

    @Test
    fun sameAsOfConflictingShareClassIsConflict() {
        val a =
            overviewRecord(
                archiveId = "ov-1",
                ticker = "AAPL",
                date = "2024-06-01",
                eligibility = eligT1,
                shareClass = "BBGSHARE_A",
                composite = "BBGCOMP_X",
            )
        val b =
            overviewRecord(
                archiveId = "ov-2",
                ticker = "AAPL",
                date = "2024-06-01",
                eligibility = eligT2,
                shareClass = "BBGSHARE_B",
                composite = "BBGCOMP_Y",
            )
        val evidence = SecurityIdentityContinuityEvidenceDeriver.derive(a, b)
        assertEquals(SecurityIdentityContinuityStatus.CONFLICT, evidence.status)
        assertEquals(
            SecurityIdentityContinuityReason.IDENTITY_LAYER_CONFLICT,
            evidence.reason,
        )
    }

    @Test
    fun sameCompositeDifferentShareClassIsConflictNotContinuityOrRecycle() {
        val t1 =
            overviewRecord(
                archiveId = "ov-a",
                ticker = "AAPL",
                date = "2024-01-02",
                eligibility = eligT1,
                shareClass = "BBGSHARE_A",
                composite = "BBGCOMP_SAME",
            )
        val t2 =
            overviewRecord(
                archiveId = "ov-b",
                ticker = "AAPL",
                date = "2024-06-01",
                eligibility = eligT2,
                shareClass = "BBGSHARE_B",
                composite = "BBGCOMP_SAME",
            )
        val evidence = SecurityIdentityContinuityEvidenceDeriver.derive(t1, t2)
        assertEquals(SecurityIdentityContinuityStatus.CONFLICT, evidence.status)
        assertEquals(
            SecurityIdentityContinuityReason.IDENTITY_LAYER_CONFLICT,
            evidence.reason,
        )
    }

    @Test
    fun differentTickerDifferentShareClassIsUnresolvedNotRecycle() {
        val t1 =
            overviewRecord(
                archiveId = "ov-a",
                ticker = "AAA",
                date = "2024-01-02",
                eligibility = eligT1,
                shareClass = "BBGSHARE_A",
            )
        val t2 =
            overviewRecord(
                archiveId = "ov-b",
                ticker = "BBB",
                date = "2024-06-01",
                eligibility = eligT2,
                shareClass = "BBGSHARE_B",
            )
        val evidence = SecurityIdentityContinuityEvidenceDeriver.derive(t1, t2)
        assertEquals(SecurityIdentityContinuityStatus.UNRESOLVED, evidence.status)
        assertEquals(
            SecurityIdentityContinuityReason.SNAPSHOT_AMBIGUOUS,
            evidence.reason,
        )
    }

    @Test
    fun primaryExchangeChangeAloneDoesNotBreakContinuityCandidate() {
        val t1 =
            overviewRecord(
                archiveId = "ov-a",
                ticker = "AAPL",
                date = "2024-01-02",
                eligibility = eligT1,
                shareClass = "BBG001S5N8V8",
                primaryExchange = "XNAS",
            )
        val t2 =
            overviewRecord(
                archiveId = "ov-b",
                ticker = "AAPL",
                date = "2024-06-01",
                eligibility = eligT2,
                shareClass = "BBG001S5N8V8",
                primaryExchange = "XNYS",
            )
        val evidence = SecurityIdentityContinuityEvidenceDeriver.derive(t1, t2)
        assertEquals(SecurityIdentityContinuityStatus.CONTINUITY_CANDIDATE, evidence.status)
        assertEquals("XNAS", evidence.earlierPrimaryExchange)
        assertEquals("XNYS", evidence.laterPrimaryExchange)
    }

    @Test
    fun nonObservedInputIsUnresolved() {
        val t1 =
            overviewRecord(
                archiveId = "ov-ok",
                ticker = "AAPL",
                date = "2024-01-02",
                eligibility = eligT1,
                shareClass = "BBG001S5N8V8",
            )
        val t2 =
            overviewRecord(
                archiveId = "ov-rej",
                ticker = "AAPL",
                date = "2024-06-01",
                eligibility = null,
                shareClass = "BBG001S5N8V8",
                status = ObservationStatus.REJECTED_VALIDATION,
            )
        val evidence = SecurityIdentityContinuityEvidenceDeriver.derive(t1, t2)
        assertEquals(SecurityIdentityContinuityStatus.UNRESOLVED, evidence.status)
        assertEquals(
            SecurityIdentityContinuityReason.INPUT_NOT_OBSERVED,
            evidence.reason,
        )
    }

    @Test
    fun rawShaMismatchFailsClosed() {
        val t1 =
            overviewRecord(
                archiveId = "ov-a",
                ticker = "AAPL",
                date = "2024-01-02",
                eligibility = eligT1,
                shareClass = "BBG001S5N8V8",
            )
        val t2 =
            overviewRecord(
                archiveId = "ov-b",
                ticker = "AAPL",
                date = "2024-06-01",
                eligibility = eligT2,
                shareClass = "BBG001S5N8V8",
                rawHashOverride = "0".repeat(64),
            )
        assertFailsWith<ArchiveValidationException> {
            SecurityIdentityContinuityEvidenceDeriver.derive(t1, t2)
        }
    }

    @Test
    fun rawMissingFailsClosed() {
        val t1 =
            overviewRecord(
                archiveId = "ov-a",
                ticker = "AAPL",
                date = "2024-01-02",
                eligibility = eligT1,
                shareClass = "BBG001S5N8V8",
            )
        val t2 =
            overviewRecord(
                archiveId = "ov-b",
                ticker = "AAPL",
                date = "2024-06-01",
                eligibility = eligT2,
                shareClass = "BBG001S5N8V8",
                deleteRawAfterWrite = true,
            )
        assertFailsWith<ArchiveValidationException> {
            SecurityIdentityContinuityEvidenceDeriver.derive(t1, t2)
        }
    }

    @Test
    fun malformedRequestKeyFailsClosed() {
        val t1 =
            overviewRecord(
                archiveId = "ov-a",
                ticker = "AAPL",
                date = "2024-01-02",
                eligibility = eligT1,
                shareClass = "BBG001S5N8V8",
            )
        val t2 =
            overviewRecord(
                archiveId = "ov-b",
                ticker = "AAPL",
                date = "2024-06-01",
                eligibility = eligT2,
                shareClass = "BBG001S5N8V8",
                requestKeyOverride = "GET|/v3/reference/tickers/AAPL|date=bad",
            )
        assertFailsWith<ArchiveValidationException> {
            SecurityIdentityContinuityEvidenceDeriver.derive(t1, t2)
        }
    }

    @Test
    fun overviewSourcePairHappyPath() {
        val t1 =
            overviewRecord(
                archiveId = "ov-a",
                ticker = "AAPL",
                date = "2024-01-02",
                eligibility = eligT1,
                shareClass = "BBG001S5N8V8",
            )
        val t2 =
            overviewRecord(
                archiveId = "ov-b",
                ticker = "AAPL",
                date = "2024-06-01",
                eligibility = eligT2,
                shareClass = "BBG001S5N8V8",
            )
        val evidence = SecurityIdentityContinuityEvidenceDeriver.derive(t1, t2)
        assertEquals(MassiveTickerOverviewArchiveClient.SOURCE, evidence.source)
        assertEquals(SecurityIdentityContinuityStatus.CONTINUITY_CANDIDATE, evidence.status)
    }

    @Test
    fun allTickersSourcePairHappyPathWithTickerFilterAndSingleResult() {
        val t1 =
            allTickersRecord(
                archiveId = "at-a",
                ticker = "AAPL",
                date = "2024-01-02",
                eligibility = eligT1,
                shareClass = "BBG001S5N8V8",
            )
        val t2 =
            allTickersRecord(
                archiveId = "at-b",
                ticker = "AAPL",
                date = "2024-06-01",
                eligibility = eligT2,
                shareClass = "BBG001S5N8V8",
            )
        val evidence = SecurityIdentityContinuityEvidenceDeriver.derive(t1, t2)
        assertEquals(MassiveAllTickersArchiveClient.SOURCE, evidence.source)
        assertEquals(SecurityIdentityContinuityStatus.CONTINUITY_CANDIDATE, evidence.status)
    }

    @Test
    fun allTickersWithoutTickerFilterIsUnresolved() {
        val t1 =
            allTickersRecord(
                archiveId = "at-a",
                ticker = null,
                date = "2024-01-02",
                eligibility = eligT1,
                shareClass = "BBG001S5N8V8",
                bodyTicker = "AAPL",
            )
        val t2 =
            allTickersRecord(
                archiveId = "at-b",
                ticker = "AAPL",
                date = "2024-06-01",
                eligibility = eligT2,
                shareClass = "BBG001S5N8V8",
            )
        val evidence = SecurityIdentityContinuityEvidenceDeriver.derive(t1, t2)
        assertEquals(SecurityIdentityContinuityStatus.UNRESOLVED, evidence.status)
        assertEquals(
            SecurityIdentityContinuityReason.SNAPSHOT_AMBIGUOUS,
            evidence.reason,
        )
    }

    @Test
    fun allTickersMultiRowIsUnresolved() {
        // Two rows both matching ticker filter → validator OK, resultCount=2 → SNAPSHOT_AMBIGUOUS
        val multi =
            allTickersBody(
                rows =
                    listOf(
                        AllTickersRow("AAPL", "BBGSHARE1", "BBGCOMP1", "XNAS"),
                        AllTickersRow("AAPL", "BBGSHARE1", "BBGCOMP1", "XNAS"),
                    ),
            )
        val t1 =
            allTickersRecord(
                archiveId = "at-multi",
                ticker = "AAPL",
                date = "2024-01-02",
                eligibility = eligT1,
                bodyOverride = multi,
            )
        val t2 =
            allTickersRecord(
                archiveId = "at-b",
                ticker = "AAPL",
                date = "2024-06-01",
                eligibility = eligT2,
                shareClass = "BBG001S5N8V8",
            )
        val evidence = SecurityIdentityContinuityEvidenceDeriver.derive(t1, t2)
        assertEquals(SecurityIdentityContinuityStatus.UNRESOLVED, evidence.status)
        assertEquals(
            SecurityIdentityContinuityReason.SNAPSHOT_AMBIGUOUS,
            evidence.reason,
        )
    }

    @Test
    fun argumentOrderDoesNotChangeProviderAsOfEarlierLater() {
        val t1 =
            overviewRecord(
                archiveId = "ov-early",
                ticker = "AAPL",
                date = "2024-01-02",
                eligibility = eligT1,
                shareClass = "BBG001S5N8V8",
            )
        val t2 =
            overviewRecord(
                archiveId = "ov-late",
                ticker = "AAPL",
                date = "2024-06-01",
                eligibility = eligT2,
                shareClass = "BBG001S5N8V8",
            )
        val ab = SecurityIdentityContinuityEvidenceDeriver.derive(t1, t2)
        val ba = SecurityIdentityContinuityEvidenceDeriver.derive(t2, t1)
        assertEquals(ab.earlierArchiveId, ba.earlierArchiveId)
        assertEquals(ab.laterArchiveId, ba.laterArchiveId)
        assertEquals(ab.earlierProviderAsOfDate, ba.earlierProviderAsOfDate)
        assertEquals(ab.laterProviderAsOfDate, ba.laterProviderAsOfDate)
        assertEquals(ab.status, ba.status)
    }

    @Test
    fun evidenceEligibleAtIsMaxOfInputEligibility() {
        val earlyElig = Instant.parse("2026-01-01T00:00:00Z")
        val lateElig = Instant.parse("2026-06-01T00:00:00Z")
        val t1 =
            overviewRecord(
                archiveId = "ov-a",
                ticker = "AAPL",
                date = "2024-01-02",
                eligibility = lateElig,
                shareClass = "BBG001S5N8V8",
            )
        val t2 =
            overviewRecord(
                archiveId = "ov-b",
                ticker = "AAPL",
                date = "2024-06-01",
                eligibility = earlyElig,
                shareClass = "BBG001S5N8V8",
            )
        val evidence = SecurityIdentityContinuityEvidenceDeriver.derive(t1, t2)
        assertEquals(lateElig, evidence.evidenceEligibleAt)
    }

    @Test
    fun crossSourceOverviewVsAllTickersIsUnresolved() {
        val ov =
            overviewRecord(
                archiveId = "ov-a",
                ticker = "AAPL",
                date = "2024-01-02",
                eligibility = eligT1,
                shareClass = "BBG001S5N8V8",
            )
        val at =
            allTickersRecord(
                archiveId = "at-b",
                ticker = "AAPL",
                date = "2024-06-01",
                eligibility = eligT2,
                shareClass = "BBG001S5N8V8",
            )
        val evidence = SecurityIdentityContinuityEvidenceDeriver.derive(ov, at)
        assertEquals(SecurityIdentityContinuityStatus.UNRESOLVED, evidence.status)
        assertEquals(
            SecurityIdentityContinuityReason.SOURCE_PAIR_MISMATCH,
            evidence.reason,
        )
    }

    @Test
    fun noSecurityIdSecurityIdentifierKnownAtOrValidityArtifactsInModelSurface() {
        val t1 =
            overviewRecord(
                archiveId = "ov-a",
                ticker = "AAPL",
                date = "2024-01-02",
                eligibility = eligT1,
                shareClass = "BBG001S5N8V8",
            )
        val t2 =
            overviewRecord(
                archiveId = "ov-b",
                ticker = "AAPL",
                date = "2024-06-01",
                eligibility = eligT2,
                shareClass = "BBG001S5N8V8",
            )
        val evidence = SecurityIdentityContinuityEvidenceDeriver.derive(t1, t2)
        val text = evidence.toString()
        assertFalse(text.contains("SecurityId", ignoreCase = false))
        assertFalse(text.contains("SecurityIdentifier"))
        assertFalse(text.contains("knownAt", ignoreCase = true))
        assertFalse(text.contains("validFrom", ignoreCase = true))
        assertFalse(text.contains("validTo", ignoreCase = true))
        assertFalse(text.contains("DailyPrice"))
        // Field names must not include forbidden domain artifacts
        val names = SecurityIdentityContinuityEvidence::class.java.declaredFields.map { it.name }
        assertFalse(names.any { it.contains("securityId", ignoreCase = true) })
        assertFalse(names.any { it.contains("knownAt", ignoreCase = true) })
        assertFalse(names.any { it.contains("validFrom", ignoreCase = true) })
        assertFalse(names.any { it.contains("validTo", ignoreCase = true) })
    }

    // --- helpers ---

    private data class AllTickersRow(
        val ticker: String,
        val shareClass: String?,
        val composite: String?,
        val primaryExchange: String?,
    )

    private fun overviewBody(
        ticker: String,
        shareClass: String?,
        composite: String?,
        primaryExchange: String?,
        omitShareClass: Boolean,
    ): ByteArray {
        val sc =
            if (omitShareClass) {
                ""
            } else if (shareClass == null) {
                ""
            } else {
                """,
      "share_class_figi": "$shareClass""""
            }
        val comp =
            if (composite == null) {
                ""
            } else {
                """,
      "composite_figi": "$composite""""
            }
        val px =
            if (primaryExchange == null) {
                ""
            } else {
                """,
      "primary_exchange": "$primaryExchange""""
            }
        val json =
            """
            {
              "request_id": "synthetic-overview",
              "results": {
                "active": true,
                "ticker": "$ticker",
                "name": "Synthetic",
                "market": "stocks",
                "locale": "us",
                "type": "CS",
                "currency_name": "usd"$px$comp$sc
              },
              "status": "OK"
            }
            """.trimIndent()
        return json.toByteArray(StandardCharsets.UTF_8)
    }

    private fun allTickersBody(
        ticker: String = "AAPL",
        shareClass: String? = "BBG001S5N8V8",
        composite: String? = "BBG000B9XRY4",
        primaryExchange: String? = "XNAS",
        rows: List<AllTickersRow>? = null,
    ): ByteArray {
        val effectiveRows =
            rows
                ?: listOf(AllTickersRow(ticker, shareClass, composite, primaryExchange))
        val rowJson =
            effectiveRows.joinToString(",") { row ->
                val sc =
                    row.shareClass?.let { """, "share_class_figi": "$it"""" } ?: ""
                val comp =
                    row.composite?.let { """, "composite_figi": "$it"""" } ?: ""
                val px =
                    row.primaryExchange?.let { """, "primary_exchange": "$it"""" } ?: ""
                """
                {
                  "active": true,
                  "ticker": "${row.ticker}",
                  "name": "Synthetic",
                  "market": "stocks",
                  "locale": "us",
                  "type": "CS",
                  "currency_name": "usd",
                  "currency_symbol": "USD"$px$comp$sc
                }
                """.trimIndent()
            }
        val json =
            """
            {
              "count": ${effectiveRows.size},
              "request_id": "synthetic-all-tickers",
              "results": [$rowJson],
              "status": "OK"
            }
            """.trimIndent()
        return json.toByteArray(StandardCharsets.UTF_8)
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

    private fun overviewRecord(
        archiveId: String,
        ticker: String,
        date: String?,
        eligibility: Instant?,
        shareClass: String? = "BBG001S5N8V8",
        composite: String? = "BBG000B9XRY4",
        primaryExchange: String? = "XNAS",
        omitShareClass: Boolean = false,
        status: ObservationStatus = ObservationStatus.OBSERVED,
        rawHashOverride: String? = null,
        deleteRawAfterWrite: Boolean = false,
        requestKeyOverride: String? = null,
    ): ManifestRecord {
        val body =
            overviewBody(ticker, shareClass, composite, primaryExchange, omitShareClass)
        val key =
            requestKeyOverride
                ?: MassiveTickerOverviewArchiveClient.requestKey(ticker, date)
        val path =
            if (status == ObservationStatus.OBSERVED) {
                writeRaw(MassiveTickerOverviewArchiveClient.SOURCE, archiveId, body)
            } else {
                null
            }
        if (deleteRawAfterWrite && path != null) {
            Files.delete(path)
        }
        val hash =
            when {
                status != ObservationStatus.OBSERVED -> null
                rawHashOverride != null -> rawHashOverride
                else -> Sha256Hex.of(body)
            }
        return ManifestRecord(
            archiveId = archiveId,
            domain = MassiveTickerOverviewArchiveClient.DOMAIN,
            source = MassiveTickerOverviewArchiveClient.SOURCE,
            requestKey = key,
            attemptedAt = Instant.parse("2026-09-10T09:00:00Z"),
            attemptFinishedAt = Instant.parse("2026-09-10T09:00:30Z"),
            fetchedAt =
                if (status == ObservationStatus.OBSERVED) {
                    Instant.parse("2026-09-10T09:00:30Z")
                } else {
                    Instant.parse("2026-09-10T09:00:30Z")
                },
            ingestedAt = Instant.parse("2026-09-10T09:01:00Z"),
            rawPayloadHash = hash,
            rawPayloadUri = path?.toString(),
            httpStatus = 200,
            transportStatus = TransportStatus.HTTP_RESPONSE,
            observationStatus = status,
            eligibilityBoundaryAt = eligibility,
        )
    }

    private fun allTickersRecord(
        archiveId: String,
        ticker: String?,
        date: String?,
        eligibility: Instant?,
        shareClass: String? = "BBG001S5N8V8",
        composite: String? = "BBG000B9XRY4",
        primaryExchange: String? = "XNAS",
        bodyTicker: String? = null,
        bodyOverride: ByteArray? = null,
        status: ObservationStatus = ObservationStatus.OBSERVED,
    ): ManifestRecord {
        val rowTicker = bodyTicker ?: ticker ?: "AAPL"
        val body =
            bodyOverride
                ?: allTickersBody(rowTicker, shareClass, composite, primaryExchange)
        val key =
            MassiveAllTickersArchiveClient.requestKey(
                ticker = ticker,
                date = date,
                limit = 1,
            )
        val path = writeRaw(MassiveAllTickersArchiveClient.SOURCE, archiveId, body)
        return ManifestRecord(
            archiveId = archiveId,
            domain = MassiveAllTickersArchiveClient.DOMAIN,
            source = MassiveAllTickersArchiveClient.SOURCE,
            requestKey = key,
            attemptedAt = Instant.parse("2026-09-10T09:00:00Z"),
            attemptFinishedAt = Instant.parse("2026-09-10T09:00:30Z"),
            fetchedAt = Instant.parse("2026-09-10T09:00:30Z"),
            ingestedAt = Instant.parse("2026-09-10T09:01:00Z"),
            rawPayloadHash = Sha256Hex.of(body),
            rawPayloadUri = path.toString(),
            httpStatus = 200,
            transportStatus = TransportStatus.HTTP_RESPONSE,
            observationStatus = status,
            eligibilityBoundaryAt = eligibility,
        )
    }
}
