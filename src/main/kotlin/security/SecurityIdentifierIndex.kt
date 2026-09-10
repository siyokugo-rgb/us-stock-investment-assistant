package security

import java.time.Instant
import java.time.LocalDate

/**
 * 銘柄別・時点別の識別子候補を返す最小 Index。
 *
 * 行わないこと:
 * - 最新版の暗黙選択
 * - source conflict の自動解決
 * - Ticker 文字列だけでの Security 自動統合
 *
 * 同一 Ticker が複数 SecurityId にヒットした場合は、候補をすべて返す。
 * 呼び出し側が衝突を Fail-Closed する。
 */
class SecurityIdentifierIndex(
    private val identifiers: List<SecurityIdentifier>,
) {
    fun candidatesForSecurity(
        securityId: SecurityId,
        decisionAt: Instant,
        asOfDate: LocalDate,
    ): List<SecurityIdentifier> =
        identifiers.filter { identifier ->
            identifier.securityId == securityId &&
                identifier.isAvailableAt(decisionAt) &&
                identifier.isValidOn(asOfDate)
        }

    fun candidatesByIdentifier(
        type: IdentifierType,
        value: String,
        decisionAt: Instant,
        asOfDate: LocalDate,
    ): List<SecurityIdentifier> =
        identifiers.filter { identifier ->
            identifier.type == type &&
                identifier.value == value &&
                identifier.isAvailableAt(decisionAt) &&
                identifier.isValidOn(asOfDate)
        }
}
