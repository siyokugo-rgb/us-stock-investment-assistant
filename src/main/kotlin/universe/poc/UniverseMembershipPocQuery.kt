package universe.poc

import java.time.Instant
import java.time.LocalDate

/**
 * Historical Universe PoC の Fail-Closed membership 照会。
 *
 * 禁止（推測しない）:
 * - current / 別日付 snapshot の過去（または別 asOf）適用
 * - knownAt 未解決観測の知識PIT利用
 * - known observation 0件を「確定空 Universe」と扱うこと
 * - effective 済みだが未 known の change を黙って落として残件だけで確定すること
 * - coverage / inception 未証明 change log からの membership 再構築
 * - 最初の ADD 以前を暗黙に空と仮定すること
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
                reason =
                    "no observations for universeKey=$universeKey; " +
                        "indeterminate — not a confirmed empty universe",
            )
        }

        if (scoped.any { it.knownAtStatus == HistoricalKnownAtStatus.UNRESOLVED_UNUSABLE }) {
            return MembershipQueryResult(
                status = MembershipQueryStatus.UNUSABLE_KNOWN_AT,
                reason =
                    "one or more observations have historical knownAt UNRESOLVED_UNUSABLE; " +
                        "cannot use for knowledge PIT at decisionAt; " +
                        "not a confirmed empty universe",
            )
        }

        // Effective-as-of changes that are not yet known at decisionAt block safe determination.
        val notYetKnownButEffective =
            scoped.filter { obs ->
                val effective = obs.membershipEffectiveDate
                val knownAt = obs.knownAt
                (
                    obs.observationType == UniverseObservationType.ADD ||
                        obs.observationType == UniverseObservationType.REMOVE
                    ) &&
                    effective != null &&
                    !effective.isAfter(asOfDate) &&
                    (knownAt == null || decisionAt.isBefore(knownAt))
            }
        if (notYetKnownButEffective.isNotEmpty()) {
            return MembershipQueryResult(
                status = MembershipQueryStatus.UNUSABLE_KNOWN_AT,
                reason =
                    "one or more ADD/REMOVE events are effective on/before asOfDate=$asOfDate " +
                        "but not known at decisionAt; asOf membership cannot be safely determined; " +
                        "not a confirmed empty universe",
            )
        }

        val known =
            scoped.filter { obs ->
                val knownAt = obs.knownAt
                knownAt != null && !decisionAt.isBefore(knownAt)
            }
        if (known.isEmpty()) {
            // Observations exist for this universe, but none are known yet at decisionAt.
            // That is indeterminate/unusable — never "confirmed empty members".
            return MembershipQueryResult(
                status = MembershipQueryStatus.UNUSABLE_KNOWN_AT,
                reason =
                    "observations exist for universeKey=$universeKey but none are known at " +
                        "decisionAt; indeterminate/unusable — not a confirmed empty universe",
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
                reason =
                    "no change events available; indeterminate — not a confirmed empty universe",
            )
        }
        if (changes.any {
                it.completeness != ObservationCompleteness.COMPLETE_FROM_EMPTY_UNIVERSE_INCEPTION
            }
        ) {
            return MembershipQueryResult(
                status = MembershipQueryStatus.INDETERMINATE_INCOMPLETE_HISTORY,
                reason =
                    "change log lacks COMPLETE_FROM_EMPTY_UNIVERSE_INCEPTION coverage evidence; " +
                        "refusing to invent empty initial state or perpetual membership from " +
                        "incomplete / unlabeled logs",
            )
        }
        if (changes.any { it.membershipEffectiveDate == null }) {
            return MembershipQueryResult(
                status = MembershipQueryStatus.INDETERMINATE_INCOMPLETE_HISTORY,
                reason = "change event missing membershipEffectiveDate; refuse to invent",
            )
        }
        if (changes.any { it.coverageStartDate == null }) {
            return MembershipQueryResult(
                status = MembershipQueryStatus.INDETERMINATE_INCOMPLETE_HISTORY,
                reason =
                    "coverageStartDate missing; initial state / coverage start unknown; " +
                        "membership rebuild forbidden",
            )
        }

        val coverageStarts = changes.map { it.coverageStartDate }.toSet()
        if (coverageStarts.size != 1) {
            return MembershipQueryResult(
                status = MembershipQueryStatus.INDETERMINATE_INCOMPLETE_HISTORY,
                reason =
                    "inconsistent coverageStartDate across change-log rows; refuse auto-resolve",
            )
        }
        val coverageStart = coverageStarts.single()!!
        if (asOfDate.isBefore(coverageStart)) {
            return MembershipQueryResult(
                status = MembershipQueryStatus.INDETERMINATE_INCOMPLETE_HISTORY,
                reason =
                    "asOfDate=$asOfDate is before coverageStartDate=$coverageStart; " +
                        "outside proven coverage — not a confirmed empty universe",
            )
        }
        if (changes.any {
                val effective = it.membershipEffectiveDate!!
                effective.isBefore(coverageStart)
            }
        ) {
            return MembershipQueryResult(
                status = MembershipQueryStatus.INDETERMINATE_INCOMPLETE_HISTORY,
                reason =
                    "change event effective before coverageStartDate; coverage claim inconsistent",
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

        // Explicit empty inception at coverageStartDate — only because completeness claims it.
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
            reason =
                "COMPLETE_FROM_EMPTY_UNIVERSE_INCEPTION membership as of $asOfDate " +
                    "(coverageStartDate=$coverageStart; synthetic/fixture completeness is not " +
                    "provider proof)",
        )
    }

    fun securityIdMappingStatus(): SecurityIdMappingStatus =
        SecurityIdMappingStatus.FORBIDDEN_FROM_TICKER_OR_PROVIDER_ID
}
