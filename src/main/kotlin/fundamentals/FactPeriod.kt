package fundamentals

import java.time.LocalDate

/**
 * Fact の期間 identity。
 *
 * - Instant: end のみ
 * - Duration: start + end
 *
 * fy / fp / frame は補助 metadata であり、本 identity には含めない。
 * annual / quarterly を fp 文字列だけで断定しない。
 */
sealed class FactPeriod {
    abstract val end: LocalDate

    abstract fun comparisonKey(): PeriodComparisonKey

    data class InstantPoint(
        override val end: LocalDate,
    ) : FactPeriod() {
        override fun comparisonKey(): PeriodComparisonKey =
            PeriodComparisonKey(
                kind = PeriodKind.INSTANT,
                start = null,
                end = end,
            )
    }

    data class Duration(
        val start: LocalDate,
        override val end: LocalDate,
    ) : FactPeriod() {
        init {
            require(!start.isAfter(end)) { "duration start ($start) must not be after end ($end)" }
        }

        override fun comparisonKey(): PeriodComparisonKey =
            PeriodComparisonKey(
                kind = PeriodKind.DURATION,
                start = start,
                end = end,
            )
    }
}

enum class PeriodKind {
    INSTANT,
    DURATION,
}

/**
 * period 比較キー。同一キーでも unit / accession / frame / form が違えば別 version 候補。
 */
data class PeriodComparisonKey(
    val kind: PeriodKind,
    val start: LocalDate?,
    val end: LocalDate,
) {
    init {
        when (kind) {
            PeriodKind.INSTANT ->
                require(start == null) { "INSTANT period key must not carry start" }
            PeriodKind.DURATION ->
                require(start != null) { "DURATION period key requires start" }
        }
    }
}
