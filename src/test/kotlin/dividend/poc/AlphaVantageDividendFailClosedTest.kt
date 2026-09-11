package dividend.poc

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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Alpha Vantage DIVIDENDS Fail-Closed / parse tests.
 * Local HttpServer + sanitized fixtures only. No live Alpha Vantage calls.
 * Does not invent historical knownAt / currency / REGULAR. Does not assign SecurityId.
 */
class AlphaVantageDividendFailClosedTest {
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
    fun validDividendResponseParsesEventsAndKeepsAbsentOptionalDates() {
        val series =
            AlphaVantageDividendParser.parse(
                readResource("dividend/poc/av-dividends-ibm-sanitized.json"),
                requestedSymbol = "IBM",
            )
        assertEquals("IBM", series.providerSymbol)
        assertEquals(5, series.events.size)
        val newest = series.events.first()
        assertEquals(LocalDate.of(2024, 8, 9), newest.exDividendDate)
        assertEquals(LocalDate.of(2024, 7, 29), newest.declarationDate)
        assertEquals(LocalDate.of(2024, 8, 12), newest.recordDate)
        assertEquals(LocalDate.of(2024, 9, 10), newest.paymentDate)
        assertEquals(BigDecimal("1.67"), newest.amount)

        val withNone = series.events[1]
        assertEquals(LocalDate.of(2024, 5, 9), withNone.exDividendDate)
        assertNull(withNone.declarationDate)
        assertNull(withNone.recordDate)
        assertNull(withNone.paymentDate)
        assertEquals("None", withNone.declarationDateRaw)

        val zero = series.events[2]
        assertEquals(BigDecimal("0.0000"), zero.amount)

        assertEquals(HistoricalKnownAtStatus.UNRESOLVED_UNUSABLE, series.historicalKnownAtStatus)
        assertEquals(CurrencyResolutionStatus.UNRESOLVED_FROM_DIVIDENDS, series.currencyResolutionStatus)
        assertEquals(DividendTypeResolutionStatus.UNRESOLVED_FROM_DIVIDENDS, series.dividendTypeResolutionStatus)
        assertEquals(SecurityIdMappingStatus.FORBIDDEN_FROM_PROVIDER_SYMBOL, series.securityIdMappingStatus)
    }

    @Test
    fun duplicateExDatesAreNotCollapsed() {
        val series =
            AlphaVantageDividendParser.parse(
                readResource("dividend/poc/av-dividends-ibm-sanitized.json"),
                requestedSymbol = "IBM",
            )
        val sameEx = series.events.filter { it.exDividendDate == LocalDate.of(2023, 11, 9) }
        assertEquals(2, sameEx.size)
        assertEquals(BigDecimal("1.66"), sameEx[0].amount)
        assertEquals(BigDecimal("1.67"), sameEx[1].amount)
        assertEquals(LocalDate.of(2023, 10, 30), sameEx[0].declarationDate)
        assertEquals(LocalDate.of(2023, 10, 31), sameEx[1].declarationDate)
    }

    @Test
    fun providerSymbolIsNotConvertedToSecurityId() {
        val series =
            AlphaVantageDividendParser.parse(
                readResource("dividend/poc/av-dividends-ibm-sanitized.json"),
                requestedSymbol = "IBM",
            )
        val fieldNames = series::class.java.declaredFields.map { it.name }.toSet()
        assertTrue("providerSymbol" in fieldNames)
        assertFalse(fieldNames.any { it.equals("securityId", ignoreCase = true) })
        val eventFields = AlphaVantageDividendRawEvent::class.java.declaredFields.map { it.name }.toSet()
        assertFalse(eventFields.any { it.equals("securityId", ignoreCase = true) })
        assertFalse(eventFields.any { it.equals("currency", ignoreCase = true) })
        assertFalse(eventFields.any { it.equals("dividendType", ignoreCase = true) })
        assertFalse(eventFields.any { it.equals("knownAt", ignoreCase = true) })
    }

    @Test
    fun currencyAndTypeRemainUnresolvedWithoutInference() {
        val series =
            AlphaVantageDividendParser.parse(
                readResource("dividend/poc/av-dividends-ibm-sanitized.json"),
                requestedSymbol = "IBM",
            )
        assertEquals(CurrencyResolutionStatus.UNRESOLVED_FROM_DIVIDENDS, series.currencyResolutionStatus)
        assertEquals(DividendTypeResolutionStatus.UNRESOLVED_FROM_DIVIDENDS, series.dividendTypeResolutionStatus)
        assertEquals(HistoricalKnownAtStatus.UNRESOLVED_UNUSABLE, series.historicalKnownAtStatus)
        assertEquals("IBM", series.events.first().providerSymbol)
    }

