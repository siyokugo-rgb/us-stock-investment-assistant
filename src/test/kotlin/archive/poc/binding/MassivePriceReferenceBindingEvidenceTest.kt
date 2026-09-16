package archive.poc.binding

import archive.poc.ArchiveValidationException
import archive.poc.ManifestRecord
import archive.poc.ObservationStatus
import archive.poc.Sha256Hex
import archive.poc.TransportStatus
import archive.poc.massive.MassiveDailyAggsArchiveClient
import archive.poc.massive.MassiveDailyAggsRequestKey
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Synthetic QA for Massive PRICE ↔ Overview archive-level binding evidence.
 * No live network. No SecurityId / DailyPrice / knownAt / MIC / bar attribution.
 */
class MassivePriceReferenceBindingEvidenceTest {
    private lateinit var root: Path

    private val overviewBody: ByteArray =
        this::class.java.getResourceAsStream(
            "/archive/poc/massive/massive-ticker-overview-aapl-sanitized.json",
        )!!.readBytes()

    private val priceElig = Instant.parse("2026-09-16T09:00:00Z")
    private val overviewElig = Instant.parse("2026-09-16T10:00:00Z")

    @BeforeTest
    fun setup() {
        root = Files.createTempDirectory("massive-price-overview-binding")
    }

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    private fun writeOverviewRaw(
        archiveId: String,
        body: ByteArray = overviewBody,
    ): Path {
        val dir =
            root.resolve(MassiveTickerOverviewArchiveClient.DOMAIN)
                .resolve(MassiveTickerOverviewArchiveClient.SOURCE)
                .resolve("raw")
        Files.createDirectories(dir)
        val path = dir.resolve("$archiveId.raw")
        Files.write(path, body)
        return path
    }

    private fun priceRecord(
        ticker: String = "AAPL",
        status: ObservationStatus = ObservationStatus.OBSERVED,
        eligibility: Instant? = priceElig,
        from: String = "2024-01-02",
        to: String = "2024-01-10",
        source: String = MassiveDailyAggsArchiveClient.SOURCE,
        domain: String = MassiveDailyAggsArchiveClient.DOMAIN,
        requestKey: String? = null,
        archiveId: String = "price-1",
    ): ManifestRecord {
        val key =
            requestKey
                ?: MassiveDailyAggsArchiveClient.requestKey(ticker, from, to)
        return ManifestRecord(
            archiveId = archiveId,
            domain = domain,
            source = source,
            requestKey = key,
            attemptedAt = Instant.parse("2026-09-16T08:59:00Z"),
            attemptFinishedAt = Instant.parse("2026-09-16T08:59:30Z"),
            fetchedAt =
                if (status == ObservationStatus.OBSERVED || status == ObservationStatus.REJECTED_VALIDATION) {
                    Instant.parse("2026-09-16T08:59:30Z")
                } else {
                    null
                },
            ingestedAt = Instant.parse("2026-09-16T09:00:00Z"),
            rawPayloadHash = if (status != ObservationStatus.PROVIDER_FAILURE) "a".repeat(64) else null,
            rawPayloadUri = if (status != ObservationStatus.PROVIDER_FAILURE) "/tmp/unused-price.raw" else null,
            httpStatus = if (status != ObservationStatus.PROVIDER_FAILURE) 200 else null,
            transportStatus =
                if (status == ObservationStatus.PROVIDER_FAILURE) {
                    TransportStatus.TRANSPORT_FAILURE
                } else {
                    TransportStatus.HTTP_RESPONSE
                },
            observationStatus = status,
            eligibilityBoundaryAt = eligibility,
        )
    }

    private fun overviewRecord(
        ticker: String = "AAPL",
        date: String? = null,
        status: ObservationStatus = ObservationStatus.OBSERVED,
        eligibility: Instant? = overviewElig,
        body: ByteArray = overviewBody,
        source: String = MassiveTickerOverviewArchiveClient.SOURCE,
        domain: String = MassiveTickerOverviewArchiveClient.DOMAIN,
        requestKey: String? = null,
        archiveId: String = "overview-1",
        rawHashOverride: String? = null,
        omitRaw: Boolean = false,
        httpStatus: Int? = 200,
    ): ManifestRecord {
        val key =
            requestKey
                ?: MassiveTickerOverviewArchiveClient.requestKey(ticker, date)
        val path = if (!omitRaw) writeOverviewRaw(archiveId, body) else null
        val hash =
            when {
                omitRaw -> null
                rawHashOverride != null -> rawHashOverride
                else -> Sha256Hex.of(body)
            }
        return ManifestRecord(
            archiveId = archiveId,
            domain = domain,
            source = source,
            requestKey = key,
            attemptedAt = Instant.parse("2026-09-16T09:59:00Z"),
            attemptFinishedAt = Instant.parse("2026-09-16T09:59:30Z"),
            fetchedAt =
                if (omitRaw && status == ObservationStatus.PROVIDER_FAILURE) {
                    null
                } else {
                    Instant.parse("2026-09-16T09:59:30Z")
                },
            ingestedAt = Instant.parse("2026-09-16T10:00:00Z"),
            rawPayloadHash = hash,
            rawPayloadUri = path?.toString(),
            httpStatus = httpStatus,
            transportStatus =
                if (status == ObservationStatus.PROVIDER_FAILURE && omitRaw) {
                    TransportStatus.TRANSPORT_FAILURE
                } else {
                    TransportStatus.HTTP_RESPONSE
                },
            observationStatus = status,
            eligibilityBoundaryAt = eligibility,
        )
    }

