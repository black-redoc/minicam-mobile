package dev.minicam.mobile.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Sends PTZ commands one at a time, in order. An action dispatched while
 * nothing is in flight is sent right away; actions dispatched during a
 * request wait in a one-slot queue where the latest replaces the previous
 * one, so at most one request is on the wire, like desktop `ptz.js`.
 *
 * Unlike the desktop, a pending request is never cancelled: OkHttp drops a
 * cancelled call before writing it, so a quick tap lost its direction (KI-8).
 *
 * A direction is held until the next action: it is sent again every
 * [repeatEveryMs], because ONVIF `ContinuousMove` without a timeout stops after
 * the camera's default PTZ timeout. `stop` (or another direction) ends the
 * repeat; a repeat never goes out after the action that replaced it.
 */
class PtzDispatcher(
    private val scope: CoroutineScope,
    private val send: suspend (PtzAction) -> Unit,
    private val onError: (Throwable) -> Unit,
    private val repeatEveryMs: Long = REPEAT_EVERY_MS,
) {
    private val lock = Any()
    private var sending = false
    private var queued: PtzAction? = null
    private var held: PtzAction? = null
    private var repeater: Job? = null

    fun dispatch(action: PtzAction) {
        synchronized(lock) {
            repeater?.cancel()
            held = action.takeIf { it != PtzAction.STOP }
            repeater = held?.let { direction ->
                scope.launch {
                    while (true) {
                        delay(repeatEveryMs)
                        enqueue(direction) { held == direction }
                    }
                }
            }
        }
        enqueue(action) { true }
    }

    /** Sends [action] now or queues it; [stillWanted] is checked under the lock. */
    private fun enqueue(action: PtzAction, stillWanted: () -> Boolean) {
        synchronized(lock) {
            if (!stillWanted()) return
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

    companion object {
        /** Well under the shortest default PTZ timeouts seen on ONVIF cameras (about 1 s). */
        const val REPEAT_EVERY_MS = 500L
    }
}
