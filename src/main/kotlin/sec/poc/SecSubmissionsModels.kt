package sec.poc

import java.time.Instant
import java.time.LocalDate

data class SecFormerName(
    val name: String,
    val from: String?,
    val to: String?,
)

data class SecHistoryFileRef(
    val name: String,
    val filingCount: Int?,
    val filingFrom: LocalDate?,
    val filingTo: LocalDate?,
)

/**
 * EDGAR submissions の1提出行。
 *
 * [acceptanceDateTime] は EDGAR acceptance 時刻であり、
 * sec.gov での初回 public dissemination 時刻と同一とは限らない。
 * それを knownAt に無条件固定してはならない。
 */
data class SecFilingRecord(
    val accessionNumber: String,
    val form: String,
    val filingDate: LocalDate?,
    val reportDate: LocalDate?,
    val acceptanceDateTime: Instant?,
    val acceptanceDateTimeRaw: String?,
    val primaryDocument: String?,
    val primaryDocDescription: String?,
    val isXBRL: Boolean?,
    val isInlineXBRL: Boolean?,
) {
    val isAmendment: Boolean get() = form.endsWith("/A")
}

data class SecSubmissionsDocument(
    val cik: SecCik,
    val name: String,
    val entityType: String?,
    val tickers: List<String>,
    val exchanges: List<String>,
    val formerNames: List<SecFormerName>,
    val recentFilings: List<SecFilingRecord>,
    val historyFiles: List<SecHistoryFileRef>,
    val observedTopLevelKeys: Set<String>,
    val observedRecentKeys: Set<String>,
)

data class SecFetchEvidence(
    val endpoint: String,
    val httpStatus: Int,
    val fetchedAt: Instant,
    val payloadSha256: String,
    val payloadBytes: Int,
    val userAgentConfigured: Boolean,
)

enum class PitEvidenceGrade {
    /** 過去時点で public 利用可能だったことを十分な根拠で説明できる */
    CONFIRMED,
    /** 一部根拠はあるが、exact public availability は保証できない */
    PARTIAL,
    /** フィールドはあるが本 PoC では検証未了 */
    UNVERIFIED,
    /** historical knownAt の根拠として使えない */
    UNUSABLE,
}

data class SecTimestampAssessment(
    val field: String,
    val meaning: String,
    val gradeForHistoricalKnownAt: PitEvidenceGrade,
    val notes: String,
)
