package universe.poc

import java.time.Instant
import java.time.LocalDate

/**
 * Historical Universe Feasibility PoC 専用モデル。
 *
 * 本番 Universe / SecurityId 解決 / knownAt 捏造は行わない。
 */

enum class UniverseObservationType {
    /** as-of 時点の構成全体を表す主張（その日付以外へ外挿禁止） */
    SNAPSHOT,
    ADD,
    REMOVE,
}

/**
 * 観測の完全性主張。
 * 欠損 REMOVE を「membership 継続」と断定できるのは [COMPLETE_CHANGE_LOG] のみ。
 */
enum class ObservationCompleteness {
    SINGLE_SNAPSHOT,
    COMPLETE_CHANGE_LOG,
    INCOMPLETE_OR_UNKNOWN,
}

enum class HistoricalKnownAtStatus {
    /** publication/announcement Instant の一次証拠が無い */
    UNRESOLVED_UNUSABLE,
    /** 一次証拠付き Instant */
    RESOLVED_WITH_EVIDENCE,
}

enum class EvidenceStatus {
    DOCUMENTED_SECONDARY,
    OFFICIAL_PUBLIC_DOC,
    LICENSED_FEED_CLAIMED_UNVERIFIED,
    SYNTHETIC_FIXTURE_ONLY,
}

enum class SecurityIdMappingStatus {
    FORBIDDEN_FROM_TICKER_OR_PROVIDER_ID,
}

enum class ReconstructionMode {
    /** 公式 historical membership（方式A） */
    OFFICIAL_MEMBERSHIP_HISTORY,
    /** 規則からの自前再構築（方式B）。公式リスト主張禁止 */
    RULE_REBUILD_NOT_OFFICIAL_LIST,
}

/**
 * Provider 側の生 membership 観測。SecurityId は持たない。
 *
 * - [membershipEffectiveDate] と [knownAt] を混同しない
 * - knownAt 未解決なら知識PITに使えない
 * - [fetchedAt] は保有PITのみ
 */
data class RawUniverseMembershipObservation(
    val universeKey: String,
    val provider: String,
    val officialIndexId: String?,
    val observationType: UniverseObservationType,
    val memberExternalId: String,
    val memberIdentifierType: String,
    val tickerRaw: String?,
    val membershipEffectiveDate: LocalDate?,
    val membershipEffectiveAt: Instant?,
    val announcedAt: Instant?,
    val knownAt: Instant?,
    val knownAtStatus: HistoricalKnownAtStatus,
    val sourceDocumentId: String?,
    val sourceUrl: String?,
    val fetchedAt: Instant,
    val sourceContentSha256: String?,
    val evidenceStatus: EvidenceStatus,
    val completeness: ObservationCompleteness,
) {
    init {
        require(universeKey.isNotBlank()) { "universeKey must not be blank" }
        require(provider.isNotBlank()) { "provider must not be blank" }
        require(memberExternalId.isNotBlank()) { "memberExternalId must not be blank" }
        require(memberIdentifierType.isNotBlank()) { "memberIdentifierType must not be blank" }
        when (knownAtStatus) {
            HistoricalKnownAtStatus.UNRESOLVED_UNUSABLE ->
                require(knownAt == null) {
                    "knownAt must be null when status is UNRESOLVED_UNUSABLE"
                }
            HistoricalKnownAtStatus.RESOLVED_WITH_EVIDENCE ->
                require(knownAt != null) {
                    "knownAt required when status is RESOLVED_WITH_EVIDENCE"
                }
        }
        if (observationType == UniverseObservationType.SNAPSHOT) {
            require(membershipEffectiveDate != null) {
                "SNAPSHOT requires membershipEffectiveDate (as-of date of the snapshot claim)"
            }
        }
    }
}

data class UniverseSourceCatalogEntry(
    val universeKey: String,
    val displayName: String,
    val officialProvider: String,
    val officialIndexIdentifier: String?,
    val namingAmbiguityNotes: String?,
    val preferredReconstructionMode: ReconstructionMode,
)

enum class MembershipQueryStatus {
    MEMBERS,
    UNUSABLE_KNOWN_AT,
    INDETERMINATE_INCOMPLETE_HISTORY,
    FORBIDDEN_OPERATION,
}

data class MembershipQueryResult(
    val status: MembershipQueryStatus,
    val memberExternalIds: Set<String> = emptySet(),
    val reason: String,
)
