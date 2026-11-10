package dev.minicam.mobile.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.minicam.mobile.push.PushSwitch
import dev.minicam.mobile.ui.theme.CamColors

@Composable
fun CamerasScreen(viewModel: AppViewModel) {
    LaunchedEffect(Unit) { viewModel.refresh() }
    val context = LocalContext.current
    // Android 13+ asks before the first notification; denied keeps the switch off (spec 012 AC9).
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.setPushEnabled(true)
        } else {
            viewModel.pushMessage = "Notifications are blocked. Allow them for minicam in Android Settings."
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
        Header(
            isAdmin = viewModel.user?.isAdmin == true,
            onRefresh = viewModel::refresh,
            onOpen = { viewModel.screen = it },
            onLogout = viewModel::logout,
            push = viewModel.pushSwitch,
            onPushToggle = onPushToggle,
        )
        NetworkSwitch(viewModel, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))

        if (viewModel.cameras.isEmpty() && !viewModel.camerasLoaded && viewModel.errorMessage == null) {
            CameraListSkeleton()
        } else if (viewModel.cameras.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(
                    viewModel.errorMessage ?: "No cameras yet. Ask an admin to grant you one.",
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(camera.name, color = CamColors.Text, fontSize = 15.sp, modifier = Modifier.weight(1f))
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
        TextButton(onClick = onRefresh) { Text("Refresh", color = CamColors.Muted, fontSize = 13.sp) }
        Box {
            TextButton(onClick = { menuOpen = true }) { Text("⋮", color = CamColors.Muted, fontSize = 18.sp) }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                modifier = Modifier.background(CamColors.Surface),
            ) {
                DropdownMenuItem(
                    text = { Text("Recordings", color = CamColors.Text) },
                    onClick = {
                        menuOpen = false
                        onOpen(Screen.RECORDINGS)
                    },
                )
                if (isAdmin) {
                    listOf(
                        Screen.CAMERA_SETTINGS to "Camera settings",
                        Screen.AI_DETECTION to "AI detection",
                        Screen.ADMIN to "Admin",
                        Screen.CENTRAL_DASHBOARD to "Connect to dashboard",
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
                                Text("Notifications on this phone", color = CamColors.Text)
                                if (!available) {
                                    Text(
                                        "Needs the device connected to the dashboard.",
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
                    text = { Text("Log out", color = CamColors.Danger) },
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
