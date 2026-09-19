package archive.poc.binding

import archive.poc.ManifestRecord
import archive.poc.ObservationStatus
import archive.poc.Sha256Hex
import archive.poc.massive.MassiveTickerEventsArchiveClient
import archive.poc.massive.MassiveTickerEventsArchiveResult
import archive.poc.massive.MassiveTickerEventsForwardArchiveService
import archive.poc.massive.MassiveTickerOverviewArchiveClient
import archive.poc.massive.MassiveTickerOverviewArchiveResult
import archive.poc.massive.MassiveTickerOverviewForwardArchiveService
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Live Ticker Events ↔ Overview corroboration validation (free-tier, max 3 requests).
 *
 * Fixed scope:
 * - Overview T1: SQ @ 2025-01-17
 * - Overview T2: XYZ @ 2025-01-22
 * - Ticker Events: lookupId=XYZ
 *
 * Reuses existing Overview/Events archive services + continuity/corroboration derivers.
 * No custom HTTP. No retry. No mock. No SecurityId / knownAt / validity / DailyPrice.
 *
 * Env:
 * - MASSIVE_API_KEY (required for live; absent → LIVE_UNVERIFIED, requestCount=0)
 * - ARCHIVE_ROOT (optional; default ./archive-runtime; gitignored)
 *
 * Does not print API key or raw body bytes.
 */
fun main() {
    val env = System.getenv()
    val key = env[MassiveTickerOverviewArchiveClient.ENV_API_KEY]?.trim().orEmpty()
    val runAt = Instant.now()

    println("Ticker Events ↔ Overview live corroboration validation")
    println("runAtUtc=$runAt")
    println("MASSIVE_API_KEY=${if (key.isEmpty()) "NOT SET" else "SET"}")
    println(
        "scope=T1 ${TickerEventOverviewCorroborationLivePoc.DEFAULT_T1_TICKER}/" +
            "${TickerEventOverviewCorroborationLivePoc.DEFAULT_T1_DATE} " +
            "T2 ${TickerEventOverviewCorroborationLivePoc.DEFAULT_T2_TICKER}/" +
            "${TickerEventOverviewCorroborationLivePoc.DEFAULT_T2_DATE} " +
            "Events ${TickerEventOverviewCorroborationLivePoc.DEFAULT_EVENTS_LOOKUP}",
    )
    println("requestCountMax=3 (no retry; no hole-fill)")
    println(
        "claimBoundary=TICKER_CHANGE_CANDIDATE + CORROBORATED only; " +
            "≠ SecurityId / knownAt / validFrom/validTo / DailyPrice",
    )

    if (key.isEmpty()) {
        val unverified =
            TickerEventOverviewCorroborationLivePoc.outcomeWithoutApiKey()
        TickerEventOverviewCorroborationLivePoc.printOutcome(unverified)
        return
    }

    val root =
        Path.of(env["ARCHIVE_ROOT"]?.trim()?.takeIf { it.isNotEmpty() } ?: "archive-runtime")
    println("archiveRoot=$root (gitignored; not committed)")

    val overviewClient = MassiveTickerOverviewArchiveClient.fromEnvironment(env = env)
    val overviewService =
        MassiveTickerOverviewForwardArchiveService(
            archiveRoot = root,
            client = overviewClient,
        )
    val eventsClient = MassiveTickerEventsArchiveClient.fromEnvironment(env = env)
    val eventsService =
        MassiveTickerEventsForwardArchiveService(
            archiveRoot = root,
            client = eventsClient,
        )

    val outcome =
        TickerEventOverviewCorroborationLivePoc.runLive(
            overviewService = overviewService,
            eventsService = eventsService,
        )
    TickerEventOverviewCorroborationLivePoc.printOutcome(outcome)
}

/**
 * Pure classification / orchestration helpers for live corroboration validation (testable).
 */
