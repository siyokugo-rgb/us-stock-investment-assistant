package universe.poc

import java.time.Instant
import java.time.LocalDate

/**
 * Historical Universe PoC の Fail-Closed membership 照会。
 *
 * 禁止（推測しない）:
 * - current / 別日付 snapshot の過去（または別 asOf）適用
 * - knownAt 未解決観測の知識PIT利用
 * - 欠損 REMOVE を無限 membership と断定
 * - ticker → SecurityId
 * - effectiveDate / fetchedAt からの knownAt 生成
 */
object UniverseMembershipPocQuery {
    fun membersAt(
        observations: List<RawUniverseMembershipObservation>,
        universeKey: String,
        asOfDate: LocalDate,
        decisionAt: Instant,
        allowCurrentSnapshotBackApply: Boolean = false,
    ): MembershipQueryResult {
        if (allowCurrentSnapshotBackApply) {
            return MembershipQueryResult(
                status = MembershipQueryStatus.FORBIDDEN_OPERATION,
                reason = "current snapshot back-application to past asOfDate is forbidden",
            )
        }

        val scoped = observations.filter { it.universeKey == universeKey }
        if (scoped.isEmpty()) {
            return MembershipQueryResult(
                status = MembershipQueryStatus.INDETERMINATE_INCOMPLETE_HISTORY,
                reason = "no observations for universeKey=$universeKey",
            )
        }

        if (scoped.any { it.knownAtStatus == HistoricalKnownAtStatus.UNRESOLVED_UNUSABLE }) {
            return MembershipQueryResult(
                status = MembershipQueryStatus.UNUSABLE_KNOWN_AT,
                reason =
                    "one or more observations have historical knownAt UNRESOLVED_UNUSABLE; " +
                        "cannot use for knowledge PIT at decisionAt",
            )
        }

        val known =
            scoped.filter { obs ->
                val knownAt = obs.knownAt
                knownAt != null && !decisionAt.isBefore(knownAt)
            }
        if (known.isEmpty()) {
            return MembershipQueryResult(
                status = MembershipQueryStatus.MEMBERS,
                memberExternalIds = emptySet(),
                reason = "no observations known at decisionAt",
            )
        }

        val snapshots = known.filter { it.observationType == UniverseObservationType.SNAPSHOT }
        val changes =
            known.filter {
                it.observationType == UniverseObservationType.ADD ||
                    it.observationType == UniverseObservationType.REMOVE
            }

        if (snapshots.isNotEmpty() && changes.isNotEmpty()) {
            return MembershipQueryResult(
                status = MembershipQueryStatus.INDETERMINATE_INCOMPLETE_HISTORY,
                reason = "conflicting SNAPSHOT and ADD/REMOVE evidence; refuse auto-resolve",
            )
        }

        if (snapshots.isNotEmpty()) {
            return membersFromSnapshots(snapshots, asOfDate)
        }

        return membersFromChangeLog(changes, asOfDate)
    }

    private fun membersFromSnapshots(
        snapshots: List<RawUniverseMembershipObservation>,
        asOfDate: LocalDate,
    ): MembershipQueryResult {
        val matching = snapshots.filter { it.membershipEffectiveDate == asOfDate }
        if (matching.isEmpty()) {
            return MembershipQueryResult(
                status = MembershipQueryStatus.FORBIDDEN_OPERATION,
                reason =
                    "no SNAPSHOT exactly at asOfDate=$asOfDate; " +
                        "refusing to reuse another dated snapshot (no list back-apply)",
            )
        }
        return MembershipQueryResult(
            status = MembershipQueryStatus.MEMBERS,
            memberExternalIds = matching.map { it.memberExternalId }.toSet(),
            reason = "exact asOfDate snapshot members",
        )
    }

    private fun membersFromChangeLog(
        changes: List<RawUniverseMembershipObservation>,
        asOfDate: LocalDate,
    ): MembershipQueryResult {
        if (changes.isEmpty()) {
            return MembershipQueryResult(
                status = MembershipQueryStatus.INDETERMINATE_INCOMPLETE_HISTORY,
                reason = "no change events available",
            )
        }
        if (changes.any { it.completeness != ObservationCompleteness.COMPLETE_CHANGE_LOG }) {
            return MembershipQueryResult(
                status = MembershipQueryStatus.INDETERMINATE_INCOMPLETE_HISTORY,
                reason =
                    "change log completeness is not COMPLETE_CHANGE_LOG; " +
                        "missing REMOVE must not be treated as perpetual membership",
            )
        }
        if (changes.any { it.membershipEffectiveDate == null }) {
            return MembershipQueryResult(
                status = MembershipQueryStatus.INDETERMINATE_INCOMPLETE_HISTORY,
                reason = "change event missing membershipEffectiveDate; refuse to invent",
            )
        }

        val keyed =
            changes.groupBy {
                Triple(it.memberExternalId, it.membershipEffectiveDate, it.observationType)
            }
        if (keyed.any { it.value.size > 1 }) {
            return MembershipQueryResult(
                status = MembershipQueryStatus.INDETERMINATE_INCOMPLETE_HISTORY,
                reason = "duplicate source rows for same member/date/type; refuse silent collapse",
            )
        }

        val byMemberDate = changes.groupBy { it.memberExternalId to it.membershipEffectiveDate }
        if (byMemberDate.any { (_, rows) ->
                rows.any { it.observationType == UniverseObservationType.ADD } &&
                    rows.any { it.observationType == UniverseObservationType.REMOVE }
            }
        ) {
            return MembershipQueryResult(
                status = MembershipQueryStatus.INDETERMINATE_INCOMPLETE_HISTORY,
                reason = "conflicting ADD and REMOVE on same member/effectiveDate",
            )
        }

        val members = linkedSetOf<String>()
        val ordered =
            changes.sortedWith(
                compareBy<RawUniverseMembershipObservation> { it.membershipEffectiveDate }
                    .thenBy { it.observationType.name },
            )
        for (event in ordered) {
            val effective = event.membershipEffectiveDate!!
            // Future ADD/REMOVE relative to asOfDate are ignored (not applied backward).
            if (effective.isAfter(asOfDate)) continue
            // Date semantics: ADD inclusive on effectiveDate; REMOVE inclusive on effectiveDate
            // means not a member on that date or after.
            when (event.observationType) {
                UniverseObservationType.ADD -> members.add(event.memberExternalId)
                UniverseObservationType.REMOVE -> members.remove(event.memberExternalId)
                UniverseObservationType.SNAPSHOT -> error("unreachable")
            }
        }
        return MembershipQueryResult(
            status = MembershipQueryStatus.MEMBERS,
            memberExternalIds = members,
            reason = "COMPLETE_CHANGE_LOG membership as of $asOfDate",
        )
    }

    fun securityIdMappingStatus(): SecurityIdMappingStatus =
        SecurityIdMappingStatus.FORBIDDEN_FROM_TICKER_OR_PROVIDER_ID
}
