package dev.minicam.mobile.ui

import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.ui.PlayerView
import dev.minicam.mobile.R
import dev.minicam.mobile.data.CameraSummary
import dev.minicam.mobile.data.Detections
import dev.minicam.mobile.data.Fmp4Exception
import dev.minicam.mobile.data.StreamClient
import dev.minicam.mobile.data.StreamEvent
import dev.minicam.mobile.data.StreamFormat
import dev.minicam.mobile.data.StreamStatus
import dev.minicam.mobile.data.VideoZoom
import dev.minicam.mobile.data.ViewingBudget
import dev.minicam.mobile.ui.theme.CamColors
import dev.minicam.mobile.ui.theme.CamDimens
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext

@Composable
fun CameraScreen(viewModel: AppViewModel, camera: CameraSummary) {
    val api = viewModel.api ?: return
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var status by remember { mutableStateOf(StreamStatus.CONNECTING) }
    // Spec 009: audio is off every time the camera opens; fMP4 until it fails once (then MJPEG).
    var audioOn by remember { mutableStateOf(false) }
    var fmp4Failed by remember { mutableStateOf(false) }
    var session by remember { mutableStateOf<StreamEvent.Session?>(null) }
    var detections by remember { mutableStateOf<Detections?>(null) }
    var hasPicture by remember { mutableStateOf(false) }
    // The newest JPEG only: if decoding falls behind, older frames are skipped, as the server does.
    val jpegs = remember { MutableStateFlow<ByteArray?>(null) }
    val livePlayer = remember { LivePlayer(context) }
    // Spec 011: at most 30 s of streaming per open; then back to the camera list.
    val budget = remember { ViewingBudget() }
    DisposableEffect(livePlayer) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                fmp4Failed = true
            }

            override fun onRenderedFirstFrame() {
                hasPicture = true
            }
        }
        livePlayer.player.addListener(listener)
        onDispose {
            livePlayer.player.removeListener(listener)
            livePlayer.release()
        }
    }

    // Streaming only while STARTED, like the desktop's visibilitychange
    // handling: a backgrounded app costs the Pi nothing. Toggling audio or
    // falling back to MJPEG starts a new stream.
    LaunchedEffect(api, camera.name, audioOn, fmp4Failed) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val client = StreamClient(api, camera.name, preferFmp4 = !fmp4Failed, audio = audioOn, canPlay = ::canDecode)
            try {
                client.events().collect { event ->
                    when (event) {
                        is StreamEvent.Status -> status = event.status
                        is StreamEvent.Session -> {
                            session = event
                            if (event.choice.format == StreamFormat.MJPEG) livePlayer.stop()
                        }
                        is StreamEvent.Frame -> {
                            status = StreamStatus.CONNECTED
                            jpegs.value = event.jpeg
                        }
                        is StreamEvent.Init -> try {
                            livePlayer.start(event.data, audio = session?.choice?.audio == true)
                        } catch (_: Fmp4Exception) {
                            fmp4Failed = true
                        }
                        is StreamEvent.Fragment -> livePlayer.append(event.data)
                        is StreamEvent.Boxes -> detections = event.detections
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                status = StreamStatus.DISCONNECTED
                viewModel.handle(e)
            } finally {
                livePlayer.stop()
            }
        }
    }
    // The viewing time runs under the same lifecycle rule as the stream, across its restarts.
    // Leaving the screen closes the stream connection.
    LaunchedEffect(budget) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            budget.start()
            try {
                delay(budget.remainingMs)
                viewModel.selectedCamera = null
            } finally {
                budget.stop()
            }
        }
    }
    LaunchedEffect(jpegs) {
        jpegs.filterNotNull().collect { jpeg ->
            withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) }
                ?.let { frame = it.asImageBitmap() }
        }
    }
    LaunchedEffect(livePlayer) {
        while (true) {
            delay(CATCH_UP_CHECK_MS)
            livePlayer.catchUp()
        }
    }
    val live = if (session?.choice?.format == StreamFormat.FMP4) LiveVideo(livePlayer.player, hasPicture, detections) else null
    // Only offered when the server says this user may listen and the device can play it.
    val canHear = camera.canListen && session?.audioAvailable == true
    val speaker: @Composable (Modifier, Dp) -> Unit = { modifier, size ->
        if (canHear) {
            GlassIconButton(
                label = if (audioOn) "Turn audio off" else "Turn audio on",
                onClick = { audioOn = !audioOn },
                modifier = modifier,
                size = size,
            ) { SpeakerIcon(on = audioOn) }
        }
    }

    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var showPtz by rememberSaveable { mutableStateOf(false) }
    // Shared by both layouts, so entering or leaving fullscreen keeps the zoom (spec 008 AC3).
    var zoom by remember { mutableStateOf(VideoZoom()) }
    val onPan: (Float, Float) -> Unit = { dx, dy -> zoom = zoom.panBy(dx, dy) }
    val ptzPad: @Composable (Boolean) -> Unit = { glass -> PtzPad(api, camera.name, onError = viewModel::handle, glass = glass) }

    if (fullscreen) {
        BackHandler { fullscreen = false }
        ImmersiveLandscape()
        FullscreenCamera(
            frame = frame,
            live = live,
            status = status,
            zoom = zoom,
            onZoom = { zoom = it },
            onPan = onPan,
            canUsePtz = camera.canUsePtz,
            showPtz = showPtz,
            onShowPtz = { showPtz = it },
            onExit = { fullscreen = false },
            ptzPad = { ptzPad(true) },
            speaker = { speaker(it, 44.dp) },
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
                    .background(Color.Black)
                    .zoomPan(zoom, onPan),
            ) {
                VideoFeed(frame, live, status, zoom)
                ZoomButtons(zoom, onZoom = { zoom = it }, modifier = Modifier.align(Alignment.TopEnd).padding(10.dp))
                speaker(Modifier.align(Alignment.BottomStart).padding(10.dp), 40.dp)
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

private const val CATCH_UP_CHECK_MS = 500L

/** fMP4 playback for [VideoFeed] (spec 009): the player, whether it has shown a frame, boxes. */
private class LiveVideo(val player: Player, val hasPicture: Boolean, val detections: Detections?)

/**
 * Frame fitted to the box (`object-fit: contain`): a placeholder until the first frame, then the
 * dimmed last frame with a spinner while reconnecting (spec 007 AC4). With [live], the fMP4
 * player instead of the JPEG frame, with the detection boxes drawn over it.
 */
@Composable
private fun VideoFeed(frame: ImageBitmap?, live: LiveVideo?, status: StreamStatus, zoom: VideoZoom) {
    val hasPicture = live?.hasPicture ?: (frame != null)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (live != null) {
            Box(Modifier.fillMaxSize().zoomed(zoom)) {
                AndroidView(
                    factory = {
                        // AndroidView sets the layout params; the throwaway parent only resolves the XML's.
                        (LayoutInflater.from(it).inflate(R.layout.live_player_view, FrameLayout(it), false) as PlayerView).apply {
                            contentDescription = "Live camera feed"
                        }
                    },
                    update = { it.player = live.player },
                    // The other layout's view takes the player over; this one lets go of it.
                    onRelease = { it.player = null },
                    modifier = Modifier.fillMaxSize(),
                )
                live.detections?.let { DetectionOverlay(it) }
            }
        } else {
            frame?.let {
                Image(
                    it,
                    contentDescription = "Live camera feed",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().zoomed(zoom),
                )
            }
        }
        if (!hasPicture) {
            Skeleton(Modifier.fillMaxSize()) { SkeletonBlock(Modifier.fillMaxSize(), RectangleShape) }
        } else if (status != StreamStatus.CONNECTED) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = CamColors.Accent, trackColor = CamColors.Border)
            }
        }
    }
}

