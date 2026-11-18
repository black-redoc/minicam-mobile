package dev.minicam.mobile.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.minicam.mobile.i18n.tr
import dev.minicam.mobile.push.PushSwitch
import dev.minicam.mobile.ui.theme.CamColors
import dev.minicam.mobile.ui.theme.CamDimens

@Composable
fun CamerasScreen(viewModel: AppViewModel) {
    LaunchedEffect(Unit) { viewModel.refresh() }
    val context = LocalContext.current
    // Android 13+ asks before the first notification; denied keeps the switch off (spec 012 AC9).
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.setPushEnabled(true)
        } else {
            viewModel.pushMessage = tr("Notifications are blocked. Allow them for minicam in Android Settings.")
        }
    }
    LaunchedEffect(viewModel.pushMessage) {
        viewModel.pushMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            viewModel.pushMessage = null
        }
    }
    val onPushToggle = { on: Boolean ->
        val needsPermission = on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) else viewModel.setPushEnabled(on)
    }

    Column(Modifier.fillMaxSize().background(CamColors.Bg).safeDrawingPadding()) {
        val update = viewModel.systemUpdate?.takeIf { it.updateAvailable }
        Header(
            isAdmin = viewModel.user?.isAdmin == true,
            updateAvailable = update != null,
            onRefresh = viewModel::refresh,
            onOpen = { viewModel.screen = it },
            onLogout = viewModel::logout,
            push = viewModel.pushSwitch,
            onPushToggle = onPushToggle,
        )
        NetworkSwitch(viewModel, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
        // Admins: a newer minicam is out (docs/adr/0017); the update itself is in Settings.
        update?.latest?.let { latest ->
            UpdateBanner(latest) { viewModel.screen = Screen.SETTINGS }
        }

        if (viewModel.cameras.isEmpty() && !viewModel.camerasLoaded && viewModel.errorMessage == null) {
            CameraListSkeleton()
        } else if (viewModel.cameras.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(
                    viewModel.errorMessage ?: tr("No cameras yet. Ask an admin to grant you one."),
                    color = CamColors.Muted,
                    fontSize = 14.sp,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(horizontal = 16.dp),
                contentPadding = PaddingValues(vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(viewModel.cameras, key = { it.name }) { camera ->
                    Box(Modifier.fillMaxWidth().clickable { viewModel.selectedCamera = camera }) {
                        Card(Modifier.fillMaxWidth()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                VideoCameraIcon()
                                Text(camera.name, color = CamColors.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                                ChevronRightIcon()
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Placeholder camera rows, same padding and spacing as the real list (spec 007 AC1). */
@Composable
private fun CameraListSkeleton() {
    Skeleton(Modifier.fillMaxSize().padding(16.dp)) {
        listOf(140.dp, 96.dp, 120.dp, 80.dp).forEach { width ->
            Card(Modifier.fillMaxWidth()) {
                SkeletonBlock(Modifier.width(width).height(18.dp))
            }
        }
    }
}

/**
 * The desktop top bar (`h-14`, slate-900, bottom border) with its "More options" menu:
 * Recordings for everyone (ADR 0005); admins also get the admin sections there (ADR 0004).
 * The server enforces it either way.
 */
@Composable
private fun Header(
    isAdmin: Boolean,
    updateAvailable: Boolean,
    onRefresh: () -> Unit,
    onOpen: (Screen) -> Unit,
    onLogout: () -> Unit,
    push: PushSwitch,
    onPushToggle: (Boolean) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandTitle()
        Box(Modifier.weight(1f))
        TextButton(onClick = onRefresh) { Text(tr("Refresh"), color = CamColors.Muted, fontSize = 13.sp) }
        SettingsButton(updateAvailable) { onOpen(Screen.SETTINGS) }
        Box {
            TextButton(onClick = { menuOpen = true }) { Text("⋮", color = CamColors.Muted, fontSize = 18.sp) }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                modifier = Modifier.background(CamColors.Surface),
            ) {
                DropdownMenuItem(
                    text = { Text(tr("Recordings"), color = CamColors.Text) },
                    onClick = {
                        menuOpen = false
                        onOpen(Screen.RECORDINGS)
                    },
                )
                if (isAdmin) {
                    listOf(
                        Screen.CAMERA_SETTINGS to tr("Camera settings"),
                        Screen.AI_DETECTION to tr("AI detection"),
                        Screen.ADMIN to tr("Admin"),
                        Screen.CENTRAL_DASHBOARD to tr("Connect to dashboard"),
                    ).forEach { (screen, label) ->
                        DropdownMenuItem(
                            text = { Text(label, color = CamColors.Text) },
                            onClick = {
                                menuOpen = false
                                onOpen(screen)
                            },
                        )
                    }
                }
                // Like the dashboard's "Notifications on this device" (docs/adr/0007).
                if (push != PushSwitch.HIDDEN) {
                    val available = push != PushSwitch.UNAVAILABLE
                    DropdownMenuItem(
                        enabled = available,
                        text = {
                            Column {
                                Text(tr("Notifications on this phone"), color = CamColors.Text)
                                if (!available) {
                                    Text(
                                        tr("Needs the device connected to the dashboard."),
                                        color = CamColors.Muted,
                                        fontSize = 11.sp,
                                    )
                                }
                            }
                        },
                        trailingIcon = {
                            Switch(
                                checked = push == PushSwitch.ON,
                                onCheckedChange = null,
                                enabled = available,
                                colors = SwitchDefaults.colors(checkedTrackColor = CamColors.Accent),
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onPushToggle(push != PushSwitch.ON)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(tr("Log out"), color = CamColors.Danger) },
                    onClick = {
                        menuOpen = false
                        onLogout()
                    },
                )
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(CamColors.Surface))
}

/** The gear that opens Settings, with a dot while an update is waiting (docs/adr/0017). */
@Composable
private fun SettingsButton(dot: Boolean, onClick: () -> Unit) {
    val label = if (dot) tr("Settings, update available") else tr("Settings")
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        GearIcon()
        if (dot) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 8.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(CamColors.Success),
            )
        }
    }
}

/** A cog drawn with the theme's muted stroke, like the other line icons. */
@Composable
private fun GearIcon() {
    Canvas(Modifier.size(20.dp)) {
        val stroke = 1.6.dp.toPx()
        val center = Offset(size.width / 2, size.height / 2)
        val outer = size.minDimension / 2
        for (i in 0 until 8) {
            val angle = Math.toRadians(i * 45.0)
            val dir = Offset(kotlin.math.cos(angle).toFloat(), kotlin.math.sin(angle).toFloat())
            drawLine(CamColors.Muted, center + dir * (outer * 0.62f), center + dir * outer, stroke * 1.6f, StrokeCap.Round)
        }
        drawCircle(CamColors.Muted, outer * 0.62f, center, style = Stroke(stroke))
        drawCircle(CamColors.Muted, outer * 0.26f, center, style = Stroke(stroke))
    }
}

/** "minicam vX is available": opens Settings, where the admin installs it. */
@Composable
private fun UpdateBanner(version: String, onOpen: () -> Unit) {
    Row(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(CamDimens.Radius))
            .background(CamColors.Success.copy(alpha = 0.12f))
            .border(1.dp, CamColors.Success.copy(alpha = 0.4f), RoundedCornerShape(CamDimens.Radius))
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(tr("minicam %s is available", version), color = CamColors.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(tr("Update"), color = CamColors.Success, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}