object TickerEventOverviewCorroborationLivePoc {
    const val DEFAULT_T1_TICKER = "SQ"
    const val DEFAULT_T1_DATE = "2025-01-17"
    const val DEFAULT_T2_TICKER = "XYZ"
    const val DEFAULT_T2_DATE = "2025-01-22"
    const val DEFAULT_EVENTS_LOOKUP = "XYZ"

    enum class LiveClassification {
        LIVE_CORROBORATED,
        LIVE_UNRESOLVED,
        LIVE_PROVIDER_FAILURE,
        LIVE_VALIDATION_REJECTED,
        LIVE_LOCAL_FAILURE,
        LIVE_UNVERIFIED,
    }

    data class ArchiveAudit(
        val label: String,
        val requestKey: String?,
        val observationStatus: ObservationStatus?,
        val httpStatus: Int?,
        val rawPayloadHash: String?,
        val rawPayloadUri: String?,
        val eligibilityBoundaryAt: Instant?,
        val observedIngestSucceeded: Boolean?,
        val localException: String? = null,
    )

    data class LiveRunOutcome(
        val apiKeyPresent: Boolean,
        val requestCount: Int,
        val t1: ArchiveAudit,
        val t2: ArchiveAudit,
        val events: ArchiveAudit,
        val continuityStatus: SecurityIdentityContinuityStatus?,
        val continuityReason: SecurityIdentityContinuityReason?,
        val firstProviderTicker: String?,
        val secondProviderTicker: String?,
        val firstProviderAsOfDate: String?,
        val secondProviderAsOfDate: String?,
        val firstShareClassFigi: String?,
        val secondShareClassFigi: String?,
        val firstCompositeFigi: String?,
        val secondCompositeFigi: String?,
        val continuityEvidenceEligibleAt: Instant?,
        val corroborationStatus: TickerEventOverviewCorroborationStatus?,
        val corroborationReason: TickerEventOverviewCorroborationReason?,
        val tickerEventsLookupId: String?,
        val matchedEventType: String?,
        val matchedEventDate: String?,
        val matchedEventTicker: String?,
        val corroborationContinuityEligibleAt: Instant?,
        val tickerEventsEligibilityBoundaryAt: Instant?,
        val corroborationEligibleAt: Instant?,
        val liveClassification: LiveClassification,
        val deriverError: String? = null,
    )

    fun classifyWithoutApiKey(): LiveClassification = LiveClassification.LIVE_UNVERIFIED

    fun outcomeWithoutApiKey(): LiveRunOutcome =
        LiveRunOutcome(
            apiKeyPresent = false,
            requestCount = 0,
            t1 = emptyAudit("T1"),
            t2 = emptyAudit("T2"),
            events = emptyAudit("Events"),
            continuityStatus = null,
            continuityReason = null,
            firstProviderTicker = null,
            secondProviderTicker = null,
            firstProviderAsOfDate = null,
            secondProviderAsOfDate = null,
            firstShareClassFigi = null,
            secondShareClassFigi = null,
            firstCompositeFigi = null,
            secondCompositeFigi = null,
            continuityEvidenceEligibleAt = null,
            corroborationStatus = null,
            corroborationReason = null,
            tickerEventsLookupId = null,
            matchedEventType = null,
            matchedEventDate = null,
            matchedEventTicker = null,
            corroborationContinuityEligibleAt = null,
            tickerEventsEligibilityBoundaryAt = null,
            corroborationEligibleAt = null,
            liveClassification = LiveClassification.LIVE_UNVERIFIED,
        )

