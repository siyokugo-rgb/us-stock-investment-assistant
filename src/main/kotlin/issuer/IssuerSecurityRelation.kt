package issuer

import pit.Pit
import security.SecurityId
import java.time.Instant
import java.time.LocalDate

/**
 * Issuer と Security の明示的な関係の1版。
 *
 * validFrom / validTo = 現実世界でその relation が成立した期間
 *   - validFrom: inclusive
 *   - validTo: exclusive
 *   - validTo == null: 終了日未知（最新の暗黙選択ではない）
 *
 * knownAt = その relation 情報が利用可能になった時刻
 * ingestedAt = アプリが取得した時刻
 *
 * 三者を混同しない。knownAt / ingestedAt を validFrom から生成しない。
 */
data class IssuerSecurityRelation(
    val issuerId: IssuerId,
    val securityId: SecurityId,
    val validFrom: LocalDate,
    val validTo: LocalDate?,
    val knownAt: Instant,
    val ingestedAt: Instant,
    val source: String,
) {
    init {
        require(source.isNotBlank()) { "source must not be blank" }
        if (validTo != null) {
            require(validTo.isAfter(validFrom)) {
                "validTo ($validTo) must be after validFrom ($validFrom) because validTo is exclusive"
            }
        }
        Pit.requireKnownBeforeOrAtIngested(knownAt, ingestedAt)
    }

    fun isAvailableAt(decisionAt: Instant): Boolean = Pit.isAvailableAt(knownAt, decisionAt)

    fun wasHeldBySystemAt(decisionAt: Instant): Boolean = Pit.wasHeldBySystemAt(ingestedAt, decisionAt)

    fun isValidOn(asOfDate: LocalDate): Boolean {
        if (asOfDate.isBefore(validFrom)) return false
        val end = validTo
        return end == null || asOfDate.isBefore(end)
    }
}