    @Test
    fun confirmedNoneStringSentinelBecomesNullForOptionalDates() {
        val series =
            AlphaVantageDividendParser.parse(
                singleEventJson(declaration = "\"None\"", record = "\"None\"", payment = "\"None\""),
                requestedSymbol = "IBM",
            )
        val event = series.events.single()
        assertNull(event.declarationDate)
        assertNull(event.recordDate)
        assertNull(event.paymentDate)
        assertEquals("None", event.declarationDateRaw)
        assertTrue(AlphaVantageDividendParser.isAbsentSentinel("None"))
    }

    @Test
    fun jsonNullOptionalDatesBecomeNull() {
        val series =
            AlphaVantageDividendParser.parse(
                singleEventJson(declaration = "null", record = "null", payment = "null"),
                requestedSymbol = "IBM",
            )
        val event = series.events.single()
        assertNull(event.declarationDate)
        assertNull(event.recordDate)
        assertNull(event.paymentDate)
        assertNull(event.declarationDateRaw)
    }

    @Test
    fun absentOptionalDateFieldsBecomeNull() {
        val json =
            """
            {
              "symbol": "IBM",
              "data": [
                {
                  "ex_dividend_date": "2024-08-09",
                  "amount": "1.67"
                }
              ]
            }
            """.trimIndent()
        val series = AlphaVantageDividendParser.parse(json, requestedSymbol = "IBM")
        val event = series.events.single()
        assertNull(event.declarationDate)
        assertNull(event.recordDate)
        assertNull(event.paymentDate)
        assertNull(event.declarationDateRaw)
    }

    @Test
    fun unconfirmedNaSentinelIsFailClosed() {
        val ex =
            assertFailsWith<AlphaVantageDividendPocException> {
                AlphaVantageDividendParser.parse(
                    singleEventJson(declaration = "\"N/A\""),
                    requestedSymbol = "IBM",
                )
            }
        assertTrue(ex.message!!.contains("declaration_date"), ex.message)
        assertFalse(AlphaVantageDividendParser.isAbsentSentinel("N/A"))
    }

    @Test
    fun unconfirmedZeroDateSentinelIsFailClosed() {
        val ex =
            assertFailsWith<AlphaVantageDividendPocException> {
                AlphaVantageDividendParser.parse(
                    singleEventJson(record = "\"0000-00-00\""),
                    requestedSymbol = "IBM",
                )
            }
        assertTrue(ex.message!!.contains("record_date"), ex.message)
        assertFalse(AlphaVantageDividendParser.isAbsentSentinel("0000-00-00"))
    }

    @Test
    fun emptyStringOptionalDateIsFailClosed() {
        val ex =
            assertFailsWith<AlphaVantageDividendPocException> {
                AlphaVantageDividendParser.parse(
                    singleEventJson(payment = "\"\""),
                    requestedSymbol = "IBM",
                )
            }
        assertTrue(ex.message!!.contains("payment_date"), ex.message)
        assertFalse(AlphaVantageDividendParser.isAbsentSentinel(""))
    }

    @Test
    fun unconfirmedLiteralNullStringIsFailClosed() {
        val ex =
            assertFailsWith<AlphaVantageDividendPocException> {
                AlphaVantageDividendParser.parse(
                    singleEventJson(declaration = "\"null\""),
                    requestedSymbol = "IBM",
                )
            }
        assertTrue(ex.message!!.contains("declaration_date"), ex.message)
        assertFalse(AlphaVantageDividendParser.isAbsentSentinel("null"))
    }

    @Test
    fun malformedJsonIsFailClosed() {
        val ex =
            assertFailsWith<AlphaVantageDividendPocException> {
                AlphaVantageDividendParser.parse("{ not-json ", requestedSymbol = "IBM")
            }
        assertTrue(ex.message!!.isNotBlank(), ex.message)
    }

