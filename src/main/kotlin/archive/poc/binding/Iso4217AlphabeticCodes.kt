package archive.poc.binding

import java.util.Currency

/**
 * Minimal ISO 4217 **alphabetic** code membership check for Trading Currency Layer B.
 *
 * Method: [Currency.getInstance] (JDK current ISO 4217 table).
 * Maintenance: follows the JVM / JDK currency data updates — no hand-maintained code list.
 *
 * Fail-Closed contract:
 * - Caller must reject values that do not already match `^[A-Z]{3}$` **before** calling
 *   [isAlphabeticMember]. Never pass lowercase for “repair”.
 * - This helper does not uppercase or otherwise normalize input.
 */
object Iso4217AlphabeticCodes {
    fun isAlphabeticMember(canonicalUppercaseCode: String): Boolean {
        require(TradingCurrencyEvidence.CANONICAL_ISO_ALPHA.matches(canonicalUppercaseCode)) {
            "Iso4217AlphabeticCodes requires pre-validated ^[A-Z]{3}$ input; got=$canonicalUppercaseCode"
        }
        return try {
            Currency.getInstance(canonicalUppercaseCode)
            true
        } catch (_: IllegalArgumentException) {
            false
        }
    }
}
