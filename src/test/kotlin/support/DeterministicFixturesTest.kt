package support

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import java.time.Instant
import java.time.LocalDate

class DeterministicFixturesTest {
    @Test
    fun timestampsAreLiteralFixturesAndDoNotUseClockOrRandom() {
        assertEquals(Instant.parse("2024-06-10T16:00:00Z"), Fixtures.KNOWN_AT)
        assertEquals(Instant.parse("2024-06-10T16:00:00Z"), Fixtures.DECISION_EQUAL)
        assertEquals(LocalDate.of(2024, 6, 10), Fixtures.AS_OF)
        assertEquals(Fixtures.KNOWN_AT, Instant.parse("2024-06-10T16:00:00Z"))
        assertNotEquals(Fixtures.DECISION_BEFORE, Fixtures.KNOWN_AT)
    }
}
