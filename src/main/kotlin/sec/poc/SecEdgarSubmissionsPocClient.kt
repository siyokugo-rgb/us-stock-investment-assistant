package sec.poc

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

data class SecEdgarPocConfig(
    val userAgent: String,
    val baseUrl: String = DEFAULT_BASE_URL,
    val connectTimeout: Duration = Duration.ofSeconds(20),
    val requestTimeout: Duration = Duration.ofSeconds(30),
    val minIntervalBetweenRequests: Duration = Duration.ofMillis(1000),
) {
    init {
        if (userAgent.isBlank()) {
            throw SecEdgarPocException("SEC User-Agent must be configured; refusing to call EDGAR without identity")
        }
        if (userAgent.contains("TODO", ignoreCase = true) ||
            userAgent.contains("example.com", ignoreCase = true)
        ) {
            throw SecEdgarPocException("Refusing placeholder User-Agent: '$userAgent'")
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://data.sec.gov"

        fun fromEnvironment(
            env: Map<String, String> = System.getenv(),
            minIntervalBetweenRequests: Duration = Duration.ofMillis(1000),
        ): SecEdgarPocConfig {
            val ua = env["SEC_EDGAR_USER_AGENT"]?.trim().orEmpty()
            if (ua.isEmpty()) {
                throw SecEdgarPocException(
                    "SEC_EDGAR_USER_AGENT is not set. Configure an identifiable User-Agent and retry. Fail-Closed.",
                )
            }
            return SecEdgarPocConfig(
                userAgent = ua,
                minIntervalBetweenRequests = minIntervalBetweenRequests,
            )
        }
    }
}

data class SecSubmissionsFetchResult(
    val evidence: SecFetchEvidence,
    val document: SecSubmissionsDocument,
    val rawJson: String,
)

/**
 * SEC EDGAR submissions の最小 live client（PoC）。
 * 本番 Provider 抽象ではない。HTTP 失敗時に mock へ切り替えない。
 */
class SecEdgarSubmissionsPocClient(
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

    fun fetchSubmissions(cik: SecCik): SecSubmissionsFetchResult {
        throttle()
        val endpoint = "${config.baseUrl.trimEnd('/')}/submissions/CIK${cik.value}.json"
        val fetchedAt = clock()
        val request =
            HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(config.requestTimeout)
                .header("User-Agent", config.userAgent)
                .header("Accept", "application/json")
                // Do not request gzip: HttpClient may return compressed bytes without decoding,
                // which would fail JSON parse. Plain JSON is enough for this PoC.
                .GET()
                .build()

        val response =
            try {
                httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
            } catch (e: Exception) {
                throw SecEdgarPocException("SEC HTTP call failed for $endpoint: ${e.message}", e)
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
            throw SecEdgarPocException("Empty SEC response body for $endpoint")
        }

        val rawJson = String(bodyBytes, StandardCharsets.UTF_8)
        val sha = sha256Hex(bodyBytes)
        val document = SecSubmissionsParser.parse(rawJson)
        if (document.cik != cik) {
            throw SecEdgarPocException(
                "CIK mismatch: requested ${cik.value}, payload ${document.cik.value}",
            )
        }

        return SecSubmissionsFetchResult(
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
        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString("") { b -> "%02x".format(b) }
        }
    }
}
