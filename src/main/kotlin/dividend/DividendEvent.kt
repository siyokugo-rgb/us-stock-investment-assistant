package dividend

import pit.Pit
import security.SecurityId
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * 配当イベント。effectiveAt は exDate。
 *
 * 履歴上 exDate が存在することと、過去の decisionAt 時点でその配当予定を
 * 知っていたこと（knownAt <= decisionAt）は別である。
 *
 * declarationDate / recordDate / paymentDate は欠損し得る。
 */
data class DividendEvent(
    val securityId: SecurityId,
    val declarationDate: LocalDate?,
    val exDate: LocalDate,
    val recordDate: LocalDate?,
    val paymentDate: LocalDate?,
    val amountPerShare: BigDecimal,
    val currency: String,
    val dividendType: DividendType,
    val knownAt: Instant,
    val ingestedAt: Instant,
    val source: String,
) {
    init {
        require(currency.isNotBlank()) { "currency must not be blank" }
        require(source.isNotBlank()) { "source must not be blank" }
        require(amountPerShare.signum() >= 0) {
            "amountPerShare must not be negative: $amountPerShare"
        }
        Pit.requireKnownBeforeOrAtIngested(knownAt, ingestedAt)
    }

    fun isAvailableAt(decisionAt: Instant): Boolean = Pit.isAvailableAt(knownAt, decisionAt)

    fun wasHeldBySystemAt(decisionAt: Instant): Boolean = Pit.wasHeldBySystemAt(ingestedAt, decisionAt)
}
