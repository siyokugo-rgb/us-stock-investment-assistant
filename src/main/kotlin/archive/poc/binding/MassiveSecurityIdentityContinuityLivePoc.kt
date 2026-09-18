package archive.poc.binding

import archive.poc.ManifestRecord
import archive.poc.ObservationStatus
import archive.poc.massive.MassiveTickerOverviewArchiveClient
import archive.poc.massive.MassiveTickerOverviewForwardArchiveService
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Live multi-as-of Massive Ticker Overview → [SecurityIdentityContinuityEvidenceDeriver] validation.
 *
 * Env:
 * - MASSIVE_API_KEY (required for live; absent → LIVE_UNVERIFIED, exit 0; no mock)
 * - ARCHIVE_ROOT (optional; default ./archive-runtime; gitignored)
 *
 * Never invents knownAt / SecurityId / SecurityIdentifier / validFrom/validTo / DailyPrice.
 * Provider `date=` is as-of selector only — ≠ knownAt / identity validity.
 * Does not print API key or raw body bytes.
 */
fun main() {
    val env = System.getenv()
    val key = env[MassiveTickerOverviewArchiveClient.ENV_API_KEY]?.trim().orEmpty()
    val runAt = Instant.now()
    println("Security identity continuity — Massive Overview real multi-as-of validation")
    println("runAtUtc=$runAt")
    println("providerAsOf!=knownAt; evidenceEligibleAt!=identity validity")
    println("tickers=${MassiveSecurityIdentityContinuityLivePoc.DEFAULT_TICKERS.joinToString(",")}")
    println(
        "dates=${MassiveSecurityIdentityContinuityLivePoc.DEFAULT_T1}/" +
            "${MassiveSecurityIdentityContinuityLivePoc.DEFAULT_T2}",
    )

    if (key.isEmpty()) {
        println("liveClassification=LIVE_UNVERIFIED")
        println("reason=MASSIVE_API_KEY not set; refusing mock fallback")
        return
    }

    val root =
        Path.of(env["ARCHIVE_ROOT"]?.trim()?.takeIf { it.isNotEmpty() } ?: "archive-runtime")
    val client = MassiveTickerOverviewArchiveClient.fromEnvironment(env = env)
    val service =
        MassiveTickerOverviewForwardArchiveService(
            archiveRoot = root,
            client = client,
        )
    println("archiveRoot=$root (gitignored; not committed)")

    val outcomes =
        MassiveSecurityIdentityContinuityLivePoc.DEFAULT_TICKERS.map { ticker ->
            MassiveSecurityIdentityContinuityLivePoc.runTickerPair(
                service = service,
                ticker = ticker,
                t1 = MassiveSecurityIdentityContinuityLivePoc.DEFAULT_T1,
                t2 = MassiveSecurityIdentityContinuityLivePoc.DEFAULT_T2,
            )
        }

    outcomes.forEach { MassiveSecurityIdentityContinuityLivePoc.printTickerOutcome(it) }

    val classification = MassiveSecurityIdentityContinuityLivePoc.classifyOverall(outcomes)
    println("liveClassification=$classification")
    println(
        "identityEvaluablePairs=" +
            outcomes.count { MassiveSecurityIdentityContinuityLivePoc.isIdentityEvaluable(it) },
    )
    println("integrityFail=${outcomes.any { it.integrityFail }}")
}

/**
 * Pure classification / orchestration helpers for live continuity validation (testable).
 */
object MassiveSecurityIdentityContinuityLivePoc {
    val DEFAULT_TICKERS = listOf("AAPL", "MSFT", "GOOGL")
    const val DEFAULT_T1 = "2021-01-04"
    const val DEFAULT_T2 = "2024-06-03"

    enum class LiveClassification {
        LIVE_VERIFIED,
        LIVE_PARTIAL,
        LIVE_UNVERIFIED,
        LIVE_FAIL,
    }

