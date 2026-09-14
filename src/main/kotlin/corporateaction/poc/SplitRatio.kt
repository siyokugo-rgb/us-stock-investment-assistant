package corporateaction.poc

import java.math.BigDecimal

/**
 * Stock split / reverse split の比率。
 *
 * 意味は固定:
 * - [oldShares]: split 直前に 1 単位として扱う旧株数
 *   （例: 2-for-1 → oldShares=1、1-for-10 reverse → oldShares=10）
 * - [newShares]: その旧株数に対して交付される新株数
 *   （例: 2-for-1 → newShares=2、1-for-10 reverse → newShares=1）
 *
 * `"2:1"` のような向き不明文字列を推測で解釈しない。
 * Double は使わない。0 / 負は Fail-Closed。
 */
data class SplitRatio(
    val oldShares: BigDecimal,
    val newShares: BigDecimal,
) {
    init {
        require(oldShares.signum() > 0) { "oldShares must be positive, got: $oldShares" }
        require(newShares.signum() > 0) { "newShares must be positive, got: $newShares" }
    }

    fun isForwardSplit(): Boolean = newShares.compareTo(oldShares) > 0

    fun isReverseSplit(): Boolean = newShares.compareTo(oldShares) < 0

    fun isIdentityRatio(): Boolean = newShares.compareTo(oldShares) == 0

    companion object {
        fun of(
            oldShares: String,
            newShares: String,
        ): SplitRatio = SplitRatio(BigDecimal(oldShares), BigDecimal(newShares))
    }
}
