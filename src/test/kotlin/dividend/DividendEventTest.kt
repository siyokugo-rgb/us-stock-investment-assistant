package dividend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import support.Fixtures
import java.math.BigDecimal

class DividendEventTest {
    @Test
    fun optionalDatesMayBeNull() {
        val event = Fixtures.dividend(
            declarationDate = null,
            recordDate = null,
            paymentDate = null,
            dividendType = DividendType.UNKNOWN,
        )
        assertNull(event.declarationDate)
        assertNull(event.recordDate)
        assertNull(event.paymentDate)
        assertEquals(DividendType.UNKNOWN, event.dividendType)
        assertEquals(Fixtures.SEC_A, event.securityId)
    }

    @Test
    fun partialOptionalDatesMayBeNullIndependently() {
        val event = Fixtures.dividend(declarationDate = null, recordDate = Fixtures.AS_OF, paymentDate = null)
        assertNull(event.declarationDate)
        assertEquals(Fixtures.AS_OF, event.recordDate)
        assertNull(event.paymentDate)
    }

    @Test
    fun negativeAmountIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            Fixtures.dividend(amountPerShare = "-0.01")
        }
    }

    @Test
    fun zeroAmountIsAcceptedAtModelLayer() {
        val event = Fixtures.dividend(amountPerShare = "0")
        assertEquals(0, event.amountPerShare.signum())
    }

    @Test
    fun specialDividendIsKeptDistinctFromRegular() {
        val special = Fixtures.dividend(dividendType = DividendType.SPECIAL, amountPerShare = "1.50")
        assertEquals(DividendType.SPECIAL, special.dividendType)
        assertEquals(BigDecimal("1.50"), special.amountPerShare)
    }

    @Test
    fun dividendDoesNotJoinDifferentSecurityIds() {
        val a = Fixtures.dividend(securityId = Fixtures.SEC_A)
        val b = Fixtures.dividend(securityId = Fixtures.SEC_B, amountPerShare = "9.99")
        val onlyA = listOf(a, b).filter { it.securityId == Fixtures.SEC_A }
        assertEquals(listOf(a), onlyA)
    }
}
