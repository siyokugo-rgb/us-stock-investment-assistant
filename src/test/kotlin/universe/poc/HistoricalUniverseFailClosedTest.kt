package universe.poc

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Historical Universe PoC Fail-Closed / boundary tests.
 * Network-free fixtures only. Does not invent knownAt or SecurityId.
 *
 * Synthetic coverage window [2020-01-01, 2023-12-31] is a fixture assumption only —
 * not real provider completeness evidence.
 */
class HistoricalUniverseFailClosedTest {
    private val universe = "TEST_UNIVERSE"
    private val fetchedAt = Instant.parse("2026-09-11T12:00:00Z")
    private val inception = LocalDate.of(2020, 1, 1)
    private val coverageThrough = LocalDate.of(2023, 12, 31)

    private fun change(
        type: UniverseObservationType,
        member: String,
        effective: LocalDate,
        knownAt: Instant,
        completeness: ObservationCompleteness =
            ObservationCompleteness.COMPLETE_FROM_EMPTY_UNIVERSE_INCEPTION,
        coverageStart: LocalDate? = inception,
        coverageThroughDate: LocalDate? = coverageThrough,
        ticker: String? = member,
    ): RawUniverseMembershipObservation =
        RawUniverseMembershipObservation(
            universeKey = universe,
            provider = "synthetic-fixture",
            officialIndexId = "TEST",
            observationType = type,
            memberExternalId = member,
            memberIdentifierType = "FIXTURE_ID",
            tickerRaw = ticker,
            membershipEffectiveDate = effective,
            membershipEffectiveAt = null,
            announcedAt = null,
            knownAt = knownAt,
            knownAtStatus = HistoricalKnownAtStatus.RESOLVED_WITH_EVIDENCE,
            sourceDocumentId = "fixture-changelog",
            sourceUrl = null,
            fetchedAt = fetchedAt,
            sourceContentSha256 = null,
            evidenceStatus = EvidenceStatus.SYNTHETIC_FIXTURE_ONLY,
            completeness = completeness,
            coverageStartDate = coverageStart,
            coverageThroughDate = coverageThroughDate,
        )

    private fun unresolvedChange(
        type: UniverseObservationType,
        member: String,
        effective: LocalDate?,
        completeness: ObservationCompleteness =
            ObservationCompleteness.COMPLETE_FROM_EMPTY_UNIVERSE_INCEPTION,
        coverageStart: LocalDate? = inception,
        coverageThroughDate: LocalDate? = coverageThrough,
    ): RawUniverseMembershipObservation =
        RawUniverseMembershipObservation(
            universeKey = universe,
            provider = "synthetic-fixture",
            officialIndexId = "TEST",
            observationType = type,
            memberExternalId = member,
            memberIdentifierType = "FIXTURE_ID",
            tickerRaw = member,
            membershipEffectiveDate = effective,
            membershipEffectiveAt = null,
            announcedAt = null,
            knownAt = null,
            knownAtStatus = HistoricalKnownAtStatus.UNRESOLVED_UNUSABLE,
            sourceDocumentId = "fixture-unresolved",
            sourceUrl = null,
            fetchedAt = fetchedAt,
            sourceContentSha256 = null,
            evidenceStatus = EvidenceStatus.SYNTHETIC_FIXTURE_ONLY,
            completeness = completeness,
            coverageStartDate = coverageStart,
            coverageThroughDate = coverageThroughDate,
        )

    private fun snapshot(
        member: String,
        asOf: LocalDate,
        knownAt: Instant?,
        knownAtStatus: HistoricalKnownAtStatus =
            if (knownAt == null) {
                HistoricalKnownAtStatus.UNRESOLVED_UNUSABLE
            } else {
                HistoricalKnownAtStatus.RESOLVED_WITH_EVIDENCE
            },
        ticker: String? = member,
    ): RawUniverseMembershipObservation =
        RawUniverseMembershipObservation(
            universeKey = universe,
            provider = "synthetic-fixture",
            officialIndexId = "TEST",
            observationType = UniverseObservationType.SNAPSHOT,
            memberExternalId = member,
            memberIdentifierType = "FIXTURE_ID",
            tickerRaw = ticker,
            membershipEffectiveDate = asOf,
            membershipEffectiveAt = null,
            announcedAt = null,
            knownAt = knownAt,
            knownAtStatus = knownAtStatus,
            sourceDocumentId = "fixture-snapshot-$asOf",
            sourceUrl = null,
            fetchedAt = fetchedAt,
            sourceContentSha256 = null,
            evidenceStatus = EvidenceStatus.SYNTHETIC_FIXTURE_ONLY,
            completeness = ObservationCompleteness.SINGLE_SNAPSHOT,
            coverageStartDate = null,
            coverageThroughDate = null,
        )

    /** Explicit empty-inception complete log: A from 2020-01-01, B from 2021-03-01, A removed 2022-06-01. */
    private fun survivorshipChangeLog(): List<RawUniverseMembershipObservation> {
        val kA = Instant.parse("2019-12-15T21:00:00Z")
        val kB = Instant.parse("2021-02-20T21:00:00Z")
        val kRem = Instant.parse("2022-05-20T21:00:00Z")
        return listOf(
            change(UniverseObservationType.ADD, "A", LocalDate.of(2020, 1, 1), kA),
            change(UniverseObservationType.ADD, "B", LocalDate.of(2021, 3, 1), kB),
            change(UniverseObservationType.REMOVE, "A", LocalDate.of(2022, 6, 1), kRem),
        )
    }

