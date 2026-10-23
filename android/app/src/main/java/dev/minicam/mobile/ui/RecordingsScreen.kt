package dev.minicam.mobile.ui

import android.annotation.SuppressLint
import android.provider.DocumentsContract
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.children
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import dev.minicam.mobile.data.MinicamApi
import dev.minicam.mobile.R
import dev.minicam.mobile.data.MinicamException
import dev.minicam.mobile.data.Recording
import dev.minicam.mobile.data.RecordingPage
import dev.minicam.mobile.data.VideoZoom
import dev.minicam.mobile.data.deleteRecording
import dev.minicam.mobile.data.downloadRecording
import dev.minicam.mobile.data.formatClipDuration
import dev.minicam.mobile.data.formatClipSize
import dev.minicam.mobile.data.nextRecording
import dev.minicam.mobile.data.recordingDays
import dev.minicam.mobile.data.recordingFileName
import dev.minicam.mobile.data.recordingVideoUrl
import dev.minicam.mobile.data.recordings
import dev.minicam.mobile.ui.admin.AdminScaffold
import dev.minicam.mobile.ui.admin.ConfirmDialog
import dev.minicam.mobile.ui.admin.FormMessage
import dev.minicam.mobile.ui.admin.Hint
import dev.minicam.mobile.ui.admin.SectionTitle
import dev.minicam.mobile.ui.admin.attempt
import dev.minicam.mobile.ui.theme.CamColors
import dev.minicam.mobile.ui.theme.CamDimens
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.hypot
import kotlinx.coroutines.launch

