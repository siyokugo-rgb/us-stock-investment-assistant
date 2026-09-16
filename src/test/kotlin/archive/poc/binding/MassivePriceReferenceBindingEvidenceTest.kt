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
 * Fixtures write real temp raw files with exact SHA-256 — no /tmp dummy paths.
 * No live network. No SecurityId / DailyPrice / knownAt / MIC / bar attribution.
 */
class MassivePriceReferenceBindingEvidenceTest {
    private lateinit var root: Path

    private val overviewBody: ByteArray =
        this::class.java.getResourceAsStream(
            "/archive/poc/massive/massive-ticker-overview-aapl-sanitized.json",
        )!!.readBytes()

    private val priceBody: ByteArray =
        this::class.java.getResourceAsStream(
            "/archive/poc/massive/massive-aapl-unadjusted-sanitized.json",
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

    private fun writeRaw(
        domain: String,
        source: String,
        archiveId: String,
        body: ByteArray,
    ): Path {
        val dir = root.resolve(domain).resolve(source).resolve("raw")
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
        body: ByteArray = priceBody,
        rawHashOverride: String? = null,
        deleteRawAfterWrite: Boolean = false,
        omitRaw: Boolean = false,
    ): ManifestRecord {
        val key =
            requestKey
                ?: MassiveDailyAggsArchiveClient.requestKey(ticker, from, to)
        val path =
            if (!omitRaw && status != ObservationStatus.PROVIDER_FAILURE) {
                writeRaw(domain, source, archiveId, body)
            } else {
                null
            }
        if (deleteRawAfterWrite && path != null) {
            Files.delete(path)
        }
        val hash =
            when {
                omitRaw || status == ObservationStatus.PROVIDER_FAILURE -> null
                rawHashOverride != null -> rawHashOverride
                else -> Sha256Hex.of(body)
            }
        return ManifestRecord(
            archiveId = archiveId,
            domain = domain,
            source = source,
            requestKey = key,
            attemptedAt = Instant.parse("2026-09-16T08:59:00Z"),
            attemptFinishedAt = Instant.parse("2026-09-16T08:59:30Z"),
            fetchedAt =
                if (status == ObservationStatus.PROVIDER_FAILURE) {
                    null
                } else {
                    Instant.parse("2026-09-16T08:59:30Z")
                },
            ingestedAt = Instant.parse("2026-09-16T09:00:00Z"),
            rawPayloadHash = hash,
            rawPayloadUri = path?.toString(),
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
        deleteRawAfterWrite: Boolean = false,
        httpStatus: Int? = 200,
    ): ManifestRecord {
        val key =
            requestKey
                ?: MassiveTickerOverviewArchiveClient.requestKey(ticker, date)
        val path =
            if (!omitRaw && status != ObservationStatus.PROVIDER_FAILURE) {
                writeRaw(domain, source, archiveId, body)
            } else {
                null
            }
        if (deleteRawAfterWrite && path != null) {
            Files.delete(path)
        }
        val hash =
            when {
                omitRaw || status == ObservationStatus.PROVIDER_FAILURE -> null
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
                if (status == ObservationStatus.PROVIDER_FAILURE) {
                    null
                } else {
                    Instant.parse("2026-09-16T09:59:30Z")
                },
            ingestedAt = Instant.parse("2026-09-16T10:00:00Z"),
            rawPayloadHash = hash,
            rawPayloadUri = path?.toString(),
            httpStatus = httpStatus,
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

    @Test
    fun validObservedPairWithOnDiskRawsIsCandidate() {
        val price = priceRecord()
        val overview = overviewRecord()
        assertTrue(Files.isRegularFile(Path.of(price.rawPayloadUri!!)))
        assertTrue(Files.isRegularFile(Path.of(overview.rawPayloadUri!!)))
        assertEquals(Sha256Hex.of(priceBody), price.rawPayloadHash)
        assertEquals(Sha256Hex.of(overviewBody), overview.rawPayloadHash)

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
    fun priceRawFileMissingFailsClosed() {
        assertFailsWith<ArchiveValidationException> {
            MassivePriceReferenceBindingEvidenceDeriver.derive(
                priceRecord(deleteRawAfterWrite = true),
                overviewRecord(),
            )
        }
    }

    @Test
    fun priceRawHashMismatchFailsClosed() {
        assertFailsWith<ArchiveValidationException> {
            MassivePriceReferenceBindingEvidenceDeriver.derive(
                priceRecord(rawHashOverride = "b".repeat(64)),
                overviewRecord(),
            )
        }
    }

    @Test
    fun overviewRawFileMissingFailsClosed() {
        assertFailsWith<ArchiveValidationException> {
            MassivePriceReferenceBindingEvidenceDeriver.derive(
                priceRecord(),
                overviewRecord(deleteRawAfterWrite = true),
            )
        }
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
    fun deriveApiHasNoCallerBytesBypassParameter() {
        // Production path is derive(price, overview) only — no overviewResponseBytes.
        val method =
            MassivePriceReferenceBindingEvidenceDeriver::class.java.methods
                .filter { it.name == "derive" }
        assertEquals(1, method.size)
        assertEquals(2, method.single().parameterCount)
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
    fun missingOverviewRawUriWhenNotObservedIsIneligible() {
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
        assertNotNull(evidence.currencyName)
        assertEquals(
            MassiveReferenceTemporalApplicability.UNRESOLVED,
            evidence.referenceTemporalApplicability,
        )
    }
}
