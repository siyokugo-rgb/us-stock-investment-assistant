package archive.poc.massive

/**
 * Optional live smoke for Massive Stocks All Tickers forward archive.
 *
 * Env:
 * - MASSIVE_API_KEY (required for live; if absent → LIVE_UNVERIFIED, exit 0)
 * - ARCHIVE_ROOT (optional; default ./archive-runtime)
 * - MASSIVE_TICKER (optional; default AAPL — exact ticker filter)
 * - MASSIVE_ALL_TICKERS_ACTIVE (optional; "true"/"false"; omitted when unset)
 * - MASSIVE_ALL_TICKERS_DATE (optional; YYYY-MM-DD provider as-of selector; NOT knownAt/PIT)
 * - MASSIVE_ALL_TICKERS_LIMIT (optional; default 1 for single-page PoC)
 *
 * Never invents knownAt / SecurityId / DailyPrice.currency / MIC / venue / IssuerId.
 * Never follows next_url. Does not claim complete Security Master.
 */
fun main() {
    val env = System.getenv()
    val key = env[MassiveAllTickersArchiveClient.ENV_API_KEY]?.trim().orEmpty()
    if (key.isEmpty()) {
        println("SECURITY_MASTER Massive All Tickers forward archive live smoke")
        println("liveClassification=LIVE_UNVERIFIED")
        println("reason=MASSIVE_API_KEY not set; refusing mock fallback")
        return
    }
    val root =
        java.nio.file.Path.of(
            env["ARCHIVE_ROOT"]?.trim()?.takeIf { it.isNotEmpty() } ?: "archive-runtime",
        )
    val ticker = env["MASSIVE_TICKER"]?.trim()?.takeIf { it.isNotEmpty() } ?: "AAPL"
    val active =
        env["MASSIVE_ALL_TICKERS_ACTIVE"]?.trim()?.takeIf { it.isNotEmpty() }?.let {
            when (it.lowercase()) {
                "true" -> true
                "false" -> false
                else -> error("MASSIVE_ALL_TICKERS_ACTIVE must be true or false")
            }
        }
    val date = env["MASSIVE_ALL_TICKERS_DATE"]?.trim()?.takeIf { it.isNotEmpty() }
    val limit =
        env["MASSIVE_ALL_TICKERS_LIMIT"]?.trim()?.takeIf { it.isNotEmpty() }?.toIntOrNull()
            ?: MassiveAllTickersArchiveClient.DEFAULT_LIMIT
    val client =
        MassiveAllTickersArchiveClient.fromEnvironment(
            env = env,
            limit = limit,
        )
    val service =
        MassiveAllTickersForwardArchiveService(
            archiveRoot = root,
            client = client,
        )
    println("SECURITY_MASTER Massive All Tickers forward archive live smoke")
    println("domain=${MassiveAllTickersArchiveClient.DOMAIN}")
    println("source=${MassiveAllTickersArchiveClient.SOURCE}")
    println("ticker=$ticker (provider ticker filter only; not SecurityId)")
    println("active=${active ?: "(omitted; provider default true)"}")
    println("date=${date ?: "(latest; date query omitted)"}")
    println("limit=$limit")
    println("requestKey=${client.requestKeyFor(ticker = ticker, active = active, date = date)}")
    println("archiveRoot=$root")
    val result = service.archiveAllTickers(ticker = ticker, active = active, date = date)
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
