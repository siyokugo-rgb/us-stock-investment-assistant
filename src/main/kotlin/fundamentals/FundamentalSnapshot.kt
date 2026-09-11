package fundamentals

import issuer.IssuerId
import pit.Pit
import java.time.Instant
import java.time.LocalDate

/**
 * 提出済み財務資料（Issuer / filing-entity 側）のメタデータ。財務数値そのものは持たない。
 *
 * securityId は持たない。SEC filing / CompanyFacts は Issuer 側集約であり、
 * Security への解決は [issuer.IssuerSecurityRelation] 経由で明示的に行う。
 * 本型から Security を自動解決しない。
 *
 * fiscalPeriodEnd は対象会計期間の終了日（effectiveAt）。
 * filedAt / knownAt は提出・利用可能時刻であり、fiscalPeriodEnd・CIK・issuerId と混同しない。
 *
 * CompanyFacts の `filed`（日付）や submissions の acceptanceDateTime を
 * knownAt へ自動投入してはならない（Data Contract: filed は UNUSABLE、acceptanceDateTime は CONFIRMED ではない）。
 *
 * 不変条件: filedAt <= knownAt <= ingestedAt
 */
data class FundamentalSnapshot(
    val issuerId: IssuerId,
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
