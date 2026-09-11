package fundamentals

import issuer.IssuerId

/**
 * raw fact に正規化結果と version classification を付与した候補。
 * 最終値ではない。
 */
data class FinancialFactVersionCandidate(
    val fact: RawFinancialFact,
    val period: FactPeriod,
    val periodKey: PeriodComparisonKey,
    /** 明示 mapping がある場合のみ。unmapped は null（UNKNOWN へ丸めない） */
    val normalizedConcept: NormalizedFinancialConcept?,
    val classification: FactVersionClassification,
) {
    init {
        require(period.comparisonKey() == periodKey) { "periodKey must match fact period" }
        require(period == fact.period()) { "period must match fact.period()" }
    }

    companion object {
        fun from(
            fact: RawFinancialFact,
            peers: List<RawFinancialFact>,
        ): FinancialFactVersionCandidate {
            val period = fact.period()
            return FinancialFactVersionCandidate(
                fact = fact,
                period = period,
                periodKey = period.comparisonKey(),
                normalizedConcept = UsGaapConceptNormalizer.normalize(fact.taxonomy, fact.concept),
                classification = FactVersionClassifier.classify(fact, peers),
            )
        }
    }
}

/**
 * 候補集合 API。latest-wins / amendment 自動置換 / 単一値自動解決はしない。
 *
 * 単一値が必要な上位層は [requireSingleDeterminedCandidate] を使う。
 * 候補数 ≠ 1、または concept 未確定の場合は Fail-Closed。
 */
class FinancialFactCandidateSet(
    facts: List<RawFinancialFact>,
) {
    val candidates: List<FinancialFactVersionCandidate> =
        facts.map { fact -> FinancialFactVersionCandidate.from(fact, peers = facts) }

    fun candidatesFor(
        issuerId: IssuerId,
        normalizedConcept: NormalizedFinancialConcept,
        periodKey: PeriodComparisonKey,
    ): List<FinancialFactVersionCandidate> =
        candidates.filter {
            it.fact.issuerId == issuerId &&
                it.normalizedConcept == normalizedConcept &&
                it.periodKey == periodKey
        }

    /**
     * 単一候補かつ normalized concept が確定している場合のみ返す。
     * それ以外は Fail-Closed（最新 accession / filed での自動選択はしない）。
     */
    fun requireSingleDeterminedCandidate(
        issuerId: IssuerId,
        normalizedConcept: NormalizedFinancialConcept,
        periodKey: PeriodComparisonKey,
    ): FinancialFactVersionCandidate {
        val matched = candidatesFor(issuerId, normalizedConcept, periodKey)
        require(matched.size == 1) {
            "Fail-Closed: expected exactly 1 determined candidate for " +
                "$issuerId / $normalizedConcept / $periodKey, got ${matched.size}"
        }
        val only = matched.single()
        require(only.normalizedConcept == normalizedConcept) {
            "Fail-Closed: candidate concept is not determined"
        }
        return only
    }
}
