package dev.minicam.mobile.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.minicam.mobile.data.VideoZoom
import dev.minicam.mobile.ui.theme.CamColors

/**
 * Translucent "glass" surface for controls floating over the video: a tint of the app's dark
 * blue background so they stay readable on bright frames and match the theme, a light top-down sheen and a thin bright edge. Compose has no
 * backdrop blur without extra dependencies, so the look comes from translucency alone.
 */
fun Modifier.glass(shape: Shape): Modifier = this
    .clip(shape)
    .background(CamColors.Bg.copy(alpha = 0.55f))
    .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.03f))))
    .border(
        1.dp,
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.12f))),
        shape,
    )

/** Round glass button floating over the video. */
@Composable
fun GlassIconButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .size(size)
            .glass(CircleShape)
            .clickable(enabled = enabled, onClickLabel = label, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Glass "Zoom in" / "Zoom out" buttons for digital zoom over the video (spec 008). */
@Composable
fun ZoomButtons(zoom: VideoZoom, onZoom: (VideoZoom) -> Unit, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassIconButton("Zoom in", onClick = { onZoom(zoom.zoomIn()) }, size = size, enabled = zoom.canZoomIn) { ZoomIcon(plus = true) }
        GlassIconButton("Zoom out", onClick = { onZoom(zoom.zoomOut()) }, size = size, enabled = zoom.canZoomOut) { ZoomIcon(plus = false) }
    }
}

/**
 * Clips the content to its bounds and, while [zoom] is zoomed, turns drags into pans given as
 * fractions of the size (spec 008 AC2). [onPan] must read the current zoom when it runs.
 */
fun Modifier.zoomPan(zoom: VideoZoom, onPan: (dx: Float, dy: Float) -> Unit): Modifier = this
    .clipToBounds()
    .pointerInput(zoom.isZoomed) {
        if (!zoom.isZoomed) return@pointerInput
        detectDragGestures { change, drag ->
            change.consume()
            onPan(drag.x / size.width, drag.y / size.height)
        }
    }

/** Scales the content around its centre and moves it by [zoom]'s pan. */
fun Modifier.zoomed(zoom: VideoZoom): Modifier = graphicsLayer {
    scaleX = zoom.scale
    scaleY = zoom.scale
    translationX = zoom.panX * size.width
    translationY = zoom.panY * size.height
}

/** "+" / "−" for the zoom buttons, drawn so it does not depend on font glyph coverage. */
@Composable
fun ZoomIcon(plus: Boolean, modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier.size(14.dp)) {
        val stroke = 2.dp.toPx()
        val mid = size.height / 2
        drawLine(color, Offset(0f, mid), Offset(size.width, mid), stroke, StrokeCap.Round)
        if (plus) drawLine(color, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), stroke, StrokeCap.Round)
    }
}

/** Four corner brackets: pointing outwards to enter fullscreen, inwards to exit it. */
@Composable
fun FullscreenIcon(exit: Boolean, modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier.size(18.dp)) {
        val stroke = 2.dp.toPx()
        val arm = size.minDimension * 0.34f
        val corners = listOf(Offset(0f, 0f), Offset(size.width, 0f), Offset(0f, size.height), Offset(size.width, size.height))
        for (corner in corners) {
            // Unit vectors from the corner towards the centre of the icon.
            val dx = if (corner.x == 0f) 1f else -1f
            val dy = if (corner.y == 0f) 1f else -1f
            // Exiting: the bracket's elbow sits one arm inside the corner, arms pointing outwards.
            val elbow = if (exit) Offset(corner.x + dx * arm, corner.y + dy * arm) else corner
            val sign = if (exit) -1f else 1f
            drawLine(color, elbow, Offset(elbow.x + sign * dx * arm, elbow.y), stroke, StrokeCap.Round)
            drawLine(color, elbow, Offset(elbow.x, elbow.y + sign * dy * arm), stroke, StrokeCap.Round)
        }
    }
}

/** Game controller outline (d-pad + two buttons) for the "show PTZ controls" button. */
@Composable
fun GamepadIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier.size(width = 24.dp, height = 16.dp)) {
        val stroke = 1.8.dp.toPx()
        val inset = stroke / 2
        drawRoundRect(
            color,
            topLeft = Offset(inset, inset),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius(size.height / 2),
            style = Stroke(stroke),
        )
        // D-pad on the left.
        val pad = Offset(size.width * 0.30f, size.height / 2)
        val arm = size.height * 0.22f
        drawLine(color, Offset(pad.x - arm, pad.y), Offset(pad.x + arm, pad.y), stroke, StrokeCap.Round)
        drawLine(color, Offset(pad.x, pad.y - arm), Offset(pad.x, pad.y + arm), stroke, StrokeCap.Round)
        // Two action buttons on the right.
        val dot = 1.4.dp.toPx()
        drawCircle(color, dot, Offset(size.width * 0.66f, size.height * 0.58f))
        drawCircle(color, dot, Offset(size.width * 0.76f, size.height * 0.40f))
    }
}

/** Thin "×" for close buttons, drawn so it does not depend on font glyph coverage. */
@Composable
fun CloseIcon(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier.size(12.dp)) {
        val stroke = 2.dp.toPx()
        drawLine(color, Offset(0f, 0f), Offset(size.width, size.height), stroke, StrokeCap.Round)
        drawLine(color, Offset(size.width, 0f), Offset(0f, size.height), stroke, StrokeCap.Round)
    }
}

/** Speaker with sound waves ([on]) or a slash (muted), for the live audio button (spec 009). */
@Composable
fun SpeakerIcon(on: Boolean, modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier.size(20.dp)) {
        val stroke = 1.8.dp.toPx()
        val w = size.width
        val h = size.height
        val body = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.08f, h * 0.36f)
            lineTo(w * 0.28f, h * 0.36f)
            lineTo(w * 0.52f, h * 0.14f)
            lineTo(w * 0.52f, h * 0.86f)
            lineTo(w * 0.28f, h * 0.64f)
            lineTo(w * 0.08f, h * 0.64f)
            close()
        }
        drawPath(body, color, style = Stroke(stroke, join = androidx.compose.ui.graphics.StrokeJoin.Round))
        if (on) {
            listOf(0.18f, 0.32f).forEach { radius ->
                drawArc(
                    color,
                    startAngle = -45f,
                    sweepAngle = 90f,
                    useCenter = false,
                    topLeft = Offset(w * (0.52f - radius), h * (0.5f - radius)),
                    size = Size(w * radius * 2, h * radius * 2),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        } else {
            drawLine(color, Offset(w * 0.66f, h * 0.36f), Offset(w * 0.94f, h * 0.64f), stroke, StrokeCap.Round)
            drawLine(color, Offset(w * 0.94f, h * 0.36f), Offset(w * 0.66f, h * 0.64f), stroke, StrokeCap.Round)
        }
    }
}
