package dev.minicam.mobile.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.minicam.mobile.data.MinicamApi
import dev.minicam.mobile.data.PtzAction
import dev.minicam.mobile.data.PtzDispatcher
import dev.minicam.mobile.ui.theme.CamColors
import dev.minicam.mobile.ui.theme.CamDimens

/**
 * Compass-style 3x3 PTZ grid (`.ptz-grid` in main.css). Hold to move, release to stop.
 * [glass] draws translucent buttons for the fullscreen overlay (spec 004).
 */
@Composable
fun PtzPad(api: MinicamApi, camera: String, onError: (Throwable) -> Unit, glass: Boolean = false) {
    val scope = rememberCoroutineScope()
    val dispatcher = remember(api, camera) { PtzDispatcher(scope, { api.ptz(camera, it) }, onError) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Spacer()
            PtzButton("▲", PtzAction.UP, dispatcher, glass)
            Spacer()
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PtzButton("◀", PtzAction.LEFT, dispatcher, glass)
            PtzButton("■", PtzAction.STOP, dispatcher, glass)
            PtzButton("▶", PtzAction.RIGHT, dispatcher, glass)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Spacer()
            PtzButton("▼", PtzAction.DOWN, dispatcher, glass)
            Spacer()
        }
    }
}

@Composable
private fun Spacer() = Box(Modifier.size(CamDimens.PtzSize))

@Composable
private fun PtzButton(glyph: String, action: PtzAction, dispatcher: PtzDispatcher, glass: Boolean) {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.93f else 1f, label = "ptzScale")
    val shape = RoundedCornerShape(CamDimens.Radius)
    val idle = if (action == PtzAction.STOP) CamColors.PtzStop else CamColors.Surface2
    val surface = when {
        pressed && glass -> Modifier.clip(shape).background(CamColors.Accent.copy(alpha = 0.75f)).border(1.dp, CamColors.AccentHover, shape)
        pressed -> Modifier.clip(shape).background(CamColors.Accent).border(1.dp, CamColors.Accent, shape)
        glass -> Modifier.glass(shape)
        else -> Modifier.clip(shape).background(idle).border(1.dp, CamColors.Border, shape)
    }

    Box(
        Modifier
            .size(CamDimens.PtzSize)
            .scale(scale)
            .then(surface)
            .semantics { contentDescription = "PTZ ${action.path}" }
            .pointerInput(action) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        dispatcher.dispatch(action)
                        tryAwaitRelease()
                        pressed = false
                        if (action != PtzAction.STOP) dispatcher.dispatch(PtzAction.STOP)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, color = if (pressed) Color.White else CamColors.Text, fontSize = if (action == PtzAction.STOP) 14.sp else 18.sp)
    }
}
