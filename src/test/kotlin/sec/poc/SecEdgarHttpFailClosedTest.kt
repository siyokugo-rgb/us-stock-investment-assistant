package sec.poc

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executors
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

    @Test
    fun fetchedAtIsStampedOnlyAfterSuccessfulParseAndCikMatch() {
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
                  "accessionNumber": ["0000320193-25-000001"],
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
        server.createContext("/submissions/CIK0000320193.json") { exchange ->
            val body = json.toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }

        val instants =
            mutableListOf(
                Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:10Z"),
            )
        val client =
            SecEdgarSubmissionsPocClient(
                config =
                    SecEdgarPocConfig(
                        userAgent = "USStockInvestmentAssistant-PoC-Test local@test",
                        baseUrl = baseUrl,
                        minIntervalBetweenRequests = Duration.ZERO,
                    ),
                httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                clock = { instants.removeAt(0) },
            )

        val result = client.fetchSubmissions(SecCik.parse("0000320193"))
        // Only one clock() call should remain after success-path stamping.
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), result.evidence.fetchedAt)
        assertEquals(1, instants.size)
        assertEquals(Instant.parse("2026-01-01T00:00:10Z"), instants.single())
    }

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
