package archive.poc.openfigi

import archive.poc.Sha256Hex
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

data class OpenFigiMappingJob(
    val idType: String,
    val idValue: String,
    val exchCode: String? = null,
) {
    init {
        require(idType.isNotBlank())
        require(idValue.isNotBlank())
    }
}

object OpenFigiMappingRequestBody {
    fun encode(jobs: List<OpenFigiMappingJob>): ByteArray {
        require(jobs.isNotEmpty()) { "mapping jobs must not be empty" }
        require(jobs.size <= 10) {
            "OpenFIGI without API key allows at most 10 jobs/request; got ${jobs.size}"
        }
        val json =
            jobs.joinToString(",", "[", "]") { job ->
                buildString {
                    append('{')
                    append("\"idType\":").append(quote(job.idType)).append(',')
                    append("\"idValue\":").append(quote(job.idValue))
                    if (job.exchCode != null) {
                        append(",\"exchCode\":").append(quote(job.exchCode))
                    }
                    append('}')
                }
            }
        return json.toByteArray(StandardCharsets.UTF_8)
    }

    private fun quote(value: String): String =
        buildString {
            append('"')
            value.forEach { c ->
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    else -> {
                        require(c.code >= 0x20) { "control char not allowed in OpenFIGI field" }
                        append(c)
                    }
                }
            }
            append('"')
        }
}

/**
 * HTTP possession result.
 * [fetchedAt] set only when response body bytes were fully received (independent of HTTP status).
 */
data class OpenFigiHttpPossession(
    val attemptedAt: Instant,
    val attemptFinishedAt: Instant,
    val fetchedAt: Instant?,
    val httpStatus: Int?,
    val contentType: String?,
    val bodyBytes: ByteArray?,
    val transportFailureMessage: String?,
) {
    val bodyFullyReceived: Boolean get() = bodyBytes != null && fetchedAt != null
}

/**
 * Official mapping endpoint: POST https://api.openfigi.com/v3/mapping
 * Optional header X-OPENFIGI-APIKEY from env OPENFIGI_API_KEY only.
 * API key never enters requestKey / logs / fixtures.
 */
class OpenFigiMappingClient(
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

    fun executeMapping(requestBodyBytes: ByteArray): OpenFigiHttpPossession {
        val attemptedAt = clock()
        val endpoint = "${baseUrl.trimEnd('/')}$MAPPING_PATH"
        val builder =
            HttpRequest.newBuilder()
                .uri(URI.create(endpoint))
                .timeout(requestTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(requestBodyBytes))
        apiKey?.let { builder.header(API_KEY_HEADER, it) }

        return try {
            val response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
            val attemptFinishedAt = clock()
            val body = response.body() ?: ByteArray(0)
            OpenFigiHttpPossession(
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
            OpenFigiHttpPossession(
                attemptedAt = attemptedAt,
                attemptFinishedAt = attemptFinishedAt,
                fetchedAt = null,
                httpStatus = null,
                contentType = null,
                bodyBytes = null,
                transportFailureMessage =
                    e::class.java.simpleName + ": " + (e.message ?: "transport failure"),
            )
        }
    }

    companion object {
        const val DEFAULT_BASE_URL = "https://api.openfigi.com"
        const val MAPPING_PATH = "/v3/mapping"
        const val API_KEY_HEADER = "X-OPENFIGI-APIKEY"
        const val ENV_API_KEY = "OPENFIGI_API_KEY"
        const val SOURCE = "openfigi.v3.mapping"
        const val DOMAIN = "SECURITY_MASTER"

        fun requestKey(requestBodyBytes: ByteArray): String =
            "POST|$MAPPING_PATH|sha256:${Sha256Hex.of(requestBodyBytes)}"

        fun fromEnvironment(
            env: Map<String, String> = System.getenv(),
            baseUrl: String = DEFAULT_BASE_URL,
            clock: () -> Instant = { Instant.now() },
            httpClient: HttpClient =
                HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(20))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build(),
        ): OpenFigiMappingClient {
            val key = env[ENV_API_KEY]?.trim()?.takeIf { it.isNotEmpty() }
            return OpenFigiMappingClient(
                baseUrl = baseUrl,
                apiKey = key,
                httpClient = httpClient,
                clock = clock,
            )
        }
    }
}
