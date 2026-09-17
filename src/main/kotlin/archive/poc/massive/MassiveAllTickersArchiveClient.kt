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
 * Official endpoint: GET https://api.massive.com/v3/reference/tickers
 * Query (PoC): market=stocks (required) + optional ticker / active / date + limit (+ sort/order if used).
 *
 * Auth: query `apiKey` from env [ENV_API_KEY] only.
 *
 * Domain: SECURITY_MASTER
 * Source: massive.stocks.all_tickers
 *
 * Distinct from Ticker Overview (`GET /v3/reference/tickers/{ticker}` /
 * `massive.stocks.ticker_overview`).
 *
 * Never elevates currency_symbol / currency_name / primary_exchange / FIGI / CIK / ticker
 * to DailyPrice.currency, MIC, SecurityId, or IssuerId.
 * Never enters apiKey into requestKey / logs / fixtures / raw paths / manifest.
 *
 * Pagination: this client does **not** follow `next_url`. Validator Fail-Closes when present.
 */
class MassiveAllTickersArchiveClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val apiKey: String? = null,
    private val limit: Int = DEFAULT_LIMIT,
    private val sort: String? = DEFAULT_SORT,
    private val order: String? = DEFAULT_ORDER,
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
        sort?.let { require(it.isNotBlank()) { "sort must not be blank when provided" } }
        order?.let { require(it.isNotBlank()) { "order must not be blank when provided" } }
    }

    /**
     * @param ticker optional exact ticker filter (provider query `ticker=`).
     * @param active optional active filter (`true`/`false`). Null → omitted (provider default true).
     * @param date optional official `date` query (YYYY-MM-DD): provider as-of selector only.
     *   Never historical knownAt / knowledge-PIT / eligibility backdating.
     */
    fun executeAllTickers(
        ticker: String? = null,
        active: Boolean? = null,
        date: String? = null,
    ): MassiveHttpPossession {
        val normalizedTicker = ticker?.trim()?.takeIf { it.isNotEmpty() }
        val normalizedDate = date?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedDate != null) {
            require(DATE_PATTERN.matches(normalizedDate)) {
                "date must be YYYY-MM-DD when provided"
            }
        }

        val key =
            apiKey
                ?: throw IllegalStateException(
                    "${ENV_API_KEY} required for live HTTP (local configuration; no provider attempt)",
                )
        require(key.isNotBlank()) {
            "${ENV_API_KEY} required for live HTTP (local configuration; no provider attempt)"
        }

        val requestKey =
            requestKeyFor(
                ticker = normalizedTicker,
                active = active,
                date = normalizedDate,
            )
        val attemptedAt = clock()

        return try {
            val encKey = URLEncoder.encode(key, StandardCharsets.UTF_8)
            val query =
                buildString {
                    append("market=").append(URLEncoder.encode(MARKET_STOCKS, StandardCharsets.UTF_8))
                    if (normalizedTicker != null) {
                        append("&ticker=")
                            .append(URLEncoder.encode(normalizedTicker, StandardCharsets.UTF_8))
                    }
                    if (active != null) {
                        append("&active=").append(active)
                    }
                    if (normalizedDate != null) {
                        append("&date=")
                            .append(URLEncoder.encode(normalizedDate, StandardCharsets.UTF_8))
                    }
                    append("&limit=").append(limit)
                    if (sort != null) {
                        append("&sort=")
                            .append(URLEncoder.encode(sort, StandardCharsets.UTF_8))
                    }
                    if (order != null) {
                        append("&order=")
                            .append(URLEncoder.encode(order, StandardCharsets.UTF_8))
                    }
                    append("&apiKey=").append(encKey)
                }
            val endpoint = "${baseUrl.trimEnd('/')}$PATH?$query"
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
        ticker: String? = null,
        active: Boolean? = null,
        date: String? = null,
    ): String =
        requestKey(
            ticker = ticker?.trim()?.takeIf { it.isNotEmpty() },
            active = active,
            date = date?.trim()?.takeIf { it.isNotEmpty() },
            limit = limit,
            sort = sort,
            order = order,
        )

    companion object {
        const val DEFAULT_BASE_URL = "https://api.massive.com"
        const val PATH = "/v3/reference/tickers"
        const val MARKET_STOCKS = "stocks"
        const val DEFAULT_LIMIT = 1
        const val MAX_LIMIT = 1000
        const val DEFAULT_SORT = "ticker"
        const val DEFAULT_ORDER = "asc"
        const val ENV_API_KEY = "MASSIVE_API_KEY"
        const val DOMAIN = "SECURITY_MASTER"
        /** Massive Stocks All Tickers list — distinct from ticker_overview. */
        const val SOURCE = "massive.stocks.all_tickers"
        private val DATE_PATTERN = Regex("^\\d{4}-\\d{2}-\\d{2}$")

        /**
         * Secret-free request identity. Includes every query semantic that changes the request.
         * Provider ticker is request provenance only — not SecurityId / externalIdentifier.
         *
         * Canonical order:
         * `GET|/v3/reference/tickers|market=stocks[|ticker=…][|active=…][|date=…]|limit=N[|sort=…][|order=…]`
         */
        fun requestKey(
            ticker: String? = null,
            active: Boolean? = null,
            date: String? = null,
            limit: Int = DEFAULT_LIMIT,
            sort: String? = DEFAULT_SORT,
            order: String? = DEFAULT_ORDER,
        ): String {
            require(limit > 0)
            require(limit <= MAX_LIMIT)
            val normalizedTicker = ticker?.trim()?.takeIf { it.isNotEmpty() }
            val normalizedDate = date?.trim()?.takeIf { it.isNotEmpty() }
            if (normalizedDate != null) {
                require(DATE_PATTERN.matches(normalizedDate)) {
                    "date must be YYYY-MM-DD when provided"
                }
            }
            val normalizedSort = sort?.trim()?.takeIf { it.isNotEmpty() }
            val normalizedOrder = order?.trim()?.takeIf { it.isNotEmpty() }
            return buildString {
                append("GET|$PATH|market=$MARKET_STOCKS")
                if (normalizedTicker != null) {
                    append("|ticker=$normalizedTicker")
                }
                if (active != null) {
                    append("|active=$active")
                }
                if (normalizedDate != null) {
                    append("|date=$normalizedDate")
                }
                append("|limit=$limit")
                if (normalizedSort != null) {
                    append("|sort=$normalizedSort")
                }
                if (normalizedOrder != null) {
                    append("|order=$normalizedOrder")
                }
            }
        }

        fun fromEnvironment(
            env: Map<String, String> = System.getenv(),
            baseUrl: String = DEFAULT_BASE_URL,
            limit: Int = DEFAULT_LIMIT,
            sort: String? = DEFAULT_SORT,
            order: String? = DEFAULT_ORDER,
            clock: () -> Instant = { Instant.now() },
            httpClient: HttpClient =
                HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(20))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build(),
        ): MassiveAllTickersArchiveClient {
            val key = env[ENV_API_KEY]?.trim()?.takeIf { it.isNotEmpty() }
            return MassiveAllTickersArchiveClient(
                baseUrl = baseUrl,
                apiKey = key,
                limit = limit,
                sort = sort,
                order = order,
                httpClient = httpClient,
                clock = clock,
            )
        }
    }
}