    @Test
    fun snapshotMembersAreRetainedExactlyOnAsOfDate() {
        val knownAt = Instant.parse("2020-06-01T00:00:00Z")
        val rows =
            listOf(
                snapshot("A", LocalDate.of(2020, 6, 1), knownAt),
                snapshot("B", LocalDate.of(2020, 6, 1), knownAt),
            )
        val result =
            UniverseMembershipPocQuery.membersAt(
                rows,
                universe,
                asOfDate = LocalDate.of(2020, 6, 1),
                decisionAt = Instant.parse("2020-06-02T00:00:00Z"),
            )
        assertEquals(MembershipQueryStatus.MEMBERS, result.status)
        assertEquals(setOf("A", "B"), result.memberExternalIds)
    }

    @Test
    fun addEventIsRetainedAndVisibleAfterEffectiveDate() {
        val rows = survivorshipChangeLog()
        val result =
            UniverseMembershipPocQuery.membersAt(
                rows,
                universe,
                asOfDate = LocalDate.of(2021, 6, 1),
                decisionAt = Instant.parse("2021-06-15T00:00:00Z"),
            )
        assertEquals(MembershipQueryStatus.MEMBERS, result.status)
        assertEquals(setOf("A", "B"), result.memberExternalIds)
    }

    @Test
    fun removeEventIsRetainedAndExcludesOnAndAfterEffectiveDate() {
        val rows = survivorshipChangeLog()
        val onRemoval =
            UniverseMembershipPocQuery.membersAt(
                rows,
                universe,
                asOfDate = LocalDate.of(2022, 6, 1),
                decisionAt = Instant.parse("2022-06-15T00:00:00Z"),
            )
        assertEquals(setOf("B"), onRemoval.memberExternalIds)
        val after =
            UniverseMembershipPocQuery.membersAt(
                rows,
                universe,
                asOfDate = LocalDate.of(2022, 6, 2),
                decisionAt = Instant.parse("2022-06-15T00:00:00Z"),
            )
        assertEquals(setOf("B"), after.memberExternalIds)
    }

    @Test
    fun effectiveFromIsInclusiveForAdd() {
        val rows = survivorshipChangeLog()
        val onAdd =
            UniverseMembershipPocQuery.membersAt(
                rows,
                universe,
                asOfDate = LocalDate.of(2021, 3, 1),
                decisionAt = Instant.parse("2021-03-15T00:00:00Z"),
            )
        assertTrue("B" in onAdd.memberExternalIds)
    }

    @Test
    fun membershipQueryUsesHalfOpenStyleRemoveBoundary() {
        val rows = survivorshipChangeLog()
        val dayBefore =
            UniverseMembershipPocQuery.membersAt(
                rows,
                universe,
                asOfDate = LocalDate.of(2022, 5, 31),
                decisionAt = Instant.parse("2022-06-15T00:00:00Z"),
            )
        assertEquals(setOf("A", "B"), dayBefore.memberExternalIds)
        val onRemove =
            UniverseMembershipPocQuery.membersAt(
                rows,
                universe,
                asOfDate = LocalDate.of(2022, 6, 1),
                decisionAt = Instant.parse("2022-06-15T00:00:00Z"),
            )
        assertFalse("A" in onRemove.memberExternalIds)
    }

    @Test
    fun memberBeforeRemoval() {
        val rows = survivorshipChangeLog()
        val mid =
            UniverseMembershipPocQuery.membersAt(
                rows,
                universe,
                asOfDate = LocalDate.of(2020, 6, 1),
                decisionAt = Instant.parse("2020-06-15T00:00:00Z"),
            )
        assertEquals(setOf("A"), mid.memberExternalIds)
    }

    @Test
    fun notMemberOnOrAfterRemovalDate() {
        val rows = survivorshipChangeLog()
        val after =
            UniverseMembershipPocQuery.membersAt(
                rows,
                universe,
                asOfDate = LocalDate.of(2023, 1, 1),
                decisionAt = Instant.parse("2023-01-15T00:00:00Z"),
            )
        assertFalse("A" in after.memberExternalIds)
    }

    @Test
    fun futureAddIsNotAppliedToPastAsOfDate() {
        val rows = survivorshipChangeLog()
        val past =
            UniverseMembershipPocQuery.membersAt(
                rows,
                universe,
                asOfDate = LocalDate.of(2020, 6, 1),
                decisionAt = Instant.parse("2023-01-01T00:00:00Z"),
            )
        assertEquals(setOf("A"), past.memberExternalIds)
        assertFalse("B" in past.memberExternalIds)
    }

