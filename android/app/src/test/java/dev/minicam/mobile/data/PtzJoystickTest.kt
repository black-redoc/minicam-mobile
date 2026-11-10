package dev.minicam.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PtzJoystickTest {
    @Test
    fun insideDeadZoneIsNoDirection() {
        assertNull(PtzJoystick.direction(3f, -4f, deadZone = 10f))
    }

    @Test
    fun dominantAxisWins() {
        assertEquals(PtzAction.RIGHT, PtzJoystick.direction(30f, 10f, 10f))
        assertEquals(PtzAction.LEFT, PtzJoystick.direction(-30f, -29f, 10f))
        assertEquals(PtzAction.UP, PtzJoystick.direction(5f, -30f, 10f))
        assertEquals(PtzAction.DOWN, PtzJoystick.direction(-5f, 30f, 10f))
    }

    @Test
    fun activeDirectionHoldsUntilWellInsideDeadZone() {
        // 8 is under the dead zone but above 60 % of it: still moving.
        assertEquals(PtzAction.UP, PtzJoystick.direction(0f, -8f, 10f, current = PtzAction.UP))
        assertNull(PtzJoystick.direction(0f, -5f, 10f, current = PtzAction.UP))
        // From rest, the same deflection does not start a move.
        assertNull(PtzJoystick.direction(0f, -8f, 10f))
    }

    @Test
    fun otherAxisNeedsAClearWin() {
        // Slightly past the diagonal: keep going right.
        assertEquals(PtzAction.RIGHT, PtzJoystick.direction(20f, 24f, 10f, current = PtzAction.RIGHT))
        // Clearly down now.
        assertEquals(PtzAction.DOWN, PtzJoystick.direction(20f, 30f, 10f, current = PtzAction.RIGHT))
        // Same axis, other side: follow at once.
        assertEquals(PtzAction.LEFT, PtzJoystick.direction(-20f, 5f, 10f, current = PtzAction.RIGHT))
    }

    @Test
    fun transitionSendsNewDirectionOrStop() {
        assertEquals(PtzAction.LEFT, PtzJoystick.transition(null, PtzAction.LEFT))
        assertEquals(PtzAction.UP, PtzJoystick.transition(PtzAction.LEFT, PtzAction.UP))
        assertEquals(PtzAction.STOP, PtzJoystick.transition(PtzAction.UP, null))
        assertNull(PtzJoystick.transition(PtzAction.UP, PtzAction.UP))
        assertNull(PtzJoystick.transition(null, null))
    }
}
