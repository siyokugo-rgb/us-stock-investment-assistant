package pit

import security.SecurityId
import java.time.Instant

/**
 * PIT 条件を満たす候補集合を返す最小 Query。
 *
 * 最新版・訂正版の自動選択、source conflict 解決は行わない。
 * 条件を満たすレコードをすべて返す。
 */
object PitQuery {
    fun <T> availableAt(
        records: Collection<T>,
        decisionAt: Instant,
        knownAt: (T) -> Instant,
    ): List<T> = records.filter { Pit.isAvailableAt(knownAt(it), decisionAt) }

    fun <T> heldBySystemAt(
        records: Collection<T>,
        decisionAt: Instant,
        ingestedAt: (T) -> Instant,
    ): List<T> = records.filter { Pit.wasHeldBySystemAt(ingestedAt(it), decisionAt) }

    fun <T> forSecurity(
        records: Collection<T>,
        securityId: SecurityId,
        idOf: (T) -> SecurityId,
    ): List<T> = records.filter { idOf(it) == securityId }
}
