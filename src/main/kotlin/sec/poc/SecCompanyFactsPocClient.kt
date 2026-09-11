package sec.poc

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * SEC XBRL CompanyFacts の最小 live client（PoC）。
 * SecurityId へ割当しない。失敗時 mock へ切り替えない。
 */
class SecCompanyFactsPocClient(
    private val config: SecEdgarPocConfig,
    private val httpClient: HttpClient =
        HttpClient.newBuilder()
            .connectTimeout(config.connectTimeout)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build(),
    private val sleeper: (Long) -> Unit = { ms -> Thread.sleep(ms) },
    private val clock: () -> Instant = { Instant.now() },
) {
    private val lastRequestEpochMs = AtomicLong(0L)

    fun fetchCompanyFacts(
        cik: SecCik,
        retainTags: Set<String>? = DEFAULT_RETAIN_TAGS,
    ): SecCompanyFactsFetchResult {
        throttle()
        val endpoint = "${config.baseUrl.trimEnd('/')}/api/xbrl/companyfacts/CIK${cik.value}.json"
        val request =
            HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(config.requestTimeout)
                .header("User-Agent", config.userAgent)
                .header("Accept", "application/json")
                .GET()
                .build()

        val response =
            try {
                httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
            } catch (e: Exception) {
                throw SecEdgarPocException("SEC CompanyFacts HTTP call failed for $endpoint: ${e.message}", e)
            }

        val status = response.statusCode()
        val bodyBytes = response.body() ?: ByteArray(0)
        if (status in 400..599) {
            throw SecEdgarPocException(
                "SEC HTTP $status for $endpoint (Fail-Closed; no mock fallback). bodyBytes=${bodyBytes.size}",
            )
        }
        if (status !in 200..299) {
            throw SecEdgarPocException("Unexpected SEC HTTP $status for $endpoint")
        }
        if (bodyBytes.isEmpty()) {
            throw SecEdgarPocException("Empty SEC CompanyFacts body for $endpoint")
        }

        val rawJson = String(bodyBytes, StandardCharsets.UTF_8)
        val sha = SecEdgarSubmissionsPocClient.sha256Hex(bodyBytes)
        val document = SecCompanyFactsParser.parse(rawJson, retainTags = retainTags)
        if (document.cik != cik) {
            throw SecEdgarPocException(
                "CIK mismatch: requested ${cik.value}, payload ${document.cik.value}",
            )
        }

        // fetchedAt only after body received, parse OK, and CIK matched.
        val fetchedAt = clock()

        return SecCompanyFactsFetchResult(
            evidence =
                SecFetchEvidence(
                    endpoint = endpoint,
                    httpStatus = status,
                    fetchedAt = fetchedAt,
                    payloadSha256 = sha,
                    payloadBytes = bodyBytes.size,
                    userAgentConfigured = true,
                ),
            document = document,
            rawJson = rawJson,
        )
    }

    private fun throttle() {
        val minInterval = config.minIntervalBetweenRequests.toMillis().coerceAtLeast(0L)
        if (minInterval == 0L) return
        while (true) {
            val now = System.currentTimeMillis()
            val prev = lastRequestEpochMs.get()
            val wait = minInterval - (now - prev)
            if (prev == 0L || wait <= 0L) {
                if (lastRequestEpochMs.compareAndSet(prev, now)) return
            } else {
                sleeper(wait)
            }
        }
    }

    companion object {
        val DEFAULT_RETAIN_TAGS: Set<String> =
            setOf(
                "RevenueFromContractWithCustomerExcludingAssessedTax",
                "Revenues",
                "NetIncomeLoss",
                "Assets",
                "Liabilities",
                "CashAndCashEquivalentsAtCarryingValue",
                "FiniteLivedIntangibleAssetsUsefulLifeMaximum",
            )
    }
}

object SecCompanyFactsAccessionJoiner {
    /**
     * accn がある fact のみ結合する。accn 欠落は推測結合しない。
     * submissions.recent に無い場合でも、archive index 取得で追跡可能かを確認する。
     */
    fun joinToArchiveAndSubmissions(
        fact: SecCompanyFactVersion,
        issuerCik: SecCik,
        submissions: SecSubmissionsDocument?,
        artifactClient: SecFilingArtifactClient?,
    ): SecCompanyFactsAccessionJoin {
        val accession =
            fact.accn
                ?: throw SecEdgarPocException("Cannot join CompanyFacts fact without accn (no guessing)")
        val inRecent =
            submissions?.recentFilings?.any { it.accessionNumber == accession.value } == true
        var archiveUrl: String? = null
        var archiveStatus: Int? = null
        if (artifactClient != null) {
            archiveUrl =
                SecEdgarArchivePaths.filingIndexHtml(issuerCik, accession)
            val fetched =
                artifactClient.fetchArtifact(
                    expectedAccession = accession,
                    artifactType = SecArtifactType.FILING_INDEX,
                    url = archiveUrl,
                    source = "CompanyFacts accn → archive filing index join",
                )
            archiveStatus = fetched.provenance.httpStatus
        }
        return SecCompanyFactsAccessionJoin(
            fact = fact,
            accession = accession,
            foundInSubmissionsRecent = inRecent,
            archiveIndexUrl = archiveUrl,
            archiveIndexHttpStatus = archiveStatus,
        )
    }
}
