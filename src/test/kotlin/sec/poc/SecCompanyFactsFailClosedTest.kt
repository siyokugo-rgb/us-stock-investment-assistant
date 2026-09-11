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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * CompanyFacts Fail-Closed / parse / version-set tests against local HttpServer + fixtures.
 * Does not call production SEC endpoints. Does not assign SecurityId.
 */
class SecCompanyFactsFailClosedTest {
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
    fun validCompanyFactsParseKeepsMultiAccessionCandidates() {
        val json = readResource("sec/poc/aapl-companyfacts-sanitized.json")
        val doc = SecCompanyFactsParser.parse(json)
        assertEquals(SecCik.parse("0000320193"), doc.cik)
        assertEquals("Apple Inc.", doc.entityName)
        assertTrue(doc.taxonomies.containsAll(setOf("us-gaap", "dei")))
        val netIncome = doc.concepts.single { it.tag == "NetIncomeLoss" }
        val usd = netIncome.units.getValue("USD")
        assertEquals(3, usd.size)
        val samePeriod = usd.filter { it.end.toString() == "2025-09-27" && it.fp == "FY" }
        assertEquals(2, samePeriod.size)
        assertEquals(
            setOf("0000320193-25-000079", "0000320193-25-000099"),
            samePeriod.map { it.accn!!.value }.toSet(),
        )
        // CompanyFacts models stay on CIK/entityName — no SecurityId assignment API.
        assertEquals("0000320193", doc.cik.value)
    }

    @Test
    fun conceptMultipleUnitsArePreserved() {
        val doc = SecCompanyFactsParser.parse(readResource("sec/poc/aapl-companyfacts-sanitized.json"))
        val life = doc.concepts.single { it.tag == "FiniteLivedIntangibleAssetsUsefulLifeMaximum" }
        assertEquals(setOf("pure", "Year"), life.units.keys)
        assertEquals(1, life.units.getValue("pure").size)
        assertEquals(1, life.units.getValue("Year").size)
    }

    @Test
    fun invalidAccnIsFailClosed() {
        val broken =
            readResource("sec/poc/aapl-companyfacts-sanitized.json")
                .replace("0000320193-25-000079", "NOT-AN-ACCESSION")
        val ex =
            assertFailsWith<SecEdgarPocException> {
                SecCompanyFactsParser.parse(broken)
            }
        assertTrue(ex.message!!.contains("accn"), ex.message)
    }

