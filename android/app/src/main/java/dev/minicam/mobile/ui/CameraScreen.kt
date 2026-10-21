package dev.minicam.mobile.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.minicam.mobile.data.CameraSummary
import dev.minicam.mobile.data.StreamClient
import dev.minicam.mobile.data.StreamEvent
import dev.minicam.mobile.data.StreamStatus
import dev.minicam.mobile.ui.theme.CamColors
import dev.minicam.mobile.ui.theme.CamDimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

@Composable
fun CameraScreen(viewModel: AppViewModel, camera: CameraSummary) {
    val api = viewModel.api ?: return
    val lifecycleOwner = LocalLifecycleOwner.current
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var status by remember { mutableStateOf(StreamStatus.CONNECTING) }

    // Streaming only while STARTED, like the desktop's visibilitychange
    // handling: a backgrounded app costs the Pi nothing. conflate() keeps
    // only the newest frame if decoding falls behind, as the server does.
    LaunchedEffect(api, camera.name) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                StreamClient(api, camera.name).events().conflate().collect { event ->
                    when (event) {
                        is StreamEvent.Status -> status = event.status
                        is StreamEvent.Frame -> {
                            status = StreamStatus.CONNECTED
                            withContext(Dispatchers.Default) {
                                BitmapFactory.decodeByteArray(event.jpeg, 0, event.jpeg.size)
                            }?.let { frame = it.asImageBitmap() }
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                status = StreamStatus.DISCONNECTED
                viewModel.handle(e)
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(CamColors.Bg)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { viewModel.selectedCamera = null }) { Text("‹ Cameras", color = CamColors.Muted) }
            Text(
                camera.name,
                color = CamColors.Text,
                fontWeight = FontWeight.SemiBold,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f),
            )
            StatusBadge(status)
            Box(Modifier.padding(end = 8.dp))
        }

        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // `.video-container`: 16:9, black, object-fit: contain.
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(CamDimens.RadiusLg))
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                frame?.let {
                    Image(it, contentDescription = "Live camera feed", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
                if (status != StreamStatus.CONNECTED) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = CamColors.Accent, trackColor = CamColors.Border)
                    }
                }
            }

            if (camera.canUsePtz) {
                Card(Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        PtzPad(api, camera.name, onError = viewModel::handle)
                    }
                }
            }
        }
    }
}
