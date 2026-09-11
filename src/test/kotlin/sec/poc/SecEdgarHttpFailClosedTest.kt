package sec.poc

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * HTTP Fail-Closed tests against a local JDK HttpServer.
 * Does not call production SEC endpoints.
 */
class SecEdgarHttpFailClosedTest {
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
    fun http404IsFailure() {
        server.createContext("/submissions/CIK0000320193.json") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        val ex =
            assertFailsWith<SecEdgarPocException> {
                client().fetchSubmissions(SecCik.parse("0000320193"))
            }
        assertTrue(ex.message!!.contains("404"), ex.message)
    }

    @Test
    fun http500IsFailure() {
        server.createContext("/submissions/CIK0000320193.json") { exchange ->
            val body = "error".toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(500, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val ex =
            assertFailsWith<SecEdgarPocException> {
                client().fetchSubmissions(SecCik.parse("0000320193"))
            }
        assertTrue(ex.message!!.contains("500"), ex.message)
    }

    @Test
    fun emptyResponseIsFailure() {
        server.createContext("/submissions/CIK0000320193.json") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.close()
        }
        val ex =
            assertFailsWith<SecEdgarPocException> {
                client().fetchSubmissions(SecCik.parse("0000320193"))
            }
        assertTrue(ex.message!!.contains("Empty"), ex.message)
    }

    /**
     * Regression: fetchedAt / ingestedAt clock must run only after the local HTTP handler
     * has fully written the response body (body received), then parse + CIK match succeed.
     *
     * Calling clock() before HTTP response completion fails this test via AtomicBoolean.
     * Malformed JSON / CIK mismatch paths are covered by sibling tests that require zero clock calls.
     */
    @Test
    fun fetchedAtIsStampedOnlyAfterSuccessfulParseAndCikMatch() {
        val responseBodyFullyWritten = AtomicBoolean(false)
        val clockCalls = AtomicInteger(0)
        val expectedFetchedAt = Instant.parse("2026-01-01T00:00:00Z")

        server.createContext("/submissions/CIK0000320193.json") { exchange ->
            val body = validSubmissionsJson(cik = "0000320193").toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { out ->
                out.write(body)
                out.flush()
                // Mark completion only after the full body has been written.
                responseBodyFullyWritten.set(true)
            }
            exchange.close()
        }

        val client =
            SecEdgarSubmissionsPocClient(
                config =
                    SecEdgarPocConfig(
                        userAgent = "USStockInvestmentAssistant-PoC-Test local@test",
                        baseUrl = baseUrl,
                        minIntervalBetweenRequests = Duration.ZERO,
                    ),
                httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                clock = {
                    check(responseBodyFullyWritten.get()) {
                        "fetchedAt clock() must not run before HTTP response body is fully written " +
                            "(detects stamping before/during HTTP send)"
                    }
                    clockCalls.incrementAndGet()
                    expectedFetchedAt
                },
            )

        val result = client.fetchSubmissions(SecCik.parse("0000320193"))
        assertTrue(responseBodyFullyWritten.get())
        assertEquals(1, clockCalls.get(), "clock() must be called exactly once on success")
        assertEquals(expectedFetchedAt, result.evidence.fetchedAt)
    }

    @Test
    fun malformedJsonDoesNotCallFetchedAtClock() {
        val clockCalls = AtomicInteger(0)
        server.createContext("/submissions/CIK0000320193.json") { exchange ->
            val body = "{ not-json ".toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        val client =
            SecEdgarSubmissionsPocClient(
                config =
                    SecEdgarPocConfig(
                        userAgent = "USStockInvestmentAssistant-PoC-Test local@test",
                        baseUrl = baseUrl,
                        minIntervalBetweenRequests = Duration.ZERO,
                    ),
                httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                clock = {
                    clockCalls.incrementAndGet()
                    Instant.parse("2026-01-01T00:00:00Z")
                },
            )

        val ex =
            assertFailsWith<SecEdgarPocException> {
                client.fetchSubmissions(SecCik.parse("0000320193"))
            }
        assertTrue(
            ex.message!!.contains("Malformed", ignoreCase = true) ||
                ex.message!!.contains("JSON", ignoreCase = true),
            ex.message,
        )
        assertEquals(0, clockCalls.get(), "ingestedAt/fetchedAt clock must not run on malformed JSON")
    }

    @Test
    fun cikMismatchDoesNotCallFetchedAtClock() {
        val clockCalls = AtomicInteger(0)
        server.createContext("/submissions/CIK0000320193.json") { exchange ->
            // Request CIK is Apple; payload claims Microsoft → must Fail-Closed before stamp.
            val body = validSubmissionsJson(cik = "0000789019").toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        val client =
            SecEdgarSubmissionsPocClient(
                config =
                    SecEdgarPocConfig(
                        userAgent = "USStockInvestmentAssistant-PoC-Test local@test",
                        baseUrl = baseUrl,
                        minIntervalBetweenRequests = Duration.ZERO,
                    ),
                httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                clock = {
                    clockCalls.incrementAndGet()
                    Instant.parse("2026-01-01T00:00:00Z")
                },
            )

        val ex =
            assertFailsWith<SecEdgarPocException> {
                client.fetchSubmissions(SecCik.parse("0000320193"))
            }
        assertTrue(ex.message!!.contains("CIK mismatch"), ex.message)
        assertEquals(0, clockCalls.get(), "ingestedAt/fetchedAt clock must not run on CIK mismatch")
    }

    private fun validSubmissionsJson(cik: String): String =
        """
        {
          "cik": "$cik",
          "name": "Test Issuer",
          "tickers": ["TEST"],
          "exchanges": ["Nasdaq"],
          "formerNames": [],
          "filings": {
            "recent": {
              "accessionNumber": ["$cik-25-000001"],
              "filingDate": ["2025-01-02"],
              "reportDate": ["2024-12-28"],
              "acceptanceDateTime": ["2025-01-02T21:00:00.000Z"],
              "form": ["8-K"],
              "primaryDocument": ["a.htm"],
              "primaryDocDescription": ["8-K"],
              "isXBRL": [0],
              "isInlineXBRL": [0]
            },
            "files": []
          }
        }
        """.trimIndent()

    private fun client(): SecEdgarSubmissionsPocClient =
        SecEdgarSubmissionsPocClient(
            config =
                SecEdgarPocConfig(
                    userAgent = "USStockInvestmentAssistant-PoC-Test local@test",
                    baseUrl = baseUrl,
                    minIntervalBetweenRequests = Duration.ZERO,
                ),
            httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
        )
}