    /**
     * Exactly one attempt each for T1 Overview, T2 Overview, Events.
     * No retry. Continues remaining planned requests after a per-request failure.
     */
    fun runLive(
        overviewService: MassiveTickerOverviewForwardArchiveService,
        eventsService: MassiveTickerEventsForwardArchiveService,
        t1Ticker: String = DEFAULT_T1_TICKER,
        t1Date: String = DEFAULT_T1_DATE,
        t2Ticker: String = DEFAULT_T2_TICKER,
        t2Date: String = DEFAULT_T2_DATE,
        eventsLookup: String = DEFAULT_EVENTS_LOOKUP,
    ): LiveRunOutcome {
        var requestCount = 0

        val (t1Audit, t1Record) =
            archiveOverviewOnce(overviewService, "T1", t1Ticker, t1Date) { requestCount++ }
        val (t2Audit, t2Record) =
            archiveOverviewOnce(overviewService, "T2", t2Ticker, t2Date) { requestCount++ }
        val (eventsAudit, eventsRecord) =
            archiveEventsOnce(eventsService, eventsLookup) { requestCount++ }

        return buildOutcome(
            apiKeyPresent = true,
            requestCount = requestCount,
            t1 = t1Audit,
            t2 = t2Audit,
            events = eventsAudit,
            t1Record = t1Record,
            t2Record = t2Record,
            eventsRecord = eventsRecord,
        )
    }

    fun classify(outcome: LiveRunOutcome): LiveClassification {
        if (!outcome.apiKeyPresent || outcome.requestCount == 0) {
            return LiveClassification.LIVE_UNVERIFIED
        }

        val audits = listOf(outcome.t1, outcome.t2, outcome.events)
        if (audits.any { it.localException != null }) {
            return LiveClassification.LIVE_LOCAL_FAILURE
        }
        if (audits.any { it.observationStatus == ObservationStatus.PROVIDER_FAILURE }) {
            return LiveClassification.LIVE_PROVIDER_FAILURE
        }
        if (audits.any { it.observationStatus == ObservationStatus.REJECTED_VALIDATION }) {
            return LiveClassification.LIVE_VALIDATION_REJECTED
        }
        if (audits.any {
                it.observationStatus == ObservationStatus.LOCAL_ARCHIVE_FAILURE ||
                    it.observationStatus == ObservationStatus.MISSING
            }
        ) {
            return LiveClassification.LIVE_LOCAL_FAILURE
        }
        if (outcome.deriverError != null) {
            return LiveClassification.LIVE_LOCAL_FAILURE
        }

        val allObserved =
            audits.all {
                it.observationStatus == ObservationStatus.OBSERVED &&
                    it.observedIngestSucceeded == true
            }
        if (!allObserved) {
            return LiveClassification.LIVE_LOCAL_FAILURE
        }

        return when {
            outcome.continuityStatus ==
                SecurityIdentityContinuityStatus.TICKER_CHANGE_CANDIDATE &&
                outcome.corroborationStatus ==
                TickerEventOverviewCorroborationStatus.CORROBORATED_TICKER_CHANGE_CANDIDATE ->
                LiveClassification.LIVE_CORROBORATED
            outcome.corroborationStatus ==
                TickerEventOverviewCorroborationStatus.UNRESOLVED ->
                LiveClassification.LIVE_UNRESOLVED
            else -> LiveClassification.LIVE_LOCAL_FAILURE
        }
    }

