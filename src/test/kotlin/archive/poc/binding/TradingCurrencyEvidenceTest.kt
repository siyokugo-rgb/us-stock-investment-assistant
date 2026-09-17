package archive.poc.binding

import archive.poc.ArchiveValidationException
import archive.poc.ManifestRecord
import archive.poc.ObservationStatus
import archive.poc.Sha256Hex
import archive.poc.TransportStatus
import archive.poc.massive.MassiveAllTickersArchiveClient
import archive.poc.massive.MassiveDailyAggsArchiveClient
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
 * Synthetic QA for archive-level TradingCurrencyEvidence (PRICE + All Tickers).
 * Fixtures write real temp raw files with exact SHA-256.
 * No live network. No DailyPrice.currency / SecurityId / MIC / bar attribution.
 */
class TradingCurrencyEvidenceTest {
    private lateinit var root: Path

    private val allTickersBody: ByteArray =
        this::class.java.getResourceAsStream(
            "/archive/poc/massive/massive-all-tickers-aapl-sanitized.json",
        )!!.readBytes()

    private val priceBody: ByteArray =
        this::class.java.getResourceAsStream(
            "/archive/poc/massive/massive-aapl-unadjusted-sanitized.json",
        )!!.readBytes()

    private val priceElig = Instant.parse("2026-09-16T09:00:00Z")
    private val allTickersElig = Instant.parse("2026-09-16T11:00:00Z")

