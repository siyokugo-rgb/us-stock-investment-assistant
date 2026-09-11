package universe.poc

import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Historical Universe PoC Fail-Closed / boundary tests.
 * Network-free fixtures only. Does not invent knownAt or SecurityId.
 */
class HistoricalUniverseFailClosedTest {
    private val universe = "TEST_UNIVERSE"
    private val fetchedAt = Instant.parse("2026-09-11T12:00:00Z")

    private fun change(
        type: UniverseObservationType,
        member: String,
        effective: LocalDate,
        knownAt: Instant,
        completeness: ObservationCompleteness = ObservationCompleteness.COMPLETE_CHANGE_LOG,
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
        )

    private fun snapshot(
        member: String,
        asOf: LocalDate,
        knownAt: Instant,
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
            knownAtStatus = HistoricalKnownAtStatus.RESOLVED_WITH_EVIDENCE,
            sourceDocumentId = "fixture-snapshot-$asOf",
            sourceUrl = null,
            fetchedAt = fetchedAt,
            sourceContentSha256 = null,
            evidenceStatus = EvidenceStatus.SYNTHETIC_FIXTURE_ONLY,
            completeness = ObservationCompleteness.SINGLE_SNAPSHOT,
        )

    /** Scenario: A member from 2020-01-01, removed 2022-06-01; B added 2021-03-01. */
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
                asOfDate = LocalDate.of(2023, 1, 1),
                decisionAt = Instant.parse("2023-01-15T00:00:00Z"),
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
                asOfDate = LocalDate.of(2022, 6, 1),
                decisionAt = Instant.parse("2022-07-01T00:00:00Z"),
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
    fun changeUnknownBeforeKnownAtIsNotUsed() {
        val rows = survivorshipChangeLog()
        // B's knownAt is 2021-02-20; decision before that must not see B even if asOf is later.
        val result =
            UniverseMembershipPocQuery.membersAt(
                rows,
                universe,
                asOfDate = LocalDate.of(2021, 6, 1),
                decisionAt = Instant.parse("2021-02-01T00:00:00Z"),
            )
        assertEquals(setOf("A"), result.memberExternalIds)
        assertFalse("B" in result.memberExternalIds)
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
                completeness = ObservationCompleteness.COMPLETE_CHANGE_LOG,
            )
        val result =
            UniverseMembershipPocQuery.membersAt(
                listOf(unresolved),
                universe,
                asOfDate = LocalDate.of(2020, 6, 1),
                decisionAt = Instant.parse("2026-09-11T13:00:00Z"),
            )
        assertEquals(MembershipQueryStatus.UNUSABLE_KNOWN_AT, result.status)
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
        // After A removed, past query still returns A for 2020.
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
                completeness = ObservationCompleteness.COMPLETE_CHANGE_LOG,
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
    fun catalogKeepsNamingAmbiguityWithoutSilentSubstitution() {
        val kings = UniverseSourceCatalog.entries.single { it.universeKey == "DIVIDEND_KINGS" }
        assertEquals(null, kings.officialIndexIdentifier)
        assertEquals(ReconstructionMode.RULE_REBUILD_NOT_OFFICIAL_LIST, kings.preferredReconstructionMode)
        val achievers = UniverseSourceCatalog.entries.single { it.universeKey == "NASDAQ_DIVIDEND_ACHIEVERS" }
        assertTrue(achievers.namingAmbiguityNotes!!.contains("ambiguous", ignoreCase = true))
    }
}
