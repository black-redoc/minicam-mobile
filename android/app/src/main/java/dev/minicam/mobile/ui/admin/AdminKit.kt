package dev.minicam.mobile.ui.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.minicam.mobile.ui.AppViewModel
import dev.minicam.mobile.ui.BrandTitle
import dev.minicam.mobile.ui.Card
import dev.minicam.mobile.ui.theme.CamColors
import dev.minicam.mobile.ui.theme.CamDimens
import kotlinx.coroutines.CancellationException

/**
 * Runs an admin request: returns its result, or null after reporting the failure through
 * [onError] (sign-out on a lost session is handled by [AppViewModel.describe]).
 */
suspend fun <T> AppViewModel.attempt(onError: (String) -> Unit, work: suspend () -> T): T? = try {
    work()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    onError(describe(e))
    null
}

/** Admin page shell, like the desktop `minicam / Admin` header with its "← Dashboard" link. */
@Composable
fun AdminScaffold(
    title: String,
    onBack: () -> Unit,
    scrollState: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(CamColors.Bg).safeDrawingPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("←", color = CamColors.Muted, fontSize = 20.sp) }
            BrandTitle()
            Text("  /  ", color = CamColors.Border, fontSize = 14.sp)
            Text(title, color = CamColors.Text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(CamColors.Surface))
        Column(
            Modifier.fillMaxSize().verticalScroll(scrollState).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

/** A `.card` with the desktop's small uppercase section heading. */
@Composable
fun Section(title: String, trailing: String? = null, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle(title, Modifier.weight(1f))
            trailing?.let { Text(it, color = CamColors.Muted, fontSize = 12.sp) }
        }
        content()
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        modifier = modifier,
        color = CamColors.Muted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.5.sp,
    )
}

@Composable
fun Hint(text: String) = Text(text, color = CamColors.Muted, fontSize = 12.sp)

/** Error (red) or success (green) line under a form, like the desktop's `role=alert` paragraphs. */
@Composable
fun FormMessage(error: String?, success: String? = null) {
    error?.let { Text(it, color = CamColors.Danger, fontSize = 12.sp) }
    if (error == null) success?.let { Text(it, color = CamColors.Success, fontSize = 12.sp) }
}

@Composable
fun Spinner(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.size(16.dp), color = CamColors.AccentHover, strokeWidth = 2.dp)
        Text(label, color = CamColors.Muted, fontSize = 13.sp)
    }
}

/** `.badge`: small rounded label (admin, active). */
@Composable
fun Badge(text: String, color: androidx.compose.ui.graphics.Color = CamColors.AccentHover) {
    Text(
        text,
        color = color,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.1f))
            .border(1.dp, color.copy(alpha = 0.3f), CircleShape)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
fun LabeledSwitch(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Switch) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = CamColors.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = CamColors.Accent,
                checkedThumbColor = CamColors.Text,
                uncheckedTrackColor = CamColors.Surface2,
                uncheckedBorderColor = CamColors.Border,
                uncheckedThumbColor = CamColors.Muted,
            ),
        )
    }
}

@Composable
fun CamCheckbox(checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Checkbox(
        checked = checked,
        onCheckedChange = onChange,
        enabled = enabled,
        colors = CheckboxDefaults.colors(
            checkedColor = CamColors.Accent,
            uncheckedColor = CamColors.Border,
            checkmarkColor = CamColors.OnAccent,
        ),
    )
}

@Composable
fun LabeledCheckbox(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Checkbox) { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CamCheckbox(checked, onChange = onChange)
        Text(label, color = CamColors.Text, fontSize = 14.sp)
    }
}

/**
 * A `<select>` look-alike: a bordered field that opens a dropdown of [options]. Options in
 * [unavailable] stay listed, struck through with their reason, but can't be picked.
 */
@Composable
fun <T> OptionPicker(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    unavailable: Map<T, String> = emptyMap(),
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = CamColors.Muted, fontSize = 12.sp)
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clip(RoundedCornerShape(CamDimens.Radius))
                    .background(CamColors.Bg)
                    .border(1.dp, CamColors.Border, RoundedCornerShape(CamDimens.Radius))
                    .clickable(role = Role.DropdownList) { open = true }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    options.firstOrNull { it.first == selected }?.second.orEmpty(),
                    color = CamColors.Text,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                Text("▾", color = CamColors.Muted, fontSize = 14.sp)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.background(CamColors.Surface)) {
                options.forEach { (value, text) ->
                    val reason = unavailable[value]
                    DropdownMenuItem(
                        text = {
                            if (reason == null) {
                                Text(text, color = CamColors.Text)
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(text, color = CamColors.Muted, textDecoration = TextDecoration.LineThrough)
                                    Text(reason, color = CamColors.Danger, fontSize = 12.sp)
                                }
                            }
                        },
                        enabled = reason == null,
                        onClick = {
                            open = false
                            onSelect(value)
                        },
                    )
                }
            }
        }
    }
}

/** The desktop `confirmDialog`: destructive actions always ask first (SECURITY.md). */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CamColors.Surface,
        titleContentColor = CamColors.Text,
        textContentColor = CamColors.Muted,
        title = { Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = { Text(message, fontSize = 14.sp) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text(confirmLabel, color = CamColors.Danger, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = CamColors.Muted) } },
    )
}
