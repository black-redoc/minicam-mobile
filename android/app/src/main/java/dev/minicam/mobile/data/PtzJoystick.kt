package dev.minicam.mobile.data

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Maps a joystick deflection to a PTZ direction: `null` inside the dead zone, otherwise the
 * dominant axis. [dx] grows to the right and [dy] grows downwards, like screen coordinates.
 */
object PtzJoystick {
    fun direction(dx: Float, dy: Float, deadZone: Float): PtzAction? = when {
        hypot(dx, dy) < deadZone -> null
        abs(dx) > abs(dy) -> if (dx > 0) PtzAction.RIGHT else PtzAction.LEFT
        else -> if (dy > 0) PtzAction.DOWN else PtzAction.UP
    }

    /**
     * Commands to send when the stick moves from [from] to [to]: the new direction, or `stop`
     * when it comes back to the dead zone. Nothing while the direction stays the same.
     */
    fun transition(from: PtzAction?, to: PtzAction?): PtzAction? = when {
        from == to -> null
        to == null -> PtzAction.STOP
        else -> to
    }
}
