package dev.minicam.mobile.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// First-admin setup from the app (docs/adr/0008, minicam ADR 0006): the login screen
// asks the server whether it still needs an admin and, on the Raspberry Pi OS image,
// has its LED blink a code the user types in.

@Serializable
data class SetupStatus(
    @SerialName("setup_required") val setupRequired: Boolean,
    /** "none": any LAN client may create the admin; "led": it needs the LED code. */
    val claim: String = "none",
    @SerialName("window_open") val windowOpen: Boolean = false,
    @SerialName("window_seconds_left") val windowSecondsLeft: Int = 0,
) {
    val needsLedCode: Boolean get() = claim == "led"

    val state: DeviceSetupState
        get() = when {
            !setupRequired -> DeviceSetupState.CONFIGURED
            // Old servers report no window at all: setup is open while no user exists.
            claim == "none" || windowOpen -> DeviceSetupState.READY
            else -> DeviceSetupState.RESTART_NEEDED
        }
}

enum class DeviceSetupState {
    /** An admin exists: sign in. */
    CONFIGURED,

    /** No admin yet and setup is open: create one. */
    READY,

    /** No admin yet but the 30-minute window is over: power-cycle the device. */
    RESTART_NEEDED,
}

@Serializable
data class SetupClaim(val digits: Int, @SerialName("expires_in") val expiresIn: Int)

@Serializable
internal data class FirstAdminRequest(
    val email: String,
    val password: String,
    @SerialName("setup_code") val setupCode: String? = null,
) {
    override fun toString() = "FirstAdminRequest(email=$email)"
}

object SetupCode {
    private val PATTERN = Regex("[1-9]{4}")

    /** The LED blinks 4 digits, each 1-9 (a digit is a number of blinks, so never 0). */
    fun isValid(code: String): Boolean = PATTERN.matches(code)
}

/** Public: works before any account exists. */
suspend fun MinicamApi.setupStatus(): SetupStatus = get("/api/auth/setup", SetupStatus.serializer())

/** Has the device's LED blink a new code (LAN, LED claim, setup window). */
suspend fun MinicamApi.requestSetupCode(): SetupClaim = post("/api/auth/setup/claim", SetupClaim.serializer())
