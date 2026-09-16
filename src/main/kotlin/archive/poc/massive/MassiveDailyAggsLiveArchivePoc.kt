package archive.poc.massive

/**
 * Optional live smoke for Massive Custom Bars 1d unadjusted forward archive.
 *
 * Env:
 * - MASSIVE_API_KEY (required for live; if absent → LIVE_UNVERIFIED, exit 0)
 * - ARCHIVE_ROOT (optional; default ./archive-runtime)
 * - MASSIVE_TICKER (optional; default AAPL)
 * - MASSIVE_FROM / MASSIVE_TO (optional; default a small recent window)
 * - MASSIVE_LIMIT (optional; default 50 for smoke)
 *
 * Never invents knownAt / SecurityId / currency / MIC / FIGI.
 * Never maps DailyPrice. Never fetches Ticker Overview.
 * Does not create API keys. Does not mock network.
 */
fun main() {
    val env = System.getenv()
    val key = env[MassiveDailyAggsArchiveClient.ENV_API_KEY]?.trim().orEmpty()
    if (key.isEmpty()) {
        println("PRICE Massive forward archive live smoke")
        println("liveClassification=LIVE_UNVERIFIED")
        println("reason=MASSIVE_API_KEY not set; refusing mock fallback")
        return
    }
    val root =
        java.nio.file.Path.of(
            env["ARCHIVE_ROOT"]?.trim()?.takeIf { it.isNotEmpty() } ?: "archive-runtime",
        )
    val ticker = env["MASSIVE_TICKER"]?.trim()?.takeIf { it.isNotEmpty() } ?: "AAPL"
    // Small window within Basic 2y history; caller may override.
    val to = env["MASSIVE_TO"]?.trim()?.takeIf { it.isNotEmpty() } ?: "2024-01-10"
    val from = env["MASSIVE_FROM"]?.trim()?.takeIf { it.isNotEmpty() } ?: "2024-01-02"
    val limit =
        env["MASSIVE_LIMIT"]?.trim()?.toIntOrNull()?.takeIf { it > 0 }
            ?: 50
    val client =
        MassiveDailyAggsArchiveClient.fromEnvironment(
            env = env,
            limit = limit,
        )
    val service =
        MassiveDailyAggsForwardArchiveService(
            archiveRoot = root,
            client = client,
        )
    println("PRICE Massive forward archive live smoke")
    println("domain=${MassiveDailyAggsArchiveClient.DOMAIN}")
    println("source=${MassiveDailyAggsArchiveClient.SOURCE}")
    println("ticker=$ticker (provider ticker only; not SecurityId)")
    println("from=$from to=$to limit=$limit adjusted=false")
    println("requestKey=${client.requestKeyFor(ticker, from, to)}")
    println("archiveRoot=$root")
    val result = service.archiveDailyAggs(ticker, from, to)
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
            archive.poc.ObservationStatus.REJECTED_VALIDATION ->
                "LIVE_PROVIDER_ENVELOPE_OR_VALIDATION_REJECTED"
            archive.poc.ObservationStatus.PROVIDER_FAILURE -> "LIVE_PROVIDER_FAILURE"
            archive.poc.ObservationStatus.LOCAL_ARCHIVE_FAILURE -> "LIVE_LOCAL_ARCHIVE_FAILURE"
            archive.poc.ObservationStatus.MISSING -> "LIVE_MISSING"
        }
    println("liveClassification=$liveClass")
}
