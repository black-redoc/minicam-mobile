package dev.minicam.mobile.push

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** spec 012 AC6: minicam's AES-256-GCM payload (minicam ADR 0011). */
class PushPayloadTest {
    private val key = ByteArray(32) { it.toByte() }
    private val keyText = b64(key)
    private val event = """{"type": "detection", "camera": "entrance", "classes": ["person"],
        "detected_at": "2026-11-09T12:00:00+00:00", "title": "minicam", "body": "person detected on entrance"}"""

    private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /** What minicam's `encrypt_payload` sends. */
    private fun encrypt(plain: String, iv: ByteArray = ByteArray(12) { 7 }, with: ByteArray = key): Map<String, String> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(with, "AES"), GCMParameterSpec(128, iv))
        return mapOf("v" to "1", "iv" to b64(iv), "ct" to b64(cipher.doFinal(plain.toByteArray())))
    }

    @Test
    fun pushPayload_decryptsAndRejectsTampering() {
        val push = PushPayload.decrypt(keyText, encrypt(event))!!
        assertEquals("entrance", push.camera)
        assertEquals(listOf("person"), push.classes)
        assertEquals("person detected on entrance", push.message)

        val good = encrypt(event)
        val ct = Base64.getUrlDecoder().decode(good.getValue("ct"))
        val tampered = ct.copyOf().also { it[3] = (it[3].toInt() xor 1).toByte() }
        assertNull(PushPayload.decrypt(b64(ByteArray(32)), good))
        assertNull(PushPayload.decrypt(keyText, good + ("ct" to b64(tampered))))
        assertNull(PushPayload.decrypt(keyText, good + ("ct" to b64(ct.copyOf(ct.size - 4)))))
        assertNull(PushPayload.decrypt(keyText, good + ("v" to "2")))
        assertNull(PushPayload.decrypt(keyText, good + ("iv" to b64(ByteArray(8)))))
        assertNull(PushPayload.decrypt(keyText, good - "ct"))
        assertNull(PushPayload.decrypt(keyText, good + ("ct" to "not base64!")))
        assertNull(PushPayload.decrypt(keyText, encrypt("not json")))
        assertNull(PushPayload.decrypt(keyText, encrypt("""{"camera": ""}""")))
    }

    @Test
    fun pushPayload_buildsTheTextWhenTheServerSendsNone() {
        val push = PushPayload.decrypt(keyText, encrypt("""{"camera": "patio", "classes": ["dog", "cat"]}"""))!!
        assertEquals("dog, cat detected on patio", push.message)
    }
}
