package dev.minicam.mobile.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.minicam.mobile.data.CameraConfig
import dev.minicam.mobile.data.DiscoveredCamera
import dev.minicam.mobile.data.MinicamApi
import dev.minicam.mobile.data.NotificationMode
import dev.minicam.mobile.data.StreamQuality
import dev.minicam.mobile.data.adminCameras
import dev.minicam.mobile.data.deleteCamera
import dev.minicam.mobile.data.detectionClasses
import dev.minicam.mobile.data.discoverCameras
import dev.minicam.mobile.data.saveCamera
import dev.minicam.mobile.ui.AppViewModel
import dev.minicam.mobile.ui.CamTextField
import dev.minicam.mobile.ui.Card
import dev.minicam.mobile.ui.PrimaryButton
import dev.minicam.mobile.ui.Screen
import dev.minicam.mobile.ui.SecondaryButton
import dev.minicam.mobile.ui.theme.CamColors
import kotlinx.coroutines.launch

/** The desktop "Camera settings" modal (minicam `config.js`): a list view and an add/edit form. */
@Composable
fun CameraSettingsScreen(viewModel: AppViewModel) {
    val api = viewModel.api ?: return
    // null: list view; Editing(null): new camera; Editing(camera): edit that camera.
    var editing by remember { mutableStateOf<Editing?>(null) }

    val current = editing
    if (current == null) {
        CameraList(viewModel, api, onEdit = { editing = Editing(it) })
    } else {
        CameraForm(viewModel, api, current.camera, onDone = { editing = null })
    }
}

private data class Editing(val camera: CameraConfig?)

