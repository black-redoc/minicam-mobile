package dev.minicam.mobile.data

import java.io.IOException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

enum class StreamStatus { CONNECTING, CONNECTED, DISCONNECTED }

sealed interface StreamEvent {
    data class Status(val status: StreamStatus) : StreamEvent

    /** A session started with [choice]; [audioAvailable]: the camera's audio can be turned on. */
    data class Session(val choice: StreamChoice, val audioAvailable: Boolean) : StreamEvent

    /** One complete JPEG frame, exactly as sent by minicam (MJPEG). */
    class Frame(val jpeg: ByteArray) : StreamEvent

    /** fMP4 init segment: start a new player (again whenever the camera reconnects). */
    class Init(val mime: String, val data: ByteArray) : StreamEvent

    /** One fMP4 fragment (`moof` + `mdat`), in order. */
    class Fragment(val data: ByteArray) : StreamEvent

    /** The camera's current detections, to draw over fMP4 video. */
    data class Boxes(val detections: Detections) : StreamEvent
}

/**
 * Same recovery behavior as the desktop `stream.js`: wait, then reconnect
 * with a freshly minted stream token.
 */
data class ReconnectPolicy(val delayMillis: Long = 3_000)

/**
 * Live stream of one camera. Collect `events()` from a coroutine tied to the
 * screen's STARTED state; cancelling it closes the socket.
 *
 * With [preferFmp4] each session uses fMP4 when [canPlay] accepts the camera's codecs (ADR 0006);
 * [audio] asks for the camera's audio too, and quietly goes without it if the server refuses.
 */
class StreamClient(
    private val api: MinicamApi,
    private val camera: String,
    private val policy: ReconnectPolicy = ReconnectPolicy(),
    private val preferFmp4: Boolean = false,
    private val audio: Boolean = false,
    private val canPlay: (List<String>) -> Boolean = { false },
) {
    fun events(): Flow<StreamEvent> = flow {
        while (currentCoroutineContext().isActive) {
            emit(StreamEvent.Status(StreamStatus.CONNECTING))
            try {
                emitAll(session())
            } catch (e: MinicamException.Unauthorized) {
                throw e
            } catch (e: MinicamException.AccessRequired) {
                throw e
            } catch (_: MinicamException) {
                // Camera gone, server restarting: retry below.
            } catch (_: IOException) {
                // Dropped socket or network change: retry below.
            }
            emit(StreamEvent.Status(StreamStatus.DISCONNECTED))
            delay(policy.delayMillis)
        }
    }

    private suspend fun token(): StreamToken = try {
        api.streamToken(camera, audio)
    } catch (e: MinicamException.Forbidden) {
        // can_listen revoked since the camera list was loaded: carry on without audio.
        if (!audio) throw e
        api.streamToken(camera)
    }

    private suspend fun session(): Flow<StreamEvent> {
        val token = token()
        val choice = chooseStream(token, preferFmp4, audio, canPlay)
        val audioAvailable = chooseStream(token, preferFmp4, audio = true, canPlay).audio
        val fmp4 = choice.format == StreamFormat.FMP4
        // Unlimited buffer: a dropped fragment would corrupt the video until the next keyframe.
        return callbackFlow {
            send(StreamEvent.Session(choice, audioAvailable))
            var announced = false
            var initMime: String? = null
            val socket = api.client.newWebSocket(
                api.streamRequest(camera, token.token, choice.format, choice.audio),
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        when (val message = LiveText.parse(text)) {
                            is LiveText.Init -> initMime = message.mime
                            is LiveText.Boxes -> trySend(StreamEvent.Boxes(message.detections))
                            null -> Unit
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        if (!announced) {
                            announced = true
                            trySend(StreamEvent.Status(StreamStatus.CONNECTED))
                        }
                        val mime = initMime
                        when {
                            !fmp4 -> trySend(StreamEvent.Frame(bytes.toByteArray()))
                            mime != null -> {
                                initMime = null
                                trySend(StreamEvent.Init(mime, bytes.toByteArray()))
                            }
                            else -> trySend(StreamEvent.Fragment(bytes.toByteArray()))
                        }
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        close()
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        close(if (t is IOException) t else IOException(t))
                    }
                },
            )
            awaitClose { socket.cancel() }
        }.buffer(Channel.UNLIMITED)
    }
}
