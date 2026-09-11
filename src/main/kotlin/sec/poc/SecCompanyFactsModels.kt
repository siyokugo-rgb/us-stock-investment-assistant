package sec.poc

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * CompanyFacts は Issuer / filing entity（CIK）側の XBRL 集約。
 * SecurityId へ自動割当してはならない。IssuerId 本実装も本 PoC では行わない。
 */
data class SecCompanyFactsDocument(
    val cik: SecCik,
    val entityName: String,
    val taxonomies: Set<String>,
    /** taxonomy → concept → units → fact versions（候補集合。最新自動採用しない） */
    val concepts: List<SecCompanyFactsConcept>,
    val observedTopLevelKeys: Set<String>,
)

data class SecCompanyFactsConcept(
    val taxonomy: String,
    val tag: String,
    val label: String?,
    val description: String?,
    val units: Map<String, List<SecCompanyFactVersion>>,
) {
    val allVersions: List<SecCompanyFactVersion>
        get() = units.values.flatten()
}

/**
 * 1 fact version = 1 候補。同一 concept/period に複数 accession があっても潰さない。
 */
data class SecCompanyFactVersion(
    val taxonomy: String,
    val tag: String,
    val unit: String,
    val value: BigDecimal,
    val start: LocalDate?,
    val end: LocalDate,
    val fy: Int?,
    val fp: String?,
    val form: String?,
    val filed: LocalDate?,
    val frame: String?,
    /**
     * CompanyFacts の `accn`。存在すれば accession として検証する。
     * 欠落時は accession 結合を推測しない。
     */
    val accn: SecAccessionNumber?,
)

data class SecCompanyFactsFetchResult(
    val evidence: SecFetchEvidence,
    val document: SecCompanyFactsDocument,
    val rawJson: String,
)

/**
 * CompanyFacts → accn → submissions / archive への結合証跡（推測結合禁止）。
 */
data class SecCompanyFactsAccessionJoin(
    val fact: SecCompanyFactVersion,
    val accession: SecAccessionNumber,
    val foundInSubmissionsRecent: Boolean,
    val archiveIndexUrl: String?,
    val archiveIndexHttpStatus: Int?,
)

enum class SecCompanyFactsKnownAtGrade {
    /** CompanyFacts `filed` だけでは historical knownAt にならない */
    UNUSABLE_AS_KNOWN_AT,
    /** accession join 後も acceptance は CONFIRMED knownAt ではない（既存結論） */
    PARTIAL_REQUIRES_ACCESSION_JOIN,
}
