package dev.minicam.mobile.push

import dev.minicam.mobile.data.CredentialKey
import dev.minicam.mobile.data.InMemoryCredentialStore
import dev.minicam.mobile.data.MinicamApi
import dev.minicam.mobile.data.ServerConfig
import java.util.Base64
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** spec 012 AC7 and AC8: opt-in, opt-out, logout and token refresh (docs/adr/0007). */
class PushRegistrationTest {
    private lateinit var server: MockWebServer
    private val store = InMemoryCredentialStore()

    private class FakeTokens(var current: String = "fcm-1") : PushTokens {
        var deleted = 0

        override suspend fun token() = current

        override suspend fun delete() {
            deleted++
        }
    }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start() }
    }

    @After
    fun tearDown() = server.close()

    private fun api() = MinicamApi(ServerConfig.parse(server.url("/").toString()), store)

    private fun answer(code: Int, body: String = "") =
        server.enqueue(MockResponse.Builder().code(code).body(body).build())

    private fun body(): Map<String, String> =
        Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject.mapValues { it.value.jsonPrimitive.content }

    @Test
    fun pushRegistration_registersAndUnregisters() = runTest {
        val tokens = FakeTokens()
        val push = PushRegistration(store, tokens)

        answer(201, """{"status": "ok"}""")
        push.enable(api())
        val registered = body()
        assertEquals("android", registered["platform"])
        assertEquals("fcm-1", registered["token"])
        val key = registered.getValue("key")
        assertEquals(32, Base64.getUrlDecoder().decode(key).size)
        assertEquals(key, store.get(CredentialKey.PUSH_KEY))
        assertTrue(push.enabled)

        // A refreshed FCM token is registered again with the same key.
        answer(201)
        push.refresh(api(), "fcm-2")
        assertEquals(mapOf("platform" to "android", "token" to "fcm-2", "key" to key), body())
        assertEquals("fcm-2", store.get(CredentialKey.PUSH_TOKEN))

        // Opt-out: unregister while the session still works, then forget both.
        answer(204)
        push.disable(api())
        val request = server.takeRequest()
        assertEquals("DELETE" to "/api/notifications/mobile/tokens", request.method to request.target)
        assertFalse(push.enabled)
        assertNull(store.get(CredentialKey.PUSH_TOKEN))
        assertEquals(1, tokens.deleted)

        // Logout: minicam already dropped the session's tokens; only local cleanup.
        answer(201)
        push.enable(api())
        server.takeRequest()
        push.onLogout()
        assertFalse(push.enabled)
        assertEquals(2, tokens.deleted)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun pushRegistration_keepsNothingWhenTheServerRefuses() = runTest {
        val push = PushRegistration(store, FakeTokens())
        answer(502, """{"detail": "The central dashboard did not accept the phone"}""")
        val error = runCatching { push.enable(api()) }.exceptionOrNull()
        assertTrue(error != null)
        assertFalse(push.enabled)
        // Disabled: a refreshed token is not sent anywhere.
        push.refresh(api(), "fcm-2")
        assertEquals(1, server.requestCount)
    }

    @Test
    fun pushRegistration_unavailableStates() {
        assertEquals(PushSwitch.HIDDEN, PushRegistration(store, tokens = null).switchState(available = true))
        val push = PushRegistration(store, FakeTokens())
        assertEquals(PushSwitch.UNAVAILABLE, push.switchState(available = null))
        assertEquals(PushSwitch.UNAVAILABLE, push.switchState(available = false))
        assertEquals(PushSwitch.OFF, push.switchState(available = true))
        store.set(CredentialKey.PUSH_KEY, "key")
        assertEquals(PushSwitch.ON, push.switchState(available = true))
    }
}
