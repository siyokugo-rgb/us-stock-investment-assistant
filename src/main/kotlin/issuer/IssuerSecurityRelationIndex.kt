package issuer

import java.time.Instant
import java.time.LocalDate

/**
 * Issuer ↔ Security relation の最小 Index。
 *
 * 行わないこと:
 * - 最初 / 最新 / ticker 優先での単一 Security 自動選択
 * - share class の自動統合
 * - 代表銘柄の勝手な決定
 * - CIK / ticker からの Security 推測
 *
 * 同一 Issuer に複数 Security がヒットした場合は候補をすべて返す。
 * 単一 Security が必要な上位処理は、候補数 != 1 なら Fail-Closed とする（本 Index は Decision Engine ではない）。
 */
class IssuerSecurityRelationIndex(
    private val relations: List<IssuerSecurityRelation>,
) {
    /**
     * issuerId 一致かつ
     * validFrom <= asOfDate かつ (validTo == null || asOfDate < validTo) かつ
     * decisionAt >= knownAt
     * を満たす relation 候補をすべて返す。空集合もあり得る。
     *
     * ingestedAt は知識PIT条件に含めない（保有PITは呼び出し側で [IssuerSecurityRelation.wasHeldBySystemAt]）。
     */
    fun availableSecuritiesFor(
        issuerId: IssuerId,
        asOfDate: LocalDate,
        decisionAt: Instant,
    ): List<IssuerSecurityRelation> =
        relations.filter { relation ->
            relation.issuerId == issuerId &&
                relation.isValidOn(asOfDate) &&
                relation.isAvailableAt(decisionAt)
        }
}
