package archive.poc.massive

import archive.poc.ObservationStatus
import archive.poc.Sha256Hex
import java.nio.file.Files
import java.nio.file.Path

/**
 * Live schema probe / archive validation for Massive Stocks Ticker Events.
 *
 * Env:
 * - MASSIVE_API_KEY (required for live; absent → LIVE_UNVERIFIED, exit 0; no mock)
 * - ARCHIVE_ROOT (optional; default ./archive-runtime; gitignored)
 * - MASSIVE_TICKER_EVENTS_ID (optional; default [DEFAULT_LOOKUP_ID] = XYZ)
 *
 * Reuses [MassiveTickerEventsArchiveClient] / [MassiveTickerEventsForwardArchiveService] /
 * [MassiveTickerEventsArchiveValidator] only — no custom HTTP.
 *
 * Never invents old/new / SecurityId / knownAt / validFrom/validTo / continuity.
 * Never prints API key or raw body bytes.
 * Default probe is **1 request** (XYZ). Do not add control tickers in this runner.
 */
fun main() {
    val env = System.getenv()
    val key = env[MassiveTickerEventsArchiveClient.ENV_API_KEY]?.trim().orEmpty()
    val lookupId =
        env["MASSIVE_TICKER_EVENTS_ID"]?.trim()?.takeIf { it.isNotEmpty() }
            ?: MassiveTickerEventsLiveArchivePoc.DEFAULT_LOOKUP_ID

    println("SECURITY_MASTER Massive Ticker Events live schema probe / archive validation")
    println("domain=${MassiveTickerEventsArchiveClient.DOMAIN}")
    println("source=${MassiveTickerEventsArchiveClient.SOURCE}")
    println("lookupId=$lookupId (opaque provider lookup id; not SecurityId)")
    println("requestCountMax=1")
    println("event date!=knownAt; no OLD/NEW invention; no continuity inference")

    if (key.isEmpty()) {
        println("liveClassification=LIVE_UNVERIFIED")
        println("reason=MASSIVE_API_KEY not set; refusing mock fallback")
        return
    }

    val root =
        Path.of(env["ARCHIVE_ROOT"]?.trim()?.takeIf { it.isNotEmpty() } ?: "archive-runtime")
    val client = MassiveTickerEventsArchiveClient.fromEnvironment(env = env)
    val service =
        MassiveTickerEventsForwardArchiveService(
            archiveRoot = root,
            client = client,
        )
    val requestKey = client.requestKeyFor(lookupId)
    println("requestKey=$requestKey")
    println("archiveRoot=$root (gitignored; not committed)")

    val result = service.archiveTickerEvents(lookupId)
    val classification = MassiveTickerEventsLiveArchivePoc.classify(result)
    MassiveTickerEventsLiveArchivePoc.printResult(result, classification)
}

/**
 * Pure classification / print helpers for live Ticker Events probe (testable offline).
 */
object MassiveTickerEventsLiveArchivePoc {
    /** Free-tier live default: Block, Inc. current ticker (opaque lookup id). */
    const val DEFAULT_LOOKUP_ID = "XYZ"

    enum class LiveClassification {
        LIVE_SCHEMA_VERIFIED,
        LIVE_EMPTY_OBSERVED,
        LIVE_SCHEMA_REJECTED,
        LIVE_PROVIDER_FAILURE,
        LIVE_LOCAL_FAILURE,
        LIVE_UNVERIFIED,
    }

    fun classifyWithoutApiKey(): LiveClassification = LiveClassification.LIVE_UNVERIFIED

    fun classify(result: MassiveTickerEventsArchiveResult): LiveClassification {
        val record = result.record
        return when (record.observationStatus) {
            ObservationStatus.REJECTED_VALIDATION -> LiveClassification.LIVE_SCHEMA_REJECTED
            ObservationStatus.PROVIDER_FAILURE -> LiveClassification.LIVE_PROVIDER_FAILURE
            ObservationStatus.LOCAL_ARCHIVE_FAILURE -> LiveClassification.LIVE_LOCAL_FAILURE
            ObservationStatus.MISSING -> LiveClassification.LIVE_LOCAL_FAILURE
            ObservationStatus.OBSERVED -> {
                if (!integrityOk(result)) {
                    LiveClassification.LIVE_LOCAL_FAILURE
                } else if ((result.validation?.eventCount ?: -1) == 0) {
                    LiveClassification.LIVE_EMPTY_OBSERVED
                } else if (isSchemaVerified(result)) {
                    LiveClassification.LIVE_SCHEMA_VERIFIED
                } else {
                    LiveClassification.LIVE_LOCAL_FAILURE
                }
            }
        }
    }

    fun isSchemaVerified(result: MassiveTickerEventsArchiveResult): Boolean {
        val r = result.record
        val v = result.validation ?: return false
        if (r.observationStatus != ObservationStatus.OBSERVED) return false
        if (!result.observedIngestSucceeded) return false
        if (r.rawPayloadHash.isNullOrBlank()) return false
        if (r.rawPayloadUri.isNullOrBlank()) return false
        if (!integrityOk(result)) return false
        if (!v.okForObserved) return false
        if (v.eventCount < 1) return false
        if (v.validatedEvents.size != v.eventCount) return false
        return v.validatedEvents.all { ev ->
            ev.type == MassiveTickerEventsArchiveClient.TYPES_TICKER_CHANGE &&
                ev.date.isNotBlank() &&
                ev.ticker.isNotBlank()
        }
    }

    fun integrityOk(result: MassiveTickerEventsArchiveResult): Boolean {
        val r = result.record
        val hash = r.rawPayloadHash ?: return false
        val uri = r.rawPayloadUri ?: return false
        return try {
            val bytes = Files.readAllBytes(Path.of(uri))
            Sha256Hex.of(bytes) == hash
        } catch (_: Exception) {
            false
        }
    }

    fun printResult(
        result: MassiveTickerEventsArchiveResult,
        classification: LiveClassification,
    ) {
        val r = result.record
        val v = result.validation
        println("observationStatus=${r.observationStatus}")
        println("httpStatus=${r.httpStatus}")
        println("fetchedAt=${r.fetchedAt}")
        println("ingestedAt=${r.ingestedAt}")
        println("eligibilityBoundaryAt=${r.eligibilityBoundaryAt}")
        println("rawPayloadHash=${r.rawPayloadHash}")
        println("rawPayloadUri=${r.rawPayloadUri}")
        println("observedIngestSucceeded=${result.observedIngestSucceeded}")
        println("eventCount=${v?.eventCount}")
        println("validatedEventsRawOrder:")
        val events = v?.validatedEvents.orEmpty()
        if (events.isEmpty()) {
            println("(none)")
        } else {
            events.forEachIndexed { i, ev ->
                println("  [$i] type=${ev.type} date=${ev.date} ticker=${ev.ticker}")
            }
        }
        println("notes=${r.notes}")
        println(
            "coverage observedCount=${result.coverage.observedCount} " +
                "start=${result.coverage.coverageStartAt} through=${result.coverage.coverageThroughAt}",
        )
        println("integrityOk=${integrityOk(result)}")
        println("liveClassification=$classification")
    }
}