    @Test
    fun validObservedPairIsCandidateWithRequestKeyDerivedTicker() {
        val price = priceRecord()
        val overview = overviewRecord()
        val evidence =
            MassivePriceReferenceBindingEvidenceDeriver.derive(price, overview)
        assertEquals(MassivePriceReferenceBindingStatus.CANDIDATE, evidence.status)
        assertNull(evidence.reason)
        assertEquals("AAPL", evidence.providerTicker)
        assertEquals(
            MassiveDailyAggsRequestKey.parseOrThrow(price.requestKey).ticker,
            evidence.providerTicker,
        )
        assertEquals("Massive", evidence.provider)
        assertEquals(priceElig, evidence.priceEligibilityBoundaryAt)
        assertEquals(overviewElig, evidence.overviewEligibilityBoundaryAt)
        assertEquals(overviewElig, evidence.bindingEligibleAt)
        assertNull(evidence.overviewRequestDate)
        assertEquals("usd", evidence.currencyName)
        assertEquals("XNAS", evidence.primaryExchange)
        assertEquals("BBG000B9XRY4", evidence.compositeFigi)
        assertEquals("BBG001S5N8V8", evidence.shareClassFigi)
        assertEquals(true, evidence.active)
        assertEquals(
            MassiveReferenceTemporalApplicability.UNRESOLVED,
            evidence.referenceTemporalApplicability,
        )
    }

    @Test
    fun priceNotObservedIsIneligible() {
        val evidence =
            MassivePriceReferenceBindingEvidenceDeriver.derive(
                priceRecord(status = ObservationStatus.REJECTED_VALIDATION, eligibility = null),
                overviewRecord(),
            )
        assertEquals(MassivePriceReferenceBindingStatus.INELIGIBLE, evidence.status)
        assertEquals(MassivePriceReferenceBindingReason.PRICE_NOT_OBSERVED, evidence.reason)
    }

    @Test
    fun overviewNotObservedIsIneligible() {
        val evidence =
            MassivePriceReferenceBindingEvidenceDeriver.derive(
                priceRecord(),
                overviewRecord(
                    status = ObservationStatus.REJECTED_VALIDATION,
                    eligibility = null,
                ),
            )
        assertEquals(MassivePriceReferenceBindingStatus.INELIGIBLE, evidence.status)
        assertEquals(MassivePriceReferenceBindingReason.OVERVIEW_NOT_OBSERVED, evidence.reason)
    }

    @Test
    fun priceEligibilityMissingReasonExistsForDefenseInDepth() {
        // ManifestRecord forbids OBSERVED + null eligibility; deriver still checks defensively.
        assertEquals(
            "PRICE_ELIGIBILITY_MISSING",
            MassivePriceReferenceBindingReason.PRICE_ELIGIBILITY_MISSING.name,
        )
    }

    @Test
    fun overviewEligibilityMissingReasonExistsForDefenseInDepth() {
        assertEquals(
            "OVERVIEW_ELIGIBILITY_MISSING",
            MassivePriceReferenceBindingReason.OVERVIEW_ELIGIBILITY_MISSING.name,
        )
    }

    @Test
    fun wrongPriceSourceIsIneligible() {
        val evidence =
            MassivePriceReferenceBindingEvidenceDeriver.derive(
                priceRecord(source = "alphavantage.time_series_daily.raw"),
                overviewRecord(),
            )
        assertEquals(MassivePriceReferenceBindingReason.SOURCE_MISMATCH, evidence.reason)
    }

    @Test
    fun wrongOverviewSourceIsIneligible() {
        val evidence =
            MassivePriceReferenceBindingEvidenceDeriver.derive(
                priceRecord(),
                overviewRecord(source = "massive.stocks.other_reference"),
            )
        assertEquals(MassivePriceReferenceBindingReason.SOURCE_MISMATCH, evidence.reason)
    }

