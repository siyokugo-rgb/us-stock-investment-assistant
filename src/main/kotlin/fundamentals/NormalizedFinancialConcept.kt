package fundamentals

/**
 * 正規化済み財務 concept（最小集合）。
 *
 * raw taxonomy/concept とは別レイヤ。
 * 明示 mapping が存在する standard US-GAAP tag にのみ付与する。
 * 未確認 tag・extension・企業固有 tag をここに丸め込まない。
 */
enum class NormalizedFinancialConcept {
    REVENUE,
    NET_INCOME,
    ASSETS,
    LIABILITIES,
    CASH_AND_CASH_EQUIVALENTS,
}
