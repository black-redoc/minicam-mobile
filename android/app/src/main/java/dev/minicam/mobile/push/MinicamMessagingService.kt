package dev.minicam.mobile.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.minicam.mobile.data.CredentialKey
import dev.minicam.mobile.data.KeystoreCredentialStore
import dev.minicam.mobile.data.MinicamApi
import dev.minicam.mobile.data.NotificationScheduleStore
import dev.minicam.mobile.data.ServerAddresses
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Receives minicam's encrypted detections from FCM and shows them (docs/adr/0007). */
class MinicamMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(message: RemoteMessage) {
        val store = KeystoreCredentialStore(this)
        // No key: notifications were turned off on this phone; drop anything still in flight.
        val key = store.get(CredentialKey.PUSH_KEY) ?: return
        val push = PushPayload.decrypt(key, message.data) ?: return
        // Outside this phone's schedule (docs/adr/0015): drop it quietly.
        if (!NotificationScheduleStore(store).allows(push.detectedAt)) return
        DetectionNotifications.show(this, push)
    }

    override fun onNewToken(token: String) {
        val store = KeystoreCredentialStore(this)
        val config = ServerAddresses(store).active ?: return
        val registration = PushRegistration(store, FirebasePush.tokens(this))
        if (!registration.enabled) return
        // Best-effort: the app registers again after the next sign-in anyway.
        scope.launch { runCatching { registration.refresh(MinicamApi(config, store), token) } }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
