package dev.minicam.mobile.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Sends PTZ commands one at a time, in order. An action dispatched while
 * nothing is in flight is sent right away; actions dispatched during a
 * request wait in a one-slot queue where the latest replaces the previous
 * one, so at most one request is on the wire, like desktop `ptz.js`.
 *
 * Unlike the desktop, a pending request is never cancelled: OkHttp drops a
 * cancelled call before writing it, so a quick tap lost its direction (KI-8).
 */
class PtzDispatcher(
    private val scope: CoroutineScope,
    private val send: suspend (PtzAction) -> Unit,
    private val onError: (Throwable) -> Unit,
) {
    private val lock = Any()
    private var sending = false
    private var queued: PtzAction? = null

    fun dispatch(action: PtzAction) {
        synchronized(lock) {
            if (sending) {
                queued = action
                return
            }
            sending = true
        }
        scope.launch { drain(action) }
    }

    private suspend fun drain(first: PtzAction) {
        var next: PtzAction? = first
        while (next != null) {
            try {
                send(next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e)
            }
            next = synchronized(lock) {
                queued.also {
                    queued = null
                    sending = it != null
                }
            }
        }
    }
}
