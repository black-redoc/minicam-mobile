package dev.minicam.mobile.ui

import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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

    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var showPtz by rememberSaveable { mutableStateOf(false) }
    val ptzPad: @Composable (Boolean) -> Unit = { glass -> PtzPad(api, camera.name, onError = viewModel::handle, glass = glass) }

    if (fullscreen) {
        BackHandler { fullscreen = false }
        ImmersiveLandscape()
        FullscreenCamera(
            frame = frame,
            status = status,
            canUsePtz = camera.canUsePtz,
            showPtz = showPtz,
            onShowPtz = { showPtz = it },
            onExit = { fullscreen = false },
            ptzPad = { ptzPad(true) },
        )
        return
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
            ) {
                VideoFeed(frame, status)
                GlassIconButton(
                    label = "Enter fullscreen",
                    onClick = { fullscreen = true },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
                    size = 40.dp,
                ) { FullscreenIcon(exit = false) }
            }

            if (camera.canUsePtz) {
                Card(Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ptzPad(false) }
                }
            }
        }
    }
}

/** Frame fitted to the box (`object-fit: contain`) with a spinner while not live. */
@Composable
private fun VideoFeed(frame: ImageBitmap?, status: StreamStatus) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        frame?.let {
            Image(it, contentDescription = "Live camera feed", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        }
        if (status != StreamStatus.CONNECTED) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CamColors.Accent, trackColor = CamColors.Border)
            }
        }
    }
}

/** Spec 004: the feed over the whole screen with floating glass controls. */
@Composable
private fun FullscreenCamera(
    frame: ImageBitmap?,
    status: StreamStatus,
    canUsePtz: Boolean,
    showPtz: Boolean,
    onShowPtz: (Boolean) -> Unit,
    onExit: () -> Unit,
    ptzPad: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        VideoFeed(frame, status)

        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
            Row(
                Modifier.align(Alignment.TopStart),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GlassIconButton(label = "Exit fullscreen", onClick = onExit) { FullscreenIcon(exit = true) }
                StatusBadge(status)
            }

            if (canUsePtz && !showPtz) {
                GlassIconButton(
                    label = "Show PTZ controls",
                    onClick = { onShowPtz(true) },
                    modifier = Modifier.align(Alignment.TopEnd),
                ) { GamepadIcon() }
            }

            AnimatedVisibility(
                visible = canUsePtz && showPtz,
                modifier = Modifier.align(Alignment.CenterEnd),
                enter = fadeIn() + slideInHorizontally { it / 2 },
                exit = fadeOut() + slideOutHorizontally { it / 2 },
            ) {
                PtzCircle(onClose = { onShowPtz(false) }, ptzPad = ptzPad)
            }
        }
    }
}

/**
 * The PTZ pad inside a glass circle, with the (x) centred on the circle's edge at its top-right
 * (45°): half over the circle, half outside it, drawn on top.
 */
@Composable
private fun PtzCircle(onClose: () -> Unit, ptzPad: @Composable () -> Unit) {
    val diameter = CamDimens.PtzSize * 3 + 12.dp + 32.dp
    val closeSize = 30.dp
    // Distance from the bounding box's top-right corner to the 45° point on the circle, per axis.
    val inset = diameter / 2 * (1f - 0.7071f)
    Box(Modifier.size(diameter)) {
        Box(Modifier.fillMaxSize().glass(CircleShape), contentAlignment = Alignment.Center) { ptzPad() }
        GlassIconButton(
            label = "Hide PTZ controls",
            onClick = onClose,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = closeSize / 2 - inset, y = inset - closeSize / 2)
                .background(CamColors.Bg.copy(alpha = 0.6f), CircleShape),
            size = closeSize,
        ) { CloseIcon() }
    }
}

/**
 * Hides the system bars (swipe to peek) and turns the screen to landscape while composed;
 * restores both when fullscreen ends. The activity handles the rotation itself
 * (`configChanges` in the manifest), so the stream is not torn down.
 */
@Composable
internal fun ImmersiveLandscape() {
    val activity = LocalActivity.current ?: return
    DisposableEffect(activity) {
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}