    fun printOutcome(o: LiveRunOutcome) {
        println("MASSIVE_API_KEY=${if (o.apiKeyPresent) "SET" else "NOT SET"}")
        println("requestCount=${o.requestCount}")
        printArchive(o.t1)
        printArchive(o.t2)
        printArchive(o.events)
        println("continuity.status=${o.continuityStatus}")
        println("continuity.reason=${o.continuityReason}")
        println("continuity.firstTicker=${o.firstProviderTicker}")
        println("continuity.secondTicker=${o.secondProviderTicker}")
        println("continuity.firstProviderAsOfDate=${o.firstProviderAsOfDate}")
        println("continuity.secondProviderAsOfDate=${o.secondProviderAsOfDate}")
        println("continuity.firstShareClassFigi=${o.firstShareClassFigi}")
        println("continuity.secondShareClassFigi=${o.secondShareClassFigi}")
        println("continuity.firstCompositeFigi=${o.firstCompositeFigi}")
        println("continuity.secondCompositeFigi=${o.secondCompositeFigi}")
        println("continuity.evidenceEligibleAt=${o.continuityEvidenceEligibleAt}")
        println("corroboration.status=${o.corroborationStatus}")
        println("corroboration.reason=${o.corroborationReason}")
        println("corroboration.tickerEventsLookupId=${o.tickerEventsLookupId}")
        println("corroboration.matchedEventType=${o.matchedEventType}")
        println("corroboration.matchedEventDate=${o.matchedEventDate}")
        println("corroboration.matchedEventTicker=${o.matchedEventTicker}")
        println(
            "corroboration.continuityEvidenceEligibleAt=${o.corroborationContinuityEligibleAt}",
        )
        println(
            "corroboration.tickerEventsEligibilityBoundaryAt=" +
                "${o.tickerEventsEligibilityBoundaryAt}",
        )
        println("corroboration.corroborationEligibleAt=${o.corroborationEligibleAt}")
        if (o.deriverError != null) {
            println("deriverError=${o.deriverError}")
        }
        println("liveClassification=${o.liveClassification}")
    }

    /** Secret-free text for offline QA (no key value / no raw body). */
    fun formatSecretFree(o: LiveRunOutcome): String {
        val sb = StringBuilder()
        printOutcomeTo(o) { line -> sb.appendLine(line) }
        return sb.toString()
    }

    fun integrityOk(audit: ArchiveAudit): Boolean {
        val hash = audit.rawPayloadHash ?: return false
        val uri = audit.rawPayloadUri ?: return false
        return try {
            val bytes = Files.readAllBytes(Path.of(uri))
            Sha256Hex.of(bytes) == hash
        } catch (_: Exception) {
            false
        }
    }

    private fun printArchive(a: ArchiveAudit) {
        println("--- ${a.label} ---")
        println("${a.label}.requestKey=${a.requestKey}")
        println("${a.label}.observationStatus=${a.observationStatus}")
        println("${a.label}.httpStatus=${a.httpStatus}")
        println("${a.label}.rawPayloadHash=${a.rawPayloadHash}")
        println("${a.label}.eligibilityBoundaryAt=${a.eligibilityBoundaryAt}")
        if (a.observedIngestSucceeded != null) {
            println("${a.label}.observedIngestSucceeded=${a.observedIngestSucceeded}")
        }
        if (a.localException != null) {
            println("${a.label}.localException=${a.localException}")
        }
    }

    private fun printOutcomeTo(
        o: LiveRunOutcome,
        emit: (String) -> Unit,
    ) {
        emit("MASSIVE_API_KEY=${if (o.apiKeyPresent) "SET" else "NOT SET"}")
        emit("requestCount=${o.requestCount}")
        emit("liveClassification=${o.liveClassification}")
        emit("continuity.status=${o.continuityStatus}")
        emit("corroboration.status=${o.corroborationStatus}")
        emit("corroboration.reason=${o.corroborationReason}")
    }

    private fun archiveOverviewOnce(
        service: MassiveTickerOverviewForwardArchiveService,
        label: String,
        ticker: String,
        date: String,
        onRequest: () -> Unit,
    ): Pair<ArchiveAudit, ManifestRecord?> {
        onRequest()
        return try {
            val result: MassiveTickerOverviewArchiveResult =
                service.archiveTickerOverview(ticker, date)
            auditFromOverview(label, result) to result.record
        } catch (e: Exception) {
            emptyAudit(label).copy(
                localException = "${e::class.simpleName}: ${e.message?.take(160)}",
            ) to null
        }
    }