    @Test
    fun emptyBodyIsFailClosedAndDoesNotStampFetchedAt() {
        mountBytes(ByteArray(0), status = 200)
        val clockCalls = AtomicInteger(0)
        val ex =
            assertFailsWith<AlphaVantageDividendPocException> {
                client(clockCalls).fetchDividendSeries("IBM")
            }
        assertTrue(ex.message!!.contains("Empty"), ex.message)
        assertEquals(0, clockCalls.get())
    }

    @Test
    fun http4xxIsFailClosedAndDoesNotStampFetchedAt() {
        mountText("nope", status = 404)
        val clockCalls = AtomicInteger(0)
        val ex =
            assertFailsWith<AlphaVantageDividendPocException> {
                client(clockCalls).fetchDividendSeries("IBM")
            }
        assertTrue(ex.message!!.contains("404"), ex.message)
        assertEquals(0, clockCalls.get())
    }

    @Test
    fun http5xxIsFailClosedAndDoesNotStampFetchedAt() {
        mountText("error", status = 500)
        val clockCalls = AtomicInteger(0)
        val ex =
            assertFailsWith<AlphaVantageDividendPocException> {
                client(clockCalls).fetchDividendSeries("IBM")
            }
        assertTrue(ex.message!!.contains("500"), ex.message)
        assertEquals(0, clockCalls.get())
    }

    @Test
    fun http200ErrorMessageEnvelopeIsFailClosed() {
        mountText("""{"Error Message":"Invalid API call."}""")
        val clockCalls = AtomicInteger(0)
        val ex =
            assertFailsWith<AlphaVantageDividendPocException> {
                client(clockCalls).fetchDividendSeries("IBM")
            }
        assertTrue(ex.message!!.contains("Error Message"), ex.message)
        assertEquals(0, clockCalls.get())
    }

    @Test
    fun http200InformationEnvelopeIsFailClosed() {
        mountText("""{"Information":"demo key only. claim free API key"}""")
        val clockCalls = AtomicInteger(0)
        val ex =
            assertFailsWith<AlphaVantageDividendPocException> {
                client(clockCalls).fetchDividendSeries("IBM")
            }
        assertTrue(ex.message!!.contains("Information"), ex.message)
        assertEquals(0, clockCalls.get())
    }

    @Test
    fun http200NoteEnvelopeIsFailClosed() {
        mountText("""{"Note":"Thank you for using Alpha Vantage! rate limit"}""")
        val clockCalls = AtomicInteger(0)
        val ex =
            assertFailsWith<AlphaVantageDividendPocException> {
                client(clockCalls).fetchDividendSeries("IBM")
            }
        assertTrue(ex.message!!.contains("Note"), ex.message)
        assertEquals(0, clockCalls.get())
    }

    @Test
    fun missingDataCollectionIsFailClosed() {
        mountText("""{"symbol":"IBM"}""")
        val clockCalls = AtomicInteger(0)
        val ex =
            assertFailsWith<AlphaVantageDividendPocException> {
                client(clockCalls).fetchDividendSeries("IBM")
            }
        assertTrue(ex.message!!.contains("data"), ex.message)
        assertEquals(0, clockCalls.get())
    }

    @Test
    fun requestedSymbolMismatchIsFailClosed() {
        val json =
            readResource("dividend/poc/av-dividends-ibm-sanitized.json")
                .replace("\"symbol\": \"IBM\"", "\"symbol\": \"MSFT\"")
        mountText(json)
        val clockCalls = AtomicInteger(0)
        val ex =
            assertFailsWith<AlphaVantageDividendPocException> {
                client(clockCalls).fetchDividendSeries("IBM")
            }
        assertTrue(ex.message!!.contains("mismatch"), ex.message)
        assertEquals(0, clockCalls.get())
    }

    @Test
    fun malformedExDividendDateIsFailClosed() {
        val broken =
            readResource("dividend/poc/av-dividends-ibm-sanitized.json")
                .replaceFirst("\"2024-08-09\"", "\"not-a-date\"")
        val ex =
            assertFailsWith<AlphaVantageDividendPocException> {
                AlphaVantageDividendParser.parse(broken, requestedSymbol = "IBM")
            }
        assertTrue(ex.message!!.contains("ex_dividend_date"), ex.message)
    }

    @Test
    fun malformedDeclarationDateIsFailClosed() {
        val broken =
            readResource("dividend/poc/av-dividends-ibm-sanitized.json")
                .replaceFirst("\"2024-07-29\"", "\"13/40/2024\"")
        val ex =
            assertFailsWith<AlphaVantageDividendPocException> {
                AlphaVantageDividendParser.parse(broken, requestedSymbol = "IBM")
            }
        assertTrue(ex.message!!.contains("declaration_date"), ex.message)
    }