    data class SnapshotAudit(
        val label: String,
        val date: String,
        val archiveId: String?,
        val observationStatus: ObservationStatus?,
        val requestKey: String?,
        val rawPayloadHash: String?,
        val eligibilityBoundaryAt: Instant?,
        val notes: String?,
    )

    data class TickerPairOutcome(
        val ticker: String,
        val t1: String,
        val t2: String,
        val snapshotT1: SnapshotAudit,
        val snapshotT2: SnapshotAudit,
        val bothObserved: Boolean,
        val deriverCompleted: Boolean,
        val derivedStatus: SecurityIdentityContinuityStatus?,
        val derivedReason: SecurityIdentityContinuityReason?,
        val firstShareClassFigi: String?,
        val secondShareClassFigi: String?,
        val firstCompositeFigi: String?,
        val secondCompositeFigi: String?,
        val firstPrimaryExchange: String?,
        val secondPrimaryExchange: String?,
        val evidenceEligibleAt: Instant?,
        val expectedContinuityWhenEligible: Boolean,
        val integrityFail: Boolean,
        val integrityFailNotes: String?,
        val errorMessage: String? = null,
    )

    fun runTickerPair(
        service: MassiveTickerOverviewForwardArchiveService,
        ticker: String,
        t1: String,
        t2: String,
    ): TickerPairOutcome {
        require(t1 < t2) { "T1 must be strictly before T2 by provider as-of date string" }
        val r1 =
            try {
                service.archiveTickerOverview(ticker, t1)
            } catch (e: Exception) {
                return failedOutcome(ticker, t1, t2, "T1 archive threw: ${e::class.simpleName}")
            }
        val r2 =
            try {
                service.archiveTickerOverview(ticker, t2)
            } catch (e: Exception) {
                return failedOutcome(
                    ticker,
                    t1,
                    t2,
                    "T2 archive threw: ${e::class.simpleName}",
                    snap1 = audit("T1", t1, r1.record),
                )
            }

        val snap1 = audit("T1", t1, r1.record)
        val snap2 = audit("T2", t2, r2.record)
        val bothObserved =
            r1.record.observationStatus == ObservationStatus.OBSERVED &&
                r2.record.observationStatus == ObservationStatus.OBSERVED &&
                r1.observedIngestSucceeded &&
                r2.observedIngestSucceeded

        return try {
            val evidence =
                SecurityIdentityContinuityEvidenceDeriver.derive(r1.record, r2.record)
            val expectedContinuity =
                bothObserved &&
                    !evidence.firstShareClassFigi.isNullOrBlank() &&
                    !evidence.secondShareClassFigi.isNullOrBlank() &&
                    evidence.firstProviderTicker == ticker &&
                    evidence.secondProviderTicker == ticker &&
                    evidence.firstShareClassFigi == evidence.secondShareClassFigi &&
                    evidence.firstProviderAsOfDate == t1 &&
                    evidence.secondProviderAsOfDate == t2
            val integrityNotes = mutableListOf<String>()
            if (expectedContinuity &&
                evidence.status != SecurityIdentityContinuityStatus.CONTINUITY_CANDIDATE
            ) {
                integrityNotes +=
                    "expected CONTINUITY_CANDIDATE from OBSERVED same-ticker same share_class " +
                        "T1<T2 but got ${evidence.status}/${evidence.reason}"
            }
            if (bothObserved &&
                evidence.firstProviderAsOfDate != null &&
                evidence.secondProviderAsOfDate != null &&
                evidence.firstProviderAsOfDate!! > evidence.secondProviderAsOfDate!!
            ) {
                integrityNotes += "provider as-of order inverted vs T1/T2"
            }
            // Candidate statuses must never appear when either side not OBSERVED.
            if (!bothObserved &&
                evidence.status != SecurityIdentityContinuityStatus.UNRESOLVED &&
                evidence.status != SecurityIdentityContinuityStatus.CONFLICT
            ) {
                integrityNotes +=
                    "non-OBSERVED pair produced candidate status ${evidence.status}"
            }
            // Same ticker T1/T2 with both share_class present must resolve to an
            // identity-evaluable status (CONTINUITY / TICKER_CHANGE / RECYCLE / CONFLICT).
            // UNRESOLVED here is unexplained by current Gate rules → integrityFail.
            if (bothObserved &&
                !evidence.firstShareClassFigi.isNullOrBlank() &&
                !evidence.secondShareClassFigi.isNullOrBlank() &&
                evidence.firstProviderTicker == ticker &&
                evidence.secondProviderTicker == ticker &&
                evidence.status == SecurityIdentityContinuityStatus.UNRESOLVED
            ) {
                integrityNotes +=
                    "both share_class_figi present on same-ticker T1/T2 but status=UNRESOLVED " +
                        "(reason=${evidence.reason}); unexplained by continuity Gate"
            }
            TickerPairOutcome(
                ticker = ticker,
                t1 = t1,
                t2 = t2,
                snapshotT1 = snap1,
                snapshotT2 = snap2,
                bothObserved = bothObserved,
                deriverCompleted = true,
                derivedStatus = evidence.status,
                derivedReason = evidence.reason,
                firstShareClassFigi = evidence.firstShareClassFigi,
                secondShareClassFigi = evidence.secondShareClassFigi,
                firstCompositeFigi = evidence.firstCompositeFigi,
                secondCompositeFigi = evidence.secondCompositeFigi,
                firstPrimaryExchange = evidence.firstPrimaryExchange,
                secondPrimaryExchange = evidence.secondPrimaryExchange,
                evidenceEligibleAt = evidence.evidenceEligibleAt,
                expectedContinuityWhenEligible = expectedContinuity,
                integrityFail = integrityNotes.isNotEmpty(),
                integrityFailNotes = integrityNotes.takeIf { it.isNotEmpty() }?.joinToString("; "),
            )
        } catch (e: Exception) {
            TickerPairOutcome(
                ticker = ticker,
                t1 = t1,
                t2 = t2,
                snapshotT1 = snap1,
                snapshotT2 = snap2,
                bothObserved = bothObserved,
                deriverCompleted = false,
                derivedStatus = null,
                derivedReason = null,
                firstShareClassFigi = null,
                secondShareClassFigi = null,
                firstCompositeFigi = null,
                secondCompositeFigi = null,
                firstPrimaryExchange = null,
                secondPrimaryExchange = null,
                evidenceEligibleAt = null,
                expectedContinuityWhenEligible = false,
                integrityFail = bothObserved,
                integrityFailNotes =
                    if (bothObserved) {
                        "deriver threw on OBSERVED pair: ${e::class.simpleName}"
                    } else {
                        null
                    },
                errorMessage = "${e::class.simpleName}: ${e.message?.take(200)}",
            )
        }
    }