    private fun archiveEventsOnce(
        service: MassiveTickerEventsForwardArchiveService,
        lookupId: String,
        onRequest: () -> Unit,
    ): Pair<ArchiveAudit, ManifestRecord?> {
        onRequest()
        return try {
            val result: MassiveTickerEventsArchiveResult =
                service.archiveTickerEvents(lookupId)
            auditFromEvents(result) to result.record
        } catch (e: Exception) {
            emptyAudit("Events").copy(
                localException = "${e::class.simpleName}: ${e.message?.take(160)}",
            ) to null
        }
    }

    private fun buildOutcome(
        apiKeyPresent: Boolean,
        requestCount: Int,
        t1: ArchiveAudit,
        t2: ArchiveAudit,
        events: ArchiveAudit,
        t1Record: ManifestRecord?,
        t2Record: ManifestRecord?,
        eventsRecord: ManifestRecord?,
    ): LiveRunOutcome {
        if (t1Record == null || t2Record == null || eventsRecord == null) {
            val base =
                LiveRunOutcome(
                    apiKeyPresent = apiKeyPresent,
                    requestCount = requestCount,
                    t1 = t1,
                    t2 = t2,
                    events = events,
                    continuityStatus = null,
                    continuityReason = null,
                    firstProviderTicker = null,
                    secondProviderTicker = null,
                    firstProviderAsOfDate = null,
                    secondProviderAsOfDate = null,
                    firstShareClassFigi = null,
                    secondShareClassFigi = null,
                    firstCompositeFigi = null,
                    secondCompositeFigi = null,
                    continuityEvidenceEligibleAt = null,
                    corroborationStatus = null,
                    corroborationReason = null,
                    tickerEventsLookupId = null,
                    matchedEventType = null,
                    matchedEventDate = null,
                    matchedEventTicker = null,
                    corroborationContinuityEligibleAt = null,
                    tickerEventsEligibilityBoundaryAt = null,
                    corroborationEligibleAt = null,
                    liveClassification = LiveClassification.LIVE_LOCAL_FAILURE,
                    deriverError = "archive step incomplete; derivers not invoked",
                )
            return base.copy(liveClassification = classify(base))
        }

        return try {
            val continuity =
                SecurityIdentityContinuityEvidenceDeriver.derive(t1Record, t2Record)
            val corroboration =
                TickerEventOverviewCorroborationDeriver.derive(
                    t1Record,
                    t2Record,
                    eventsRecord,
                )
            val built =
                LiveRunOutcome(
                    apiKeyPresent = apiKeyPresent,
                    requestCount = requestCount,
                    t1 = t1,
                    t2 = t2,
                    events = events,
                    continuityStatus = continuity.status,
                    continuityReason = continuity.reason,
                    firstProviderTicker = continuity.firstProviderTicker,
                    secondProviderTicker = continuity.secondProviderTicker,
                    firstProviderAsOfDate = continuity.firstProviderAsOfDate,
                    secondProviderAsOfDate = continuity.secondProviderAsOfDate,
                    firstShareClassFigi = continuity.firstShareClassFigi,
                    secondShareClassFigi = continuity.secondShareClassFigi,
                    firstCompositeFigi = continuity.firstCompositeFigi,
                    secondCompositeFigi = continuity.secondCompositeFigi,
                    continuityEvidenceEligibleAt = continuity.evidenceEligibleAt,
                    corroborationStatus = corroboration.status,
                    corroborationReason = corroboration.reason,
                    tickerEventsLookupId = corroboration.tickerEventsLookupId,
                    matchedEventType = corroboration.matchedEventType,
                    matchedEventDate = corroboration.matchedEventDate,
                    matchedEventTicker = corroboration.matchedEventTicker,
                    corroborationContinuityEligibleAt =
                        corroboration.continuityEvidenceEligibleAt,
                    tickerEventsEligibilityBoundaryAt =
                        corroboration.tickerEventsEligibilityBoundaryAt,
                    corroborationEligibleAt = corroboration.corroborationEligibleAt,
                    liveClassification = LiveClassification.LIVE_UNVERIFIED,
                )
            built.copy(liveClassification = classify(built))
        } catch (e: Exception) {
            val built =
                LiveRunOutcome(
                    apiKeyPresent = apiKeyPresent,
                    requestCount = requestCount,
                    t1 = t1,
                    t2 = t2,
                    events = events,
                    continuityStatus = null,
                    continuityReason = null,
                    firstProviderTicker = null,
                    secondProviderTicker = null,
                    firstProviderAsOfDate = null,
                    secondProviderAsOfDate = null,
                    firstShareClassFigi = null,
                    secondShareClassFigi = null,
                    firstCompositeFigi = null,
                    secondCompositeFigi = null,
                    continuityEvidenceEligibleAt = null,
                    corroborationStatus = null,
                    corroborationReason = null,
                    tickerEventsLookupId = null,
                    matchedEventType = null,
                    matchedEventDate = null,
                    matchedEventTicker = null,
                    corroborationContinuityEligibleAt = null,
                    tickerEventsEligibilityBoundaryAt = null,
                    corroborationEligibleAt = null,
                    liveClassification = LiveClassification.LIVE_LOCAL_FAILURE,
                    deriverError = "${e::class.simpleName}: ${e.message?.take(200)}",
                )
            built.copy(liveClassification = classify(built))
        }
    }

