package pit

import java.time.Instant

/**
 * Point-in-Time の2種類を混同しないための最小ユーティリティ。
 *
 * 知識PIT（isAvailableAt）:
 *   その版の情報を、根拠付きで利用可能になった時刻 knownAt と decisionAt の比較。
 *   decisionAt < knownAt  → 使用禁止
 *   decisionAt >= knownAt → 利用可能候補
 *
 * 保有PIT（wasHeldBySystemAt）:
 *   その時点でシステムが実際に保有していた情報を再現する場合に使う。
 *   ingestedAt <= decisionAt が必要。
 *
 * 現在時刻や対象日の 00:00 への暗黙変換は行わない。
 * 呼び出し側が Instant を明示する。
 */
object Pit {
    fun isAvailableAt(knownAt: Instant, decisionAt: Instant): Boolean =
        !decisionAt.isBefore(knownAt)

    fun wasHeldBySystemAt(ingestedAt: Instant, decisionAt: Instant): Boolean =
        !ingestedAt.isAfter(decisionAt)

    fun requireKnownBeforeOrAtIngested(knownAt: Instant, ingestedAt: Instant) {
        require(!ingestedAt.isBefore(knownAt)) {
            "ingestedAt ($ingestedAt) must not be before knownAt ($knownAt)"
        }
    }
}
