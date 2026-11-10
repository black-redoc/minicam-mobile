package dev.minicam.mobile.push

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import dev.minicam.mobile.BuildConfig
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Firebase started by hand from build-time values (docs/adr/0007): no `google-services` plugin,
 * no `FirebaseInitProvider`, auto-init off, so no token exists until the user opts in.
 */
object FirebasePush {
    private val values = listOf(
        BuildConfig.FIREBASE_PROJECT_ID,
        BuildConfig.FIREBASE_APP_ID,
        BuildConfig.FIREBASE_API_KEY,
        BuildConfig.FIREBASE_SENDER_ID,
    )

    /** False in builds without the `MINICAM_FIREBASE_*` values: push is compiled out of the UI. */
    val configured: Boolean get() = values.all { it.isNotBlank() }

    /** The FCM token source, or null when this build has no push. */
    fun tokens(context: Context): PushTokens? {
        if (!configured) return null
        initialize(context)
        return FcmTokens
    }

    @Synchronized
    fun initialize(context: Context) {
        if (!configured || FirebaseApp.getApps(context).isNotEmpty()) return
        val options = FirebaseOptions.Builder()
            .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
            .setApplicationId(BuildConfig.FIREBASE_APP_ID)
            .setApiKey(BuildConfig.FIREBASE_API_KEY)
            .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
            .build()
        FirebaseApp.initializeApp(context.applicationContext, options)
    }
}

private object FcmTokens : PushTokens {
    override suspend fun token(): String = FirebaseMessaging.getInstance().token.await()

    override suspend fun delete() {
        FirebaseMessaging.getInstance().deleteToken().await()
    }
}

/** A Play services [Task] as a suspend call, without the coroutines-play-services artifact. */
private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        if (task.isSuccessful) {
            cont.resume(task.result)
        } else {
            cont.resumeWithException(task.exception ?: IllegalStateException("FCM task failed"))
        }
    }
}
