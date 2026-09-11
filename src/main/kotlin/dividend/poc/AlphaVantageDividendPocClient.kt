package dividend.poc

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

/**
 * Alpha Vantage DIVIDENDS の最小 live/local client（PoC）。
 *
 * - SecurityId へ割当しない
 * - historical knownAt を declarationDate / fetchedAt から生成しない
 * - currency / REGULAR を推測しない
 * - mock fallback しない
 * - HTTP 200 + Error/Information/Note envelope は Fail-Closed
 * - fetchedAt は body 受信・parse・symbol 一致後のみ打刻
 */
class AlphaVantageDividendPocClient(
    private val apiKey: String,
    private val apiKeySource: ApiKeySource,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val httpClient: HttpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build(),
    private val clock: () -> Instant = { Instant.now() },
) {
    init {
        require(apiKey.isNotBlank()) { "apiKey must not be blank" }
        require(baseUrl.isNotBlank()) { "baseUrl must not be blank" }
    }

    fun fetchDividendSeries(symbol: String): AlphaVantageDividendFetchResult {
        require(symbol.isNotBlank()) { "symbol must not be blank" }
        val encodedSymbol = URLEncoder.encode(symbol.trim(), StandardCharsets.UTF_8)
        val encodedKey = URLEncoder.encode(apiKey, StandardCharsets.UTF_8)
        val endpoint =
            "${baseUrl.trimEnd('/')}/query" +
                "?function=$FUNCTION" +
                "&symbol=$encodedSymbol" +
                "&apikey=$encodedKey"

        val request =
            HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET()
                .build()

        val response =
            try {
                httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
            } catch (e: Exception) {
                throw AlphaVantageDividendPocException(
                    "Alpha Vantage HTTP call failed for symbol=$symbol: ${e.message}",
                    e,
                )
            }

        val status = response.statusCode()
        val bodyBytes = response.body() ?: ByteArray(0)
        if (status in 400..599) {
            throw AlphaVantageDividendPocException(
                "Alpha Vantage HTTP $status for symbol=$symbol (Fail-Closed; no mock fallback). bodyBytes=${bodyBytes.size}",
            )
        }
        if (status !in 200..299) {
            throw AlphaVantageDividendPocException("Unexpected Alpha Vantage HTTP $status for symbol=$symbol")
        }
        if (bodyBytes.isEmpty()) {
            throw AlphaVantageDividendPocException("Empty Alpha Vantage body for symbol=$symbol")
        }

        val rawJson = String(bodyBytes, StandardCharsets.UTF_8)
        val sha = AlphaVantageDividendParser.sha256Hex(bodyBytes)
        val series = AlphaVantageDividendParser.parse(rawJson, requestedSymbol = symbol)
        val fetchedAt = clock()
        val redactedEndpoint = endpoint.replace(encodedKey, "<redacted>")

        return AlphaVantageDividendFetchResult(
            evidence =
                AlphaVantageDividendFetchEvidence(
                    endpoint = redactedEndpoint,
                    function = FUNCTION,
                    requestedSymbol = symbol.trim(),
                    httpStatus = status,
                    fetchedAt = fetchedAt,
                    payloadSha256 = sha,
                    payloadBytes = bodyBytes.size,
                    apiKeySource = apiKeySource,
                ),
            series = series,
        )
    }

    companion object {
        const val FUNCTION = "DIVIDENDS"
        const val DEFAULT_BASE_URL = "https://www.alphavantage.co"
        const val ENV_API_KEY = "ALPHAVANTAGE_API_KEY"
        const val DEMO_API_KEY = "demo"

        fun fromEnvironment(): AlphaVantageDividendPocClient {
            val envKey = System.getenv(ENV_API_KEY)?.trim().orEmpty()
            return if (envKey.isNotEmpty()) {
                AlphaVantageDividendPocClient(apiKey = envKey, apiKeySource = ApiKeySource.ENVIRONMENT)
            } else {
                AlphaVantageDividendPocClient(apiKey = DEMO_API_KEY, apiKeySource = ApiKeySource.DEMO)
            }
        }
    }
}