@Composable
private fun CameraList(viewModel: AppViewModel, api: MinicamApi, onEdit: (CameraConfig?) -> Unit) {
    val scope = rememberCoroutineScope()
    var cameras by remember { mutableStateOf<List<CameraConfig>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    suspend fun load() {
        viewModel.attempt({ error = it }) { api.adminCameras() }?.let { cameras = it }
    }
    LaunchedEffect(api) { load() }

    AdminScaffold("Camera settings", onBack = { viewModel.screen = Screen.CAMERAS }) {
        PrimaryButton("Add camera") { onEdit(null) }
        FormMessage(error)
        val list = cameras
        when {
            list == null -> if (error == null) Spinner("Loading cameras…")
            list.isEmpty() -> Hint("No cameras configured yet.")
            else -> list.forEach { camera ->
                Card(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(camera.name, color = CamColors.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(camera.host, color = CamColors.Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        SecondaryButton("Edit") { onEdit(camera) }
                        SecondaryButton("Delete", danger = true) { pendingDelete = camera.name }
                    }
                }
            }
        }
    }

    pendingDelete?.let { name ->
        ConfirmDialog(
            title = "Delete camera",
            message = "Delete camera \"$name\"?",
            confirmLabel = "Delete",
            onConfirm = {
                scope.launch {
                    error = null
                    if (viewModel.attempt({ error = it }) { api.deleteCamera(name) } != null) {
                        load()
                        viewModel.refresh()
                    }
                }
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun CameraForm(viewModel: AppViewModel, api: MinicamApi, camera: CameraConfig?, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    val initial = camera ?: CameraConfig(name = "", host = "")
    var name by remember { mutableStateOf(initial.name) }
    var host by remember { mutableStateOf(initial.host) }
    var rtspPort by remember { mutableStateOf(initial.rtspPort.toString()) }
    var onvifPort by remember { mutableStateOf(initial.onvifPort.toString()) }
    var user by remember { mutableStateOf(initial.user) }
    // Never prefilled (the server redacts it) and never saved state: blank keeps the current one.
    var password by remember { mutableStateOf("") }
    var channel by remember { mutableStateOf(initial.channel.toString()) }
    var stream by remember { mutableStateOf(initial.stream) }
    var notifications by remember { mutableStateOf(initial.notifications) }
    val selectedClasses = remember { mutableStateListOf(*initial.detectionClasses.toTypedArray()) }
    var availableClasses by remember { mutableStateOf<List<String>?>(null) }
    var classesError by remember { mutableStateOf<String?>(null) }
    var discovered by remember { mutableStateOf<List<DiscoveredCamera>?>(null) }
    var discovering by remember { mutableStateOf(false) }
    var discoverError by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(api) {
        viewModel.attempt({ classesError = "Could not load detection classes." }) { api.detectionClasses() }
            ?.let { availableClasses = it }
    }

    fun save() {
        val rtsp = rtspPort.trim().toIntOrNull()
        val onvif = onvifPort.trim().toIntOrNull()
        val ch = channel.trim().toIntOrNull()
        error = when {
            name.isBlank() || host.isBlank() -> "Name and host are required."
            rtsp == null || rtsp !in 1..65535 || onvif == null || onvif !in 1..65535 -> "Ports must be between 1 and 65535."
            ch == null || ch < 1 -> "Channel must be 1 or more."
            else -> null
        }
        if (error != null) return
        val config = CameraConfig(
            name = name.trim(),
            host = host.trim(),
            rtspPort = rtsp!!,
            onvifPort = onvif!!,
            user = user.trim(),
            password = password,
            channel = ch!!,
            stream = stream,
            // With no backend there is nothing to pick from: keep the camera's classes as they are.
            detectionClasses = availableClasses?.takeIf { it.isNotEmpty() }?.filter { it in selectedClasses }
                ?: selectedClasses.toList(),
            notifications = notifications,
        )
        scope.launch {
            saving = true
            val saved = viewModel.attempt({ error = it }) { api.saveCamera(config, originalName = camera?.name) }
            saving = false
            if (saved != null) {
                viewModel.refresh()
                onDone()
            }
        }
    }

    AdminScaffold(if (camera == null) "Add camera" else "Edit camera", onBack = onDone) {
        Card(Modifier.fillMaxWidth()) {
            CamTextField("Name", name, { name = it }, placeholder = "my-camera")
            if (camera != null) Hint("Rename requires all connected streams to switch to the new name.")

            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CamTextField("Host / IP", host, { host = it }, keyboardType = KeyboardType.Uri, placeholder = "192.168.1.x", modifier = Modifier.weight(1f))
                SecondaryButton("Search", busy = discovering, modifier = Modifier.padding(bottom = 8.dp)) {
                    scope.launch {
                        discovering = true
                        discoverError = null
                        discovered = viewModel.attempt({ discoverError = "Discovery failed. Check network connectivity." }) {
                            api.discoverCameras()
                        }
                        discovering = false
                    }
                }
            }
            if (discovering || discovered != null || discoverError != null) {
                DiscoveryResults(discovering, discovered, discoverError, onUse = { host = it })
            }

            CamTextField("RTSP port", rtspPort, { rtspPort = it }, keyboardType = KeyboardType.Number, placeholder = "554")
            CamTextField("ONVIF port", onvifPort, { onvifPort = it }, keyboardType = KeyboardType.Number, placeholder = "8000")
            CamTextField("Username", user, { user = it })
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CamTextField("Password", password, { password = it }, keyboardType = KeyboardType.Password, secret = true, placeholder = "Leave blank to keep current")
                Hint("Leave blank to keep the existing password.")
            }
            CamTextField("Channel", channel, { channel = it }, keyboardType = KeyboardType.Number, placeholder = "1")
            OptionPicker("Stream", listOf(StreamQuality.MAIN to "Main", StreamQuality.SUB to "Sub"), stream) { stream = it }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Object detection", color = CamColors.Muted, fontSize = 12.sp)
                val classes = availableClasses
                when {
                    classesError != null -> Text(classesError!!, color = CamColors.Danger, fontSize = 12.sp)
                    classes == null -> Spinner("Loading detection classes…")
                    classes.isEmpty() -> Hint("No detection backend configured on this device.")
                    else -> classes.forEach { cls ->
                        LabeledCheckbox(cls, cls in selectedClasses) { checked ->
                            if (checked) selectedClasses += cls else selectedClasses -= cls
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OptionPicker(
                    "Detection notifications",
                    listOf(
                        NotificationMode.INHERIT to "Use global setting (Admin)",
                        NotificationMode.ON to "On for this camera",
                        NotificationMode.OFF to "Off for this camera",
                    ),
                    notifications,
                ) { notifications = it }
                Hint("Notifies when any of the detection classes above is seen.")
            }

            FormMessage(error)
            PrimaryButton("Save", busy = saving, onClick = ::save)
        }
    }
}

@Composable
private fun DiscoveryResults(busy: Boolean, cameras: List<DiscoveredCamera>?, error: String?, onUse: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Found on network")
        when {
            busy -> Spinner("Searching for cameras on the network…")
            error != null -> Text(error, color = CamColors.Danger, fontSize = 13.sp)
            cameras.isNullOrEmpty() -> Hint("No cameras found on the network.")
            else -> cameras.forEachIndexed { index, found ->
                if (index > 0) HorizontalDivider(color = CamColors.Border)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(found.host, color = CamColors.Text, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                        Text(found.name, color = CamColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    SecondaryButton("Use") { onUse(found.host) }
                }
            }
        }
    }
}
