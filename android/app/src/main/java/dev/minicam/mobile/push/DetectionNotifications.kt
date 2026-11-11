package dev.minicam.mobile.push

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import dev.minicam.mobile.MainActivity
import dev.minicam.mobile.R

/** System notifications for detections; a tap opens that camera (spec 012 AC10). */
object DetectionNotifications {
    const val EXTRA_CAMERA = "dev.minicam.mobile.extra.CAMERA"
    // Android never changes a created channel's sound or vibration, so each change needs a new id.
    private const val CHANNEL_ID = "detections_v4"
    private val OLD_CHANNEL_IDS = listOf("detections", "detections_v2", "detections_v3")

    /** Off/on pairs in ms: a buzz per reload clack, then a long one for the shot. */
    internal val VIBRATION_PATTERN = longArrayOf(20, 100, 40, 100, 340, 500)

    fun show(context: Context, push: DetectionPush) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        OLD_CHANNEL_IDS.forEach(manager::deleteNotificationChannel)
        val sound = Uri.parse("android.resource://${context.packageName}/${R.raw.detection_shotgun}")
        val audio = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Detections", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Objects detected on your cameras"
                setSound(sound, audio)
                enableVibration(true)
                vibrationPattern = VIBRATION_PATTERN
            },
        )
        val open = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_CAMERA, push.camera)
        val id = push.camera.hashCode()
        val tap = PendingIntent.getActivity(
            context,
            id,
            open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Camera names come from the server: shown as plain text only (SECURITY.md).
        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("minicam")
            .setContentText(push.message)
            .setCategory(Notification.CATEGORY_EVENT)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .build()
        manager.notify(id, notification)
    }
}
