package dev.minicam.mobile.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.minicam.mobile.data.MinicamApi
import dev.minicam.mobile.data.PtzAction
import dev.minicam.mobile.data.PtzDispatcher
import dev.minicam.mobile.data.PtzJoystick
import dev.minicam.mobile.ui.theme.CamColors
import dev.minicam.mobile.ui.theme.CamDimens
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private val PadSize = CamDimens.PtzSize * 3 + 12.dp
private val KnobSize = 64.dp
private val KnobTravel = 30.dp
private val DeadZone = 22.dp

/**
 * Game-controller thumbstick for PTZ: a round base with the four arrows and a knob that follows
 * the finger. Pressing the knob sends `stop` at once; dragging it past the dead zone (measured
 * from where the thumb landed, so a press slightly off-centre is not a move) moves the camera on
 * the dominant axis. Pressing an arrow moves straight away. Letting go springs the knob back to
 * the centre and sends `stop` if it was moving.
 * [glass] draws a translucent base for the fullscreen overlay (spec 004).
 */
@Composable
fun PtzPad(api: MinicamApi, camera: String, onError: (Throwable) -> Unit, glass: Boolean = false) {
    val scope = rememberCoroutineScope()
    val dispatcher = remember(api, camera) { PtzDispatcher(scope, { api.ptz(camera, it) }, onError) }
    val knob = remember { Animatable(Offset.Zero, Offset.VectorConverter) }
    var active by remember { mutableStateOf<PtzAction?>(null) }
    var held by remember { mutableStateOf(false) }
    // Where the glow wedge points; kept after release so it fades out in place.
    var lastDirection by remember { mutableStateOf(PtzAction.UP) }
    val density = LocalDensity.current
    val travelPx = with(density) { KnobTravel.toPx() }
    val deadZonePx = with(density) { DeadZone.toPx() }
    val knobRadiusPx = with(density) { KnobSize.toPx() / 2 }

    Box(
        Modifier
            .size(PadSize)
            .semantics { contentDescription = "PTZ joystick" }
            .pointerInput(dispatcher) {
                val follow = spring<Offset>(stiffness = Spring.StiffnessHigh)
                val release = spring<Offset>(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow)
                val centre = Offset(size.width / 2f, size.height / 2f)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    held = true
                    // On the knob: a centre press (stop now), deflection from the landing point.
                    // On the ring: the arrow under the finger, deflection from the centre.
                    val fromCentre = down.position - centre
                    val onKnob = hypot(fromCentre.x, fromCentre.y) <= knobRadiusPx
                    val origin = if (onKnob) down.position else centre
                    if (onKnob) dispatcher.dispatch(PtzAction.STOP)
                    fun track(position: Offset) {
                        val raw = position - origin
                        val distance = hypot(raw.x, raw.y)
                        val clamped = if (distance > travelPx) raw * (travelPx / distance) else raw
                        scope.launch { knob.animateTo(clamped, follow) }
                        val next = PtzJoystick.direction(raw.x, raw.y, deadZonePx, active)
                        PtzJoystick.transition(active, next)?.let(dispatcher::dispatch)
                        active = next
                        if (next != null) lastDirection = next
                    }
                    track(down.position)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (change.positionChange() != Offset.Zero) track(change.position)
                        change.consume()
                    }
                    if (active != null) dispatcher.dispatch(PtzAction.STOP)
                    active = null
                    held = false
                    scope.launch { knob.animateTo(Offset.Zero, release) }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        JoystickBase(glass, active != null, angleOf(lastDirection))
        Arrow("▲", PtzAction.UP, active, Modifier.align(Alignment.TopCenter).offset(y = 8.dp))
        Arrow("▼", PtzAction.DOWN, active, Modifier.align(Alignment.BottomCenter).offset(y = (-8).dp))
        Arrow("◀", PtzAction.LEFT, active, Modifier.align(Alignment.CenterStart).offset(x = 10.dp))
        Arrow("▶", PtzAction.RIGHT, active, Modifier.align(Alignment.CenterEnd).offset(x = (-10).dp))
        Knob(
            offset = knob.value,
            travelPx = travelPx,
            held = held,
            active = active != null,
            glass = glass,
        )
    }
}

