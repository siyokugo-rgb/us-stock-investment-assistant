package market.poc

import com.sun.net.httpserver.HttpServer
import java.math.BigDecimal
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Alpha Vantage TIME_SERIES_DAILY Fail-Closed / parse tests.
 * Local HttpServer + sanitized fixtures only. No live Alpha Vantage calls.
 * Does not invent historical knownAt or currency. Does not assign SecurityId.
 */
class AlphaVantageDailyFailClosedTest {
    private lateinit var server: HttpServer
    private lateinit var baseUrl: String

    @BeforeTest
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.start()
        baseUrl = "http://127.0.0.1:${server.address.port}"
    }

    @AfterTest
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun validDailyResponseParsesOhlcvAndKeepsTimezone() {
        val json = readResource("market/poc/av-daily-ibm-sanitized.json")
        val series = AlphaVantageDailyParser.parse(json, requestedSymbol = "IBM")
        assertEquals("IBM", series.providerSymbol)
        assertEquals("US/Eastern", series.timeZoneRaw)
        assertEquals("2024-01-25", series.lastRefreshedRaw)
        assertEquals(3, series.bars.size)
        assertEquals(LocalDate.of(2024, 1, 23), series.bars.first().tradingDate)
        assertEquals(LocalDate.of(2024, 1, 25), series.bars.last().tradingDate)
        val latest = series.bars.last()
        assertEquals(BigDecimal("100.1000"), latest.open)
        assertEquals(BigDecimal("101.5000"), latest.high)
        assertEquals(BigDecimal("99.5000"), latest.low)
        assertEquals(BigDecimal("100.7500"), latest.close)
        assertEquals(1_234_567L, latest.volume)
        assertEquals(HistoricalKnownAtStatus.UNRESOLVED_UNUSABLE, series.historicalKnownAtStatus)
        assertFalse(series::class.java.methods.any { it.name.contains("securityId", ignoreCase = true) })
    }

    @Test
    fun zeroPriceIsAcceptedNotRejected() {
        val json = readResource("market/poc/av-daily-ibm-sanitized.json")
        val series = AlphaVantageDailyParser.parse(json, requestedSymbol = "IBM")
        val zero = series.bars.single { it.tradingDate == LocalDate.of(2024, 1, 23) }
        assertEquals(BigDecimal("0.0000"), zero.close)
        assertEquals(0L, zero.volume)
    }

    @Test
    fun tradingDateIsPreservedAsLocalDateKey() {
        val series =
            AlphaVantageDailyParser.parse(
                readResource("market/poc/av-daily-ibm-sanitized.json"),
                requestedSymbol = "IBM",
            )
        assertEquals(
            listOf(
                LocalDate.of(2024, 1, 23),
                LocalDate.of(2024, 1, 24),
                LocalDate.of(2024, 1, 25),
            ),
            series.bars.map { it.tradingDate },
        )
    }

    @Test
    fun providerSymbolIsNotConvertedToSecurityId() {
        val series =
            AlphaVantageDailyParser.parse(
                readResource("market/poc/av-daily-ibm-sanitized.json"),
                requestedSymbol = "IBM",
            )
        val fieldNames = series::class.java.declaredFields.map { it.name }.toSet()
        assertTrue("providerSymbol" in fieldNames)
        assertFalse(fieldNames.any { it.equals("securityId", ignoreCase = true) })
        val barFields = AlphaVantageDailyRawBar::class.java.declaredFields.map { it.name }.toSet()
        assertFalse(barFields.any { it.equals("securityId", ignoreCase = true) })
    }

    @Test
    fun currencyRemainsUnresolvedAndIsNotInferredAsUsd() {
        val series =
            AlphaVantageDailyParser.parse(
                readResource("market/poc/av-daily-ibm-sanitized.json"),
                requestedSymbol = "IBM",
            )
        assertEquals(
            CurrencyResolutionStatus.UNRESOLVED_FROM_TIME_SERIES_DAILY,
            series.currencyResolutionStatus,
        )
        assertEquals(
            HistoricalKnownAtStatus.UNRESOLVED_UNUSABLE,
            series.historicalKnownAtStatus,
        )
        // Raw bar is OHLCV-only: open/high/low/close/volume + tradingDate + providerSymbol.
        // No USD (or any currency) is auto-filled for DailyPrice mapping.
        val sample = series.bars.first()
        assertEquals("IBM", sample.providerSymbol)
        assertTrue(sample.open.signum() >= 0)
    }

    @Test
    fun malformedJsonIsFailClosed() {
        val ex =
            assertFailsWith<AlphaVantagePocException> {
                AlphaVantageDailyParser.parse("{ not-json ", requestedSymbol = "IBM")
            }
        assertTrue(ex.message!!.isNotBlank(), ex.message)
    }

    @Test
    fun emptyBodyIsFailClosedAndDoesNotStampFetchedAt() {
        mountBytes(ByteArray(0), status = 200)
        val clockCalls = AtomicInteger(0)
        val ex =
            assertFailsWith<AlphaVantagePocException> {
                client(clockCalls).fetchDailySeries("IBM")
            }
        assertTrue(ex.message!!.contains("Empty"), ex.message)
        assertEquals(0, clockCalls.get())
    }

    @Test
    fun http4xxIsFailClosedAndDoesNotStampFetchedAt() {
        mountText("nope", status = 404)
        val clockCalls = AtomicInteger(0)
        val ex =
            assertFailsWith<AlphaVantagePocException> {
                client(clockCalls).fetchDailySeries("IBM")
            }
        assertTrue(ex.message!!.contains("404"), ex.message)
        assertEquals(0, clockCalls.get())
    }

    @Test
    fun http5xxIsFailClosedAndDoesNotStampFetchedAt() {
        mountText("error", status = 500)
        val clockCalls = AtomicInteger(0)
        val ex =
            assertFailsWith<AlphaVantagePocException> {
                client(clockCalls).fetchDailySeries("IBM")
            }
        assertTrue(ex.message!!.contains("500"), ex.message)
        assertEquals(0, clockCalls.get())
    }

    @Test
    fun http200ErrorMessageEnvelopeIsFailClosed() {
        mountText("""{"Error Message":"Invalid API call."}""")
        val clockCalls = AtomicInteger(0)
        val ex =
            assertFailsWith<AlphaVantagePocException> {
                client(clockCalls).fetchDailySeries("IBM")
            }
        assertTrue(ex.message!!.contains("Error Message"), ex.message)
        assertEquals(0, clockCalls.get())
    }

    @Test
    fun http200InformationEnvelopeIsFailClosed() {
        mountText("""{"Information":"demo key only. claim free API key"}""")
        val clockCalls = AtomicInteger(0)
        val ex =
            assertFailsWith<AlphaVantagePocException> {
                client(clockCalls).fetchDailySeries("IBM")
            }
        assertTrue(ex.message!!.contains("Information"), ex.message)
        assertEquals(0, clockCalls.get())
    }

    @Test
    fun http200NoteEnvelopeIsFailClosed() {
        mountText("""{"Note":"Thank you for using Alpha Vantage! rate limit"}""")
        val clockCalls = AtomicInteger(0)
        val ex =
            assertFailsWith<AlphaVantagePocException> {
                client(clockCalls).fetchDailySeries("IBM")
            }
        assertTrue(ex.message!!.contains("Note"), ex.message)
        assertEquals(0, clockCalls.get())
    }

    @Test
    fun missingTimeSeriesObjectIsFailClosed() {
        mountText(
            """
            {
              "Meta Data": {
                "1. Information": "Daily Prices",
                "2. Symbol": "IBM",
                "3. Last Refreshed": "2024-01-25",
                "4. Output Size": "Compact",
                "5. Time Zone": "US/Eastern"
              }
            }
            """.trimIndent(),
        )
        val clockCalls = AtomicInteger(0)
        val ex =
            assertFailsWith<AlphaVantagePocException> {
                client(clockCalls).fetchDailySeries("IBM")
            }
        assertTrue(ex.message!!.contains("Time Series"), ex.message)
        assertEquals(0, clockCalls.get())
    }

    @Test
    fun requestedSymbolMismatchIsFailClosed() {
        val json =
            readResource("market/poc/av-daily-ibm-sanitized.json")
                .replace("\"2. Symbol\": \"IBM\"", "\"2. Symbol\": \"MSFT\"")
        mountText(json)
        val clockCalls = AtomicInteger(0)
        val ex =
            assertFailsWith<AlphaVantagePocException> {
                client(clockCalls).fetchDailySeries("IBM")
            }
        assertTrue(ex.message!!.contains("mismatch"), ex.message)
        assertEquals(0, clockCalls.get())
    }

    @Test
    fun malformedOhlcIsFailClosed() {
        val broken =
            readResource("market/poc/av-daily-ibm-sanitized.json")
                .replace("\"100.7500\"", "\"NaN\"")
        val ex =
            assertFailsWith<AlphaVantagePocException> {
                AlphaVantageDailyParser.parse(broken, requestedSymbol = "IBM")
            }
        assertTrue(ex.message!!.contains("close") || ex.message!!.contains("decimal"), ex.message)
    }

    @Test
    fun negativePriceIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            AlphaVantageDailyRawBar(
                providerSymbol = "IBM",
                tradingDate = LocalDate.of(2024, 1, 25),
                open = BigDecimal("-1"),
                high = BigDecimal("2"),
                low = BigDecimal("1"),
                close = BigDecimal("1.5"),
                volume = 1L,
            )
        }
    }

    @Test
    fun negativeVolumeIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            AlphaVantageDailyRawBar(
                providerSymbol = "IBM",
                tradingDate = LocalDate.of(2024, 1, 25),
                open = BigDecimal("1"),
                high = BigDecimal("2"),
                low = BigDecimal("1"),
                close = BigDecimal("1.5"),
                volume = -1L,
            )
        }
    }

    @Test
    fun highLessThanLowIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            AlphaVantageDailyRawBar(
                providerSymbol = "IBM",
                tradingDate = LocalDate.of(2024, 1, 25),
                open = BigDecimal("1"),
                high = BigDecimal("1"),
                low = BigDecimal("2"),
                close = BigDecimal("1"),
                volume = 1L,
            )
        }
    }

    @Test
    fun openOutsideRangeIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            AlphaVantageDailyRawBar(
                providerSymbol = "IBM",
                tradingDate = LocalDate.of(2024, 1, 25),
                open = BigDecimal("3"),
                high = BigDecimal("2"),
                low = BigDecimal("1"),
                close = BigDecimal("1.5"),
                volume = 1L,
            )
        }
    }

    @Test
    fun closeOutsideRangeIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            AlphaVantageDailyRawBar(
                providerSymbol = "IBM",
                tradingDate = LocalDate.of(2024, 1, 25),
                open = BigDecimal("1.5"),
                high = BigDecimal("2"),
                low = BigDecimal("1"),
                close = BigDecimal("3"),
                volume = 1L,
            )
        }
    }

    @Test
    fun successStampsFetchedAtOnlyAfterBodyParseAndSymbolMatch() {
        val json = readResource("market/poc/av-daily-ibm-sanitized.json")
        mountText(json)
        val clockCalls = AtomicInteger(0)
        val expected = Instant.parse("2026-01-01T00:00:00Z")
        val client =
            AlphaVantageDailyPocClient(
                apiKey = "test-key",
                apiKeySource = ApiKeySource.DEMO,
                baseUrl = baseUrl,
                httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                clock = {
                    clockCalls.incrementAndGet()
                    expected
                },
            )
        val result = client.fetchDailySeries("IBM")
        assertEquals(1, clockCalls.get())
        assertEquals(expected, result.evidence.fetchedAt)
        assertEquals(200, result.evidence.httpStatus)
        assertEquals("IBM", result.series.providerSymbol)
        assertEquals(HistoricalKnownAtStatus.UNRESOLVED_UNUSABLE, result.series.historicalKnownAtStatus)
        assertTrue(result.evidence.payloadSha256.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun parseFailureDoesNotStampFetchedAt() {
        mountText("""{"Error Message":"bad"}""")
        val clockCalls = AtomicInteger(0)
        assertFailsWith<AlphaVantagePocException> {
            client(clockCalls).fetchDailySeries("IBM")
        }
        assertEquals(0, clockCalls.get())
    }

    private fun mountText(
        body: String,
        status: Int = 200,
    ) {
        mountBytes(body.toByteArray(StandardCharsets.UTF_8), status)
    }

    private fun mountBytes(
        bytes: ByteArray,
        status: Int = 200,
    ) {
        server.createContext("/query") { exchange ->
            if (status == 204 || bytes.isEmpty() && status == 200) {
                exchange.sendResponseHeaders(status, if (status == 200) 0 else -1)
                exchange.close()
                return@createContext
            }
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun client(clockCalls: AtomicInteger): AlphaVantageDailyPocClient =
        AlphaVantageDailyPocClient(
            apiKey = "test-key",
            apiKeySource = ApiKeySource.DEMO,
            baseUrl = baseUrl,
            httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
            clock = {
                clockCalls.incrementAndGet()
                Instant.parse("2026-01-01T00:00:00Z")
            },
        )

    private fun readResource(path: String): String =
        javaClass.classLoader.getResourceAsStream(path)?.bufferedReader()?.readText()
            ?: error("Missing resource $path")
}
