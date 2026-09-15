package archive.poc.alphavantage

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

/**
 * HTTP possession result for Alpha Vantage TIME_SERIES_DAILY.
 * [fetchedAt] is set only when response body bytes were fully received
 * (independent of HTTP status). Never a historical knownAt.
 */
data class AlphaVantageHttpPossession(
    /** Secret-free request identity bound at attempt time (same settings as the HTTP call). */
    val requestKey: String,
    val attemptedAt: Instant,
    val attemptFinishedAt: Instant,
    val fetchedAt: Instant?,
    val httpStatus: Int?,
    val contentType: String?,
    val bodyBytes: ByteArray?,
    /** Exception class simple name only — never URI / exception message / API key. */
    val transportFailureMessage: String?,
) {
    val bodyFullyReceived: Boolean get() = bodyBytes != null && fetchedAt != null
}

/**
 * Official endpoint: GET https://www.alphavantage.co/query
 * function=TIME_SERIES_DAILY
 *
 * API key from env [ENV_API_KEY] only. Never enters requestKey / logs / fixtures / raw paths.
 */
class AlphaVantageDailyArchiveClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val apiKey: String? = null,
    private val outputSize: String = DEFAULT_OUTPUT_SIZE,
    private val httpClient: HttpClient =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
    private val requestTimeout: Duration = Duration.ofSeconds(30),
    private val clock: () -> Instant = { Instant.now() },
) {
    init {
        require(baseUrl.isNotBlank())
        require(outputSize == "compact" || outputSize == "full") {
            "outputSize must be compact|full"
        }
        apiKey?.let { require(it.isNotBlank()) }
    }

    fun executeDaily(symbol: String): AlphaVantageHttpPossession {
        require(symbol.isNotBlank()) { "symbol must not be blank" }
        // Secret-free identity is fixed before any secret-bearing request construction.
        val requestKey = requestKeyFor(symbol)
        val attemptedAt = clock()

        // Endpoint / URI / HttpRequest build / send all share one sanitized failure boundary.
        // Catch never rethrows and never stores e.message (may embed request URI + apikey).
        return try {
            val key = apiKey ?: DEMO_API_KEY
            val encodedSymbol = URLEncoder.encode(symbol.trim(), StandardCharsets.UTF_8)
            val encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8)
            val endpoint =
                "${baseUrl.trimEnd('/')}$QUERY_PATH" +
                    "?function=$FUNCTION" +
                    "&symbol=$encodedSymbol" +
                    "&outputsize=$outputSize" +
                    "&datatype=json" +
                    "&apikey=$encodedKey"
            val request =
                HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(requestTimeout)
                    .header("Accept", "application/json")
                    .GET()
                    .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray())
            val attemptFinishedAt = clock()
            val body = response.body() ?: ByteArray(0)
            AlphaVantageHttpPossession(
                requestKey = requestKey,
                attemptedAt = attemptedAt,
                attemptFinishedAt = attemptFinishedAt,
                fetchedAt = attemptFinishedAt,
                httpStatus = response.statusCode(),
                contentType = response.headers().firstValue("Content-Type").orElse(null),
                bodyBytes = body,
                transportFailureMessage = null,
            )
        } catch (e: Exception) {
            val attemptFinishedAt = clock()
            AlphaVantageHttpPossession(
                requestKey = requestKey,
                attemptedAt = attemptedAt,
                attemptFinishedAt = attemptFinishedAt,
                fetchedAt = null,
                httpStatus = null,
                contentType = null,
                bodyBytes = null,
                // Class name only — never e.message (may contain request URI / apikey).
                transportFailureMessage = e::class.java.simpleName,
            )
        }
    }

    /**
     * Secret-free request identity derived from **this client's** actual HTTP settings
     * (including [outputSize] used by [executeDaily]). Never invents a different outputSize.
     */
    fun requestKeyFor(symbol: String): String = requestKey(symbol, outputSize)

    companion object {
        const val DEFAULT_BASE_URL = "https://www.alphavantage.co"
        const val QUERY_PATH = "/query"
        const val FUNCTION = "TIME_SERIES_DAILY"
        const val DEFAULT_OUTPUT_SIZE = "compact"
        const val ENV_API_KEY = "ALPHAVANTAGE_API_KEY"
        const val DEMO_API_KEY = "demo"
        const val DOMAIN = "PRICE"
        /** Raw daily only — never conflated with TIME_SERIES_DAILY_ADJUSTED. */
        const val SOURCE = "alphavantage.time_series_daily.raw"

        /**
         * Secret-free request identity.
         * Provider symbol is request identity only — not SecurityId / externalIdentifier.
         */
        fun requestKey(
            symbol: String,
            outputSize: String = DEFAULT_OUTPUT_SIZE,
        ): String =
            "GET|$QUERY_PATH|function=$FUNCTION|symbol=${symbol.trim()}|outputsize=$outputSize"

        fun fromEnvironment(
            env: Map<String, String> = System.getenv(),
            baseUrl: String = DEFAULT_BASE_URL,
            outputSize: String = DEFAULT_OUTPUT_SIZE,
            clock: () -> Instant = { Instant.now() },
            httpClient: HttpClient =
                HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(20))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build(),
        ): AlphaVantageDailyArchiveClient {
            val key = env[ENV_API_KEY]?.trim()?.takeIf { it.isNotEmpty() }
            return AlphaVantageDailyArchiveClient(
                baseUrl = baseUrl,
                apiKey = key,
                outputSize = outputSize,
                httpClient = httpClient,
                clock = clock,
            )
        }
    }
}
