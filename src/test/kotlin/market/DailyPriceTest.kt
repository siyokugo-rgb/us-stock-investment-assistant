package market

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import support.Fixtures
import support.Fixtures.SEC_A
import support.Fixtures.SEC_B
import java.math.BigDecimal

class DailyPriceTest {
    @Test
    fun negativePricesAreRejected() {
        assertFailsWith<IllegalArgumentException> { Fixtures.price(open = "-0.01") }
        assertFailsWith<IllegalArgumentException> { Fixtures.price(high = "-1") }
        assertFailsWith<IllegalArgumentException> { Fixtures.price(low = "-0.01") }
        assertFailsWith<IllegalArgumentException> { Fixtures.price(close = "-10") }
    }

    @Test
    fun negativeVolumeIsRejected() {
        assertFailsWith<IllegalArgumentException> { Fixtures.price(volume = -1L) }
    }

    @Test
    fun highBelowLowIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            Fixtures.price(high = "9.00", low = "10.00")
        }
    }

    @Test
    fun openOutsideLowHighIsRejected() {
        assertFailsWith<IllegalArgumentException> { Fixtures.price(open = "8.99") }
        assertFailsWith<IllegalArgumentException> { Fixtures.price(open = "11.01") }
    }

    @Test
    fun closeOutsideLowHighIsRejected() {
        assertFailsWith<IllegalArgumentException> { Fixtures.price(close = "8.99") }
        assertFailsWith<IllegalArgumentException> { Fixtures.price(close = "11.01") }
    }

    @Test
    fun boundaryOhlcAndZeroVolumeAreAccepted() {
        val flat = Fixtures.price(
            open = "5.00",
            high = "5.00",
            low = "5.00",
            close = "5.00",
            volume = 0L,
        )
        assertEquals(BigDecimal("5.00"), flat.close)
        val atEdges = Fixtures.price(open = "9.00", close = "11.00")
        assertEquals(BigDecimal("9.00"), atEdges.open)
        assertEquals(BigDecimal("11.00"), atEdges.close)
    }

    @Test
    fun zeroPriceIsAcceptedAtModelLayer() {
        val zero = Fixtures.price(open = "0", high = "0", low = "0", close = "0")
        assertEquals(0, zero.close.signum())
    }

    @Test
    fun scaleNormalizedComparisonDoesNotRejectEqualNumericBounds() {
        val price = DailyPrice(
            securityId = SEC_A,
            tradingDate = Fixtures.AS_OF,
            open = BigDecimal("10.0"),
            high = BigDecimal("10.00"),
            low = BigDecimal("10.000"),
            close = BigDecimal("10"),
            volume = 1L,
            currency = "USD",
            knownAt = Fixtures.KNOWN_AT,
            ingestedAt = Fixtures.INGESTED_AT,
            source = "fixture-source",
        )
        assertEquals(0, price.high.compareTo(price.low))
    }

    @Test
    fun ingestedBeforeKnownAtIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            Fixtures.price(knownAt = Fixtures.INGESTED_AT, ingestedAt = Fixtures.KNOWN_AT)
        }
    }

    @Test
    fun pricesForDifferentSecuritiesRemainSeparate() {
        val a = Fixtures.price(securityId = SEC_A, close = "10.50")
        val b = Fixtures.price(
            securityId = SEC_B,
            open = "99.00",
            high = "99.00",
            low = "99.00",
            close = "99.00",
        )
        val grouped = listOf(a, b).groupBy { it.securityId }
        assertEquals(setOf(SEC_A, SEC_B), grouped.keys)
        assertEquals(listOf(a), grouped.getValue(SEC_A))
        assertEquals(listOf(b), grouped.getValue(SEC_B))
        assertTrue(a.securityId != b.securityId)
    }

    @Test
    fun blankCurrencyOrSourceIsRejected() {
        assertFailsWith<IllegalArgumentException> { Fixtures.price(currency = " ") }
        assertFailsWith<IllegalArgumentException> { Fixtures.price(source = "") }
    }
}
