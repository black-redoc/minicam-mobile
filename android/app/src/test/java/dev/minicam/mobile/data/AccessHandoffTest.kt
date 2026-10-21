package dev.minicam.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessHandoffTest {
    private val wan = ServerConfig.parse("https://cam.example.com")

    private fun reason(handoff: AccessHandoff, url: String) =
        assertThrows(AccessHandoffException::class.java) { handoff.tokenFromCallback(url) }.reason

    @Test
    fun accessHandoff_buildsUrlWithState() {
        val handoff = AccessHandoff()

        assertEquals(43, handoff.state.length)
        assertTrue(handoff.state.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertNotEquals(handoff.state, AccessHandoff().state)
        assertEquals(
            "https://cam.example.com/api/mobile/access-handoff?state=${handoff.state}",
            handoff.handoffUrl(wan),
        )
    }

    @Test
    fun accessHandoff_extractsTokenFromFragment() {
        val handoff = AccessHandoff(state = "s1")

        assertEquals("eyJ.abc.def", handoff.tokenFromCallback("minicam://access-callback?state=s1#token=eyJ.abc.def"))
    }

    @Test
    fun accessHandoff_rejectsStateMismatch() {
        val handoff = AccessHandoff(state = "s1")

        assertEquals(
            AccessHandoffException.Reason.STATE_MISMATCH,
            reason(handoff, "minicam://access-callback?state=evil#token=t"),
        )
        assertEquals(
            AccessHandoffException.Reason.NOT_A_CALLBACK,
            reason(handoff, "https://evil.example.com/?state=s1#token=t"),
        )
        assertEquals(
            AccessHandoffException.Reason.MISSING_TOKEN,
            reason(handoff, "minicam://access-callback?state=s1"),
        )
    }
}
