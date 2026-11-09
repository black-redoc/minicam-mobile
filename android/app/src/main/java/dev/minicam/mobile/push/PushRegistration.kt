package dev.minicam.mobile.push

import dev.minicam.mobile.data.CredentialKey
import dev.minicam.mobile.data.CredentialStore
import dev.minicam.mobile.data.MinicamApi
import dev.minicam.mobile.data.registerMobilePush
import dev.minicam.mobile.data.unregisterMobilePush
import java.security.SecureRandom
import java.util.Base64

/** This phone's FCM registration token; Firebase in the app, a fake in tests. */
interface PushTokens {
    suspend fun token(): String
    suspend fun delete()
}

/** What the "Notifications on this phone" switch shows (spec 012 AC8). */
enum class PushSwitch {
    /** This build has no Firebase values: no switch at all. */
    HIDDEN,

    /** The device is not paired with the dashboard, or push is not set up there. */
    UNAVAILABLE,
    OFF,
    ON,
}

/**
 * Opt-in per phone (docs/adr/0007): turning it on creates a random AES-256 key, gets the FCM
 * token and registers both with minicam; minicam encrypts every detection with that key.
 * The key lives in the Keystore-encrypted [CredentialStore] and is never logged.
 */
class PushRegistration(
    private val store: CredentialStore,
    private val tokens: PushTokens?,
    private val random: SecureRandom = SecureRandom(),
) {
    val enabled: Boolean get() = store.get(CredentialKey.PUSH_KEY) != null

    fun switchState(available: Boolean?): PushSwitch = when {
        tokens == null -> PushSwitch.HIDDEN
        available != true -> PushSwitch.UNAVAILABLE
        enabled -> PushSwitch.ON
        else -> PushSwitch.OFF
    }

    /** Registers with minicam first: if that fails nothing is kept and the switch stays off. */
    suspend fun enable(api: MinicamApi) {
        val tokens = tokens ?: return
        val key = store.get(CredentialKey.PUSH_KEY) ?: newKey()
        val token = tokens.token()
        api.registerMobilePush(token, key)
        store.set(CredentialKey.PUSH_KEY, key)
        store.set(CredentialKey.PUSH_TOKEN, token)
    }

    /** Unregisters while the session still works, then forgets the key and the FCM token. */
    suspend fun disable(api: MinicamApi?) {
        val token = store.get(CredentialKey.PUSH_TOKEN)
        if (token != null) runCatching { api?.unregisterMobilePush(token) }
        forget()
    }

    /**
     * minicam already dropped the tokens of the session that logged out (minicam ADR 0011).
     * The key is gone before this first suspends, so no later push can be read.
     */
    suspend fun onLogout() = forget()

    /** A refreshed FCM token, or a new session after signing in again: register again. */
    suspend fun refresh(api: MinicamApi, token: String? = null) {
        val key = store.get(CredentialKey.PUSH_KEY) ?: return
        val current = token ?: tokens?.token() ?: return
        api.registerMobilePush(current, key)
        store.set(CredentialKey.PUSH_TOKEN, current)
    }

    private suspend fun forget() {
        store.set(CredentialKey.PUSH_KEY, null)
        store.set(CredentialKey.PUSH_TOKEN, null)
        runCatching { tokens?.delete() }
    }

    private fun newKey(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(KEY_BYTES).also(random::nextBytes))

    private companion object {
        const val KEY_BYTES = 32
    }
}
