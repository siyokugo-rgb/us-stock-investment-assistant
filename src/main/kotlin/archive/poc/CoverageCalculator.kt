package archive.poc

import java.time.Instant

data class CoverageWindow(
    val domain: String,
    val source: String,
    val coverageStartAt: Instant?,
    val coverageThroughAt: Instant?,
    val observedCount: Int,
    val hasNonObservedAlongside: Boolean,
)

/**
 * Coverage derived from OBSERVED rows only for (domain, source).
 * Failures do not create coverage; if present alongside OBSERVED, continuous completeness is not claimed.
 */
object CoverageCalculator {
    fun forDomainSource(
        records: List<ManifestRecord>,
        domain: String,
        source: String,
    ): CoverageWindow {
        val scoped = records.filter { it.domain == domain && it.source == source }
        val observed =
            scoped
                .filter { it.observationStatus == ObservationStatus.OBSERVED }
                .sortedBy { it.ingestedAt }
        val hasNonObserved = scoped.any { it.observationStatus != ObservationStatus.OBSERVED }
        return CoverageWindow(
            domain = domain,
            source = source,
            coverageStartAt = observed.firstOrNull()?.ingestedAt,
            coverageThroughAt = observed.lastOrNull()?.ingestedAt,
            observedCount = observed.size,
            hasNonObservedAlongside = hasNonObserved && observed.isNotEmpty(),
        )
    }
}
