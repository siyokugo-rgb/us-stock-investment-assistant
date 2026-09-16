package archive.poc.massive

import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

/**
 * HTTP possession result for Massive Custom Bars (1 day, adjusted=false).
 * [fetchedAt] is set only when response body bytes were fully received
 * (independent of HTTP status). Never a historical knownAt.
 */
data class MassiveHttpPossession(
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
 * Official endpoint: GET https://api.massive.com/v2/aggs/ticker/{ticker}/range/1/day/{from}/{to}
 * Query: adjusted=false&sort=asc&limit={limit}&apiKey=…
 *
 * Auth: query `apiKey` from env [ENV_API_KEY] only (Massive official quickstart).
 * Never enters requestKey / logs / fixtures / raw paths / manifest / e.message storage.
 *
 * `adjusted=false` means **unadjusted-for-splits Custom Bars aggregate**,
 * NOT “raw tape / raw exchange trade”.
 */
class MassiveDailyAggsArchiveClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val apiKey: String? = null,
    private val limit: Int = DEFAULT_LIMIT,
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
        require(limit > 0) { "limit must be positive" }
        require(limit <= MAX_LIMIT) { "limit must be <= $MAX_LIMIT" }
        apiKey?.let { require(it.isNotBlank()) }
    }

    fun executeDailyAggs(
        ticker: String,
        from: String,
        to: String,
    ): MassiveHttpPossession {
        require(ticker.isNotBlank()) { "ticker must not be blank" }
        require(from.isNotBlank()) { "from must not be blank" }
        require(to.isNotBlank()) { "to must not be blank" }
        val requestKey = requestKeyFor(ticker, from, to)
        val attemptedAt = clock()

        // Endpoint / URI / query(apiKey) / HttpRequest build / send share one sanitized boundary.
        // Catch never rethrows and never stores e.message (may embed request URI + apiKey).
        return try {
            val key =
                apiKey
                    ?: throw IllegalStateException("MASSIVE_API_KEY required for live HTTP")
            val encTicker = urlPathSegment(ticker.trim())
            val encFrom = urlPathSegment(from.trim())
            val encTo = urlPathSegment(to.trim())
            val encKey = URLEncoder.encode(key, StandardCharsets.UTF_8)
            val path = "$PATH_PREFIX$encTicker$PATH_RANGE_SUFFIX$encFrom/$encTo"
            val endpoint =
                "${baseUrl.trimEnd('/')}$path" +
                    "?adjusted=false" +
                    "&sort=asc" +
                    "&limit=$limit" +
                    "&apiKey=$encKey"
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
            MassiveHttpPossession(
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
            MassiveHttpPossession(
                requestKey = requestKey,
                attemptedAt = attemptedAt,
                attemptFinishedAt = attemptFinishedAt,
                fetchedAt = null,
                httpStatus = null,
                contentType = null,
                bodyBytes = null,
                transportFailureMessage = e::class.java.simpleName,
            )
        }
    }

    fun requestKeyFor(
        ticker: String,
        from: String,
        to: String,
    ): String = requestKey(ticker.trim(), from.trim(), to.trim(), limit)

    companion object {
        const val DEFAULT_BASE_URL = "https://api.massive.com"
        const val PATH_PREFIX = "/v2/aggs/ticker/"
        const val PATH_RANGE_SUFFIX = "/range/1/day/"
        const val MULTIPLIER = 1
        const val TIMESPAN = "day"
        const val DEFAULT_LIMIT = 5000
        const val MAX_LIMIT = 50_000
        const val ENV_API_KEY = "MASSIVE_API_KEY"
        const val DOMAIN = "PRICE"
        /** Unadjusted Custom Bars 1d aggregate — not adjusted=true, not Ticker Overview. */
        const val SOURCE = "massive.stocks.aggs_1d.unadjusted"

        /**
         * Secret-free request identity.
         * Provider ticker is request identity only — not SecurityId / externalIdentifier.
         */
        fun requestKey(
            ticker: String,
            from: String,
            to: String,
            limit: Int = DEFAULT_LIMIT,
        ): String {
            require(ticker.isNotBlank())
            require(from.isNotBlank())
            require(to.isNotBlank())
            require(limit > 0)
            return "GET|$PATH_PREFIX${ticker.trim()}$PATH_RANGE_SUFFIX${from.trim()}/${to.trim()}" +
                "|adjusted=false|sort=asc|limit=$limit"
        }

        fun fromEnvironment(
            env: Map<String, String> = System.getenv(),
            baseUrl: String = DEFAULT_BASE_URL,
            limit: Int = DEFAULT_LIMIT,
            clock: () -> Instant = { Instant.now() },
            httpClient: HttpClient =
                HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(20))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build(),
        ): MassiveDailyAggsArchiveClient {
            val key = env[ENV_API_KEY]?.trim()?.takeIf { it.isNotEmpty() }
            return MassiveDailyAggsArchiveClient(
                baseUrl = baseUrl,
                apiKey = key,
                limit = limit,
                httpClient = httpClient,
                clock = clock,
            )
        }

        private fun urlPathSegment(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
    }
}
