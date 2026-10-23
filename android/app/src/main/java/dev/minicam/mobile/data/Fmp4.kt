package dev.minicam.mobile.data

/** Live fMP4 bytes the server sent don't match the format (ADR 0006): drop them. */
class Fmp4Exception(message: String) : Exception(message)

/**
 * Minimal ISO BMFF reader for minicam's live fMP4 (an init segment, then `moof` + `mdat`
 * fragments). Every size is checked against the data: bad input throws [Fmp4Exception], never
 * reads out of bounds.
 */
object Fmp4 {
    enum class Kind { VIDEO, AUDIO, OTHER }

    data class Track(val id: Int, val kind: Kind, val timescale: Long)

    data class Sample(val duration: Long, val size: Int, val isSync: Boolean, val compositionOffset: Long)

    /** One `traf`: [tfdtOffset] is where its decode time is stored in the fragment, for [rebase]. */
    data class TrackFragment(
        val trackId: Int,
        val baseDecodeTime: Long,
        val samples: List<Sample>,
        internal val tfdtOffset: Int,
        internal val tfdtVersion: Int,
    )

    fun parseInit(data: ByteArray): List<Track> {
        val moov = children(data, 0, data.size).firstOrNull { it.type == "moov" }
            ?: throw Fmp4Exception("No moov box")
        return children(data, moov.start, moov.end).filter { it.type == "trak" }.map { trak(data, it) }
    }

    fun parseFragment(data: ByteArray): List<TrackFragment> {
        val moof = children(data, 0, data.size).firstOrNull { it.type == "moof" }
            ?: throw Fmp4Exception("No moof box")
        return children(data, moof.start, moof.end).filter { it.type == "traf" }.map { traf(data, it) }
    }

    /**
     * A copy of [data] with each track's decode time moved back by its offset (in that track's
     * timescale), never below 0. Tracks without an offset are left as they are.
     */
    fun rebase(data: ByteArray, offsets: Map<Int, Long>): ByteArray {
        val out = data.copyOf()
        for (fragment in parseFragment(data)) {
            val offset = offsets[fragment.trackId] ?: continue
            val time = (fragment.baseDecodeTime - offset).coerceAtLeast(0)
            if (fragment.tfdtVersion == 1) {
                writeUInt(out, fragment.tfdtOffset, time, 8)
            } else {
                writeUInt(out, fragment.tfdtOffset, time.coerceAtMost(0xFFFF_FFFFL), 4)
            }
        }
        return out
    }

    // -- Boxes ------------------------------------------------------------

    private class Box(val type: String, val start: Int, val end: Int)

    /** The boxes in data[start, end), with each payload's bounds. */
    private fun children(data: ByteArray, start: Int, end: Int): List<Box> {
        val boxes = mutableListOf<Box>()
        var offset = start
        while (end - offset >= 8) {
            var size = readUInt(data, offset, 4, end)
            val type = String(data, offset + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L) {
                size = readUInt(data, offset + 8, 8, end)
                header = 16
            } else if (size == 0L) {
                size = (end - offset).toLong()
            }
            if (size < header || size > end - offset) throw Fmp4Exception("Bad $type box size")
            boxes += Box(type, offset + header, offset + size.toInt())
            offset += size.toInt()
        }
        if (offset != end) throw Fmp4Exception("Trailing bytes")
        return boxes
    }

    private fun child(data: ByteArray, parent: Box, type: String): Box? =
        children(data, parent.start, parent.end).firstOrNull { it.type == type }

    private fun path(data: ByteArray, parent: Box, vararg types: String): Box? =
        types.fold<String, Box?>(parent) { box, type -> box?.let { child(data, it, type) } }

    private fun trak(data: ByteArray, trak: Box): Track {
        val tkhd = child(data, trak, "tkhd") ?: throw Fmp4Exception("No tkhd")
        val trackId = readUInt(data, tkhd.start + if (data.byte(tkhd.start, tkhd.end) == 1) 20 else 12, 4, tkhd.end)
        val mdhd = path(data, trak, "mdia", "mdhd") ?: throw Fmp4Exception("No mdhd")
        val timescale = readUInt(data, mdhd.start + if (data.byte(mdhd.start, mdhd.end) == 1) 20 else 12, 4, mdhd.end)
        if (timescale == 0L) throw Fmp4Exception("Zero timescale")
        val hdlr = path(data, trak, "mdia", "hdlr") ?: throw Fmp4Exception("No hdlr")
        if (hdlr.end - hdlr.start < 12) throw Fmp4Exception("Short hdlr")
        val kind = when (String(data, hdlr.start + 8, 4, Charsets.ISO_8859_1)) {
            "vide" -> Kind.VIDEO
            "soun" -> Kind.AUDIO
            else -> Kind.OTHER
        }
        return Track(trackId.toInt(), kind, timescale)
    }

