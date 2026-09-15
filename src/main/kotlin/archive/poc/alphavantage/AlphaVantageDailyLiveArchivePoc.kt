package archive.poc.alphavantage

/**
 * Optional live smoke for Alpha Vantage TIME_SERIES_DAILY forward archive.
 *
 * Env:
 * - ALPHAVANTAGE_API_KEY (optional; demo used if absent)
 * - ARCHIVE_ROOT (optional; default ./archive-runtime)
 * - AV_SYMBOL (optional; default IBM)
 *
 * Live success is NOT required. demo/keyless often returns Information envelope →
 * REJECTED_VALIDATION with raw possession (expected).
 *
 * Never invents knownAt / SecurityId / currency. Never maps DailyPrice.
 */
fun main() {
    val env = System.getenv()
    val root =
        java.nio.file.Path.of(
            env["ARCHIVE_ROOT"]?.trim()?.takeIf { it.isNotEmpty() } ?: "archive-runtime",
        )
    val symbol = env["AV_SYMBOL"]?.trim()?.takeIf { it.isNotEmpty() } ?: "IBM"
    val client = AlphaVantageDailyArchiveClient.fromEnvironment(env)
    val service =
        AlphaVantageDailyForwardArchiveService(
            archiveRoot = root,
            client = client,
        )
    println("PRICE forward archive live smoke")
    println("domain=${AlphaVantageDailyArchiveClient.DOMAIN}")
    println("source=${AlphaVantageDailyArchiveClient.SOURCE}")
    println("symbol=$symbol (provider symbol only; not SecurityId)")
    println("requestKey=${client.requestKeyFor(symbol)}")
    println("archiveRoot=$root")
    val result = service.archiveDaily(symbol)
    val r = result.record
    println("status=${r.observationStatus}")
    println("httpStatus=${r.httpStatus}")
    println("fetchedAt=${r.fetchedAt}")
    println("ingestedAt=${r.ingestedAt}")
    println("eligibilityBoundaryAt=${r.eligibilityBoundaryAt}")
    println("rawPayloadHash=${r.rawPayloadHash}")
    println("rawPayloadUri=${r.rawPayloadUri}")
    println("observedIngestSucceeded=${result.observedIngestSucceeded}")
    println("notes=${r.notes}")
    println(
        "coverage observedCount=${result.coverage.observedCount} " +
            "start=${result.coverage.coverageStartAt} through=${result.coverage.coverageThroughAt}",
    )
    val liveClass =
        when (r.observationStatus) {
            archive.poc.ObservationStatus.OBSERVED -> "LIVE_OBSERVED"
            archive.poc.ObservationStatus.REJECTED_VALIDATION -> "LIVE_PROVIDER_ENVELOPE_OR_VALIDATION_REJECTED"
            archive.poc.ObservationStatus.PROVIDER_FAILURE -> "LIVE_PROVIDER_FAILURE"
            archive.poc.ObservationStatus.LOCAL_ARCHIVE_FAILURE -> "LIVE_LOCAL_ARCHIVE_FAILURE"
            archive.poc.ObservationStatus.MISSING -> "LIVE_MISSING"
        }
    println("liveClassification=$liveClass")
}
