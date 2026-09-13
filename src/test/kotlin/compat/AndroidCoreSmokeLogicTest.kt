package compat

import kotlin.test.Test
import kotlin.test.assertTrue

class AndroidCoreSmokeLogicTest {
    @Test
    fun smokeLogicPassesOnJvm() {
        val result = AndroidCoreSmokeLogic.run()
        assertTrue(result.startsWith("ANDROID CORE SMOKE: PASS"), result)
    }
}
