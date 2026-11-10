package dev.minicam.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import dev.minicam.mobile.push.DetectionNotifications
import dev.minicam.mobile.ui.AppViewModel
import dev.minicam.mobile.ui.CameraScreen
import dev.minicam.mobile.ui.CamerasScreen
import dev.minicam.mobile.ui.LoginScreen
import dev.minicam.mobile.ui.RecordingsScreen
import dev.minicam.mobile.ui.Screen
import dev.minicam.mobile.ui.admin.AdminScreen
import dev.minicam.mobile.ui.admin.AiDetectionScreen
import dev.minicam.mobile.ui.admin.CameraSettingsScreen
import dev.minicam.mobile.ui.admin.CentralDashboardScreen
import dev.minicam.mobile.ui.theme.MinicamTheme

class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleCallback(intent)
        setContent {
            MinicamTheme {
                val camera = viewModel.selectedCamera
                when {
                    !viewModel.signedIn -> LoginScreen(viewModel)
                    camera != null -> {
                        BackHandler { viewModel.selectedCamera = null }
                        CameraScreen(viewModel, camera)
                    }
                    viewModel.screen == Screen.RECORDINGS -> RecordingsScreen(viewModel)
                    viewModel.screen == Screen.CAMERA_SETTINGS -> CameraSettingsScreen(viewModel)
                    viewModel.screen == Screen.AI_DETECTION -> AiDetectionScreen(viewModel)
                    viewModel.screen == Screen.ADMIN -> AdminScreen(viewModel)
                    viewModel.screen == Screen.CENTRAL_DASHBOARD -> CentralDashboardScreen(viewModel)
                    else -> CamerasScreen(viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleCallback(intent)
    }

    /**
     * `minicam://access-callback?state=…#token=…` from the Access handoff (docs/adr/0002), and
     * `minicam://pair?url=…&code=…` from the dashboard's QR (docs/adr/0011), and a tapped
     * detection notification.
     */
    private fun handleCallback(intent: Intent?) {
        // A tapped detection notification (docs/adr/0007).
        intent?.getStringExtra(DetectionNotifications.EXTRA_CAMERA)?.let { camera ->
            intent.removeExtra(DetectionNotifications.EXTRA_CAMERA)
            viewModel.openCameraFromNotification(camera)
            return
        }
        val data = intent?.data ?: return
        if (data.scheme != "minicam") return
        when (data.host) {
            "access-callback" -> viewModel.completeAccessLogin(data.toString())
            "pair" -> viewModel.openPairLink(data.toString())
        }
    }
}
