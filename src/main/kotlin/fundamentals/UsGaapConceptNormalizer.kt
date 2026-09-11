package fundamentals

/**
 * raw concept → normalized concept の明示 mapping 境界。
 *
 * - 巨大辞書にしない
 * - CompanyFacts PoC / fixture で観察済みの standard `us-gaap` tag のみ
 * - unknown / extension は null（unmapped）のまま。UNKNOWN へ丸めない
 * - 文字列類似・推測 mapping 禁止
 */
object UsGaapConceptNormalizer {
    private const val US_GAAP = "us-gaap"

    /**
     * PoC / fixture / live 観察済みの standard US-GAAP tag のみ。
     * 未観察 tag を追加しない。
     */
    private val EXPLICIT: Map<String, NormalizedFinancialConcept> =
        mapOf(
            "RevenueFromContractWithCustomerExcludingAssessedTax" to NormalizedFinancialConcept.REVENUE,
            "Revenues" to NormalizedFinancialConcept.REVENUE,
            "NetIncomeLoss" to NormalizedFinancialConcept.NET_INCOME,
            "Assets" to NormalizedFinancialConcept.ASSETS,
            "Liabilities" to NormalizedFinancialConcept.LIABILITIES,
            "CashAndCashEquivalentsAtCarryingValue" to
                NormalizedFinancialConcept.CASH_AND_CASH_EQUIVALENTS,
        )

    /**
     * @return 明示 mapping がある場合のみ normalized concept。それ以外 null（unmapped）。
     */
    fun normalize(
        taxonomy: String,
        concept: String,
    ): NormalizedFinancialConcept? {
        if (taxonomy != US_GAAP) return null
        return EXPLICIT[concept]
    }
}
