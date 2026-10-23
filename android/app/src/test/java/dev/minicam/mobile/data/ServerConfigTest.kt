package dev.minicam.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerConfigTest {

    private fun reason(input: String) =
        assertThrows(ServerConfigException::class.java) { ServerConfig.parse(input) }.reason

    @Test
    fun serverConfig_acceptsHttpsAndLanHttp_rejectsPublicHttp() {
        assertEquals("https://cam.example.com", ServerConfig.parse("https://cam.example.com").origin())
        assertEquals("http://192.168.1.20:8080", ServerConfig.parse(" http://192.168.1.20:8080/ ").origin())
        listOf(
            "http://10.0.0.5", "http://172.16.0.1", "http://172.31.9.9",
            "http://127.0.0.1:8000", "http://localhost:8000", "http://raspberrypi.local",
        ).forEach { ServerConfig.parse(it) }

        assertEquals(ServerConfigException.Reason.INSECURE_REMOTE_HOST, reason("http://cam.example.com"))
        assertEquals(ServerConfigException.Reason.INSECURE_REMOTE_HOST, reason("http://172.32.0.1"))
        assertEquals(ServerConfigException.Reason.INSECURE_REMOTE_HOST, reason("http://8.8.8.8"))
        assertEquals(ServerConfigException.Reason.UNSUPPORTED_SCHEME, reason("ftp://192.168.1.2"))
        assertEquals(ServerConfigException.Reason.INVALID_URL, reason("not a url"))
    }

    @Test
    fun serverConfig_dropsPathAndLowercasesHost() {
        val config = ServerConfig.parse("HTTPS://Cam.Example.com/some/path?x=1")
        assertEquals("https://cam.example.com", config.origin())
        assertTrue(config.isWan)
    }

    @Test
    fun streamUrl_matchesSchemeAndEncodesName() {
        assertEquals(
            "wss://cam.example.com/ws/stream/front%20door?token=abc",
            ServerConfig.parse("https://cam.example.com").streamUrl("front door", "abc"),
        )
        assertEquals(
            "ws://192.168.1.20:8080/ws/stream/a%2Fb?token=t",
            ServerConfig.parse("http://192.168.1.20:8080").streamUrl("a/b", "t"),
        )
    }

    @Test
    fun streamURL_addsFormatAndAudio() {
        val config = ServerConfig.parse("https://cam.example.com")
        assertEquals("wss://cam.example.com/ws/stream/a?token=t&format=fmp4", config.streamUrl("a", "t", StreamFormat.FMP4))
        assertEquals(
            "wss://cam.example.com/ws/stream/a?token=t&format=fmp4&audio=true",
            config.streamUrl("a", "t", StreamFormat.FMP4, audio = true),
        )
        // The server never sends audio with MJPEG.
        assertEquals("wss://cam.example.com/ws/stream/a?token=t", config.streamUrl("a", "t", StreamFormat.MJPEG, audio = true))
    }
}