    @Test
    fun changeUnknownBeforeKnownAtIsNotUsedAsConfirmedEmptyMembers() {
        val rows = survivorshipChangeLog()
        val result =
            UniverseMembershipPocQuery.membersAt(
                rows,
                universe,
                asOfDate = LocalDate.of(2021, 6, 1),
                decisionAt = Instant.parse("2019-01-01T00:00:00Z"),
            )
        assertEquals(MembershipQueryStatus.UNUSABLE_KNOWN_AT, result.status)
        assertTrue(result.memberExternalIds.isEmpty())
        assertTrue(result.reason.contains("not a confirmed empty universe", ignoreCase = true))
    }

    @Test
    fun relevantNotYetKnownChangeIsNotSilentlyDroppedToRebuildFromRemainder() {
        val rows = survivorshipChangeLog()
        val result =
            UniverseMembershipPocQuery.membersAt(
                rows,
                universe,
                asOfDate = LocalDate.of(2021, 6, 1),
                decisionAt = Instant.parse("2021-02-01T00:00:00Z"),
            )
        assertEquals(MembershipQueryStatus.UNUSABLE_KNOWN_AT, result.status)
        assertTrue(result.status != MembershipQueryStatus.MEMBERS)
        assertTrue(result.reason.contains("not known at decisionAt", ignoreCase = true))
    }

    @Test
    fun futureEffectiveNotYetKnownChangeDoesNotBlockAsOfMembership() {
        val rows = survivorshipChangeLog()
        val result =
            UniverseMembershipPocQuery.membersAt(
                rows,
                universe,
                asOfDate = LocalDate.of(2020, 6, 1),
                decisionAt = Instant.parse("2021-02-01T00:00:00Z"),
            )
        assertEquals(MembershipQueryStatus.MEMBERS, result.status)
        assertEquals(setOf("A"), result.memberExternalIds)
        assertFalse("B" in result.memberExternalIds)
    }

    @Test
    fun futureUnresolvedAddDoesNotBlockPastAsOfMembership() {
        // QA1: asOf=2021-06 with known complete history + future 2026 unresolved ADD must not block.
        val rows =
            survivorshipChangeLog() +
                unresolvedChange(
                    UniverseObservationType.ADD,
                    "Z",
                    LocalDate.of(2026, 1, 1),
                )
        val result =
            UniverseMembershipPocQuery.membersAt(
                rows,
                universe,
                asOfDate = LocalDate.of(2021, 6, 1),
                decisionAt = Instant.parse("2021-06-15T00:00:00Z"),
            )
        assertEquals(MembershipQueryStatus.MEMBERS, result.status)
        assertEquals(setOf("A", "B"), result.memberExternalIds)
    }

    @Test
    fun asOfRelevantUnresolvedAddIsUnusableKnownAt() {
        // QA2: asOf=2021-06 with unresolved ADD effective 2021-06 must Fail-Closed.
        val rows =
            listOf(
                change(
                    UniverseObservationType.ADD,
                    "A",
                    LocalDate.of(2020, 1, 1),
                    Instant.parse("2019-12-15T21:00:00Z"),
                ),
                unresolvedChange(
                    UniverseObservationType.ADD,
                    "B",
                    LocalDate.of(2021, 6, 1),
                ),
            )
        val result =
            UniverseMembershipPocQuery.membersAt(
                rows,
                universe,
                asOfDate = LocalDate.of(2021, 6, 1),
                decisionAt = Instant.parse("2021-06-15T00:00:00Z"),
            )
        assertEquals(MembershipQueryStatus.UNUSABLE_KNOWN_AT, result.status)
        assertTrue(result.status != MembershipQueryStatus.MEMBERS)
    }

    @Test
    fun asOfRelevantChangeWithKnownAtAfterDecisionIsUnusableKnownAt() {
        // QA3
        val rows =
            listOf(
                change(
                    UniverseObservationType.ADD,
                    "A",
                    LocalDate.of(2020, 1, 1),
                    Instant.parse("2019-12-15T21:00:00Z"),
                ),
                change(
                    UniverseObservationType.ADD,
                    "B",
                    LocalDate.of(2021, 6, 1),
                    Instant.parse("2022-01-01T00:00:00Z"),
                ),
            )
        val result =
            UniverseMembershipPocQuery.membersAt(
                rows,
                universe,
                asOfDate = LocalDate.of(2021, 6, 1),
                decisionAt = Instant.parse("2021-06-15T00:00:00Z"),
            )
        assertEquals(MembershipQueryStatus.UNUSABLE_KNOWN_AT, result.status)
    }

    @Test
    fun nullEffectiveDateAddOrRemoveIsFailClosedNotAssumedFuture() {
        // QA4
        val rows =
            listOf(
                change(
                    UniverseObservationType.ADD,
                    "A",
                    LocalDate.of(2020, 1, 1),
                    Instant.parse("2019-12-15T21:00:00Z"),
                ),
                unresolvedChange(
                    UniverseObservationType.ADD,
                    "B",
                    effective = null,
                ),
            )
        val result =
            UniverseMembershipPocQuery.membersAt(
                rows,
                universe,
                asOfDate = LocalDate.of(2021, 6, 1),
                decisionAt = Instant.parse("2021-06-15T00:00:00Z"),
            )
        assertEquals(MembershipQueryStatus.UNUSABLE_KNOWN_AT, result.status)
        assertTrue(result.status != MembershipQueryStatus.MEMBERS)
    }

