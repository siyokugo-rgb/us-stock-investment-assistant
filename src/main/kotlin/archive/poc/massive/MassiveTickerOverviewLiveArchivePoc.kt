package archive.poc.massive

/**
 * Optional live smoke for Massive Stocks Ticker Overview forward archive.
 *
 * Env:
 * - MASSIVE_API_KEY (required for live; if absent → LIVE_UNVERIFIED, exit 0)
 * - ARCHIVE_ROOT (optional; default ./archive-runtime)
 * - MASSIVE_TICKER (optional; default AAPL)
 * - MASSIVE_OVERVIEW_DATE (optional; YYYY-MM-DD point-in-time; default omit = latest)
 *
 * Never invents knownAt / SecurityId / DailyPrice.currency / MIC / venue / IssuerId.
 * Never joins PRICE ↔ Overview. Never mocks network. Does not create API keys.
 */
fun main() {
    val env = System.getenv()
    val key = env[MassiveTickerOverviewArchiveClient.ENV_API_KEY]?.trim().orEmpty()
    if (key.isEmpty()) {
        println("SECURITY_MASTER Massive Ticker Overview forward archive live smoke")
        println("liveClassification=LIVE_UNVERIFIED")
        println("reason=MASSIVE_API_KEY not set; refusing mock fallback")
        return
    }
    val root =
        java.nio.file.Path.of(
            env["ARCHIVE_ROOT"]?.trim()?.takeIf { it.isNotEmpty() } ?: "archive-runtime",
        )
    val ticker = env["MASSIVE_TICKER"]?.trim()?.takeIf { it.isNotEmpty() } ?: "AAPL"
    val date = env["MASSIVE_OVERVIEW_DATE"]?.trim()?.takeIf { it.isNotEmpty() }
    val client = MassiveTickerOverviewArchiveClient.fromEnvironment(env = env)
    val service =
        MassiveTickerOverviewForwardArchiveService(
            archiveRoot = root,
            client = client,
        )
    println("SECURITY_MASTER Massive Ticker Overview forward archive live smoke")
    println("domain=${MassiveTickerOverviewArchiveClient.DOMAIN}")
    println("source=${MassiveTickerOverviewArchiveClient.SOURCE}")
    println("ticker=$ticker (provider ticker only; not SecurityId)")
    println("date=${date ?: "(latest; date query omitted)"}")
    println("requestKey=${client.requestKeyFor(ticker, date)}")
    println("archiveRoot=$root")
    val result = service.archiveTickerOverview(ticker, date)
    val r = result.record
    println("status=${r.observationStatus}")
    println("httpStatus=${r.httpStatus}")
    println("fetchedAt=${r.fetchedAt}")
    println("ingestedAt=${r.ingestedAt}")
    println("eligibilityBoundaryAt=${r.eligibilityBoundaryAt}")
    println("rawPayloadHash=${r.rawPayloadHash}")
    println("rawPayloadUri=${r.rawPayloadUri}")
    println("externalIdentifier=${r.externalIdentifier}")
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
