package corporateaction.poc

import java.math.BigDecimal
import java.math.MathContext

/**
 * Split 適用時の数量変換（数学境界のみ）。
 *
 * newQuantity = oldQuantity × newShares / oldShares
 *
 * 対象外: portfolio ledger / cash-in-lieu / tax lot / broker rounding / order simulation。
 * 端株の整数化や現金精算を仮定しない。非終端少数は Fail-Closed（丸めない）。
 */
object SplitQuantityMath {
    fun applySplitQuantity(
        oldQuantity: BigDecimal,
        oldShares: BigDecimal,
        newShares: BigDecimal,
    ): BigDecimal = applySplitQuantity(oldQuantity, SplitRatio(oldShares, newShares))

    fun applySplitQuantity(
        oldQuantity: BigDecimal,
        ratio: SplitRatio,
    ): BigDecimal {
        require(oldQuantity.signum() >= 0) {
            "oldQuantity must not be negative: $oldQuantity"
        }
        return try {
            oldQuantity
                .multiply(ratio.newShares)
                .divide(ratio.oldShares, MathContext.UNLIMITED)
        } catch (ex: ArithmeticException) {
            throw IllegalArgumentException(
                "split quantity division is non-terminating and must not be rounded: " +
                    "oldQuantity=$oldQuantity ratio=$ratio",
                ex,
            )
        }
    }
}