/** Round well with a recessed ring and a glow wedge towards the active direction. */
@Composable
private fun JoystickBase(glass: Boolean, active: Boolean, angle: Float) {
    val fill = if (glass) CamColors.Bg.copy(alpha = 0.35f) else CamColors.Surface2
    val edge = if (glass) Color.White.copy(alpha = 0.25f) else CamColors.Border
    val glow by animateFloatAsState(if (active) 1f else 0f, label = "ptzGlow")
    Canvas(Modifier.fillMaxSize().background(fill, CircleShape).border(1.dp, edge, CircleShape)) {
        val radius = size.minDimension / 2
        // Inner well the knob slides in.
        drawCircle(Color.Black.copy(alpha = if (glass) 0.18f else 0.22f), radius = radius * 0.62f)
        drawCircle(edge, radius = radius * 0.62f, style = Stroke(1.dp.toPx()))
        if (glow > 0f) {
            drawArc(
                color = CamColors.Accent.copy(alpha = 0.55f * glow),
                startAngle = angle - 35f,
                sweepAngle = 70f,
                useCenter = false,
                topLeft = Offset(3.dp.toPx(), 3.dp.toPx()),
                size = Size(size.width - 6.dp.toPx(), size.height - 6.dp.toPx()),
                style = Stroke(4.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

private fun angleOf(action: PtzAction): Float = when (action) {
    PtzAction.RIGHT -> 0f
    PtzAction.DOWN -> 90f
    PtzAction.LEFT -> 180f
    else -> 270f
}

@Composable
private fun Arrow(glyph: String, action: PtzAction, active: PtzAction?, modifier: Modifier) {
    val on = active == action
    val color by animateColorAsState(if (on) CamColors.AccentHover else CamColors.Muted, label = "ptzArrow")
    val scale by animateFloatAsState(if (on) 1.3f else 1f, spring(dampingRatio = 0.5f), label = "ptzArrowScale")
    Text(
        glyph,
        color = color,
        fontSize = 15.sp,
        modifier = modifier.scale(scale).semantics { contentDescription = "PTZ ${action.path}" },
    )
}

/** The thumbstick cap: domed, with grip rings, tilting towards where it is pushed. */
@Composable
private fun Knob(offset: Offset, travelPx: Float, held: Boolean, active: Boolean, glass: Boolean) {
    val press by animateFloatAsState(if (held) 0.94f else 1f, label = "ptzKnobPress")
    val ring by animateColorAsState(if (active) CamColors.Accent else CamColors.Border, label = "ptzKnobRing")
    val top = if (active) CamColors.AccentHover else Color(0xFF3A4A63)
    val bottom = if (active) CamColors.Accent else CamColors.PtzStop
    val alpha = if (glass) 0.92f else 1f
    Box(
        Modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .size(KnobSize)
            .graphicsLayer {
                scaleX = press
                scaleY = press
                // Lean the cap towards the push, like a real stick pivoting in its socket.
                rotationY = offset.x / travelPx * 14f
                rotationX = -offset.y / travelPx * 14f
                cameraDistance = 12f * density
            }
            .shadow(if (held) 4.dp else 8.dp, CircleShape)
            .background(Brush.verticalGradient(listOf(top.copy(alpha = alpha), bottom.copy(alpha = alpha))), CircleShape)
            .border(2.dp, ring, CircleShape),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            val grip = Color.White.copy(alpha = if (active) 0.35f else 0.14f)
            drawCircle(grip, radius = r * 0.62f, style = Stroke(1.5.dp.toPx()))
            drawCircle(grip, radius = r * 0.38f, style = Stroke(1.5.dp.toPx()))
            // Specular highlight near the top of the dome.
            drawCircle(
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.28f), Color.Transparent),
                    center = Offset(r, r * 0.6f),
                    radius = r * 0.7f,
                ),
                radius = r * 0.7f,
                center = Offset(r, r * 0.6f),
            )
        }
    }
}
