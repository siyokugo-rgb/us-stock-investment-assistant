package fundamentals

/**
 * version 候補の最小 classification。
 *
 * AMENDMENT ≠ authoritative replacement。
 * SEC 一次情報で確定できない場合は UNRESOLVED / candidate に留める。
 */
enum class FactVersionClassification {
    /** form が /A で終わらない提出版候補 */
    ORIGINAL,

    /** form が /A で終わる提出版候補。original を自動置換しない */
    AMENDMENT,

    /** 同一 concept/period/unit/value で accession のみ異なる開示候補 */
    REPEATED,

    /** 同一 concept/period/unit で value が異なる別 accession（restatement 可能性。未確定） */
    RESTATEMENT_CANDIDATE,

    /** 関係を確定できない */
    UNRESOLVED,
}

/**
 * 候補間の関係から最小 classification を付与する。
 * 単一の authoritative 値を選ばない。
 */
object FactVersionClassifier {
    fun classify(
        fact: RawFinancialFact,
        peers: List<RawFinancialFact>,
    ): FactVersionClassification {
        val sameBucket =
            peers.filter {
                it.issuerId == fact.issuerId &&
                    it.taxonomy == fact.taxonomy &&
                    it.concept == fact.concept &&
                    it.unit == fact.unit &&
                    it.period().comparisonKey() == fact.period().comparisonKey() &&
                    it.accessionNumber != fact.accessionNumber
            }

        val sameValueDifferentAccession =
            sameBucket.any { it.value.compareTo(fact.value) == 0 }
        val differentValueDifferentAccession =
            sameBucket.any { it.value.compareTo(fact.value) != 0 }

        return when {
            differentValueDifferentAccession -> FactVersionClassification.RESTATEMENT_CANDIDATE
            sameValueDifferentAccession -> FactVersionClassification.REPEATED
            fact.form.trim().endsWith("/A") -> FactVersionClassification.AMENDMENT
            fact.form.isNotBlank() -> FactVersionClassification.ORIGINAL
            else -> FactVersionClassification.UNRESOLVED
        }
    }
}
