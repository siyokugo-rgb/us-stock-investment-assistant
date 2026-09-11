package sec.poc

import java.time.Instant
import java.time.LocalDate

enum class SecArtifactType {
    FILING_INDEX,
    PRIMARY_DOCUMENT,
    COMPLETE_SUBMISSION_TEXT,
}

/**
 * 最小 provenance（巨大な class 体系は作らない）。
 * provider / accession / URL / retrieval time / hash / type / size。
 */
data class SecArtifactProvenance(
    val provider: String = "SEC",
    val accessionNumber: SecAccessionNumber,
    val artifactType: SecArtifactType,
    val endpoint: String,
    val httpStatus: Int,
    val contentType: String?,
    val payloadBytes: Int,
    val payloadSha256: String,
    /**
     * HTTP 成功 → body 受信 → 整合確認成功の直後。
     * historical knownAt ではない（PR #4 と同意味）。
     */
    val fetchedAt: Instant,
    val source: String,
)

data class SecFetchedArtifact(
    val provenance: SecArtifactProvenance,
    /** 生 body。repository 永続化はしない（PoC 実行時メモリ / テスト用）。 */
    val body: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SecFetchedArtifact) return false
        return provenance == other.provenance && body.contentEquals(other.body)
    }

    override fun hashCode(): Int = 31 * provenance.hashCode() + body.contentHashCode()
}

enum class SecSubmissionKind {
    ORIGINAL,
    AMENDMENT,
    OTHER,
}

data class SecAccessionFilingMeta(
    val issuerCik: SecCik,
    val accessionNumber: SecAccessionNumber,
    val form: String,
    val filingDate: LocalDate?,
    val reportDate: LocalDate?,
    val acceptanceDateTime: Instant?,
    val acceptanceDateTimeRaw: String?,
    val primaryDocument: String,
    val kind: SecSubmissionKind,
)

/**
 * original / amendment 関係。
 * form+reportDate 一致だけでは CONFIRMED にしない。
 */
enum class SecAmendmentRelationshipGrade {
    CONFIRMED,
    LIKELY,
    UNVERIFIED,
}

data class SecAmendmentRelationshipAssessment(
    val originalAccession: SecAccessionNumber,
    val amendmentAccession: SecAccessionNumber,
    val grade: SecAmendmentRelationshipGrade,
    val evidenceNotes: List<String>,
)

/**
 * 1 accession = 1 提出版。amendment は別 accession として保持し、original を上書きしない。
 */
data class SecAccessionArtifactBundle(
    val meta: SecAccessionFilingMeta,
    val filingIndex: SecFetchedArtifact,
    val primaryDocument: SecFetchedArtifact,
    val completeSubmissionText: SecFetchedArtifact,
)

class SecAccessionArtifactStore {
    private val byAccession = linkedMapOf<String, SecAccessionArtifactBundle>()

    fun put(bundle: SecAccessionArtifactBundle) {
        val key = bundle.meta.accessionNumber.value
        if (byAccession.containsKey(key)) {
            throw SecEdgarPocException(
                "Refusing to overwrite accession $key; original/amendment must remain distinct versions",
            )
        }
        byAccession[key] = bundle
    }

    fun get(accession: SecAccessionNumber): SecAccessionArtifactBundle? = byAccession[accession.value]

    fun all(): List<SecAccessionArtifactBundle> = byAccession.values.toList()

    fun size(): Int = byAccession.size
}