    private fun traf(data: ByteArray, traf: Box): TrackFragment {
        val tfhd = child(data, traf, "tfhd") ?: throw Fmp4Exception("No tfhd")
        val tfhdFlags = readUInt(data, tfhd.start, 4, tfhd.end).toInt() and 0xFFFFFF
        var offset = tfhd.start + 4
        val trackId = readUInt(data, offset, 4, tfhd.end).toInt()
        offset += 4
        if (tfhdFlags and 0x01 != 0) offset += 8 // base-data-offset
        if (tfhdFlags and 0x02 != 0) offset += 4 // sample-description-index
        val defaultDuration = if (tfhdFlags and 0x08 != 0) readUInt(data, offset, 4, tfhd.end).also { offset += 4 } else 0L
        val defaultSize = if (tfhdFlags and 0x10 != 0) readUInt(data, offset, 4, tfhd.end).also { offset += 4 } else 0L
        val defaultFlags = if (tfhdFlags and 0x20 != 0) readUInt(data, offset, 4, tfhd.end).toInt() else 0

        val tfdt = child(data, traf, "tfdt") ?: throw Fmp4Exception("No tfdt")
        val tfdtVersion = data.byte(tfdt.start, tfdt.end)
        val timeSize = if (tfdtVersion == 1) 8 else 4
        val baseDecodeTime = readUInt(data, tfdt.start + 4, timeSize, tfdt.end)

        val samples = children(data, traf.start, traf.end).filter { it.type == "trun" }.flatMap { trun ->
            trun(data, trun, Defaults(defaultDuration, defaultSize, defaultFlags))
        }
        return TrackFragment(trackId, baseDecodeTime, samples, tfdt.start + 4, tfdtVersion)
    }

    private class Defaults(val duration: Long, val size: Long, val flags: Int)

    private fun trun(data: ByteArray, trun: Box, defaults: Defaults): List<Sample> {
        val flags = readUInt(data, trun.start, 4, trun.end).toInt() and 0xFFFFFF
        val count = readUInt(data, trun.start + 4, 4, trun.end)
        var offset = trun.start + 8
        if (flags and 0x001 != 0) offset += 4 // data-offset
        val firstFlags = if (flags and 0x004 != 0) readUInt(data, offset, 4, trun.end).toInt().also { offset += 4 } else null
        if (count > MAX_SAMPLES) throw Fmp4Exception("Too many samples")
        val perSample = listOf(0x100, 0x200, 0x400, 0x800).count { flags and it != 0 } * 4
        if (count * perSample > trun.end - offset) throw Fmp4Exception("Short trun")
        return List(count.toInt()) { index ->
            val duration = if (flags and 0x100 != 0) readUInt(data, offset, 4, trun.end).also { offset += 4 } else defaults.duration
            val size = if (flags and 0x200 != 0) readUInt(data, offset, 4, trun.end).also { offset += 4 } else defaults.size
            val sampleFlags = if (flags and 0x400 != 0) {
                readUInt(data, offset, 4, trun.end).toInt().also { offset += 4 }
            } else if (index == 0 && firstFlags != null) {
                firstFlags
            } else {
                defaults.flags
            }
            // Version 1 offsets are signed; version 0 ones are too large to be meaningful here.
            val cto = if (flags and 0x800 != 0) readUInt(data, offset, 4, trun.end).toInt().toLong().also { offset += 4 } else 0L
            if (size > Int.MAX_VALUE) throw Fmp4Exception("Sample too large")
            Sample(duration, size.toInt(), sampleFlags and NON_SYNC == 0, cto)
        }
    }

    private const val NON_SYNC = 0x10000
    // One fragment holds one video frame plus the audio in between: far below this.
    private const val MAX_SAMPLES = 10_000L

    private fun ByteArray.byte(offset: Int, end: Int): Int {
        if (offset >= end) throw Fmp4Exception("Short box")
        return this[offset].toInt() and 0xFF
    }

    private fun readUInt(data: ByteArray, offset: Int, bytes: Int, end: Int): Long {
        if (offset < 0 || offset + bytes > end) throw Fmp4Exception("Short box")
        var value = 0L
        for (i in 0 until bytes) value = (value shl 8) or (data[offset + i].toLong() and 0xFF)
        if (value < 0) throw Fmp4Exception("Value too large")
        return value
    }

    private fun writeUInt(data: ByteArray, offset: Int, value: Long, bytes: Int) {
        for (i in 0 until bytes) data[offset + i] = (value shr (8 * (bytes - 1 - i))).toByte()
    }
}

/**
 * Moves a live stream's decode times so its first fragment starts at 0, the same amount of time
 * for every track: ExoPlayer starts its clock at 0 and would otherwise wait until it reaches the
 * camera's timestamps (ADR 0006).
 */
class Fmp4Rebaser(init: ByteArray) {
    private val timescales = Fmp4.parseInit(init).associate { it.id to it.timescale }
    private var originSeconds: Double? = null

    fun rebase(fragment: ByteArray): ByteArray {
        val trafs = Fmp4.parseFragment(fragment)
        val origin = originSeconds ?: trafs.mapNotNull { traf ->
            timescales[traf.trackId]?.let { traf.baseDecodeTime.toDouble() / it }
        }.minOrNull()?.also { originSeconds = it } ?: return fragment
        val offsets = trafs.mapNotNull { traf ->
            timescales[traf.trackId]?.let { traf.trackId to (origin * it).toLong() }
        }.toMap()
        return Fmp4.rebase(fragment, offsets)
    }
}
