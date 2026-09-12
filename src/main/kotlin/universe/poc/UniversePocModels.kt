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
 * Feed / coverage 全体の完全性証拠。
 *
 * 単一 row の装飾ではなく、「どの期間を空 inception から欠損なく覆うか」の主張。
 * synthetic fixture の complete はテスト上の仮定であり、実 Provider 完全性の証明ではない。
 */
enum class ObservationCompleteness {
    /** 単発 snapshot。その as-of 以外へ外挿禁止 */
    SINGLE_SNAPSHOT,

    /**
     * coverageStartDate が Universe inception であり、
     * inception 直前 membership は空、そこから全 ADD/REMOVE が欠損なく存在する、
     * という明示主張がある場合のみ change log 再構築を許可する。
     */
    COMPLETE_FROM_EMPTY_UNIVERSE_INCEPTION,

    /** coverage 開始・初期状態・欠損有無が未証明。membership 再構築禁止 */
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
 * - [coverageStartDate] は COMPLETE_FROM_EMPTY_UNIVERSE_INCEPTION のとき必須
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
    /**
     * Change-log coverage 開始日（Universe inception）。
     * COMPLETE_FROM_EMPTY_UNIVERSE_INCEPTION のとき必須。
     * その日の直前 membership は空、以降の ADD/REMOVE が完全であるという主張に紐づく。
     */
    val coverageStartDate: LocalDate? = null,
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
        when (completeness) {
            ObservationCompleteness.COMPLETE_FROM_EMPTY_UNIVERSE_INCEPTION ->
                require(coverageStartDate != null) {
                    "COMPLETE_FROM_EMPTY_UNIVERSE_INCEPTION requires coverageStartDate " +
                        "(explicit empty inception; do not invent initial emptiness)"
                }
            ObservationCompleteness.SINGLE_SNAPSHOT,
            ObservationCompleteness.INCOMPLETE_OR_UNKNOWN,
            -> Unit
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