    @Test
    fun missingOverviewRawUriIsIneligible() {
        val evidence =
            MassivePriceReferenceBindingEvidenceDeriver.derive(
                priceRecord(),
                overviewRecord(
                    omitRaw = true,
                    status = ObservationStatus.PROVIDER_FAILURE,
                    eligibility = null,
                    httpStatus = null,
                ),
            )
        assertEquals(MassivePriceReferenceBindingReason.OVERVIEW_NOT_OBSERVED, evidence.reason)
    }

    @Test
    fun malformedPriceRequestKeyFailsClosed() {
        assertFailsWith<ArchiveValidationException> {
            MassivePriceReferenceBindingEvidenceDeriver.derive(
                priceRecord(requestKey = "GET|/not-a-massive-key"),
                overviewRecord(),
            )
        }
    }

    @Test
    fun malformedOverviewRequestKeyIsIneligible() {
        val evidence =
            MassivePriceReferenceBindingEvidenceDeriver.derive(
                priceRecord(),
                overviewRecord(requestKey = "GET|/v3/reference/tickers/AAPL|bogus"),
            )
        assertEquals(
            MassivePriceReferenceBindingReason.OVERVIEW_REQUEST_KEY_INVALID,
            evidence.reason,
        )
    }

    @Test
    fun tickerMismatchIsIneligible() {
        val evidence =
            MassivePriceReferenceBindingEvidenceDeriver.derive(
                priceRecord(ticker = "AAPL"),
                overviewRecord(ticker = "MSFT"),
            )
        assertEquals(MassivePriceReferenceBindingReason.TICKER_MISMATCH, evidence.reason)
    }

    @Test
    fun overviewRawHashMismatchFailsClosed() {
        assertFailsWith<ArchiveValidationException> {
            MassivePriceReferenceBindingEvidenceDeriver.derive(
                priceRecord(),
                overviewRecord(rawHashOverride = "b".repeat(64)),
            )
        }
    }

    @Test
    fun overviewRawValidationFailureIsIneligible() {
        val bad =
            """{"status":"ERROR","error":"nope"}""".toByteArray(StandardCharsets.UTF_8)
        val evidence =
            MassivePriceReferenceBindingEvidenceDeriver.derive(
                priceRecord(),
                overviewRecord(body = bad),
            )
        assertEquals(MassivePriceReferenceBindingReason.OVERVIEW_RAW_INVALID, evidence.reason)
    }

    @Test
    fun overviewDate2024KeepsBindingEligibleAt2026AndTemporalUnresolved() {
        val evidence =
            MassivePriceReferenceBindingEvidenceDeriver.derive(
                priceRecord(),
                overviewRecord(date = "2024-06-01"),
            )
        assertEquals(MassivePriceReferenceBindingStatus.CANDIDATE, evidence.status)
        assertEquals("2024-06-01", evidence.overviewRequestDate)
        assertEquals(overviewElig, evidence.bindingEligibleAt)
        assertTrue(evidence.bindingEligibleAt!!.toString().startsWith("2026-09-16"))
        assertFalse(evidence.bindingEligibleAt.toString().contains("2024-06-01"))
        assertEquals(
            MassiveReferenceTemporalApplicability.UNRESOLVED,
            evidence.referenceTemporalApplicability,
        )
        // Carried raw fields remain evidence only — not PRICE adoption.
        assertEquals("usd", evidence.currencyName)
    }

    @Test
    fun dateOmittedYieldsNullOverviewRequestDate() {
        val evidence =
            MassivePriceReferenceBindingEvidenceDeriver.derive(priceRecord(), overviewRecord())
        assertNull(evidence.overviewRequestDate)
    }

    @Test
    fun bindingEligibleAtIsMaxOfPriceAndOverview() {
        val evidence =
            MassivePriceReferenceBindingEvidenceDeriver.derive(
                priceRecord(eligibility = Instant.parse("2026-09-16T11:00:00Z")),
                overviewRecord(eligibility = Instant.parse("2026-09-16T10:00:00Z")),
            )
        assertEquals(Instant.parse("2026-09-16T11:00:00Z"), evidence.bindingEligibleAt)
    }

    @Test
    fun doesNotGenerateSecurityIdDailyPriceKnownAtMicOrBarFields() {
        val evidence =
            MassivePriceReferenceBindingEvidenceDeriver.derive(priceRecord(), overviewRecord())
        val text = evidence.toString()
        assertFalse(text.contains("SecurityId", ignoreCase = true) && text.contains("securityId="))
        assertFalse(text.contains("DailyPrice"))
        assertFalse(text.contains("knownAt"))
        assertFalse(text.contains("tradingDate"))
        assertFalse(text.contains("barTimestamp"))
        // Model has no MIC/SecurityId/DailyPrice properties — spot-check carried fields only.
        assertNotNull(evidence.currencyName)
        assertEquals(
            MassiveReferenceTemporalApplicability.UNRESOLVED,
            evidence.referenceTemporalApplicability,
        )
    }
}