/** The desktop Recordings page (minicam `recordings.html`): clips by day, player, download, delete. */
@Composable
fun RecordingsScreen(viewModel: AppViewModel) {
    val api = viewModel.api ?: return
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var camera by remember { mutableStateOf<String?>(null) }
    var items by remember { mutableStateOf<List<Recording>?>(null) }
    var nextBefore by remember { mutableStateOf<Int?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Recording?>(null) }
    var pendingDelete by remember { mutableStateOf<Recording?>(null) }
    var downloading by remember { mutableStateOf<Recording?>(null) }
    var fullscreen by rememberSaveable { mutableStateOf(false) }
    var shownRecording by remember { mutableStateOf<Recording?>(null) }
    var playError by remember { mutableStateOf<String?>(null) }
    // Shared by both layouts like the player; every clip starts unzoomed (spec 008 AC4).
    var zoom by remember { mutableStateOf(VideoZoom()) }
    // One player for both layouts, so entering or leaving fullscreen doesn't restart the clip.
    val player = rememberClipPlayer(viewModel, api) { playError = it }

    LaunchedEffect(player, selected?.id) {
        playError = null
        zoom = VideoZoom()
        val recording = selected
        if (recording == null) {
            fullscreen = false
            player.clearMediaItems()
        } else {
            player.setMediaItem(MediaItem.fromUri(api.recordingVideoUrl(recording.id).toString()))
            player.prepare()
            player.playWhenReady = true
        }
    }

    suspend fun load(reset: Boolean) {
        if (loading) return
        loading = true
        error = null
        viewModel.attempt({ error = it }) { api.recordings(camera, if (reset) null else nextBefore) }
            ?.let { page: RecordingPage ->
                items = if (reset) page.items else items.orEmpty() + page.items
                nextBefore = page.nextBefore
            }
        loading = false
    }
    LaunchedEffect(api, camera) { load(reset = true) }

    // Spec 008 AC5: "next" plays the clip below, loading the next page from the last one.
    val hasNext = selected?.let { nextRecording(items.orEmpty(), it) != null || nextBefore != null } == true
    val currentHasNext by rememberUpdatedState(hasNext)
    val controlsPlayer = remember(player) {
        ClipNavigationPlayer(player, hasNext = { currentHasNext }) {
            scope.launch {
                val current = selected ?: return@launch
                if (nextRecording(items.orEmpty(), current) == null && nextBefore != null) load(reset = false)
                nextRecording(items.orEmpty(), current)?.let {
                    selected = it
                    shownRecording = it
                }
            }
        }
    }
    val onPan: (Float, Float) -> Unit = { dx, dy -> zoom = zoom.panBy(dx, dy) }

    // The user picks where the MP4 goes (Storage Access Framework): no storage permission, and
    // the session cookie never leaves this app (SECURITY.md "Recordings").
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        val recording = downloading ?: return@rememberLauncherForActivityResult
        if (uri == null) {
            downloading = null
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            error = null
            notice = null
            val resolver = context.contentResolver
            val done = viewModel.attempt({ error = it }) {
                resolver.openOutputStream(uri)?.use { api.downloadRecording(recording.id, it) }
                    ?: throw MinicamException.InvalidResponse
            }
            if (done == null) runCatching { DocumentsContract.deleteDocument(resolver, uri) } else notice = "Saved."
            downloading = null
        }
    }

    if (fullscreen && selected != null) {
        BackHandler { fullscreen = false }
        ImmersiveLandscape()
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            ClipVideo(
                controlsPlayer,
                fullscreen = true,
                onFullscreen = { fullscreen = it },
                hasNext = hasNext,
                zoom = zoom,
                onZoom = { zoom = it },
                onPan = onPan,
                modifier = Modifier.fillMaxSize(),
            )
        }
        return
    }

    val isAdmin = viewModel.user?.isAdmin == true
    // Player, clip actions, camera filter and the list title stay fixed; only the clips scroll.
    val fixedTop: @Composable ColumnScope.() -> Unit = {
        AnimatedVisibility(
            visible = selected != null,
            enter = fadeIn(playerAnimation()) + expandVertically(playerAnimation(), expandFrom = Alignment.Top),
            exit = fadeOut(playerAnimation()) + shrinkVertically(playerAnimation(), shrinkTowards = Alignment.Top),
        ) {
            // Keeps showing the last clip while the player closes.
            val recording = selected ?: shownRecording ?: return@AnimatedVisibility
            Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ClipVideo(
                    controlsPlayer,
                    fullscreen = false,
                    onFullscreen = { fullscreen = it },
                    hasNext = hasNext,
                    zoom = zoom,
                    onZoom = { zoom = it },
                    onPan = onPan,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(CamDimens.RadiusLg))
                        .background(Color.Black),
                )
                FormMessage(playError)
                ClipDetails(recording)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The clip's actions slide in with the player, pushing the filter along (AC11).
            AnimatedVisibility(
                visible = selected != null,
                enter = fadeIn(playerAnimation()) + expandHorizontally(playerAnimation(), expandFrom = Alignment.Start),
                exit = fadeOut(playerAnimation()) + shrinkHorizontally(playerAnimation(), shrinkTowards = Alignment.Start),
            ) {
                val recording = selected ?: shownRecording ?: return@AnimatedVisibility
                Row(Modifier.padding(end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton("Download", busy = downloading != null) {
                        downloading = recording
                        saveAs.launch(recordingFileName(recording))
                    }
                    // Admins only; the server's `require_admin` enforces it either way.
                    if (isAdmin) SecondaryButton("Delete", danger = true) { pendingDelete = recording }
                }
            }
            CameraFilter(viewModel.cameras.map { it.name }, camera) { camera = it }
        }
        SectionTitle("Event recordings", Modifier.padding(top = 16.dp))
    }

    AdminScaffold("Recordings", onBack = { viewModel.screen = Screen.CAMERAS }, fixedTop = fixedTop) {
        FormMessage(error, notice)

        val list = items
        when {
            list == null -> if (error == null) ClipListSkeleton()
            list.isEmpty() -> Hint(
                if (camera != null) {
                    "No recordings for this camera yet."
                } else {
                    "No recordings yet. Clips appear here when a camera detects one of its classes."
                },
            )
            else -> recordingDays(list).forEach { day ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(day.label, color = CamColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Card(Modifier.fillMaxWidth()) {
                        day.recordings.forEachIndexed { index, recording ->
                            if (index > 0) HorizontalDivider(color = CamColors.Border)
                            ClipRow(recording, selected = recording.id == selected?.id) {
                                selected = recording
                                shownRecording = recording
                            }
                        }
                    }
                }
            }
        }
        if (nextBefore != null) {
            SecondaryButton("Load more", Modifier.align(Alignment.CenterHorizontally), busy = loading) {
                scope.launch { load(reset = false) }
            }
        }
    }

    pendingDelete?.let { recording ->
        ConfirmDialog(
            title = "Delete recording?",
            message = "The clip is deleted from this device for every user.",
            confirmLabel = "Delete",
            onConfirm = {
                scope.launch {
                    error = null
                    notice = null
                    if (viewModel.attempt({ error = it }) { api.deleteRecording(recording.id) } != null) {
                        items = items?.filter { it.id != recording.id }
                        if (selected?.id == recording.id) selected = null
                    }
                }
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/**
 * AC11: opening or closing the player. The fade and the slide of everything below it share this
 * curve, so they start and end together; slow enough to follow.
 */
private fun <T> playerAnimation() = tween<T>(durationMillis = 500, easing = FastOutSlowInEasing)

private val timeFormat: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)

private fun localTime(recording: Recording): String =
    recording.started.atZone(ZoneId.systemDefault()).format(timeFormat)

@Composable
private fun ClipRow(recording: Recording, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CamDimens.RadiusSm))
            .background(if (selected) CamColors.Accent.copy(alpha = 0.1f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            localTime(recording),
            color = if (selected) CamColors.AccentHover else CamColors.Text,
            fontSize = 14.sp,
            fontFamily = FontFamily.Monospace,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(recording.cameraName, color = CamColors.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (recording.classes.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    recording.classes.forEach { ClassChip(it) }
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(formatClipDuration(recording.durationSeconds), color = CamColors.Muted, fontSize = 12.sp)
            Text(formatClipSize(recording.sizeBytes), color = CamColors.Muted.copy(alpha = 0.7f), fontSize = 12.sp)
        }
    }
}

/** Placeholder day label and clip rows shaped like [ClipRow] (spec 007 AC2). */
@Composable
private fun ClipListSkeleton() {
    Skeleton {
        SkeletonBlock(Modifier.width(64.dp).height(12.dp))
        Card(Modifier.fillMaxWidth()) {
            listOf(110.dp, 80.dp, 96.dp, 72.dp).forEachIndexed { index, width ->
                if (index > 0) HorizontalDivider(color = CamColors.Border)
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SkeletonBlock(Modifier.width(44.dp).height(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SkeletonBlock(Modifier.width(width).height(14.dp))
                        SkeletonBlock(Modifier.width(48.dp).height(16.dp), RoundedCornerShape(4.dp))
                    }
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        SkeletonBlock(Modifier.width(32.dp).height(12.dp))
                        SkeletonBlock(Modifier.width(44.dp).height(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ClassChip(text: String) {
    Text(
        text,
        color = CamColors.Text.copy(alpha = 0.85f),
        fontSize = 11.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(CamColors.Border)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun ClipDetails(recording: Recording) {
    val day = recordingDays(listOf(recording)).first().label
    val details = listOfNotNull(
        "$day, ${localTime(recording)}",
        formatClipDuration(recording.durationSeconds),
        formatClipSize(recording.sizeBytes),
        recording.classes.takeIf { it.isNotEmpty() }?.joinToString(", "),
    )
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(recording.cameraName, color = CamColors.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(details.joinToString(" · "), color = CamColors.Muted, fontSize = 12.sp)
    }
}

/** Camera filter: a button like Download/Delete (no label) that opens the camera list. */
@Composable
private fun CameraFilter(cameras: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        SecondaryButton("${selected ?: "All cameras"}  ▾") { open = true }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(CamColors.Surface)) {
            (listOf<String?>(null) + cameras).forEach { camera ->
                DropdownMenuItem(
                    text = { Text(camera ?: "All cameras", color = if (camera == selected) CamColors.AccentHover else CamColors.Text) },
                    onClick = {
                        open = false
                        onSelect(camera)
                    },
                )
            }
        }
    }
}

/**
 * Media3 player for the clips. It reads through the app's OkHttp client with the same auth
 * headers as every other request (ADR 0005), seeking with HTTP Range, and pauses when the app
 * leaves the foreground. Playback failures go to [onError].
 */
@OptIn(UnstableApi::class)
@Composable
private fun rememberClipPlayer(viewModel: AppViewModel, api: MinicamApi, onError: (String) -> Unit): ExoPlayer {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val player = remember(api) {
        val dataSource = OkHttpDataSource.Factory(api.client).setDefaultRequestProperties(api.authHeaders())
        ExoPlayer.Builder(context).setMediaSourceFactory(DefaultMediaSourceFactory(dataSource)).build()
    }
    val reportError by rememberUpdatedState(onError)
    DisposableEffect(player, lifecycleOwner) {
        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val status = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode ?: 0
                reportError(
                    when (status) {
                        // Same mapping as the JSON API: a lost session signs out.
                        401 -> viewModel.describe(MinicamException.Unauthorized)
                        in 300..399 -> viewModel.describe(MinicamException.AccessRequired)
                        404 -> viewModel.describe(MinicamException.NotFound)
                        else -> "Can't play this clip."
                    },
                )
            }
        }
        // Never keep playing (and downloading) in the background.
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        player.addListener(listener)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.removeListener(listener)
            player.release()
        }
    }
    return player
}

/** The controls' time bar, found by type: Media3's view ids are private resources. */
@OptIn(UnstableApi::class)
private fun View.findTimeBar(): DefaultTimeBar? = when (this) {
    is DefaultTimeBar -> this
    is ViewGroup -> children.firstNotNullOfOrNull { it.findTimeBar() }
    else -> null
}

/** Played part in the brand sky, clearly apart from the buffered and remaining track. */
@OptIn(UnstableApi::class)
private fun DefaultTimeBar.styleProgress() {
    setPlayedColor(CamColors.AccentHover.toArgb())
    setScrubberColor(CamColors.AccentHover.toArgb())
    setBufferedColor(Color.White.copy(alpha = 0.4f).toArgb())
    setUnplayedColor(Color.White.copy(alpha = 0.15f).toArgb())
}

/**
 * Spec 008 AC5/AC6: the controls' "previous" button starts the clip over instead of going to
 * another clip, and "next" asks for the clip below in the list through [onNext].
 */
@OptIn(UnstableApi::class)
private class ClipNavigationPlayer(
    player: Player,
    private val hasNext: () -> Boolean,
    private val onNext: () -> Unit,
) : ForwardingPlayer(player) {
    override fun isCommandAvailable(command: Int): Boolean = when (command) {
        COMMAND_SEEK_TO_PREVIOUS -> super.isCommandAvailable(COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
        COMMAND_SEEK_TO_NEXT -> hasNext()
        else -> super.isCommandAvailable(command)
    }

    override fun getAvailableCommands(): Player.Commands = super.getAvailableCommands().buildUpon()
        .addIf(COMMAND_SEEK_TO_PREVIOUS, isCommandAvailable(COMMAND_SEEK_TO_PREVIOUS))
        .removeIf(COMMAND_SEEK_TO_PREVIOUS, !isCommandAvailable(COMMAND_SEEK_TO_PREVIOUS))
        .addIf(COMMAND_SEEK_TO_NEXT, hasNext())
        .removeIf(COMMAND_SEEK_TO_NEXT, !hasNext())
        .build()

    override fun seekToPrevious() = seekTo(0)

    override fun seekToNext() {
        if (hasNext()) onNext()
    }
}

/** Scales and moves the video surface by [zoom]; the content frame clips it to the picture. */
@OptIn(UnstableApi::class)
private fun PlayerView.applyZoom(zoom: VideoZoom) {
    val surface = videoSurfaceView ?: return
    surface.scaleX = zoom.scale
    surface.scaleY = zoom.scale
    surface.translationX = zoom.panX * surface.width
    surface.translationY = zoom.panY * surface.height
}

/**
 * While zoomed, a drag past the touch slop pans the video (spec 008 AC2) and is kept from the
 * player view; taps still reach it and show or hide the controls.
 */
@OptIn(UnstableApi::class)
@SuppressLint("ClickableViewAccessibility") // Clicks are left to PlayerView, which handles them.
private fun PlayerView.panWhenZoomed(zoom: () -> VideoZoom, onPan: (dx: Float, dy: Float) -> Unit) {
    val slop = ViewConfiguration.get(context).scaledTouchSlop
    var downX = 0f
    var downY = 0f
    var lastX = 0f
    var lastY = 0f
    var panning = false
    setOnTouchListener { _, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                panning = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!panning && zoom().isZoomed && hypot(event.x - downX, event.y - downY) > slop) {
                    panning = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                val surface = videoSurfaceView ?: this
                if (panning && surface.width > 0) onPan((event.x - lastX) / surface.width, (event.y - lastY) / surface.height)
            }
        }
        lastX = event.x
        lastY = event.y
        val consumed = panning
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) panning = false
        consumed
    }
}

/**
 * The player's video and controls; the controls' fullscreen button toggles [fullscreen] (AC9).
 * The zoom buttons show and hide with the controls (spec 008 AC4).
 */
@OptIn(UnstableApi::class)
@Composable
private fun ClipVideo(
    player: Player,
    fullscreen: Boolean,
    onFullscreen: (Boolean) -> Unit,
    hasNext: Boolean,
    zoom: VideoZoom,
    onZoom: (VideoZoom) -> Unit,
    onPan: (Float, Float) -> Unit,
    modifier: Modifier,
) {
    val toggle by rememberUpdatedState(onFullscreen)
    val currentZoom by rememberUpdatedState(zoom)
    val pan by rememberUpdatedState(onPan)
    var controlsVisible by remember { mutableStateOf(true) }
    Box(modifier) {
        AndroidView(
            factory = {
                // AndroidView sets the layout params; the throwaway parent only resolves the XML's.
                (LayoutInflater.from(it).inflate(R.layout.clip_player_view, FrameLayout(it), false) as PlayerView).apply {
                    this.player = player
                    setFullscreenButtonClickListener { enter -> toggle(enter) }
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility -> controlsVisible = visibility == View.VISIBLE })
                    findTimeBar()?.styleProgress()
                    videoSurfaceView?.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyZoom(currentZoom) }
                    panWhenZoomed({ currentZoom }) { dx, dy -> pan(dx, dy) }
                }
            },
            update = {
                it.setFullscreenButtonState(fullscreen)
                // The controls only re-read "next" on player events; nudge them when it changes.
                if (it.tag != hasNext) {
                    it.tag = hasNext
                    it.setShowNextButton(true)
                }
                it.applyZoom(zoom)
            },
            // The other layout's view takes the player over; this one lets go of it.
            onRelease = { it.player = null },
            modifier = Modifier.fillMaxSize(),
        )
        AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.TopEnd).padding(10.dp),
            enter = fadeIn(),
            exit = fadeOut(),
        ) { ZoomButtons(zoom, onZoom) }
    }
}
