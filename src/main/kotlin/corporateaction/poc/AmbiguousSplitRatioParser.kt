package corporateaction.poc

/**
 * Provider 固有の曖昧な ratio 文字列を共通化して解釈しない。
 *
 * `"2:1"` / `"2-for-1"` / `"0.5"` 等は向き・定義が Provider 依存のため、
 * 本 PoC では **パース成功させない**（Fail-Closed）。
 * 明示的な [SplitRatio] 構築（oldShares/newShares）のみ許可。
 */
object AmbiguousSplitRatioParser {
    fun parseOrFail(raw: String): Nothing =
        throw IllegalArgumentException(
            "refusing to parse ambiguous split ratio string without explicit orientation: '$raw'. " +
                "Construct SplitRatio(oldShares, newShares) with provider-confirmed semantics instead.",
        )
}