    /**
     * Identity-evaluable pair: OBSERVED + deriver completed + both share_class present +
     * derived status is not UNRESOLVED. Does **not** require CONTINUITY_CANDIDATE
     * (RECYCLE / CONFLICT / TICKER_CHANGE remain valid when raw evidence matches).
     */
    fun isIdentityEvaluable(outcome: TickerPairOutcome): Boolean =
        outcome.bothObserved &&
            outcome.deriverCompleted &&
            !outcome.integrityFail &&
            !outcome.firstShareClassFigi.isNullOrBlank() &&
            !outcome.secondShareClassFigi.isNullOrBlank() &&
            outcome.derivedStatus != null &&
            outcome.derivedStatus != SecurityIdentityContinuityStatus.UNRESOLVED

    fun classifyOverall(outcomes: List<TickerPairOutcome>): LiveClassification {
        if (outcomes.any { it.integrityFail }) return LiveClassification.LIVE_FAIL
        val identityEvaluable = outcomes.count { isIdentityEvaluable(it) }
        return when {
            identityEvaluable >= 2 -> LiveClassification.LIVE_VERIFIED
            identityEvaluable == 1 -> LiveClassification.LIVE_PARTIAL
            outcomes.any {
                it.bothObserved ||
                    it.snapshotT1.observationStatus != null ||
                    it.snapshotT2.observationStatus != null
            } -> LiveClassification.LIVE_PARTIAL
            else -> LiveClassification.LIVE_UNVERIFIED
        }
    }

