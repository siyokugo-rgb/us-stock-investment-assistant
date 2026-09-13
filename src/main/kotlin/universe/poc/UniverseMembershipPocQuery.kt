package universe.poc

import java.time.Instant
import java.time.LocalDate

/**
 * Historical Universe PoC の Fail-Closed membership 照会。
 *
 * 禁止（推測しない）:
 * - current / 別日付 snapshot の過去（または別 asOf）適用
 * - asOf に relevant な観測の knownAt 未解決 / not-yet-known を無視して確定すること
 * - future-effective 観測だけで過去 asOf を BLOCK すること
 * - known observation 0件を「確定空 Universe」と扱うこと
 * - effective 済みだが未 known の relevant change を黙って落として残件だけで確定すること
 * - coverage / inception / through 未証明 change log からの membership 再構築
 * - coverage 窗外を「member なし」と扱うこと
 * - 最初の ADD 以前を暗黙に空と仮定すること
 * - ticker → SecurityId
 * - effectiveDate / fetchedAt / max event からの knownAt / coverageThrough 生成
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

        val relevant = scoped.filter { isRelevantForAsOf(it, asOfDate) }

        // Fail-Closed only on observations semantically relevant to this asOfDate.
        // Future-effective ADD/REMOVE and other-dated SNAPSHOTs do not block this asOf.
        if (relevant.any { it.knownAtStatus == HistoricalKnownAtStatus.UNRESOLVED_UNUSABLE }) {
            return MembershipQueryResult(
                status = MembershipQueryStatus.UNUSABLE_KNOWN_AT,
                reason =
                    "one or more asOf-relevant observations have historical knownAt " +
                        "UNRESOLVED_UNUSABLE; cannot use for knowledge PIT at decisionAt; " +
                        "not a confirmed empty universe",
            )
        }
        if (relevant.any { obs ->
                val knownAt = obs.knownAt
                knownAt == null || decisionAt.isBefore(knownAt)
            }
        ) {
            return MembershipQueryResult(
                status = MembershipQueryStatus.UNUSABLE_KNOWN_AT,
                reason =
                    "one or more asOf-relevant observations are not known at decisionAt " +
                        "(effective on/before asOfDate=$asOfDate or exact asOf snapshot); " +
                        "asOf membership cannot be safely determined; " +
                        "not a confirmed empty universe",
            )
        }

        // Usable evidence known at decisionAt (may include future-effective changes for coverage).
        val known =
            scoped.filter { obs ->
                val knownAt = obs.knownAt
                knownAt != null && !decisionAt.isBefore(knownAt)
            }

        val knownExactSnapshots =
            known.filter {
                it.observationType == UniverseObservationType.SNAPSHOT &&
                    it.membershipEffectiveDate == asOfDate
            }
        val knownChanges =
            known.filter {
                it.observationType == UniverseObservationType.ADD ||
                    it.observationType == UniverseObservationType.REMOVE
            }
        val relevantKnownChanges =
            knownChanges.filter { isRelevantChangeForAsOf(it, asOfDate) }

        if (knownExactSnapshots.isNotEmpty() && relevantKnownChanges.isNotEmpty()) {
            return MembershipQueryResult(
                status = MembershipQueryStatus.INDETERMINATE_INCOMPLETE_HISTORY,
                reason =
                    "conflicting exact asOf SNAPSHOT and asOf-relevant ADD/REMOVE evidence; " +
                        "refuse auto-resolve",
            )
        }

        if (knownExactSnapshots.isNotEmpty()) {
            return membersFromSnapshots(knownExactSnapshots)
        }

        if (knownChanges.isNotEmpty()) {
            return membersFromChangeLog(knownChanges, asOfDate)
        }

        if (scoped.any { it.observationType == UniverseObservationType.SNAPSHOT }) {
            return MembershipQueryResult(
                status = MembershipQueryStatus.FORBIDDEN_OPERATION,
                reason =
                    "no SNAPSHOT exactly at asOfDate=$asOfDate; " +
                        "refusing to reuse another dated snapshot (no list back-apply / forward-apply)",
            )
        }

        return MembershipQueryResult(
            status = MembershipQueryStatus.UNUSABLE_KNOWN_AT,
            reason =
                "observations exist for universeKey=$universeKey but none usable for " +
                    "asOfDate=$asOfDate at decisionAt; indeterminate/unusable — " +
                    "not a confirmed empty universe",
        )
    }

    /**
     * Observations that can affect determination of membership at [asOfDate].
     *
     * - ADD/REMOVE with null effectiveDate: always relevant (cannot assume "future")
     * - ADD/REMOVE with effectiveDate <= asOfDate: relevant
     * - ADD/REMOVE with effectiveDate > asOfDate: not relevant (must not block this asOf)
     * - SNAPSHOT with effectiveDate == asOfDate: relevant
     * - SNAPSHOT on other dates: not relevant (no back/forward apply)
     */
    private fun isRelevantForAsOf(
        obs: RawUniverseMembershipObservation,
        asOfDate: LocalDate,
    ): Boolean =
        when (obs.observationType) {
            UniverseObservationType.ADD,
            UniverseObservationType.REMOVE,
            -> isRelevantChangeForAsOf(obs, asOfDate)
            UniverseObservationType.SNAPSHOT -> obs.membershipEffectiveDate == asOfDate
        }

    private fun isRelevantChangeForAsOf(
        obs: RawUniverseMembershipObservation,
        asOfDate: LocalDate,
    ): Boolean {
        val effective = obs.membershipEffectiveDate
        // Null effective cannot be treated as "future / irrelevant".
        return effective == null || !effective.isAfter(asOfDate)
    }

    private fun membersFromSnapshots(
        snapshots: List<RawUniverseMembershipObservation>,
    ): MembershipQueryResult =
        MembershipQueryResult(
            status = MembershipQueryStatus.MEMBERS,
            memberExternalIds = snapshots.map { it.memberExternalId }.toSet(),
            reason = "exact asOfDate snapshot members",
        )

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
                reason =
                    "change event missing membershipEffectiveDate; refuse to invent; " +
                        "null effective is not assumed future/irrelevant",
            )
        }
        if (changes.any { it.coverageStartDate == null || it.coverageThroughDate == null }) {
            return MembershipQueryResult(
                status = MembershipQueryStatus.INDETERMINATE_INCOMPLETE_HISTORY,
                reason =
                    "coverageStartDate / coverageThroughDate missing; coverage window unknown; " +
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
        val coverageThroughs = changes.map { it.coverageThroughDate }.toSet()
        if (coverageThroughs.size != 1) {
            return MembershipQueryResult(
                status = MembershipQueryStatus.INDETERMINATE_INCOMPLETE_HISTORY,
                reason =
                    "inconsistent coverageThroughDate across change-log rows; refuse auto-resolve",
            )
        }
        val coverageStart = coverageStarts.single()!!
        val coverageThrough = coverageThroughs.single()!!

        if (asOfDate.isBefore(coverageStart)) {
            return MembershipQueryResult(
                status = MembershipQueryStatus.INDETERMINATE_INCOMPLETE_HISTORY,
                reason =
                    "asOfDate=$asOfDate is before coverageStartDate=$coverageStart; " +
                        "outside proven coverage window — not a confirmed empty universe",
            )
        }
        if (asOfDate.isAfter(coverageThrough)) {
            return MembershipQueryResult(
                status = MembershipQueryStatus.INDETERMINATE_INCOMPLETE_HISTORY,
                reason =
                    "asOfDate=$asOfDate is after coverageThroughDate=$coverageThrough; " +
                        "outside proven coverage window — not a confirmed empty universe",
            )
        }

        if (changes.any {
                val effective = it.membershipEffectiveDate!!
                effective.isBefore(coverageStart) || effective.isAfter(coverageThrough)
            }
        ) {
            return MembershipQueryResult(
                status = MembershipQueryStatus.INDETERMINATE_INCOMPLETE_HISTORY,
                reason =
                    "change event effective outside claimed coverage window " +
                        "[$coverageStart, $coverageThrough]; coverage claim inconsistent",
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
                    "(coverage window [$coverageStart, $coverageThrough]; " +
                    "synthetic/fixture completeness is not provider proof)",
        )
    }

    fun securityIdMappingStatus(): SecurityIdMappingStatus =
        SecurityIdMappingStatus.FORBIDDEN_FROM_TICKER_OR_PROVIDER_ID
}
