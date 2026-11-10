package dev.minicam.mobile.data

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Maps a joystick deflection to a PTZ direction. [dx] grows to the right and [dy] grows
 * downwards, like screen coordinates.
 *
 * Built against false positives: nothing inside the dead zone; once a direction is [current]
 * it holds until the stick is back under [RELEASE_RATIO] of the dead zone (hysteresis), and it
 * only turns to the other axis when that axis clearly wins ([SWITCH_RATIO]), so a shaky thumb
 * near a diagonal does not flip between directions.
 */
object PtzJoystick {
    const val RELEASE_RATIO = 0.6f
    const val SWITCH_RATIO = 1.35f

    fun direction(dx: Float, dy: Float, deadZone: Float, current: PtzAction? = null): PtzAction? {
        val threshold = if (current == null) deadZone else deadZone * RELEASE_RATIO
        if (hypot(dx, dy) < threshold) return null
        val dominant = if (abs(dx) > abs(dy)) {
            if (dx > 0) PtzAction.RIGHT else PtzAction.LEFT
        } else {
            if (dy > 0) PtzAction.DOWN else PtzAction.UP
        }
        if (current == null || current == PtzAction.STOP || dominant == current) return dominant
        // Same axis, other side: follow it. Other axis: only when it clearly wins.
        val currentHorizontal = current == PtzAction.LEFT || current == PtzAction.RIGHT
        val along = if (currentHorizontal) dx else dy
        val across = if (currentHorizontal) dy else dx
        val sameSide = if (current == PtzAction.RIGHT || current == PtzAction.DOWN) along > 0 else along < 0
        return if (sameSide && abs(across) < abs(along) * SWITCH_RATIO) current else dominant
    }

    /**
     * Command to send when the stick moves from [from] to [to]: the new direction, or `stop`
     * when it comes back to the dead zone. Nothing while the direction stays the same.
     */
    fun transition(from: PtzAction?, to: PtzAction?): PtzAction? = when {
        from == to -> null
        to == null -> PtzAction.STOP
        else -> to
    }
}
