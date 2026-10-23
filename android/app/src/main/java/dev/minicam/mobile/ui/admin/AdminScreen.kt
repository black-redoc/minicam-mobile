package dev.minicam.mobile.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.minicam.mobile.data.CameraPermission
import dev.minicam.mobile.data.MinicamApi
import dev.minicam.mobile.data.NotificationSettings
import dev.minicam.mobile.data.RecordingSettings
import dev.minicam.mobile.data.UserAccess
import dev.minicam.mobile.data.adminCameras
import dev.minicam.mobile.data.createUser
import dev.minicam.mobile.data.deleteUser
import dev.minicam.mobile.data.formatStorageBytes
import dev.minicam.mobile.data.notificationSettings
import dev.minicam.mobile.data.recordingSettings
import dev.minicam.mobile.data.saveRecordingSettings
import dev.minicam.mobile.data.saveNotificationSettings
import dev.minicam.mobile.data.setPermission
import dev.minicam.mobile.data.setWanAccess
import dev.minicam.mobile.data.userAccess
import dev.minicam.mobile.ui.AppViewModel
import dev.minicam.mobile.ui.CamTextField
import dev.minicam.mobile.ui.Card
import dev.minicam.mobile.ui.PrimaryButton
import dev.minicam.mobile.ui.Screen
import dev.minicam.mobile.ui.theme.CamColors
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** The desktop "Admin" page (minicam `admin.html`): users, permissions, WAN access, notifications. */
@Composable
fun AdminScreen(viewModel: AppViewModel) {
    val api = viewModel.api ?: return
    val scope = rememberCoroutineScope()
    var cameras by remember { mutableStateOf<List<String>>(emptyList()) }
    var users by remember { mutableStateOf<List<UserAccess>?>(null) }
    var usersError by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<UserAccess?>(null) }

    suspend fun loadUsers() {
        viewModel.attempt({ usersError = it }) {
            coroutineScope {
                val cameraNames = async { api.adminCameras().map { it.name } }
                api.userAccess() to cameraNames.await()
            }
        }?.let { (access, cameraNames) ->
            users = access
            cameras = cameraNames
        }
    }
    LaunchedEffect(api) { loadUsers() }

    /** Updates one user's row right away; callers put the old value back if the request fails. */
    fun updateUser(id: Int, change: (UserAccess) -> UserAccess) {
        users = users?.map { if (it.user.id == id) change(it) else it }
    }

    fun setWan(access: UserAccess, active: Boolean) {
        usersError = null
        updateUser(access.user.id) { it.copy(wanAccess = active) }
        scope.launch {
            viewModel.attempt({ usersError = it }) { api.setWanAccess(access.user.id, active) }
                ?: updateUser(access.user.id) { it.copy(wanAccess = !active) }
        }
    }

    fun setPermission(access: UserAccess, permission: CameraPermission) {
        usersError = null
        val previous = access.permissions
        updateUser(access.user.id) { it.copy(permissions = previous.filter { p -> p.cameraName != permission.cameraName } + permission) }
        scope.launch {
            viewModel.attempt({ usersError = it }) { api.setPermission(permission) }
                ?: updateUser(access.user.id) { it.copy(permissions = previous) }
        }
    }

    AdminScaffold("Admin", onBack = { viewModel.screen = Screen.CAMERAS }) {
        CreateUserSection(viewModel, api) { scope.launch { loadUsers() } }
        NotificationsSection(viewModel, api)
        RecordingSettingsSection(viewModel, api)

        SectionTitle("Users & camera permissions")
        FormMessage(usersError)
        val list = users
        when {
            list == null -> if (usersError == null) ListSkeleton()
            list.isEmpty() -> Hint("No users yet.")
            else -> list.forEach { access ->
                UserCard(
                    access = access,
                    cameras = cameras,
                    isSelf = access.user.id == viewModel.user?.id,
                    onWanAccess = { setWan(access, it) },
                    onPermission = { setPermission(access, it) },
                    onDelete = { pendingDelete = access },
                )
            }
        }
    }

    pendingDelete?.let { access ->
        ConfirmDialog(
            title = "Delete user",
            message = "Delete \"${access.user.email}\"? This also revokes their sessions and camera permissions.",
            confirmLabel = "Delete",
            onConfirm = {
                scope.launch {
                    usersError = null
                    if (viewModel.attempt({ usersError = it }) { api.deleteUser(access.user.id) } != null) loadUsers()
                }
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun CreateUserSection(viewModel: AppViewModel, api: MinicamApi, onCreated: () -> Unit) {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    // Plain remember: the new user's password never goes into saved state (SECURITY.md).
    var password by remember { mutableStateOf("") }
    var isAdmin by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Section("Create user") {
        CamTextField("Email", email, { email = it }, keyboardType = KeyboardType.Email)
        CamTextField("Password", password, { password = it }, keyboardType = KeyboardType.Password, secret = true, placeholder = "At least 8 characters")
        LabeledCheckbox("Admin", isAdmin) { isAdmin = it }
        FormMessage(error)
        PrimaryButton("Create", enabled = email.isNotBlank() && password.length >= 8, busy = busy) {
            scope.launch {
                busy = true
                error = null
                if (viewModel.attempt({ error = it }) { api.createUser(email.trim(), password, isAdmin) } != null) {
                    email = ""
                    password = ""
                    isAdmin = false
                    onCreated()
                }
                busy = false
            }
        }
    }
}

@Composable
private fun NotificationsSection(viewModel: AppViewModel, api: MinicamApi) {
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf<NotificationSettings?>(null) }
    var cooldown by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(api) {
        viewModel.attempt({ error = it }) { api.notificationSettings() }?.let {
            settings = it
            cooldown = it.cooldownSeconds.toString()
        }
    }

    Section("Detection notifications") {
        val current = settings
        if (current == null) {
            if (error == null) FormSkeleton(fields = 1, toggle = true)
        } else {
            LabeledSwitch("Notify on detections for all cameras", current.enabled) {
                settings = current.copy(enabled = it)
                saved = false
            }
            CamTextField("Cooldown per camera (seconds)", cooldown, {
                cooldown = it
                saved = false
            }, keyboardType = KeyboardType.Number)
            Hint(
                "Notifies users who can view a camera when one of its selected detection classes is seen. " +
                    "A camera's own setting (Camera settings → Detection notifications) overrides this switch.",
            )
            PrimaryButton("Save", busy = busy) {
                val seconds = cooldown.trim().toIntOrNull()
                if (seconds == null || seconds !in 1..86400) {
                    error = "Cooldown must be between 1 and 86400 seconds."
                    return@PrimaryButton
                }
                scope.launch {
                    busy = true
                    error = null
                    viewModel.attempt({ error = it }) { api.saveNotificationSettings(current.copy(cooldownSeconds = seconds)) }
                        ?.let {
                            settings = it
                            cooldown = it.cooldownSeconds.toString()
                            saved = true
                        }
                    busy = false
                }
            }
        }
        FormMessage(error, if (saved) "Saved." else null)
    }
}

/** The desktop "Event recordings" card (minicam `recording-settings.js`), ADR 0005. */
@Composable
private fun RecordingSettingsSection(viewModel: AppViewModel, api: MinicamApi) {
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf<RecordingSettings?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(api) {
        viewModel.attempt({ error = it }) { api.recordingSettings() }?.let { settings = it }
    }

    Section("Event recordings") {
        val current = settings
        if (current == null) {
            if (error == null) FormSkeleton(fields = 1, toggle = true)
        } else {
            LabeledSwitch("Record a clip while a camera detects one of its classes", current.enabled) {
                settings = current.copy(enabled = it)
                saved = false
            }
            OptionPicker(
                label = "Storage limit",
                options = current.options.map { it.gb to "${it.gb} GB" },
                selected = current.maxStorageGb,
                // Limits the disk can't hold stay visible but can't be chosen, like the web.
                unavailable = current.options.filter { !it.available }.associate { it.gb to "Not enough space" },
            ) {
                settings = current.copy(maxStorageGb = it)
                saved = false
            }
            Hint("Recordings use ${formatStorageBytes(current.usedBytes)} · ${formatStorageBytes(current.freeBytes)} free on disk")
            Hint(
                "Clips start about 3 seconds before a detection and stop about 5 seconds after the last one. " +
                    "When the limit is reached, the oldest clips are deleted. Users watch them from ⋮ → Recordings.",
            )
            PrimaryButton("Save", busy = busy) {
                scope.launch {
                    busy = true
                    error = null
                    viewModel.attempt({ error = it }) { api.saveRecordingSettings(current.enabled, current.maxStorageGb) }
                        ?.let {
                            settings = it
                            saved = true
                        }
                    busy = false
                }
            }
        }
        FormMessage(error, if (saved) "Saved." else null)
    }
}

@Composable
private fun UserCard(
    access: UserAccess,
    cameras: List<String>,
    isSelf: Boolean,
    onWanAccess: (Boolean) -> Unit,
    onPermission: (CameraPermission) -> Unit,
    onDelete: () -> Unit,
) {
    val byCamera = access.permissions.associateBy { it.cameraName }
    Card(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                access.user.email,
                color = CamColors.Text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (access.user.isAdmin) Badge("admin")
            Spacer(Modifier.weight(1f))
            // The server refuses to delete yourself; don't offer it.
            if (!isSelf) {
                TextButton(onClick = onDelete) { Text("Delete", color = CamColors.Danger, fontSize = 13.sp) }
            }
        }
        LabeledSwitch("WAN access", access.wanAccess, onChange = onWanAccess)
        Hint("Viewing streams and PTZ from outside the local network, through Cloudflare Access.")

        if (cameras.isEmpty()) {
            Hint("No cameras configured yet.")
        } else {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Camera", color = CamColors.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("View", color = CamColors.Muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.width(56.dp))
                    Text("PTZ", color = CamColors.Muted, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.width(56.dp))
                }
                cameras.forEach { camera ->
                    val permission = byCamera[camera] ?: CameraPermission(access.user.id, camera, canView = false, canUsePtz = false)
                    HorizontalDivider(color = CamColors.Border)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(camera, color = CamColors.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Row(Modifier.width(56.dp), horizontalArrangement = Arrangement.Center) {
                            CamCheckbox(permission.canView) { onPermission(permission.copy(canView = it)) }
                        }
                        Row(Modifier.width(56.dp), horizontalArrangement = Arrangement.Center) {
                            CamCheckbox(permission.canUsePtz) { onPermission(permission.copy(canUsePtz = it)) }
                        }
                    }
                }
            }
        }
    }
}
