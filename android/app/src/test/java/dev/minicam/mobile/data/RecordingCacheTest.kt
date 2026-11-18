package dev.minicam.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** docs/specs/019 AC7: clips are cached by path, whatever address reached the server. */
class RecordingCacheTest {
    private val store = InMemoryCredentialStore()

    @Test
    fun cacheKey_isTheClipPath() {
        val lan = MinicamApi(ServerConfig.parse("http://192.168.1.20:8000"), store)
        val remote = MinicamApi(ServerConfig.parse("https://cam.example.com"), store)

        assertEquals("clip:/api/recordings/7/video", RecordingCache.cacheKey(lan.recordingVideoUrl(7).encodedPath))
        assertEquals(
            RecordingCache.cacheKey(lan.recordingVideoUrl(7).encodedPath),
            RecordingCache.cacheKey(remote.recordingVideoUrl(7).encodedPath),
        )
    }

    @Test
    fun maxSize_is512MegabytesLru() {
        assertEquals(512L * 1024 * 1024, RecordingCache.MAX_BYTES)
    }
}
