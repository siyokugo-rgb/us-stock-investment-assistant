package security

import pit.Pit
import java.time.Instant
import java.time.LocalDate

/**
 * 銘柄に紐づく識別子の1バージョン。
 *
 * validFrom / validTo は、その識別子が当該 Security に意味上ひも付いていた日付範囲。
 * knownAt は、このバージョンの対応関係を根拠付きで知り得た時刻。
 * 両者は別概念であり、日付の 00:00 へ変換して比較しない。
 *
 * validFrom は inclusive、validTo は exclusive。
 * validTo == null は、このバージョンでは終了日が未知であることを表す（「最新」の暗黙選択ではない）。
 */
data class SecurityIdentifier(
    val securityId: SecurityId,
    val type: IdentifierType,
    val value: String,
    val validFrom: LocalDate,
    val validTo: LocalDate?,
    val knownAt: Instant,
    val ingestedAt: Instant,
    val source: String,
) {
    init {
        require(value.isNotBlank()) { "identifier value must not be blank" }
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
