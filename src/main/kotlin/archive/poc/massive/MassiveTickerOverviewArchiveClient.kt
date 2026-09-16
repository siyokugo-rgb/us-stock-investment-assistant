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
 * Official endpoint: GET https://api.massive.com/v3/reference/tickers/{ticker}
 * Optional query: date=YYYY-MM-DD (point-in-time reference snapshot).
 * Auth: query `apiKey` from env [ENV_API_KEY] only.
 *
 * Domain: SECURITY_MASTER (reference metadata — not PRICE).
 * Source: massive.stocks.ticker_overview
 *
 * Never elevates ticker / FIGI / CIK / currency_name / primary_exchange to SecurityId,
 * DailyPrice.currency, venue resolution, or IssuerId.
 * Never enters apiKey into requestKey / logs / fixtures / raw paths / manifest.
 */
class MassiveTickerOverviewArchiveClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val apiKey: String? = null,
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
        apiKey?.let { require(it.isNotBlank()) }
    }

    /**
     * @param date optional official `date` query (YYYY-MM-DD). Null/blank → latest available
     *   (omitted from URL and from requestKey).
     */
    fun executeTickerOverview(
        ticker: String,
        date: String? = null,
    ): MassiveHttpPossession {
        require(ticker.isNotBlank()) { "ticker must not be blank" }
        val normalizedDate = date?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedDate != null) {
            require(DATE_PATTERN.matches(normalizedDate)) {
                "date must be YYYY-MM-DD when provided"
            }
        }

        // Local configuration: fail-fast BEFORE provider HTTP attempt / possession record.
        val key =
            apiKey
                ?: throw IllegalStateException(
                    "${ENV_API_KEY} required for live HTTP (local configuration; no provider attempt)",
                )
        require(key.isNotBlank()) {
            "${ENV_API_KEY} required for live HTTP (local configuration; no provider attempt)"
        }

        val requestKey = requestKeyFor(ticker, normalizedDate)
        val attemptedAt = clock()

        return try {
            val encTicker = urlPathSegment(ticker.trim())
            val encKey = URLEncoder.encode(key, StandardCharsets.UTF_8)
            val path = "$PATH_PREFIX$encTicker"
            val query =
                buildString {
                    append("apiKey=").append(encKey)
                    if (normalizedDate != null) {
                        append("&date=").append(URLEncoder.encode(normalizedDate, StandardCharsets.UTF_8))
                    }
                }
            val endpoint = "${baseUrl.trimEnd('/')}$path?$query"
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
        date: String? = null,
    ): String = requestKey(ticker.trim(), date?.trim()?.takeIf { it.isNotEmpty() })

    companion object {
        const val DEFAULT_BASE_URL = "https://api.massive.com"
        const val PATH_PREFIX = "/v3/reference/tickers/"
        const val ENV_API_KEY = "MASSIVE_API_KEY"
        const val DOMAIN = "SECURITY_MASTER"
        /** Massive Stocks Ticker Overview — reference metadata, not PRICE aggregate. */
        const val SOURCE = "massive.stocks.ticker_overview"
        private val DATE_PATTERN = Regex("^\\d{4}-\\d{2}-\\d{2}$")

        /**
         * Secret-free request identity.
         * Provider ticker is request provenance only — not SecurityId / externalIdentifier.
         */
        fun requestKey(
            ticker: String,
            date: String? = null,
        ): String {
            require(ticker.isNotBlank())
            val normalizedDate = date?.trim()?.takeIf { it.isNotEmpty() }
            if (normalizedDate != null) {
                require(DATE_PATTERN.matches(normalizedDate)) {
                    "date must be YYYY-MM-DD when provided"
                }
            }
            val base = "GET|$PATH_PREFIX${ticker.trim()}"
            return if (normalizedDate == null) {
                base
            } else {
                "$base|date=$normalizedDate"
            }
        }

        fun fromEnvironment(
            env: Map<String, String> = System.getenv(),
            baseUrl: String = DEFAULT_BASE_URL,
            clock: () -> Instant = { Instant.now() },
            httpClient: HttpClient =
                HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(20))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build(),
        ): MassiveTickerOverviewArchiveClient {
            val key = env[ENV_API_KEY]?.trim()?.takeIf { it.isNotEmpty() }
            return MassiveTickerOverviewArchiveClient(
                baseUrl = baseUrl,
                apiKey = key,
                httpClient = httpClient,
                clock = clock,
            )
        }

        private fun urlPathSegment(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
    }
}