    @Test
    fun http404IsFailureAndDoesNotStampFetchedAt() {
        server.createContext("/api/xbrl/companyfacts/CIK0000320193.json") { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        val clockCalls = AtomicInteger(0)
        val ex =
            assertFailsWith<SecEdgarPocException> {
                client(clockCalls).fetchCompanyFacts(SecCik.parse("0000320193"), retainTags = null)
            }
        assertTrue(ex.message!!.contains("404"), ex.message)
        assertEquals(0, clockCalls.get())
    }

    @Test
    fun http500IsFailureAndDoesNotStampFetchedAt() {
        server.createContext("/api/xbrl/companyfacts/CIK0000320193.json") { exchange ->
            val body = "error".toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(500, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val clockCalls = AtomicInteger(0)
        val ex =
            assertFailsWith<SecEdgarPocException> {
                client(clockCalls).fetchCompanyFacts(SecCik.parse("0000320193"), retainTags = null)
            }
        assertTrue(ex.message!!.contains("500"), ex.message)
        assertEquals(0, clockCalls.get())
    }

    @Test
    fun emptyBodyIsFailureAndDoesNotStampFetchedAt() {
        server.createContext("/api/xbrl/companyfacts/CIK0000320193.json") { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.close()
        }
        val clockCalls = AtomicInteger(0)
        val ex =
            assertFailsWith<SecEdgarPocException> {
                client(clockCalls).fetchCompanyFacts(SecCik.parse("0000320193"), retainTags = null)
            }
        assertTrue(ex.message!!.contains("Empty"), ex.message)
        assertEquals(0, clockCalls.get())
    }

    @Test
    fun malformedJsonIsFailureAndDoesNotStampFetchedAt() {
        mountBody("{ not-json ")
        val clockCalls = AtomicInteger(0)
        assertFailsWith<SecEdgarPocException> {
            client(clockCalls).fetchCompanyFacts(SecCik.parse("0000320193"), retainTags = null)
        }
        assertEquals(0, clockCalls.get())
    }

    @Test
    fun cikMismatchIsFailureAndDoesNotStampFetchedAt() {
        val json =
            readResource("sec/poc/aapl-companyfacts-sanitized.json")
                .replace("\"cik\": 320193", "\"cik\": 789019")
        mountBody(json)
        val clockCalls = AtomicInteger(0)
        val ex =
            assertFailsWith<SecEdgarPocException> {
                client(clockCalls).fetchCompanyFacts(SecCik.parse("0000320193"), retainTags = null)
            }
        assertTrue(ex.message!!.contains("CIK mismatch"), ex.message)
        assertEquals(0, clockCalls.get())
    }

    @Test
    fun successStampsFetchedAtOnlyAfterBodyParseAndCikMatch() {
        val bodyWritten = AtomicBoolean(false)
        val json = readResource("sec/poc/aapl-companyfacts-sanitized.json")
        val bytes = json.toByteArray(StandardCharsets.UTF_8)
        server.createContext("/api/xbrl/companyfacts/CIK0000320193.json") { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { out ->
                out.write(bytes)
                out.flush()
                bodyWritten.set(true)
            }
        }
        val clockCalls = AtomicInteger(0)
        val expected = Instant.parse("2026-01-01T00:00:00Z")
        val client =
            SecCompanyFactsPocClient(
                config =
                    SecEdgarPocConfig(
                        userAgent = "USStockInvestmentAssistant-PoC-Test local@test",
                        baseUrl = baseUrl,
                        minIntervalBetweenRequests = Duration.ZERO,
                    ),
                httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                clock = {
                    check(bodyWritten.get()) {
                        "fetchedAt clock() must not run before HTTP response body is fully written"
                    }
                    clockCalls.incrementAndGet()
                    expected
                },
            )
        val result = client.fetchCompanyFacts(SecCik.parse("0000320193"), retainTags = null)
        assertEquals(1, clockCalls.get())
        assertEquals(expected, result.evidence.fetchedAt)
        assertEquals(SecCik.parse("0000320193"), result.document.cik)
    }

    @Test
    fun missingAccnDoesNotInventAccessionJoin() {
        val json =
            """
            {
              "cik": 320193,
              "entityName": "Apple Inc.",
              "facts": {
                "us-gaap": {
                  "Assets": {
                    "units": {
                      "USD": [
                        {
                          "end": "2025-09-27",
                          "val": 1,
                          "fy": 2025,
                          "fp": "FY",
                          "form": "10-K",
                          "filed": "2025-10-31"
                        }
                      ]
                    }
                  }
                }
              }
            }
            """.trimIndent()
        val doc = SecCompanyFactsParser.parse(json)
        val fact = doc.concepts.single().allVersions.single()
        assertNull(fact.accn)
        val ex =
            assertFailsWith<SecEdgarPocException> {
                SecCompanyFactsAccessionJoiner.joinToArchiveAndSubmissions(
                    fact = fact,
                    issuerCik = SecCik.parse("0000320193"),
                    submissions = null,
                    artifactClient = null,
                )
            }
        assertTrue(ex.message!!.contains("without accn"), ex.message)
    }

    @Test
    fun doesNotAutoAssignSecurityId() {
        val doc = SecCompanyFactsParser.parse(readResource("sec/poc/aapl-companyfacts-sanitized.json"))
        // PoC models expose CIK/entityName only — caller must not treat CIK as SecurityId.
        assertEquals(SecCik.parse("0000320193"), doc.cik)
        assertEquals("Apple Inc.", doc.entityName)
        assertTrue(doc.concepts.isNotEmpty())
        // Fact versions carry taxonomy/tag/accn — never a SecurityId.
        assertTrue(doc.concepts.all { c -> c.tag.isNotBlank() && c.allVersions.all { it.unit.isNotBlank() } })
    }

    private fun mountBody(body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        server.createContext("/api/xbrl/companyfacts/CIK0000320193.json") { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    private fun client(clockCalls: AtomicInteger = AtomicInteger(0)): SecCompanyFactsPocClient =
        SecCompanyFactsPocClient(
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

    private fun readResource(path: String): String =
        javaClass.classLoader.getResourceAsStream(path)?.bufferedReader()?.readText()
            ?: error("Missing resource $path")
}
