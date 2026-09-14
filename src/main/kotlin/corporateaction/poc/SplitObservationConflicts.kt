package corporateaction.poc

/**
 * duplicate / correction 候補の衝突検出（自動解決しない）。
 *
 * latest-wins / fetchedAt 新しい方の自動採用は禁止。
 * 衝突時は呼び出し側が UNRESOLVED として扱わなければならない。
 */
object SplitObservationConflicts {
    data class RatioConflict(
        val left: RawSplitObservation,
        val right: RawSplitObservation,
        val reason: String,
    )

    data class DateConflict(
        val left: RawSplitObservation,
        val right: RawSplitObservation,
        val reason: String,
    )

    /**
     * 同一 provider + providerSymbol + eventId（両方 non-null）で ratio が異なる場合。
     * eventId が無い場合は「同一 event」と断定せず、衝突判定しない。
     */
    fun conflictingRatiosForSameEventId(
        observations: List<RawSplitObservation>,
    ): List<RatioConflict> {
        val groups =
            observations
                .filter { it.eventId != null }
                .groupBy { Triple(it.provider, it.providerSymbol, it.eventId) }
        val out = mutableListOf<RatioConflict>()
        for ((_, rows) in groups) {
            if (rows.size < 2) continue
            val first = rows.first()
            for (other in rows.drop(1)) {
                if (first.ratio != other.ratio || first.actionType != other.actionType) {
                    out +=
                        RatioConflict(
                            left = first,
                            right = other,
                            reason =
                                "same provider/symbol/eventId has conflicting ratio or actionType; " +
                                    "auto-resolve forbidden",
                        )
                }
            }
        }
        return out
    }

    /**
     * 同一 eventId で effectiveDate が両方あり、かつ異なる場合。
     */
    fun conflictingEffectiveDatesForSameEventId(
        observations: List<RawSplitObservation>,
    ): List<DateConflict> {
        val groups =
            observations
                .filter { it.eventId != null && it.effectiveDate != null }
                .groupBy { Triple(it.provider, it.providerSymbol, it.eventId) }
        val out = mutableListOf<DateConflict>()
        for ((_, rows) in groups) {
            if (rows.size < 2) continue
            val first = rows.first()
            for (other in rows.drop(1)) {
                if (first.effectiveDate != other.effectiveDate) {
                    out +=
                        DateConflict(
                            left = first,
                            right = other,
                            reason =
                                "same provider/symbol/eventId has conflicting effectiveDate; " +
                                    "auto-resolve forbidden",
                        )
                }
            }
        }
        return out
    }

    /** identical evidence payload の重複（除外は可だが「最新版」扱い禁止）。 */
    fun duplicateIdenticalObservations(
        observations: List<RawSplitObservation>,
    ): List<Pair<RawSplitObservation, RawSplitObservation>> {
        val out = mutableListOf<Pair<RawSplitObservation, RawSplitObservation>>()
        for (i in observations.indices) {
            for (j in i + 1 until observations.size) {
                val a = observations[i]
                val b = observations[j]
                if (a.copy(fetchedAt = b.fetchedAt) == b) {
                    out += a to b
                }
            }
        }
        return out
    }
}