    private fun auditFromOverview(
        label: String,
        result: MassiveTickerOverviewArchiveResult,
    ): ArchiveAudit {
        val r = result.record
        return ArchiveAudit(
            label = label,
            requestKey = r.requestKey,
            observationStatus = r.observationStatus,
            httpStatus = r.httpStatus,
            rawPayloadHash = r.rawPayloadHash,
            rawPayloadUri = r.rawPayloadUri,
            eligibilityBoundaryAt = r.eligibilityBoundaryAt,
            observedIngestSucceeded = result.observedIngestSucceeded,
        )
    }

    private fun auditFromEvents(result: MassiveTickerEventsArchiveResult): ArchiveAudit {
        val r = result.record
        return ArchiveAudit(
            label = "Events",
            requestKey = r.requestKey,
            observationStatus = r.observationStatus,
            httpStatus = r.httpStatus,
            rawPayloadHash = r.rawPayloadHash,
            rawPayloadUri = r.rawPayloadUri,
            eligibilityBoundaryAt = r.eligibilityBoundaryAt,
            observedIngestSucceeded = result.observedIngestSucceeded,
        )
    }

    private fun emptyAudit(label: String): ArchiveAudit =
        ArchiveAudit(
            label = label,
            requestKey = null,
            observationStatus = null,
            httpStatus = null,
            rawPayloadHash = null,
            rawPayloadUri = null,
            eligibilityBoundaryAt = null,
            observedIngestSucceeded = null,
        )

