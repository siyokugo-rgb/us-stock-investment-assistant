package fundamentals

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import support.Fixtures
import java.time.Instant
import java.time.LocalDate

class FundamentalSnapshotTest {
    @Test
    fun fiscalPeriodEndIsNotAvailability() {
        val snapshot =
            Fixtures.fundamental(
                fiscalPeriodEnd = LocalDate.of(2025, 12, 31),
                filedAt = Instant.parse("2026-02-20T21:00:00Z"),
                knownAt = Instant.parse("2026-02-20T21:00:00Z"),
                ingestedAt = Instant.parse("2026-02-21T01:00:00Z"),
            )
        val decisionBeforeFiling = Instant.parse("2026-01-15T15:00:00Z")
        // fiscalPeriodEnd が 2025-12-31 でも、filedAt/knownAt 前の decision では使用禁止。
        assertEquals(LocalDate.of(2025, 12, 31), snapshot.fiscalPeriodEnd)
        assertEquals(Instant.parse("2026-02-20T21:00:00Z"), snapshot.knownAt)
        assertFalse(snapshot.isAvailableAt(decisionBeforeFiling))
    }

    @Test
    fun filedAtMustNotBeAfterKnownAt() {
        assertFailsWith<IllegalArgumentException> {
            Fixtures.fundamental(
                filedAt = Instant.parse("2026-02-21T00:00:00Z"),
                knownAt = Instant.parse("2026-02-20T21:00:00Z"),
                ingestedAt = Instant.parse("2026-02-21T01:00:00Z"),
            )
        }
    }

    @Test
    fun knownAtMustNotBeAfterIngestedAt() {
        assertFailsWith<IllegalArgumentException> {
            Fixtures.fundamental(
                filedAt = Instant.parse("2026-02-20T21:00:00Z"),
                knownAt = Instant.parse("2026-02-21T02:00:00Z"),
                ingestedAt = Instant.parse("2026-02-21T01:00:00Z"),
            )
        }
    }

    @Test
    fun equalTimestampsAreAccepted() {
        val t = Instant.parse("2026-02-20T21:00:00Z")
        val snapshot = Fixtures.fundamental(filedAt = t, knownAt = t, ingestedAt = t)
        assertTrue(snapshot.isAvailableAt(t))
        assertTrue(snapshot.wasHeldBySystemAt(t))
    }

    @Test
    fun snapshotHoldsIssuerIdNotSecurityId() {
        val snapshot = Fixtures.fundamental(issuerId = Fixtures.ISSUER_A)
        assertEquals(Fixtures.ISSUER_A, snapshot.issuerId)
        // FundamentalSnapshot has no securityId property and does not auto-select Security.
        val props = snapshot::class.members.map { it.name }.toSet()
        assertTrue("issuerId" in props)
        assertFalse("securityId" in props)
    }

    @Test
    fun snapshotsDoNotJoinDifferentIssuerIds() {
        val a = Fixtures.fundamental(issuerId = Fixtures.ISSUER_A)
        val b = Fixtures.fundamental(issuerId = Fixtures.ISSUER_B)
        assertEquals(listOf(a), listOf(a, b).filter { it.issuerId == Fixtures.ISSUER_A })
    }

    @Test
    fun snapshotDoesNotAutoSelectSecurity() {
        val snapshot = Fixtures.fundamental(issuerId = Fixtures.ISSUER_A)
        // Resolving Security requires an explicit IssuerSecurityRelation query elsewhere.
        // This type only carries issuerId.
        assertEquals(Fixtures.ISSUER_A, snapshot.issuerId)
        assertEquals("issuer-0001", snapshot.issuerId.value)
    }
}
