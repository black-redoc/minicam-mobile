package dev.minicam.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import dev.minicam.mobile.ui.AppViewModel
import dev.minicam.mobile.ui.CameraScreen
import dev.minicam.mobile.ui.CamerasScreen
import dev.minicam.mobile.ui.LoginScreen
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
                    else -> CamerasScreen(viewModel)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleCallback(intent)
    }

    /** `minicam://access-callback?state=…#token=…` from the Access handoff (docs/adr/0002). */
    private fun handleCallback(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "minicam" && data.host == "access-callback") {
            viewModel.completeAccessLogin(data.toString())
        }
    }
}