    @Test
    fun futureSnapshotOnlyIsNotUsedForPastAsOf() {
        // QA5
        val rows =
            listOf(
                snapshot("B", LocalDate.of(2026, 9, 1), Instant.parse("2026-09-01T00:00:00Z")),
            )
        val result =
            UniverseMembershipPocQuery.membersAt(
                rows,
                universe,
                asOfDate = LocalDate.of(2020, 6, 1),
                decisionAt = Instant.parse("2026-09-02T00:00:00Z"),
            )
        assertEquals(MembershipQueryStatus.FORBIDDEN_OPERATION, result.status)
        assertTrue(result.memberExternalIds.isEmpty())
        assertTrue(result.status != MembershipQueryStatus.MEMBERS)
    }

    @Test
    fun exactAsOfSnapshotWithKnownAtAfterDecisionForbidsMembers() {
        // QA6
        val rows =
            listOf(
                snapshot("A", LocalDate.of(2020, 6, 1), Instant.parse("2021-01-01T00:00:00Z")),
            )
        val result =
            UniverseMembershipPocQuery.membersAt(
                rows,
                universe,
                asOfDate = LocalDate.of(2020, 6, 1),
                decisionAt = Instant.parse("2020-06-15T00:00:00Z"),
            )
        assertEquals(MembershipQueryStatus.UNUSABLE_KNOWN_AT, result.status)
        assertTrue(result.status != MembershipQueryStatus.MEMBERS)
    }

    @Test
    fun knownAtExactlyAtDecisionAtIsCandidate() {
        val knownAt = Instant.parse("2021-02-20T21:00:00Z")
        val rows =
            listOf(
                change(UniverseObservationType.ADD, "B", LocalDate.of(2021, 3, 1), knownAt),
            )
        val result =
            UniverseMembershipPocQuery.membersAt(
                rows,
                universe,
                asOfDate = LocalDate.of(2021, 3, 1),
                decisionAt = knownAt,
            )
        assertEquals(MembershipQueryStatus.MEMBERS, result.status)
        assertEquals(setOf("B"), result.memberExternalIds)
    }

    @Test
    fun fetchedAtIsNotTreatedAsKnownAt() {
        val unresolved =
            RawUniverseMembershipObservation(
                universeKey = universe,
                provider = "synthetic-fixture",
                officialIndexId = "TEST",
                observationType = UniverseObservationType.ADD,
                memberExternalId = "A",
                memberIdentifierType = "FIXTURE_ID",
                tickerRaw = "A",
                membershipEffectiveDate = LocalDate.of(2020, 1, 1),
                membershipEffectiveAt = null,
                announcedAt = null,
                knownAt = null,
                knownAtStatus = HistoricalKnownAtStatus.UNRESOLVED_UNUSABLE,
                sourceDocumentId = "fixture",
                sourceUrl = null,
                fetchedAt = Instant.parse("2026-09-11T12:00:00Z"),
                sourceContentSha256 = null,
                evidenceStatus = EvidenceStatus.SYNTHETIC_FIXTURE_ONLY,
                completeness = ObservationCompleteness.COMPLETE_FROM_EMPTY_UNIVERSE_INCEPTION,
                coverageStartDate = inception,
                coverageThroughDate = coverageThrough,
            )
        val result =
            UniverseMembershipPocQuery.membersAt(
                listOf(unresolved),
                universe,
                asOfDate = LocalDate.of(2020, 6, 1),
                decisionAt = Instant.parse("2026-09-11T13:00:00Z"),
            )
        assertEquals(MembershipQueryStatus.UNUSABLE_KNOWN_AT, result.status)
        assertTrue(result.reason.contains("not a confirmed empty universe", ignoreCase = true))
    }

    @Test
    fun currentSnapshotIsNotBackAppliedToPast() {
        val currentOnly =
            listOf(
                snapshot("B", LocalDate.of(2026, 9, 1), Instant.parse("2026-09-01T00:00:00Z")),
            )
        val result =
            UniverseMembershipPocQuery.membersAt(
                currentOnly,
                universe,
                asOfDate = LocalDate.of(2020, 6, 1),
                decisionAt = Instant.parse("2026-09-02T00:00:00Z"),
            )
        assertEquals(MembershipQueryStatus.FORBIDDEN_OPERATION, result.status)
        assertTrue(result.memberExternalIds.isEmpty())
    }

    @Test
    fun allowCurrentSnapshotBackApplyFlagIsForbidden() {
        val rows = listOf(snapshot("B", LocalDate.of(2026, 9, 1), Instant.parse("2026-09-01T00:00:00Z")))
        val result =
            UniverseMembershipPocQuery.membersAt(
                rows,
                universe,
                asOfDate = LocalDate.of(2020, 6, 1),
                decisionAt = Instant.parse("2026-09-02T00:00:00Z"),
                allowCurrentSnapshotBackApply = true,
            )
        assertEquals(MembershipQueryStatus.FORBIDDEN_OPERATION, result.status)
    }

    @Test
    fun tickerDoesNotCreateSecurityId() {
        assertEquals(
            SecurityIdMappingStatus.FORBIDDEN_FROM_TICKER_OR_PROVIDER_ID,
            UniverseMembershipPocQuery.securityIdMappingStatus(),
        )
        val fields = RawUniverseMembershipObservation::class.java.declaredFields.map { it.name }
        assertFalse(fields.any { it.equals("securityId", ignoreCase = true) })
    }

