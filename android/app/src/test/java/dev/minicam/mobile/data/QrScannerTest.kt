package dev.minicam.mobile.data

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** docs/adr/0012: the in-app QR scanner. */
class QrScannerTest {
    private val link = "minicam://pair?url=https%3A%2F%2Fadmin.example.com&code=ABCDEFGH"

    /** A grayscale frame (one byte per pixel, [padding] extra bytes per row) holding [text] as a QR. */
    private fun frame(text: String, size: Int = 240, padding: Int = 16): Triple<ByteArray, Int, Int> {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val stride = size + padding
        val bytes = ByteArray(stride * size) { i ->
            val x = i % stride
            val y = i / stride
            if (x < size && matrix[x, y]) 0 else 0xFF.toByte()
        }
        return Triple(bytes, stride, size)
    }

    @Test
    fun qrDecoder_readsAFrame() {
        val (bytes, stride, size) = frame(link)
        assertEquals(link, QrDecoder().decode(bytes, stride, size, size))

        val blank = ByteArray(stride * size) { 0xFF.toByte() }
        assertNull(QrDecoder().decode(blank, stride, size, size))
        assertNull("short buffer", QrDecoder().decode(ByteArray(10), stride, size, size))
    }

    @Test
    fun qrScan_acceptsOnlyPairLinks() {
        assertEquals(PairLink("https://admin.example.com", "ABCDEFGH"), PairLink.fromScan("  $link\n"))
        for (bad in listOf(null, "", "https://admin.example.com", "ABCD-EFGH", "minicam://access-callback?x=1", link + "x".repeat(600))) {
            assertNull(bad, PairLink.fromScan(bad))
        }
        assertEquals("That QR is not a minicam dashboard code.", PairLink.NOT_A_PAIR_QR)
    }

    @Test
    fun dependencies_haveNoGoogleTelemetry() {
        val locks = File("gradle.lockfile").readText()
        assertTrue(locks.contains("com.google.zxing:core:"))
        for (banned in listOf("com.google.mlkit", "com.google.android.datatransport", "com.google.firebase", "play-services")) {
            assertFalse(banned, locks.contains(banned))
        }
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("""<uses-feature android:name="android.hardware.camera.any" android:required="false" />"""))
    }
}
