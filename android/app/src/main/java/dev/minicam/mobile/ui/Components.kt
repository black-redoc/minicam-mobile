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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
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

/** Mirrors the desktop inputs (`bg-slate-800 border-slate-700 focus:ring-sky-500`). */
@Composable
fun CamTextField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
    placeholder: String? = null,
    monospace: Boolean = false,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = CamColors.Muted, fontSize = 12.sp)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(CamDimens.Radius),
            textStyle = androidx.compose.ui.text.TextStyle(
                fontSize = 14.sp,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default,
            ),
            placeholder = placeholder?.let { { Text(it, color = CamColors.Muted.copy(alpha = 0.6f), fontSize = 14.sp) } },
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = CamColors.Bg,
                unfocusedContainerColor = CamColors.Bg,
                focusedBorderColor = CamColors.Accent,
                unfocusedBorderColor = CamColors.Border,
                focusedTextColor = CamColors.Text,
                unfocusedTextColor = CamColors.Text,
                cursorColor = CamColors.Accent,
            ),
        )
    }
}

/** Sky primary button (`bg-sky-500 text-slate-950`). */
@Composable
fun PrimaryButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = modifier.fillMaxWidth().heightIn(min = 44.dp),
        shape = RoundedCornerShape(CamDimens.Radius),
        colors = ButtonDefaults.buttonColors(
            containerColor = CamColors.Accent,
            contentColor = CamColors.OnAccent,
            disabledContainerColor = CamColors.Accent.copy(alpha = 0.4f),
            disabledContentColor = CamColors.OnAccent,
        ),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), color = CamColors.OnAccent, strokeWidth = 2.dp)
        } else {
            Text(label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        }
    }
}

/** Bordered secondary button (`border-slate-600 text-slate-300`); [danger] uses the red variant. */
@Composable
fun SecondaryButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val color = if (danger) CamColors.Danger else CamColors.Text
    OutlinedButton(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = modifier.heightIn(min = 36.dp),
        shape = RoundedCornerShape(CamDimens.Radius),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (danger) CamColors.Danger.copy(alpha = 0.5f) else CamColors.Border,
        ),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = color, disabledContentColor = color.copy(alpha = 0.4f)),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(14.dp), color = color, strokeWidth = 2.dp)
        } else {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}