    @Test
    fun tickerRecycleDoesNotMergeDistinctExternalIds() {
        val knownAt = Instant.parse("2020-01-01T00:00:00Z")
        val rows =
            listOf(
                change(UniverseObservationType.ADD, "PERM-1", LocalDate.of(2020, 1, 1), knownAt, ticker = "ABC"),
                change(UniverseObservationType.ADD, "PERM-2", LocalDate.of(2020, 1, 1), knownAt, ticker = "ABC"),
            )
        val result =
            UniverseMembershipPocQuery.membersAt(
                rows,
                universe,
                asOfDate = LocalDate.of(2020, 6, 1),
                decisionAt = Instant.parse("2020-06-01T00:00:00Z"),
            )
        assertEquals(setOf("PERM-1", "PERM-2"), result.memberExternalIds)
    }

    @Test
    fun delistedPastMemberIsNotDroppedBecauseAbsentFromCurrentSnapshot() {
        val rows = survivorshipChangeLog()
        val past =
            UniverseMembershipPocQuery.membersAt(
                rows,
                universe,
                asOfDate = LocalDate.of(2020, 6, 1),
                decisionAt = Instant.parse("2023-01-01T00:00:00Z"),
            )
        assertEquals(setOf("A"), past.memberExternalIds)
    }

    @Test
    fun duplicateSourceRowsAreNotSilentlyCollapsed() {
        val knownAt = Instant.parse("2020-01-01T00:00:00Z")
        val dup =
            listOf(
                change(UniverseObservationType.ADD, "A", LocalDate.of(2020, 1, 1), knownAt),
                change(UniverseObservationType.ADD, "A", LocalDate.of(2020, 1, 1), knownAt),
            )
        val result =
            UniverseMembershipPocQuery.membersAt(
                dup,
                universe,
                asOfDate = LocalDate.of(2020, 6, 1),
                decisionAt = Instant.parse("2020-06-01T00:00:00Z"),
            )
        assertEquals(MembershipQueryStatus.INDETERMINATE_INCOMPLETE_HISTORY, result.status)
    }

    @Test
    fun conflictingMembershipEvidenceIsNotAutoResolved() {
        val knownAt = Instant.parse("2020-01-01T00:00:00Z")
        val conflict =
            listOf(
                change(UniverseObservationType.ADD, "A", LocalDate.of(2020, 1, 1), knownAt),
                change(UniverseObservationType.REMOVE, "A", LocalDate.of(2020, 1, 1), knownAt),
            )
        val result =
            UniverseMembershipPocQuery.membersAt(
                conflict,
                universe,
                asOfDate = LocalDate.of(2020, 1, 1),
                decisionAt = Instant.parse("2020-06-01T00:00:00Z"),
            )
        assertEquals(MembershipQueryStatus.INDETERMINATE_INCOMPLETE_HISTORY, result.status)
    }

    @Test
    fun unknownEffectiveDateIsNotInvented() {
        val knownAt = Instant.parse("2020-01-01T00:00:00Z")
        val row =
            RawUniverseMembershipObservation(
                universeKey = universe,
                provider = "synthetic-fixture",
                officialIndexId = "TEST",
                observationType = UniverseObservationType.ADD,
                memberExternalId = "A",
                memberIdentifierType = "FIXTURE_ID",
                tickerRaw = "A",
                membershipEffectiveDate = null,
                membershipEffectiveAt = null,
                announcedAt = null,
                knownAt = knownAt,
                knownAtStatus = HistoricalKnownAtStatus.RESOLVED_WITH_EVIDENCE,
                sourceDocumentId = "fixture",
                sourceUrl = null,
                fetchedAt = fetchedAt,
                sourceContentSha256 = null,
                evidenceStatus = EvidenceStatus.SYNTHETIC_FIXTURE_ONLY,
                completeness = ObservationCompleteness.COMPLETE_FROM_EMPTY_UNIVERSE_INCEPTION,
                coverageStartDate = inception,
                coverageThroughDate = coverageThrough,
            )
        val result =
            UniverseMembershipPocQuery.membersAt(
                listOf(row),
                universe,
                asOfDate = LocalDate.of(2020, 6, 1),
                decisionAt = Instant.parse("2020-06-01T00:00:00Z"),
            )
        assertEquals(MembershipQueryStatus.INDETERMINATE_INCOMPLETE_HISTORY, result.status)
    }

    @Test
    fun unknownKnownAtIsNotGeneratedFromEffectiveOrFetched() {
        assertEquals(
            HistoricalKnownAtStatus.UNRESOLVED_UNUSABLE,
            RawUniverseMembershipObservation(
                universeKey = universe,
                provider = "x",
                officialIndexId = null,
                observationType = UniverseObservationType.SNAPSHOT,
                memberExternalId = "A",
                memberIdentifierType = "T",
                tickerRaw = "A",
                membershipEffectiveDate = LocalDate.of(2020, 1, 1),
                membershipEffectiveAt = null,
                announcedAt = null,
                knownAt = null,
                knownAtStatus = HistoricalKnownAtStatus.UNRESOLVED_UNUSABLE,
                sourceDocumentId = null,
                sourceUrl = null,
                fetchedAt = fetchedAt,
                sourceContentSha256 = null,
                evidenceStatus = EvidenceStatus.SYNTHETIC_FIXTURE_ONLY,
                completeness = ObservationCompleteness.SINGLE_SNAPSHOT,
            ).knownAtStatus,
        )
    }