/**
 * Detection boxes of an fMP4 stream, which can't be drawn into the video (spec 009 AC7): the
 * class colour, the label and confidence on a tab above the box, like the server's MJPEG boxes.
 */
@Composable
private fun DetectionOverlay(detections: Detections) {
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxSize()) {
        val stroke = 2.dp.toPx()
        val padding = 3.dp.toPx()
        detections.boxes.forEach { box ->
            val rect = box.fit(detections.width, detections.height, size.width, size.height)
            val color = runCatching { Color(android.graphics.Color.parseColor(box.color)) }.getOrDefault(CamColors.Accent)
            drawRect(color, Offset(rect.left, rect.top), Size(rect.width, rect.height), style = Stroke(stroke))
            val text = measurer.measure(
                "${box.label} ${(box.confidence * 100).roundToInt()}%",
                TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
            )
            val tab = Size(text.size.width + padding * 2, text.size.height.toFloat())
            val tabTop = (rect.top - tab.height).coerceAtLeast(0f)
            drawRect(color, Offset(rect.left, tabTop), tab)
            drawText(text, topLeft = Offset(rect.left + padding, tabTop))
        }
    }
}

/** Spec 004: the feed over the whole screen with floating glass controls. */
@Composable
private fun FullscreenCamera(
    frame: ImageBitmap?,
    live: LiveVideo?,
    status: StreamStatus,
    zoom: VideoZoom,
    onZoom: (VideoZoom) -> Unit,
    onPan: (Float, Float) -> Unit,
    canUsePtz: Boolean,
    showPtz: Boolean,
    onShowPtz: (Boolean) -> Unit,
    onExit: () -> Unit,
    ptzPad: @Composable () -> Unit,
    speaker: @Composable (Modifier) -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black).zoomPan(zoom, onPan)) {
        VideoFeed(frame, live, status, zoom)

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

            ZoomButtons(zoom, onZoom = onZoom, modifier = Modifier.align(Alignment.BottomEnd), size = 44.dp)
            speaker(Modifier.align(Alignment.BottomStart))

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
