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
 * Official endpoint: GET https://api.massive.com/vX/reference/tickers/{id}/events
 * Query (this PoC): types=ticker_change&apiKey=…
 *
 * Domain: SECURITY_MASTER
 * Source: massive.stocks.ticker_events
 *
 * [id] is an opaque provider lookup id (Ticker / CUSIP / Composite FIGI per docs).
 * This client does **not** infer id namespace and never elevates id / ticker_change.ticker
 * to SecurityId / SecurityIdentifier.
 *
 * Endpoint is experimental. Never enters apiKey into requestKey / logs / fixtures /
 * raw paths / manifest / exception message storage.
 */
class MassiveTickerEventsArchiveClient(
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
     * @param lookupId opaque provider path id (not SecurityId; namespace not inferred).
     */
    fun executeTickerEvents(lookupId: String): MassiveHttpPossession {
        val id = normalizeLookupIdOrThrow(lookupId)

        val key =
            apiKey
                ?: throw IllegalStateException(
                    "${ENV_API_KEY} required for live HTTP (local configuration; no provider attempt)",
                )
        require(key.isNotBlank()) {
            "${ENV_API_KEY} required for live HTTP (local configuration; no provider attempt)"
        }

        val requestKey = requestKeyFor(id)
        val attemptedAt = clock()

        return try {
            val encId = urlPathSegment(id)
            val encKey = URLEncoder.encode(key, StandardCharsets.UTF_8)
            val path = "$PATH_PREFIX$encId$PATH_SUFFIX"
            val query = "types=$TYPES_TICKER_CHANGE&apiKey=$encKey"
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

    fun requestKeyFor(lookupId: String): String = requestKey(normalizeLookupIdOrThrow(lookupId))

    companion object {
        const val DEFAULT_BASE_URL = "https://api.massive.com"
        const val PATH_PREFIX = "/vX/reference/tickers/"
        const val PATH_SUFFIX = "/events"
        const val TYPES_TICKER_CHANGE = "ticker_change"
        const val ENV_API_KEY = "MASSIVE_API_KEY"
        const val DOMAIN = "SECURITY_MASTER"
        /** Massive Stocks Ticker Events — experimental reference timeline, not PRICE. */
        const val SOURCE = "massive.stocks.ticker_events"

        /**
         * Secret-free request identity.
         * Lookup id is request provenance only — not SecurityId / externalIdentifier.
         * types=ticker_change is fixed for this PoC.
         */
        fun requestKey(lookupId: String): String {
            val id = normalizeLookupIdOrThrow(lookupId)
            return "GET|$PATH_PREFIX$id$PATH_SUFFIX|types=$TYPES_TICKER_CHANGE"
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
        ): MassiveTickerEventsArchiveClient {
            val key = env[ENV_API_KEY]?.trim()?.takeIf { it.isNotEmpty() }
            return MassiveTickerEventsArchiveClient(
                baseUrl = baseUrl,
                apiKey = key,
                httpClient = httpClient,
                clock = clock,
            )
        }

        fun normalizeLookupIdOrThrow(lookupId: String): String {
            val id = lookupId.trim()
            require(id.isNotBlank()) { "lookup id must not be blank" }
            require(!id.contains('|')) { "lookup id must not contain '|'" }
            require(!id.contains('/')) { "lookup id must not contain '/'" }
            require(!id.contains('?')) { "lookup id must not contain '?'" }
            require(!id.contains('#')) { "lookup id must not contain '#'" }
            require(!id.contains('&')) { "lookup id must not contain '&'" }
            require(id == lookupId.trim()) { "lookup id must be trim-stable" }
            return id
        }

        private fun urlPathSegment(value: String): String =
            URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")
    }
}
