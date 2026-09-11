package fundamentals

/**
 * version 候補の最小 classification。
 *
 * AMENDMENT ≠ authoritative replacement。
 * 値差だけでは RESTATEMENT_CANDIDATE に昇格しない。
 * RESTATEMENT_CANDIDATE は SEC 一次情報など明示 evidence が将来追加された場合のみ付与可能（本 classifier では自動生成しない）。
 */
enum class FactVersionClassification {
    /** form が /A で終わらない通常 filing 候補 */
    ORIGINAL,

    /** form が /A で終わる提出版候補。original を自動置換しない */
    AMENDMENT,

    /** 同一 concept/period/unit/value で accession のみ異なる開示候補 */
    REPEATED,

    /**
     * restatement 候補。
     * 値差だけでは付与しない。明示的な一次 evidence が無い限り本 classifier は返さない。
     */
    RESTATEMENT_CANDIDATE,

    /** 関係を確定できない（例: 同一 bucket の値差で /A でもない） */
    UNRESOLVED,
}

/**
 * 候補間の関係から最小 classification を付与する。
 * 単一の authoritative 値を選ばない。
 *
 * 規則:
 * - form が `/A` → [AMENDMENT]（値差があっても自動 replacement / restatement にしない）
 * - 同一 concept/period/unit で値差 peer が存在 → [UNRESOLVED]（同値 peer がいても REPEATED より優先。RESTATEMENT_CANDIDATE へ自動昇格しない）
 * - 値差 peer が無く、同値・別 accession のみ → [REPEATED]
 * - peer 無しの通常 filing → [ORIGINAL]
 */
object FactVersionClassifier {
    fun classify(
        fact: RawFinancialFact,
        peers: List<RawFinancialFact>,
    ): FactVersionClassification {
        if (fact.form.trim().endsWith("/A")) {
            return FactVersionClassification.AMENDMENT
        }

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
            differentValueDifferentAccession -> FactVersionClassification.UNRESOLVED
            sameValueDifferentAccession -> FactVersionClassification.REPEATED
            fact.form.isNotBlank() -> FactVersionClassification.ORIGINAL
            else -> FactVersionClassification.UNRESOLVED
        }
    }
}
