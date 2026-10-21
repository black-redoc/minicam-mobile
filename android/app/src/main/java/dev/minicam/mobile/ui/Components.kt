package dev.minicam.mobile.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.minicam.mobile.data.StreamStatus
import dev.minicam.mobile.ui.theme.CamColors
import dev.minicam.mobile.ui.theme.CamDimens

/** Brand mark from the desktop header: glowing sky dot + "minicam" (`.dot-glow`). */
@Composable
fun BrandTitle() {
    val glow by rememberInfiniteTransition(label = "glow").animateFloat(
        initialValue = 8f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1200), RepeatMode.Reverse),
        label = "glowRadius",
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier
                .size(8.dp)
                .shadow(glow.dp, CircleShape, ambientColor = CamColors.AccentHover, spotColor = CamColors.AccentHover)
                .background(CamColors.AccentHover, CircleShape),
        )
        Text("minicam", color = CamColors.AccentHover, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

/** `.card` from main.css. */
@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(CamDimens.RadiusLg))
            .background(CamColors.Surface)
            .border(CamDimens.CardBorder, RoundedCornerShape(CamDimens.RadiusLg))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) { content() }
}

/** `.badge--connected/disconnected/connecting` from main.css. */
@Composable
fun StatusBadge(status: StreamStatus) {
    val (color, label) = when (status) {
        StreamStatus.CONNECTED -> CamColors.Success to "Live"
        StreamStatus.DISCONNECTED -> CamColors.Danger to "Disconnected"
        StreamStatus.CONNECTING -> CamColors.Muted to "Connecting…"
    }
    Row(
        Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.25f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}
