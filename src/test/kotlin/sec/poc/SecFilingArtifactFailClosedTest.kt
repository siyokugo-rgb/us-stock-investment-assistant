package sec.poc

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Accession artifact Fail-Closed / identity / relationship tests against local HttpServer.
 * Does not call production SEC endpoints.
 */
class SecFilingArtifactFailClosedTest {
    private lateinit var server: HttpServer
    private lateinit var wwwBase: String

    private val issuerCik = SecCik.parse("0000320193")
    private val originalAccession = SecAccessionNumber.parse("0001140361-26-015711")
    private val amendmentAccession = SecAccessionNumber.parse("0001140361-26-035325")

    @BeforeTest
    fun startServer() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        server.start()
        wwwBase = "http://127.0.0.1:${server.address.port}"
    }

    @AfterTest
    fun stopServer() {
        server.stop(0)
    }

    @Test
    fun validAccessionArtifactsAreFetchedWithProvenance() {
        mountValidOriginalArtifacts()
        val clockCalls = AtomicInteger(0)
        val client = artifactClient(clockCalls)
        val bundle = client.fetchBundle(originalMeta())

        assertEquals(3, clockCalls.get(), "clock once per successful artifact")
        assertEquals(originalAccession, bundle.filingIndex.provenance.accessionNumber)
        assertEquals(SecArtifactType.FILING_INDEX, bundle.filingIndex.provenance.artifactType)
        assertEquals(200, bundle.filingIndex.provenance.httpStatus)
        assertTrue(bundle.filingIndex.provenance.payloadSha256.isNotBlank())
        assertTrue(bundle.filingIndex.provenance.payloadBytes > 0)
        assertEquals("SEC", bundle.filingIndex.provenance.provider)
        assertEquals(SecArtifactType.PRIMARY_DOCUMENT, bundle.primaryDocument.provenance.artifactType)
        assertEquals(
            SecArtifactType.COMPLETE_SUBMISSION_TEXT,
            bundle.completeSubmissionText.provenance.artifactType,
        )
    }

    @Test
    fun http404IsFailure() {
        val path = archivePath(originalAccession, "${originalAccession.value}-index.htm")
        server.createContext(path) { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        val clockCalls = AtomicInteger(0)
        val ex =
            assertFailsWith<SecEdgarPocException> {
                artifactClient(clockCalls).fetchArtifact(
                    expectedAccession = originalAccession,
                    artifactType = SecArtifactType.FILING_INDEX,
                    url = wwwBase + path,
                    source = "test",
                )
            }
        assertTrue(ex.message!!.contains("404"), ex.message)
        assertEquals(0, clockCalls.get())
    }

    @Test
    fun http500IsFailure() {
        val path = archivePath(originalAccession, "${originalAccession.value}-index.htm")
        server.createContext(path) { exchange ->
            val body = "error".toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(500, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val clockCalls = AtomicInteger(0)
        val ex =
            assertFailsWith<SecEdgarPocException> {
                artifactClient(clockCalls).fetchArtifact(
                    expectedAccession = originalAccession,
                    artifactType = SecArtifactType.FILING_INDEX,
                    url = wwwBase + path,
                    source = "test",
                )
            }
        assertTrue(ex.message!!.contains("500"), ex.message)
        assertEquals(0, clockCalls.get())
    }

    @Test
    fun emptyBodyIsFailure() {
        val path = archivePath(originalAccession, "${originalAccession.value}-index.htm")
        server.createContext(path) { exchange ->
            exchange.sendResponseHeaders(200, 0)
            exchange.close()
        }
        val clockCalls = AtomicInteger(0)
        val ex =
            assertFailsWith<SecEdgarPocException> {
                artifactClient(clockCalls).fetchArtifact(
                    expectedAccession = originalAccession,
                    artifactType = SecArtifactType.FILING_INDEX,
                    url = wwwBase + path,
                    source = "test",
                )
            }
        assertTrue(ex.message!!.contains("Empty"), ex.message)
        assertEquals(0, clockCalls.get())
    }

    @Test
    fun requestedAccessionMismatchInCompleteSubmissionIsFailure() {
        val path = archivePath(originalAccession, "${originalAccession.value}.txt")
        val mismatched =
            completeSubmissionText(accession = amendmentAccession.value)
                .toByteArray(StandardCharsets.UTF_8)
        server.createContext(path) { exchange ->
            exchange.sendResponseHeaders(200, mismatched.size.toLong())
            exchange.responseBody.use { it.write(mismatched) }
        }
        val clockCalls = AtomicInteger(0)
        val ex =
            assertFailsWith<SecEdgarPocException> {
                artifactClient(clockCalls).fetchArtifact(
                    expectedAccession = originalAccession,
                    artifactType = SecArtifactType.COMPLETE_SUBMISSION_TEXT,
                    url = wwwBase + path,
                    source = "test",
                )
            }
        assertTrue(ex.message!!.contains("Accession mismatch"), ex.message)
        assertEquals(0, clockCalls.get())
    }

    @Test
    fun hashChangesWhenBodyChanges() {
        mountValidOriginalArtifacts()
        val client = artifactClient()
        val first = client.fetchBundle(originalMeta()).filingIndex.provenance.payloadSha256

        // Remount with altered index body (still contains accession).
        val path = archivePath(originalAccession, "${originalAccession.value}-index.htm")
        server.removeContext(path)
        val altered = (filingIndexHtml(originalAccession.value) + "\n<!--alt-->").toByteArray(StandardCharsets.UTF_8)
        server.createContext(path) { exchange ->
            exchange.sendResponseHeaders(200, altered.size.toLong())
            exchange.responseBody.use { it.write(altered) }
        }
        val second =
            client.fetchArtifact(
                expectedAccession = originalAccession,
                artifactType = SecArtifactType.FILING_INDEX,
                url = wwwBase + path,
                source = "test",
            ).provenance.payloadSha256
        assertNotEquals(first, second)
    }

    @Test
    fun failedFetchDoesNotStampFetchedAt() {
        val path = archivePath(originalAccession, "${originalAccession.value}-index.htm")
        server.createContext(path) { exchange ->
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
        val responseDone = AtomicBoolean(false)
        val clockCalls = AtomicInteger(0)
        // Even if handler somehow completed, clock must not run on failure.
        responseDone.set(true)
        assertFailsWith<SecEdgarPocException> {
            artifactClient(clockCalls).fetchArtifact(
                expectedAccession = originalAccession,
                artifactType = SecArtifactType.FILING_INDEX,
                url = wwwBase + path,
                source = "test",
            )
        }
        assertEquals(0, clockCalls.get())
    }

    @Test
    fun originalAndAmendmentAreDistinctAccessions() {
        assertNotEquals(originalAccession.value, amendmentAccession.value)
        assertEquals(SecCik.parse("0001140361"), originalAccession.submittingEntityCik)
        assertNotEquals(issuerCik, originalAccession.submittingEntityCik)
    }

    @Test
    fun amendmentDoesNotOverwriteOriginalInStore() {
        mountValidOriginalArtifacts()
        mountValidAmendmentArtifacts()
        val client = artifactClient()
        val store = SecAccessionArtifactStore()
        store.put(client.fetchBundle(originalMeta()))
        store.put(client.fetchBundle(amendmentMeta()))
        assertEquals(2, store.size())
        assertTrue(store.get(originalAccession) != null)
        assertTrue(store.get(amendmentAccession) != null)

        val ex =
            assertFailsWith<SecEdgarPocException> {
                store.put(client.fetchBundle(originalMeta()))
            }
        assertTrue(ex.message!!.contains("overwrite"), ex.message)
    }

    @Test
    fun relationshipWithoutDirectEvidenceIsNotConfirmed() {
        val original = originalMeta()
        val amendment = amendmentMeta()
        // Same reportDate + forms only — must not be CONFIRMED.
        val weak =
            SecAmendmentRelationshipAssessor.assess(
                original = original,
                amendment = amendment,
                amendmentPrimaryText = "<html>Form 8-K/A report date April 17, 2026</html>",
                amendmentCompleteText = completeSubmissionText(amendmentAccession.value),
            )
        assertNotEquals(SecAmendmentRelationshipGrade.CONFIRMED, weak.grade)

        val confirmed =
            SecAmendmentRelationshipAssessor.assess(
                original = original,
                amendment = amendment,
                amendmentPrimaryText =
                    "<html>This Amendment amends accession ${originalAccession.value}</html>",
                amendmentCompleteText = completeSubmissionText(amendmentAccession.value),
            )
        assertEquals(SecAmendmentRelationshipGrade.CONFIRMED, confirmed.grade)

        val likely =
            SecAmendmentRelationshipAssessor.assess(
                original = original,
                amendment = amendment,
                amendmentPrimaryText =
                    "<html>This Amendment to the Original Form 8-K filed on April 20, 2026.</html>",
                amendmentCompleteText = completeSubmissionText(amendmentAccession.value),
            )
        assertEquals(SecAmendmentRelationshipGrade.LIKELY, likely.grade)
    }

    @Test
    fun fetchedAtClockRunsOnlyAfterResponseBodyWritten() {
        val path = archivePath(originalAccession, "${originalAccession.value}-index.htm")
        val bodyWritten = AtomicBoolean(false)
        val body = filingIndexHtml(originalAccession.value).toByteArray(StandardCharsets.UTF_8)
        server.createContext(path) { exchange ->
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { out ->
                out.write(body)
                out.flush()
                bodyWritten.set(true)
            }
        }
        val clockCalls = AtomicInteger(0)
        val client =
            SecFilingArtifactClient(
                config =
                    SecEdgarPocConfig(
                        userAgent = "USStockInvestmentAssistant-PoC-Test local@test",
                        minIntervalBetweenRequests = Duration.ZERO,
                    ),
                wwwBaseUrl = wwwBase,
                httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
                clock = {
                    check(bodyWritten.get()) {
                        "fetchedAt clock() must not run before HTTP response body is fully written"
                    }
                    clockCalls.incrementAndGet()
                    Instant.parse("2026-01-01T00:00:00Z")
                },
            )
        val artifact =
            client.fetchArtifact(
                expectedAccession = originalAccession,
                artifactType = SecArtifactType.FILING_INDEX,
                url = wwwBase + path,
                source = "test",
            )
        assertEquals(1, clockCalls.get())
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), artifact.provenance.fetchedAt)
    }

    private fun artifactClient(clockCalls: AtomicInteger = AtomicInteger(0)): SecFilingArtifactClient =
        SecFilingArtifactClient(
            config =
                SecEdgarPocConfig(
                    userAgent = "USStockInvestmentAssistant-PoC-Test local@test",
                    minIntervalBetweenRequests = Duration.ZERO,
                ),
            wwwBaseUrl = wwwBase,
            httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
            clock = {
                clockCalls.incrementAndGet()
                Instant.parse("2026-01-01T00:00:00Z")
            },
        )

    private fun originalMeta(): SecAccessionFilingMeta =
        SecAccessionFilingMeta(
            issuerCik = issuerCik,
            accessionNumber = originalAccession,
            form = "8-K",
            filingDate = LocalDate.parse("2026-04-20"),
            reportDate = LocalDate.parse("2026-04-17"),
            acceptanceDateTime = Instant.parse("2026-04-20T21:29:51Z"),
            acceptanceDateTimeRaw = "2026-04-20T21:29:51.000Z",
            primaryDocument = "ef20071035_8k.htm",
            kind = SecSubmissionKind.ORIGINAL,
        )

    private fun amendmentMeta(): SecAccessionFilingMeta =
        SecAccessionFilingMeta(
            issuerCik = issuerCik,
            accessionNumber = amendmentAccession,
            form = "8-K/A",
            filingDate = LocalDate.parse("2026-09-01"),
            reportDate = LocalDate.parse("2026-04-17"),
            acceptanceDateTime = Instant.parse("2026-09-01T20:30:35Z"),
            acceptanceDateTimeRaw = "2026-09-01T20:30:35.000Z",
            primaryDocument = "ef20081427_8ka.htm",
            kind = SecSubmissionKind.AMENDMENT,
        )

    private fun archivePath(
        accession: SecAccessionNumber,
        fileName: String,
    ): String =
        "/Archives/edgar/data/${issuerCik.forArchivesPath()}/${accession.compactNoDashes}/$fileName"

    private fun mountValidOriginalArtifacts() {
        mountTriplet(
            accession = originalAccession,
            primaryFile = "ef20071035_8k.htm",
            primaryBody = "<html>Original 8-K body</html>",
        )
    }

    private fun mountValidAmendmentArtifacts() {
        mountTriplet(
            accession = amendmentAccession,
            primaryFile = "ef20081427_8ka.htm",
            primaryBody = "<html>Amendment 8-K/A body</html>",
        )
    }

    private fun mountTriplet(
        accession: SecAccessionNumber,
        primaryFile: String,
        primaryBody: String,
    ) {
        val indexBytes = filingIndexHtml(accession.value).toByteArray(StandardCharsets.UTF_8)
        val primaryBytes = primaryBody.toByteArray(StandardCharsets.UTF_8)
        val completeBytes = completeSubmissionText(accession.value).toByteArray(StandardCharsets.UTF_8)
        server.createContext(archivePath(accession, "${accession.value}-index.htm")) { exchange ->
            exchange.sendResponseHeaders(200, indexBytes.size.toLong())
            exchange.responseBody.use { it.write(indexBytes) }
        }
        server.createContext(archivePath(accession, primaryFile)) { exchange ->
            exchange.sendResponseHeaders(200, primaryBytes.size.toLong())
            exchange.responseBody.use { it.write(primaryBytes) }
        }
        server.createContext(archivePath(accession, "${accession.value}.txt")) { exchange ->
            exchange.sendResponseHeaders(200, completeBytes.size.toLong())
            exchange.responseBody.use { it.write(completeBytes) }
        }
    }

    private fun filingIndexHtml(accession: String): String =
        """
        <html><head><title>Filing Index</title></head>
        <body>
        <p>Accession Number: $accession</p>
        <p>Form Type: 8-K</p>
        </body></html>
        """.trimIndent()

    private fun completeSubmissionText(accession: String): String =
        """
        <SEC-DOCUMENT>$accession.txt : 20260420
        <SEC-HEADER>$accession.hdr.sgml : 20260420
        ACCESSION NUMBER:		$accession
        CONFORMED SUBMISSION TYPE:	8-K
        </SEC-HEADER>
        <DOCUMENT>
        <TYPE>8-K
        <TEXT>
        sample
        </TEXT>
        </DOCUMENT>
        </SEC-DOCUMENT>
        """.trimIndent()
}