    @Test
    fun missingRemovalIsNotTreatedAsPerpetualMembershipWhenLogIncomplete() {
        val knownAt = Instant.parse("2020-01-01T00:00:00Z")
        val incomplete =
            listOf(
                change(
                    UniverseObservationType.ADD,
                    "A",
                    LocalDate.of(2020, 1, 1),
                    knownAt,
                    completeness = ObservationCompleteness.INCOMPLETE_OR_UNKNOWN,
                    coverageStart = null,
                    coverageThroughDate = null,
                ),
            )
        val result =
            UniverseMembershipPocQuery.membersAt(
                incomplete,
                universe,
                asOfDate = LocalDate.of(2025, 1, 1),
                decisionAt = Instant.parse("2025-01-01T00:00:00Z"),
            )
        assertEquals(MembershipQueryStatus.INDETERMINATE_INCOMPLETE_HISTORY, result.status)
    }

    @Test
    fun survivorshipScenarioMatchesRequiredTimeline() {
        val rows = survivorshipChangeLog()
        val d202006 =
            UniverseMembershipPocQuery.membersAt(
                rows,
                universe,
                LocalDate.of(2020, 6, 1),
                Instant.parse("2020-06-15T00:00:00Z"),
            )
        val d202106 =
            UniverseMembershipPocQuery.membersAt(
                rows,
                universe,
                LocalDate.of(2021, 6, 1),
                Instant.parse("2021-06-15T00:00:00Z"),
            )
        val d202301 =
            UniverseMembershipPocQuery.membersAt(
                rows,
                universe,
                LocalDate.of(2023, 1, 1),
                Instant.parse("2023-01-15T00:00:00Z"),
            )
        assertEquals(setOf("A"), d202006.memberExternalIds)
        assertEquals(setOf("A", "B"), d202106.memberExternalIds)
        assertEquals(setOf("B"), d202301.memberExternalIds)
    }

    @Test
    fun zeroKnownObservationsIsNotConfirmedEmptyUniverse() {
        val rows = survivorshipChangeLog()
        val result =
            UniverseMembershipPocQuery.membersAt(
                rows,
                universe,
                asOfDate = LocalDate.of(2021, 6, 1),
                decisionAt = Instant.parse("2019-06-01T00:00:00Z"),
            )
        assertTrue(result.status != MembershipQueryStatus.MEMBERS)
        assertEquals(MembershipQueryStatus.UNUSABLE_KNOWN_AT, result.status)
        assertTrue(result.reason.contains("not a confirmed empty", ignoreCase = true))
    }

    @Test
    fun effectiveChangeNotYetKnownBlocksMembershipDetermination() {
        val early =
            change(
                UniverseObservationType.ADD,
                "A",
                LocalDate.of(2020, 1, 1),
                Instant.parse("2019-12-15T21:00:00Z"),
            )
        val lateKnownRemoval =
            change(
                UniverseObservationType.REMOVE,
                "A",
                LocalDate.of(2020, 6, 1),
                Instant.parse("2021-01-01T00:00:00Z"),
            )
        val result =
            UniverseMembershipPocQuery.membersAt(
                listOf(early, lateKnownRemoval),
                universe,
                asOfDate = LocalDate.of(2020, 12, 31),
                decisionAt = Instant.parse("2020-07-01T00:00:00Z"),
            )
        assertEquals(MembershipQueryStatus.UNUSABLE_KNOWN_AT, result.status)
        assertTrue(result.reason.contains("not known at decisionAt", ignoreCase = true))
        assertTrue(result.status != MembershipQueryStatus.MEMBERS)
    }

    @Test
    fun changeLogWithoutCompleteInceptionCoverageIsIndeterminate() {
        val knownAt = Instant.parse("2020-01-01T00:00:00Z")
        val rows =
            listOf(
                change(
                    UniverseObservationType.ADD,
                    "A",
                    LocalDate.of(2020, 1, 1),
                    knownAt,
                    completeness = ObservationCompleteness.INCOMPLETE_OR_UNKNOWN,
                    coverageStart = null,
                    coverageThroughDate = null,
                ),
                change(
                    UniverseObservationType.ADD,
                    "B",
                    LocalDate.of(2021, 3, 1),
                    knownAt,
                    completeness = ObservationCompleteness.INCOMPLETE_OR_UNKNOWN,
                    coverageStart = null,
                    coverageThroughDate = null,
                ),
            )
        val result =
            UniverseMembershipPocQuery.membersAt(
                rows,
                universe,
                asOfDate = LocalDate.of(2021, 6, 1),
                decisionAt = Instant.parse("2021-06-15T00:00:00Z"),
            )
        assertEquals(MembershipQueryStatus.INDETERMINATE_INCOMPLETE_HISTORY, result.status)
        assertTrue(result.reason.contains("COMPLETE_FROM_EMPTY_UNIVERSE_INCEPTION", ignoreCase = false))
    }