    /** Test helper: build an outcome shell then re-classify. */
    fun classified(
        apiKeyPresent: Boolean = true,
        requestCount: Int = 3,
        t1Status: ObservationStatus = ObservationStatus.OBSERVED,
        t2Status: ObservationStatus = ObservationStatus.OBSERVED,
        eventsStatus: ObservationStatus = ObservationStatus.OBSERVED,
        t1Ingest: Boolean = true,
        t2Ingest: Boolean = true,
        eventsIngest: Boolean = true,
        t1LocalException: String? = null,
        t2LocalException: String? = null,
        eventsLocalException: String? = null,
        continuityStatus: SecurityIdentityContinuityStatus? =
            SecurityIdentityContinuityStatus.TICKER_CHANGE_CANDIDATE,
        continuityReason: SecurityIdentityContinuityReason? = null,
        corroborationStatus: TickerEventOverviewCorroborationStatus? =
            TickerEventOverviewCorroborationStatus.CORROBORATED_TICKER_CHANGE_CANDIDATE,
        corroborationReason: TickerEventOverviewCorroborationReason? = null,
        deriverError: String? = null,
    ): LiveRunOutcome {
        val shell =
            LiveRunOutcome(
                apiKeyPresent = apiKeyPresent,
                requestCount = requestCount,
                t1 =
                    ArchiveAudit(
                        label = "T1",
                        requestKey = "GET|/v3/reference/tickers/SQ|date=2025-01-17",
                        observationStatus = t1Status,
                        httpStatus = 200,
                        rawPayloadHash = "a".repeat(64),
                        rawPayloadUri = "/tmp/t1.raw",
                        eligibilityBoundaryAt = Instant.parse("2026-09-19T12:00:00Z"),
                        observedIngestSucceeded = t1Ingest,
                        localException = t1LocalException,
                    ),
                t2 =
                    ArchiveAudit(
                        label = "T2",
                        requestKey = "GET|/v3/reference/tickers/XYZ|date=2025-01-22",
                        observationStatus = t2Status,
                        httpStatus = 200,
                        rawPayloadHash = "b".repeat(64),
                        rawPayloadUri = "/tmp/t2.raw",
                        eligibilityBoundaryAt = Instant.parse("2026-09-19T12:01:00Z"),
                        observedIngestSucceeded = t2Ingest,
                        localException = t2LocalException,
                    ),
                events =
                    ArchiveAudit(
                        label = "Events",
                        requestKey = "GET|/vX/reference/tickers/XYZ/events|types=ticker_change",
                        observationStatus = eventsStatus,
                        httpStatus = 200,
                        rawPayloadHash = "c".repeat(64),
                        rawPayloadUri = "/tmp/ev.raw",
                        eligibilityBoundaryAt = Instant.parse("2026-09-19T12:02:00Z"),
                        observedIngestSucceeded = eventsIngest,
                        localException = eventsLocalException,
                    ),
                continuityStatus = continuityStatus,
                continuityReason = continuityReason,
                firstProviderTicker = "SQ",
                secondProviderTicker = "XYZ",
                firstProviderAsOfDate = DEFAULT_T1_DATE,
                secondProviderAsOfDate = DEFAULT_T2_DATE,
                firstShareClassFigi = "BBGSHARE",
                secondShareClassFigi = "BBGSHARE",
                firstCompositeFigi = "BBGCOMP",
                secondCompositeFigi = "BBGCOMP",
                continuityEvidenceEligibleAt = Instant.parse("2026-09-19T12:01:00Z"),
                corroborationStatus = corroborationStatus,
                corroborationReason = corroborationReason,
                tickerEventsLookupId = DEFAULT_EVENTS_LOOKUP,
                matchedEventType =
                    if (corroborationStatus ==
                        TickerEventOverviewCorroborationStatus
                            .CORROBORATED_TICKER_CHANGE_CANDIDATE
                    ) {
                        "ticker_change"
                    } else {
                        null
                    },
                matchedEventDate =
                    if (corroborationStatus ==
                        TickerEventOverviewCorroborationStatus
                            .CORROBORATED_TICKER_CHANGE_CANDIDATE
                    ) {
                        "2025-01-21"
                    } else {
                        null
                    },
                matchedEventTicker =
                    if (corroborationStatus ==
                        TickerEventOverviewCorroborationStatus
                            .CORROBORATED_TICKER_CHANGE_CANDIDATE
                    ) {
                        "XYZ"
                    } else {
                        null
                    },
                corroborationContinuityEligibleAt = Instant.parse("2026-09-19T12:01:00Z"),
                tickerEventsEligibilityBoundaryAt = Instant.parse("2026-09-19T12:02:00Z"),
                corroborationEligibleAt = Instant.parse("2026-09-19T12:02:00Z"),
                liveClassification = LiveClassification.LIVE_UNVERIFIED,
                deriverError = deriverError,
            )
        return shell.copy(liveClassification = classify(shell))
    }
}
