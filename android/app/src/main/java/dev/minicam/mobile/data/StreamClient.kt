package dev.minicam.mobile.data

import java.io.IOException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
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

    /** One complete JPEG frame, exactly as sent by minicam. */
    class Frame(val jpeg: ByteArray) : StreamEvent
}

/**
 * Same recovery behavior as the desktop `stream.js`: wait, then reconnect
 * with a freshly minted stream token.
 */
data class ReconnectPolicy(val delayMillis: Long = 3_000)

/**
 * Live stream of one camera. Collect `events()` from a coroutine tied to the
 * screen's STARTED state; cancelling it closes the socket.
 */
class StreamClient(
    private val api: MinicamApi,
    private val camera: String,
    private val policy: ReconnectPolicy = ReconnectPolicy(),
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

    private suspend fun session(): Flow<StreamEvent> {
        val token = api.streamToken(camera)
        return callbackFlow {
            var announced = false
            val socket = api.client.newWebSocket(
                api.streamRequest(camera, token),
                object : WebSocketListener() {
                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        if (!announced) {
                            announced = true
                            trySend(StreamEvent.Status(StreamStatus.CONNECTED))
                        }
                        trySend(StreamEvent.Frame(bytes.toByteArray()))
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
        }
    }
}