    @Test
    fun completeClaimMissingCoverageStartIsRejected() {
        val knownAt = Instant.parse("2020-01-01T00:00:00Z")
        val ex =
            assertFailsWith<IllegalArgumentException> {
                RawUniverseMembershipObservation(
                    universeKey = universe,
                    provider = "synthetic-fixture",
                    officialIndexId = "TEST",
                    observationType = UniverseObservationType.ADD,
                    memberExternalId = "A",
                    memberIdentifierType = "FIXTURE_ID",
                    tickerRaw = "A",
                    membershipEffectiveDate = LocalDate.of(2020, 1, 1),
                    membershipEffectiveAt = null,
                    announcedAt = null,
                    knownAt = knownAt,
                    knownAtStatus = HistoricalKnownAtStatus.RESOLVED_WITH_EVIDENCE,
                    sourceDocumentId = "fixture",
                    sourceUrl = null,
                    fetchedAt = fetchedAt,
                    sourceContentSha256 = null,
                    evidenceStatus = EvidenceStatus.SYNTHETIC_FIXTURE_ONLY,
                    completeness = ObservationCompleteness.COMPLETE_FROM_EMPTY_UNIVERSE_INCEPTION,
                    coverageStartDate = null,
                    coverageThroughDate = coverageThrough,
                )
            }
        assertTrue(ex.message!!.contains("coverageStartDate"))
    }

    @Test
    fun coverageStartAfterCoverageThroughIsRejected() {
        // QA11
        val knownAt = Instant.parse("2020-01-01T00:00:00Z")
        val ex =
            assertFailsWith<IllegalArgumentException> {
                RawUniverseMembershipObservation(
                    universeKey = universe,
                    provider = "synthetic-fixture",
                    officialIndexId = "TEST",
                    observationType = UniverseObservationType.ADD,
                    memberExternalId = "A",
                    memberIdentifierType = "FIXTURE_ID",
                    tickerRaw = "A",
                    membershipEffectiveDate = LocalDate.of(2020, 1, 1),
                    membershipEffectiveAt = null,
                    announcedAt = null,
                    knownAt = knownAt,
                    knownAtStatus = HistoricalKnownAtStatus.RESOLVED_WITH_EVIDENCE,
                    sourceDocumentId = "fixture",
                    sourceUrl = null,
                    fetchedAt = fetchedAt,
                    sourceContentSha256 = null,
                    evidenceStatus = EvidenceStatus.SYNTHETIC_FIXTURE_ONLY,
                    completeness = ObservationCompleteness.COMPLETE_FROM_EMPTY_UNIVERSE_INCEPTION,
                    coverageStartDate = LocalDate.of(2021, 1, 1),
                    coverageThroughDate = LocalDate.of(2020, 1, 1),
                )
            }
        assertTrue(ex.message!!.contains("coverageStartDate must be <= coverageThroughDate"))
    }

    @Test
    fun asOfBeforeCoverageStartIsIndeterminateNotEmptyUniverse() {
        // QA7
        val rows = survivorshipChangeLog()
        val result =
            UniverseMembershipPocQuery.membersAt(
                rows,
                universe,
                asOfDate = LocalDate.of(2019, 6, 1),
                decisionAt = Instant.parse("2023-01-01T00:00:00Z"),
            )
        assertEquals(MembershipQueryStatus.INDETERMINATE_INCOMPLETE_HISTORY, result.status)
        assertTrue(result.reason.contains("before coverageStartDate", ignoreCase = true))
        assertTrue(result.status != MembershipQueryStatus.MEMBERS)
    }

    @Test
    fun asOfAfterCoverageThroughIsIndeterminateNotEmptyUniverse() {
        // QA8
        val rows = survivorshipChangeLog()
        val result =
            UniverseMembershipPocQuery.membersAt(
                rows,
                universe,
                asOfDate = LocalDate.of(2024, 1, 1),
                decisionAt = Instant.parse("2024-06-01T00:00:00Z"),
            )
        assertEquals(MembershipQueryStatus.INDETERMINATE_INCOMPLETE_HISTORY, result.status)
        assertTrue(result.reason.contains("after coverageThroughDate", ignoreCase = true))
        assertTrue(result.status != MembershipQueryStatus.MEMBERS)
    }

    @Test
    fun coverageStartEqualAsOfIsInclusive() {
        // QA9
        val rows = survivorshipChangeLog()
        val result =
            UniverseMembershipPocQuery.membersAt(
                rows,
                universe,
                asOfDate = inception,
                decisionAt = Instant.parse("2020-01-15T00:00:00Z"),
            )
        assertEquals(MembershipQueryStatus.MEMBERS, result.status)
        assertEquals(setOf("A"), result.memberExternalIds)
    }

    @Test
    fun coverageThroughEqualAsOfIsInclusive() {
        // QA10
        val rows = survivorshipChangeLog()
        val result =
            UniverseMembershipPocQuery.membersAt(
                rows,
                universe,
                asOfDate = coverageThrough,
                decisionAt = Instant.parse("2024-01-01T00:00:00Z"),
            )
        assertEquals(MembershipQueryStatus.MEMBERS, result.status)
        assertEquals(setOf("B"), result.memberExternalIds)
    }

