package dev.minicam.mobile.data

import java.io.ByteArrayOutputStream

/** Hand-built fMP4 boxes shaped like minicam's live stream (FFmpeg `empty_moov` + `default_base_moof`). */
object Fmp4Fixtures {
    fun u32(value: Long): ByteArray = ByteArray(4) { (value shr (8 * (3 - it))).toByte() }

    fun u64(value: Long): ByteArray = ByteArray(8) { (value shr (8 * (7 - it))).toByte() }

    fun box(type: String, vararg payload: ByteArray): ByteArray {
        val body = ByteArrayOutputStream().apply { payload.forEach { write(it) } }.toByteArray()
        return u32(8L + body.size) + type.toByteArray(Charsets.ISO_8859_1) + body
    }

    fun fullBox(type: String, version: Int, flags: Int, vararg payload: ByteArray): ByteArray =
        box(type, u32((version.toLong() shl 24) or flags.toLong()), *payload)

    fun trak(id: Int, handler: String, timescale: Long): ByteArray = box(
        "trak",
        fullBox("tkhd", 0, 3, u32(0), u32(0), u32(id.toLong()), ByteArray(68)),
        box(
            "mdia",
            fullBox("mdhd", 0, 0, u32(0), u32(0), u32(timescale), u32(0), ByteArray(4)),
            fullBox("hdlr", 0, 0, u32(0), handler.toByteArray(), ByteArray(12), "minicam\u0000".toByteArray()),
        ),
    )

    /** Video track 1 at 90 kHz and AAC track 2 at 16 kHz. */
    fun init(): ByteArray = box("ftyp", "isom".toByteArray(), u32(512)) +
        box("moov", fullBox("mvhd", 0, 0, ByteArray(96)), trak(1, "vide", 90_000), trak(2, "soun", 16_000), box("mvex"))

    class Traf(val trackId: Int, val decodeTime: Long, val sizes: List<Int>, val sync: Boolean = true, val tfdtVersion: Int = 1)

    /** moof + mdat; the samples' bytes count up from 0 so tests can check where each one is. */
    fun fragment(vararg trafs: Traf): ByteArray {
        fun moof(dataOffsets: List<Int>) = box(
            "moof",
            fullBox("mfhd", 0, 0, u32(1)),
            *trafs.mapIndexed { index, traf ->
                box(
                    "traf",
                    // default-base-is-moof | default duration | default size | default flags
                    fullBox("tfhd", 0, 0x020038, u32(traf.trackId.toLong()), u32(3_000), u32(0), u32(0x10000)),
                    if (traf.tfdtVersion == 1) fullBox("tfdt", 1, 0, u64(traf.decodeTime)) else fullBox("tfdt", 0, 0, u32(traf.decodeTime)),
                    // data-offset | first-sample-flags | per-sample size
                    fullBox(
                        "trun", 0, 0x000205, u32(traf.sizes.size.toLong()), u32(dataOffsets[index].toLong()),
                        u32(if (traf.sync) 0x2000000L else 0x10000L), *traf.sizes.map { u32(it.toLong()) }.toTypedArray(),
                    ),
                )
            }.toTypedArray(),
        )
        val placeholder = moof(trafs.map { 0 })
        var offset = placeholder.size + 8
        val offsets = trafs.map { traf -> offset.also { offset += traf.sizes.sum() } }
        val total = trafs.sumOf { it.sizes.sum() }
        return moof(offsets) + box("mdat", ByteArray(total) { it.toByte() })
    }
}