    @Test
    fun malformedRecordDateIsFailClosed() {
        val broken =
            readResource("dividend/poc/av-dividends-ibm-sanitized.json")
                .replaceFirst("\"2024-08-12\"", "\"bogus\"")
        val ex =
            assertFailsWith<AlphaVantageDividendPocException> {
                AlphaVantageDividendParser.parse(broken, requestedSymbol = "IBM")
            }
        assertTrue(ex.message!!.contains("record_date"), ex.message)
    }

    @Test
    fun malformedPaymentDateIsFailClosed() {
        val broken =
            readResource("dividend/poc/av-dividends-ibm-sanitized.json")
                .replaceFirst("\"2024-09-10\"", "\"xxxx-yy-zz\"")
        val ex =
            assertFailsWith<AlphaVantageDividendPocException> {
                AlphaVantageDividendParser.parse(broken, requestedSymbol = "IBM")
            }
        assertTrue(ex.message!!.contains("payment_date"), ex.message)
    }

    @Test
    fun malformedAmountIsFailClosed() {
        val broken =
            readResource("dividend/poc/av-dividends-ibm-sanitized.json")
                .replaceFirst("\"1.67\"", "\"not-a-number\"")
        val ex =
            assertFailsWith<AlphaVantageDividendPocException> {
                AlphaVantageDividendParser.parse(broken, requestedSymbol = "IBM")
            }
        assertTrue(ex.message!!.contains("amount"), ex.message)
    }

    @Test
    fun negativeAmountIsRejected() {
        val broken =
            readResource("dividend/poc/av-dividends-ibm-sanitized.json")
                .replaceFirst("\"1.67\"", "\"-1.67\"")
        val ex =
            assertFailsWith<AlphaVantageDividendPocException> {
                AlphaVantageDividendParser.parse(broken, requestedSymbol = "IBM")
            }
        assertTrue(ex.message!!.contains("negative"), ex.message)
    }

    @Test
    fun successStampsFetchedAtOnlyAfterBodyParseAndSymbolMatch() {
        val json = readResource("dividend/poc/av-dividends-ibm-sanitized.json")
        mountText(json)
        val clockCalls = AtomicInteger(0)
        val expected = Instant.parse("2026-01-01T00:00:00Z")
        val client =
            AlphaVantageDividendPocClient(
                apiKey = "test-key",
                apiKeySource = ApiKeySource.DEMO,
                baseUrl = baseUrl,
                httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                clock = {
                    clockCalls.incrementAndGet()
                    expected
                },
            )
        val result = client.fetchDividendSeries("IBM")
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
        assertFailsWith<AlphaVantageDividendPocException> {
            client(clockCalls).fetchDividendSeries("IBM")
        }
        assertEquals(0, clockCalls.get())
    }

    @Test
    fun zeroAmountIsKeptNotDeleted() {
        val series =
            AlphaVantageDividendParser.parse(
                readResource("dividend/poc/av-dividends-ibm-sanitized.json"),
                requestedSymbol = "IBM",
            )
        assertTrue(series.events.any { it.amount.compareTo(BigDecimal.ZERO) == 0 })
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

    private fun client(clockCalls: AtomicInteger): AlphaVantageDividendPocClient =
        AlphaVantageDividendPocClient(
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

    private fun singleEventJson(
        declaration: String? = "\"2024-07-29\"",
        record: String? = "\"2024-08-12\"",
        payment: String? = "\"2024-09-10\"",
    ): String {
        val declarationLine = declaration?.let { """      "declaration_date": $it,""" }
        val recordLine = record?.let { """      "record_date": $it,""" }
        val paymentLine = payment?.let { """      "payment_date": $it,""" }
        return buildString {
            appendLine("{")
            appendLine("""  "symbol": "IBM",""")
            appendLine("""  "data": [""")
            appendLine("    {")
            appendLine("""      "ex_dividend_date": "2024-08-09",""")
            if (declarationLine != null) appendLine(declarationLine)
            if (recordLine != null) appendLine(recordLine)
            if (paymentLine != null) appendLine(paymentLine)
            appendLine("""      "amount": "1.67"""")
            appendLine("    }")
            appendLine("  ]")
            append("}")
        }
    }
}