    @Test
    fun inconsistentCoverageStartAcrossRowsIsIndeterminate() {
        // QA12
        val knownAt = Instant.parse("2020-01-01T00:00:00Z")
        val rows =
            listOf(
                change(
                    UniverseObservationType.ADD,
                    "A",
                    LocalDate.of(2020, 1, 1),
                    knownAt,
                    coverageStart = LocalDate.of(2020, 1, 1),
                ),
                change(
                    UniverseObservationType.ADD,
                    "B",
                    LocalDate.of(2021, 3, 1),
                    knownAt,
                    coverageStart = LocalDate.of(2019, 1, 1),
                ),
            )
        val result =
            UniverseMembershipPocQuery.membersAt(
                rows,
                universe,
                asOfDate = LocalDate.of(2021, 6, 1),
                decisionAt = Instant.parse("2021-06-15T00:00:00Z"),
            )
        assertEquals(MembershipQueryStatus.INDETERMINATE_INCOMPLETE_HISTORY, result.status)
        assertTrue(result.reason.contains("coverageStartDate", ignoreCase = true))
    }

    @Test
    fun inconsistentCoverageThroughAcrossRowsIsIndeterminate() {
        // QA13
        val knownAt = Instant.parse("2020-01-01T00:00:00Z")
        val rows =
            listOf(
                change(
                    UniverseObservationType.ADD,
                    "A",
                    LocalDate.of(2020, 1, 1),
                    knownAt,
                    coverageThroughDate = LocalDate.of(2023, 12, 31),
                ),
                change(
                    UniverseObservationType.ADD,
                    "B",
                    LocalDate.of(2021, 3, 1),
                    knownAt,
                    coverageThroughDate = LocalDate.of(2022, 12, 31),
                ),
            )
        val result =
            UniverseMembershipPocQuery.membersAt(
                rows,
                universe,
                asOfDate = LocalDate.of(2021, 6, 1),
                decisionAt = Instant.parse("2021-06-15T00:00:00Z"),
            )
        assertEquals(MembershipQueryStatus.INDETERMINATE_INCOMPLETE_HISTORY, result.status)
        assertTrue(result.reason.contains("coverageThroughDate", ignoreCase = true))
    }

    @Test
    fun eventOutsideCoverageWindowMakesCompleteClaimIndeterminate() {
        // QA14
        val knownAt = Instant.parse("2020-01-01T00:00:00Z")
        val rows =
            listOf(
                change(
                    UniverseObservationType.ADD,
                    "A",
                    LocalDate.of(2020, 1, 1),
                    knownAt,
                    coverageStart = LocalDate.of(2020, 1, 1),
                    coverageThroughDate = LocalDate.of(2021, 12, 31),
                ),
                change(
                    UniverseObservationType.ADD,
                    "B",
                    LocalDate.of(2022, 6, 1),
                    knownAt,
                    coverageStart = LocalDate.of(2020, 1, 1),
                    coverageThroughDate = LocalDate.of(2021, 12, 31),
                ),
            )
        val result =
            UniverseMembershipPocQuery.membersAt(
                rows,
                universe,
                asOfDate = LocalDate.of(2021, 6, 1),
                decisionAt = Instant.parse("2021-06-15T00:00:00Z"),
            )
        assertEquals(MembershipQueryStatus.INDETERMINATE_INCOMPLETE_HISTORY, result.status)
        assertTrue(result.reason.contains("outside claimed coverage window", ignoreCase = true))
    }

    @Test
    fun onlyExplicitEmptyInceptionCompleteLogMayRebuildMembership() {
        val rows = survivorshipChangeLog()
        assertTrue(
            rows.all {
                it.completeness == ObservationCompleteness.COMPLETE_FROM_EMPTY_UNIVERSE_INCEPTION &&
                    it.coverageStartDate == inception &&
                    it.coverageThroughDate == coverageThrough
            },
        )
        val result =
            UniverseMembershipPocQuery.membersAt(
                rows,
                universe,
                asOfDate = LocalDate.of(2021, 6, 1),
                decisionAt = Instant.parse("2021-06-15T00:00:00Z"),
            )
        assertEquals(MembershipQueryStatus.MEMBERS, result.status)
        assertEquals(setOf("A", "B"), result.memberExternalIds)
        assertTrue(result.reason.contains("COMPLETE_FROM_EMPTY_UNIVERSE_INCEPTION"))
    }

    @Test
    fun catalogKeepsNamingAmbiguityWithoutSilentSubstitution() {
        val kings = UniverseSourceCatalog.entries.single { it.universeKey == "DIVIDEND_KINGS" }
        assertEquals(null, kings.officialIndexIdentifier)
        assertEquals(ReconstructionMode.RULE_REBUILD_NOT_OFFICIAL_LIST, kings.preferredReconstructionMode)
        val achievers = UniverseSourceCatalog.entries.single { it.universeKey == "NASDAQ_DIVIDEND_ACHIEVERS" }
        assertTrue(achievers.namingAmbiguityNotes!!.contains("ambiguous", ignoreCase = true))
    }
}