    @BeforeTest
    fun setup() {
        root = Files.createTempDirectory("trading-currency-evidence")
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

    private fun allTickersRecord(
        ticker: String? = "AAPL",
        active: Boolean? = null,
        date: String? = null,
        status: ObservationStatus = ObservationStatus.OBSERVED,
        eligibility: Instant? = allTickersElig,
        body: ByteArray = allTickersBody,
        source: String = MassiveAllTickersArchiveClient.SOURCE,
        domain: String = MassiveAllTickersArchiveClient.DOMAIN,
        requestKey: String? = null,
        archiveId: String = "all-tickers-1",
        rawHashOverride: String? = null,
        omitRaw: Boolean = false,
        deleteRawAfterWrite: Boolean = false,
        httpStatus: Int? = 200,
    ): ManifestRecord {
        val key =
            requestKey
                ?: MassiveAllTickersArchiveClient.requestKey(
                    ticker = ticker,
                    active = active,
                    date = date,
                )
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
            attemptedAt = Instant.parse("2026-09-16T10:59:00Z"),
            attemptFinishedAt = Instant.parse("2026-09-16T10:59:30Z"),
            fetchedAt =
                if (status == ObservationStatus.PROVIDER_FAILURE) {
                    null
                } else {
                    Instant.parse("2026-09-16T10:59:30Z")
                },
            ingestedAt = Instant.parse("2026-09-16T11:00:00Z"),
            rawPayloadHash = hash,
            rawPayloadUri = path?.toString(),
            httpStatus = if (status != ObservationStatus.PROVIDER_FAILURE) httpStatus else null,
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

    private fun mutateAllTickers(transform: (String) -> String): ByteArray =
        transform(String(allTickersBody, StandardCharsets.UTF_8)).toByteArray(StandardCharsets.UTF_8)

    @Test
    fun validPriceAndAllTickersUsdIsCandidate() {
        val evidence =
            TradingCurrencyEvidenceDeriver.derive(
                priceRecord(),
                allTickersRecord(),
            )
        assertEquals(TradingCurrencyEvidenceStatus.CANDIDATE, evidence.status)
        assertNull(evidence.reason)
        assertEquals("AAPL", evidence.providerTicker)
        assertEquals("USD", evidence.rawCurrencySymbol)
        assertEquals("USD", evidence.canonicalCurrencyCode)
        assertEquals(allTickersElig, evidence.evidenceEligibleAt)
        assertEquals(
            TradingCurrencyTemporalApplicability.UNRESOLVED,
            evidence.temporalApplicability,
        )
        assertEquals(TradingCurrencyEvidence.PROVIDER_MASSIVE, evidence.provider)
    }

    @Test
    fun providerTickerComesFromPriceRequestKeyOnly() {
        val evidence =
            TradingCurrencyEvidenceDeriver.derive(
                priceRecord(ticker = "MSFT"),
                allTickersRecord(
                    ticker = "MSFT",
                    body =
                        mutateAllTickers {
                            it.replace("\"ticker\": \"AAPL\"", "\"ticker\": \"MSFT\"")
                        },
                ),
            )
        assertEquals(TradingCurrencyEvidenceStatus.CANDIDATE, evidence.status)
        assertEquals("MSFT", evidence.providerTicker)
        assertTrue(
            MassiveDailyAggsArchiveClient.requestKey("MSFT", "2024-01-02", "2024-01-10")
                .contains("MSFT"),
        )
    }

    @Test
    fun priceNotObservedIsIneligible() {
        val evidence =
            TradingCurrencyEvidenceDeriver.derive(
                priceRecord(status = ObservationStatus.REJECTED_VALIDATION, eligibility = null),
                allTickersRecord(),
            )
        assertEquals(TradingCurrencyEvidenceStatus.INELIGIBLE, evidence.status)
        assertEquals(TradingCurrencyEvidenceReason.PRICE_NOT_OBSERVED, evidence.reason)
    }

    @Test
    fun allTickersNotObservedIsIneligible() {
        val evidence =
            TradingCurrencyEvidenceDeriver.derive(
                priceRecord(),
                allTickersRecord(
                    status = ObservationStatus.REJECTED_VALIDATION,
                    eligibility = null,
                ),
            )
        assertEquals(TradingCurrencyEvidenceStatus.INELIGIBLE, evidence.status)
        assertEquals(TradingCurrencyEvidenceReason.ALL_TICKERS_NOT_OBSERVED, evidence.reason)
    }

    @Test
    fun observedManifestForbidsNullEligibility() {
        // ManifestRecord invariant: OBSERVED requires eligibilityBoundaryAt.
        // PRICE_ELIGIBILITY_MISSING / ALL_TICKERS_ELIGIBILITY_MISSING remain defensive reasons.
        assertFailsWith<IllegalArgumentException> {
            priceRecord(eligibility = null)
        }
        assertFailsWith<IllegalArgumentException> {
            allTickersRecord(eligibility = null)
        }
    }

    @Test
    fun wrongPriceSourceIsIneligible() {
        val evidence =
            TradingCurrencyEvidenceDeriver.derive(
                priceRecord(source = "massive.stocks.wrong"),
                allTickersRecord(),
            )
        assertEquals(TradingCurrencyEvidenceStatus.INELIGIBLE, evidence.status)
        assertEquals(TradingCurrencyEvidenceReason.SOURCE_MISMATCH, evidence.reason)
    }

    @Test
    fun wrongAllTickersSourceIsIneligible() {
        val evidence =
            TradingCurrencyEvidenceDeriver.derive(
                priceRecord(),
                allTickersRecord(source = "massive.stocks.ticker_overview"),
            )
        assertEquals(TradingCurrencyEvidenceStatus.INELIGIBLE, evidence.status)
        assertEquals(TradingCurrencyEvidenceReason.SOURCE_MISMATCH, evidence.reason)
    }

    @Test
    fun malformedPriceRequestKeyFailsClosed() {
        assertFailsWith<ArchiveValidationException> {
            TradingCurrencyEvidenceDeriver.derive(
                priceRecord(requestKey = "NOT-A-VALID-KEY"),
                allTickersRecord(),
            )
        }
    }

    @Test
    fun malformedAllTickersRequestKeyIsIneligible() {
        val evidence =
            TradingCurrencyEvidenceDeriver.derive(
                priceRecord(),
                allTickersRecord(requestKey = "GET|/v3/reference/tickers|broken"),
            )
        assertEquals(TradingCurrencyEvidenceStatus.INELIGIBLE, evidence.status)
        assertEquals(
            TradingCurrencyEvidenceReason.ALL_TICKERS_REQUEST_KEY_INVALID,
            evidence.reason,
        )
    }

    @Test
    fun tickerMismatchIsIneligible() {
        val evidence =
            TradingCurrencyEvidenceDeriver.derive(
                priceRecord(ticker = "AAPL"),
                allTickersRecord(ticker = "MSFT"),
            )
        assertEquals(TradingCurrencyEvidenceStatus.INELIGIBLE, evidence.status)
        assertEquals(TradingCurrencyEvidenceReason.TICKER_MISMATCH, evidence.reason)
    }

    @Test
    fun priceRawMissingFailsClosed() {
        assertFailsWith<ArchiveValidationException> {
            TradingCurrencyEvidenceDeriver.derive(
                priceRecord(deleteRawAfterWrite = true),
                allTickersRecord(),
            )
        }
    }

    @Test
    fun priceRawHashMismatchFailsClosed() {
        assertFailsWith<ArchiveValidationException> {
            TradingCurrencyEvidenceDeriver.derive(
                priceRecord(rawHashOverride = "0".repeat(64)),
                allTickersRecord(),
            )
        }
    }

    @Test
    fun allTickersRawMissingFailsClosed() {
        assertFailsWith<ArchiveValidationException> {
            TradingCurrencyEvidenceDeriver.derive(
                priceRecord(),
                allTickersRecord(deleteRawAfterWrite = true),
            )
        }
    }

    @Test
    fun allTickersRawHashMismatchFailsClosed() {
        assertFailsWith<ArchiveValidationException> {
            TradingCurrencyEvidenceDeriver.derive(
                priceRecord(),
                allTickersRecord(rawHashOverride = "0".repeat(64)),
            )
        }
    }

    @Test
    fun tickerFilterOmittedIsIneligible() {
        val evidence =
            TradingCurrencyEvidenceDeriver.derive(
                priceRecord(),
                allTickersRecord(ticker = null),
            )
        assertEquals(TradingCurrencyEvidenceStatus.INELIGIBLE, evidence.status)
        assertEquals(TradingCurrencyEvidenceReason.SOURCE_MISMATCH, evidence.reason)
        assertNull(
            MassiveAllTickersArchiveClient.requestKey(ticker = null).let { key ->
                // requestKey without ticker= segment
                if (key.contains("|ticker=")) "has" else null
            },
        )
    }

    @Test
    fun currencySymbolMissingIsCurrencyMissing() {
        val body =
            mutateAllTickers {
                it.replace("\"currency_symbol\": \"USD\",", "")
            }
        val evidence =
            TradingCurrencyEvidenceDeriver.derive(
                priceRecord(),
                allTickersRecord(body = body),
            )
        assertEquals(TradingCurrencyEvidenceStatus.INELIGIBLE, evidence.status)
        assertEquals(TradingCurrencyEvidenceReason.CURRENCY_MISSING, evidence.reason)
    }

    @Test
    fun lowercaseUsdIsCurrencyCodeInvalidNoRepair() {
        val body =
            mutateAllTickers {
                it.replace("\"currency_symbol\": \"USD\"", "\"currency_symbol\": \"usd\"")
            }
        val evidence =
            TradingCurrencyEvidenceDeriver.derive(
                priceRecord(),
                allTickersRecord(body = body),
            )
        assertEquals(TradingCurrencyEvidenceStatus.INELIGIBLE, evidence.status)
        assertEquals(TradingCurrencyEvidenceReason.CURRENCY_CODE_INVALID, evidence.reason)
        assertEquals("usd", evidence.rawCurrencySymbol)
        assertNull(evidence.canonicalCurrencyCode)
    }

    @Test
    fun usDollarNameIsCurrencyCodeInvalid() {
        val body =
            mutateAllTickers {
                it.replace("\"currency_symbol\": \"USD\"", "\"currency_symbol\": \"US Dollar\"")
            }
        val evidence =
            TradingCurrencyEvidenceDeriver.derive(
                priceRecord(),
                allTickersRecord(body = body),
            )
        assertEquals(TradingCurrencyEvidenceStatus.INELIGIBLE, evidence.status)
        assertEquals(TradingCurrencyEvidenceReason.CURRENCY_CODE_INVALID, evidence.reason)
    }

    @Test
    fun numeric840IsCurrencyCodeInvalid() {
        val body =
            mutateAllTickers {
                it.replace("\"currency_symbol\": \"USD\"", "\"currency_symbol\": \"840\"")
            }
        val evidence =
            TradingCurrencyEvidenceDeriver.derive(
                priceRecord(),
                allTickersRecord(body = body),
            )
        assertEquals(TradingCurrencyEvidenceStatus.INELIGIBLE, evidence.status)
        assertEquals(TradingCurrencyEvidenceReason.CURRENCY_CODE_INVALID, evidence.reason)
    }

    @Test
    fun threeLetterNonIsoMemberIsCurrencyCodeInvalid() {
        val body =
            mutateAllTickers {
                it.replace("\"currency_symbol\": \"USD\"", "\"currency_symbol\": \"ABC\"")
            }
        val evidence =
            TradingCurrencyEvidenceDeriver.derive(
                priceRecord(),
                allTickersRecord(body = body),
            )
        assertEquals(TradingCurrencyEvidenceStatus.INELIGIBLE, evidence.status)
        assertEquals(TradingCurrencyEvidenceReason.CURRENCY_CODE_INVALID, evidence.reason)
        assertEquals("ABC", evidence.rawCurrencySymbol)
        assertNull(evidence.canonicalCurrencyCode)
    }

    @Test
    fun canonicalEqualsRawOnCandidate() {
        val evidence =
            TradingCurrencyEvidenceDeriver.derive(priceRecord(), allTickersRecord())
        assertEquals(evidence.rawCurrencySymbol, evidence.canonicalCurrencyCode)
    }

    @Test
    fun evidenceEligibleAtIsMaxOfPriceAndAllTickers() {
        val early = Instant.parse("2026-09-16T08:00:00Z")
        val late = Instant.parse("2026-09-16T12:00:00Z")
        val a =
            TradingCurrencyEvidenceDeriver.derive(
                priceRecord(eligibility = early),
                allTickersRecord(eligibility = late),
            )
        assertEquals(late, a.evidenceEligibleAt)
        val b =
            TradingCurrencyEvidenceDeriver.derive(
                priceRecord(eligibility = late),
                allTickersRecord(eligibility = early),
            )
        assertEquals(late, b.evidenceEligibleAt)
    }

    @Test
    fun allTickersRequestDateIsPreservedAndDoesNotBackdateEligibility() {
        val requestDate = "2024-06-01"
        val evidence =
            TradingCurrencyEvidenceDeriver.derive(
                priceRecord(),
                allTickersRecord(date = requestDate),
            )
        assertEquals(TradingCurrencyEvidenceStatus.CANDIDATE, evidence.status)
        assertEquals(requestDate, evidence.allTickersRequestDate)
        assertEquals(allTickersElig, evidence.evidenceEligibleAt)
        assertFalse(evidence.evidenceEligibleAt.toString().contains("2024-06-01"))
    }

    @Test
    fun temporalApplicabilityUnresolvedOnCandidate() {
        val evidence =
            TradingCurrencyEvidenceDeriver.derive(priceRecord(), allTickersRecord())
        assertEquals(
            TradingCurrencyTemporalApplicability.UNRESOLVED,
            evidence.temporalApplicability,
        )
    }

    @Test
    fun doesNotGenerateDailyPriceCurrencySecurityIdOrMic() {
        val evidence =
            TradingCurrencyEvidenceDeriver.derive(priceRecord(), allTickersRecord())
        val text = evidence.toString()
        assertFalse(text.contains("DailyPrice", ignoreCase = true))
        assertFalse(text.contains("securityId", ignoreCase = true))
        assertFalse(text.contains("\"mic\"", ignoreCase = true))
        assertNotNull(evidence.canonicalCurrencyCode)
        // Model is evidence only — no barTimestamp / tradingDate fields.
        assertFalse(text.contains("barTimestamp", ignoreCase = true))
        assertFalse(text.contains("tradingDate", ignoreCase = true))
        assertFalse(text.contains("knownAt", ignoreCase = true))
    }
}
