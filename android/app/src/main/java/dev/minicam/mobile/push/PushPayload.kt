package dev.minicam.mobile.push

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A detection, as minicam sends it to the dashboard tab and to this phone. */
@Serializable
data class DetectionPush(
    val camera: String,
    val classes: List<String> = emptyList(),
    @SerialName("detected_at") val detectedAt: String = "",
    val body: String = "",
) {
    /** "person detected on entrance", built here if the server sent no text. */
    val message: String get() = body.ifBlank { "${classes.joinToString(", ")} detected on $camera" }
}

/**
 * The FCM data message `{v: "1", iv, ct}` (base64url): minicam's event JSON under this
 * phone's AES-256-GCM key, `ct` = ciphertext + tag, no AAD (minicam ADR 0011). Google and the
 * dashboard relay only ever see ciphertext.
 */
object PushPayload {
    private const val VERSION = "1"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private val json = Json { ignoreUnknownKeys = true }

    /** The detection, or null for a wrong key, another version, or tampered or truncated data. */
    fun decrypt(key: String, data: Map<String, String>): DetectionPush? = runCatching {
        if (data["v"] != VERSION) return null
        val iv = decode(data.getValue("iv"))
        if (iv.size != IV_BYTES) return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(decode(key), "AES"), GCMParameterSpec(TAG_BITS, iv))
        val plain = cipher.doFinal(decode(data.getValue("ct")))
        json.decodeFromString(DetectionPush.serializer(), plain.decodeToString()).takeIf { it.camera.isNotBlank() }
    }.getOrNull()

    private fun decode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
}
