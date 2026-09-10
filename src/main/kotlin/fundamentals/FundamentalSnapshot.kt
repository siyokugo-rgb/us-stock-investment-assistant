package fundamentals

import pit.Pit
import security.SecurityId
import java.time.Instant
import java.time.LocalDate

/**
 * 提出済み財務資料のメタデータ。財務数値そのものは持たない。
 *
 * fiscalPeriodEnd は対象会計期間の終了日（effectiveAt）。
 * filedAt / knownAt は提出・利用可能時刻であり、fiscalPeriodEnd と混同しない。
 *
 * 例: fiscalPeriodEnd=2025-12-31, filedAt=2026-02-20 の資料は、
 * 2026-01-15 の decision では使用禁止（filedAt/knownAt より前のため）。
 *
 * 不変条件: filedAt <= knownAt <= ingestedAt
 */
data class FundamentalSnapshot(
    val securityId: SecurityId,
    val fiscalPeriodEnd: LocalDate,
    val filedAt: Instant,
    val knownAt: Instant,
    val ingestedAt: Instant,
    val source: String,
) {
    init {
        require(source.isNotBlank()) { "source must not be blank" }
        require(!knownAt.isBefore(filedAt)) {
            "knownAt ($knownAt) must not be before filedAt ($filedAt)"
        }
        Pit.requireKnownBeforeOrAtIngested(knownAt, ingestedAt)
    }

    fun isAvailableAt(decisionAt: Instant): Boolean = Pit.isAvailableAt(knownAt, decisionAt)

    fun wasHeldBySystemAt(decisionAt: Instant): Boolean = Pit.wasHeldBySystemAt(ingestedAt, decisionAt)
}
