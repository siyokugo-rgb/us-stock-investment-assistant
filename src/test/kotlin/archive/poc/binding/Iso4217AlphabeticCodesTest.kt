package archive.poc.binding

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class Iso4217AlphabeticCodesTest {
    @Test
    fun usdIsAlphabeticMember() {
        assertTrue(Iso4217AlphabeticCodes.isAlphabeticMember("USD"))
    }

    @Test
    fun abcIsNotAlphabeticMember() {
        assertFalse(Iso4217AlphabeticCodes.isAlphabeticMember("ABC"))
    }

    @Test
    fun lowercaseUsdRejectedAtRegexBeforeMembership() {
        val ex =
            assertFailsWith<IllegalArgumentException> {
                Iso4217AlphabeticCodes.isAlphabeticMember("usd")
            }
        assertTrue(ex.message!!.contains("pre-validated"))
        assertTrue(ex.message!!.contains("usd"))
    }
}
