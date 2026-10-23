package dev.minicam.mobile.ui

import android.content.Context
import android.media.MediaCodecList
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import dev.minicam.mobile.data.Fmp4Exception
import dev.minicam.mobile.data.Fmp4Rebaser
import dev.minicam.mobile.data.LiveCatchUp
import java.io.InterruptedIOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** True if the device has a decoder for every sample MIME type (`video/hevc`, …). */
fun canDecode(sampleMimes: List<String>): Boolean {
    val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filterNot { it.isEncoder }
    return sampleMimes.all { mime -> decoders.any { info -> info.supportedTypes.any { it.equals(mime, ignoreCase = true) } } }
}

/**
 * Plays a camera's live fMP4 (ADR 0006): the init segment and every fragment are written, in
 * order, into a pipe that ExoPlayer reads as one endless fragmented MP4. Decode times are
 * rebased to start at 0, buffers are kept small and playback speeds up while behind live.
 */
@OptIn(UnstableApi::class)
class LivePlayer(context: Context) {
    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, START_BUFFER_MS, START_BUFFER_MS)
                .setPrioritizeTimeOverSizeThresholds(true)
                .build(),
        )
        .build()
    private var pipe: LivePipe? = null
    private var rebaser: Fmp4Rebaser? = null

    /** Starts playing a new stream from its init segment; throws [Fmp4Exception] if it's malformed. */
    fun start(init: ByteArray, audio: Boolean) {
        val newRebaser = Fmp4Rebaser(init)
        stop()
        rebaser = newRebaser
        val newPipe = LivePipe().also { it.write(init) }
        pipe = newPipe
        val extractors = ExtractorsFactory { arrayOf(FragmentedMp4Extractor()) }
        val source = ProgressiveMediaSource.Factory({ LivePipeDataSource(newPipe) }, extractors)
            .setContinueLoadingCheckIntervalBytes(CHECK_INTERVAL_BYTES)
            .createMediaSource(MediaItem.fromUri(LIVE_URI))
        // Only take audio focus when there is audio to play.
        player.setAudioAttributes(AudioAttributes.DEFAULT, audio)
        player.setMediaSource(source)
        player.setPlaybackSpeed(1f)
        player.prepare()
        player.play()
    }

    /** Appends one fragment; a malformed one is dropped (the next keyframe recovers). */
    fun append(fragment: ByteArray) {
        val pipe = pipe ?: return
        val rebased = try {
            rebaser?.rebase(fragment) ?: return
        } catch (_: Fmp4Exception) {
            return
        }
        pipe.write(rebased)
    }

    /** Call regularly while playing: speeds up while more than a second is buffered. */
    fun catchUp() {
        val speed = LiveCatchUp.speed(player.totalBufferedDuration, player.playbackParameters.speed)
        if (speed != player.playbackParameters.speed) player.setPlaybackSpeed(speed)
    }

    fun stop() {
        pipe?.close()
        pipe = null
        rebaser = null
        player.stop()
    }

    fun release() {
        pipe?.close()
        pipe = null
        player.release()
    }

    private companion object {
        const val MIN_BUFFER_MS = 500
        const val MAX_BUFFER_MS = 2_000
        const val START_BUFFER_MS = 100
        const val CHECK_INTERVAL_BYTES = 16 * 1024
        val LIVE_URI: Uri = Uri.parse("minicam-live://stream")
    }
}

/** Bytes written by the socket thread, read in order by ExoPlayer's loader thread. */
@OptIn(UnstableApi::class)
internal class LivePipe {
    private val chunks = LinkedBlockingQueue<ByteArray>()
    @Volatile private var closed = false
    private var current = ByteArray(0)
    private var position = 0

    fun write(bytes: ByteArray) {
        if (!closed) chunks.put(bytes)
    }

    fun close() {
        closed = true
    }

    /** Blocks until data arrives; `C.RESULT_END_OF_INPUT` once closed. */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (position == current.size) {
            if (closed) return C.RESULT_END_OF_INPUT
            val next = try {
                chunks.poll(POLL_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                throw InterruptedIOException()
            }
            if (next != null) {
                current = next
                position = 0
            }
        }
        val count = minOf(length, current.size - position)
        System.arraycopy(current, position, buffer, offset, count)
        position += count
        return count
    }

    private companion object {
        // How often a blocked read notices close().
        const val POLL_MS = 100L
    }
}

@OptIn(UnstableApi::class)
internal class LivePipeDataSource(private val pipe: LivePipe) : BaseDataSource(true) {
    private var uri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = pipe.read(buffer, offset, length)
        if (count > 0) bytesTransferred(count)
        return count
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (uri != null) {
            uri = null
            transferEnded()
        }
    }
}