    /** Classification when API key is absent (no provider attempt). */
    fun classifyWithoutApiKey(): LiveClassification = LiveClassification.LIVE_UNVERIFIED

    fun printTickerOutcome(o: TickerPairOutcome) {
        println("--- ticker=${o.ticker} ---")
        println("T1=${o.t1} T2=${o.t2}")
        printSnapshot(o.snapshotT1)
        printSnapshot(o.snapshotT2)
        println("bothObserved=${o.bothObserved}")
        println("deriverCompleted=${o.deriverCompleted}")
        println("derivedStatus=${o.derivedStatus}")
        println("derivedReason=${o.derivedReason}")
        println("shareClassFigi T1=${o.firstShareClassFigi} T2=${o.secondShareClassFigi}")
        println("compositeFigi T1=${o.firstCompositeFigi} T2=${o.secondCompositeFigi}")
        println("primaryExchange T1=${o.firstPrimaryExchange} T2=${o.secondPrimaryExchange}")
        println("evidenceEligibleAt=${o.evidenceEligibleAt}")
        println("expectedContinuityWhenEligible=${o.expectedContinuityWhenEligible}")
        println("integrityFail=${o.integrityFail}")
        if (o.integrityFailNotes != null) println("integrityFailNotes=${o.integrityFailNotes}")
        if (o.errorMessage != null) println("error=${o.errorMessage}")
    }

    private fun printSnapshot(s: SnapshotAudit) {
        println(
            "${s.label}: status=${s.observationStatus} archiveId=${s.archiveId} " +
                "requestKey=${s.requestKey} rawSha256=${s.rawPayloadHash} " +
                "eligibilityBoundaryAt=${s.eligibilityBoundaryAt}",
        )
        if (!s.notes.isNullOrBlank()) {
            println("${s.label}.notes=${s.notes!!.take(240)}")
        }
    }

    private fun audit(
        label: String,
        date: String,
        record: ManifestRecord,
    ): SnapshotAudit =
        SnapshotAudit(
            label = label,
            date = date,
            archiveId = record.archiveId,
            observationStatus = record.observationStatus,
            requestKey = record.requestKey,
            rawPayloadHash = record.rawPayloadHash,
            eligibilityBoundaryAt = record.eligibilityBoundaryAt,
            notes = record.notes,
        )

    private fun failedOutcome(
        ticker: String,
        t1: String,
        t2: String,
        message: String,
        snap1: SnapshotAudit? = null,
    ): TickerPairOutcome =
        TickerPairOutcome(
            ticker = ticker,
            t1 = t1,
            t2 = t2,
            snapshotT1 =
                snap1
                    ?: SnapshotAudit(
                        label = "T1",
                        date = t1,
                        archiveId = null,
                        observationStatus = null,
                        requestKey = null,
                        rawPayloadHash = null,
                        eligibilityBoundaryAt = null,
                        notes = null,
                    ),
            snapshotT2 =
                SnapshotAudit(
                    label = "T2",
                    date = t2,
                    archiveId = null,
                    observationStatus = null,
                    requestKey = null,
                    rawPayloadHash = null,
                    eligibilityBoundaryAt = null,
                    notes = null,
                ),
            bothObserved = false,
            deriverCompleted = false,
            derivedStatus = null,
            derivedReason = null,
            firstShareClassFigi = null,
            secondShareClassFigi = null,
            firstCompositeFigi = null,
            secondCompositeFigi = null,
            firstPrimaryExchange = null,
            secondPrimaryExchange = null,
            evidenceEligibleAt = null,
            expectedContinuityWhenEligible = false,
            integrityFail = true,
            integrityFailNotes = message,
            errorMessage = message,
        )

    fun utcDateStamp(instant: Instant = Instant.now()): String =
        DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC).format(instant)
}
