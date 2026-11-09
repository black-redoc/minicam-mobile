package dev.minicam.mobile.ui.admin

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import dev.minicam.mobile.data.CentralStatus
import dev.minicam.mobile.data.CentralUrl
import dev.minicam.mobile.data.ConnectProgress
import dev.minicam.mobile.data.PairingCode
import dev.minicam.mobile.data.StepState
import dev.minicam.mobile.data.centralStatus
import dev.minicam.mobile.data.connectToDashboard
import dev.minicam.mobile.data.unpairFromCentral
import dev.minicam.mobile.ui.AppViewModel
import dev.minicam.mobile.ui.CamTextField
import dev.minicam.mobile.ui.PrimaryButton
import dev.minicam.mobile.ui.Screen
import dev.minicam.mobile.ui.SecondaryButton
import dev.minicam.mobile.ui.theme.CamColors
import kotlinx.coroutines.launch

/**
 * Admin → Connect to dashboard (minicam ADRs 0009/0010, docs/adr/0011): connects the device to
 * minicam-admin with the one-time code shown by "Pair device" there, typed or from its QR link,
 * and shows the remote access the dashboard sets up. minicam exchanges the code for its token
 * itself; the code is only held in this screen's state.
 */
@Composable
fun CentralDashboardScreen(viewModel: AppViewModel) {
    val api = viewModel.api ?: return
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf<CentralStatus?>(null) }
    var url by remember { mutableStateOf("https://") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<ConnectProgress?>(null) }
    // Filled from a QR link: the admin must see the dashboard host and confirm (docs/adr/0011).
    var linkHost by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(api) {
        if (api.config.isWan) {
            error = "The dashboard can only be connected while you're on your home network."
            return@LaunchedEffect
        }
        val link = viewModel.pendingPairLink.take()
        viewModel.attempt({ error = it }) { api.centralStatus() }?.let {
            status = it
            it.url?.let { current -> url = current }
        }
        if (link != null) {
            url = link.url
            code = link.code
            linkHost = java.net.URI(link.url).host
        }
    }

    fun pair() {
        val dashboard = CentralUrl.normalize(url)
        val pairing = PairingCode.normalize(code)
        error = null
        success = null
        if (dashboard == null) {
            error = "Enter the dashboard's https:// address, e.g. https://admin.example.com"
            return
        }
        if (pairing == null) {
            error = "The code has 8 letters and digits, like ABCD-EFGH."
            return
        }
        scope.launch {
            busy = true
            code = ""
            linkHost = null
            val result = viewModel.attempt({ error = it }) {
                api.connectToDashboard(dashboard, pairing, onProgress = { progress = it })
            }
            if (result == null) {
                progress = null
            } else {
                viewModel.attempt({}) { api.centralStatus() }?.let { status = it }
                success = "WAN access is now managed in the dashboard." +
                    (result.wanUrl?.let { " From outside your home network, sign in at $it" } ?: "")
            }
            busy = false
        }
    }

    fun unpair() {
        error = null
        success = null
        scope.launch {
            busy = true
            viewModel.attempt({ error = it }) { api.unpairFromCentral() }?.let {
                status = it
                success = "Disconnected. WAN access is managed on the device again."
            }
            busy = false
        }
    }

    AdminScaffold("Connect to dashboard", onBack = { viewModel.screen = Screen.CAMERAS }) {
        Section("Status") {
            val current = status
            when {
                current == null && error == null -> RowsSkeleton(rows = 1, trailing = false)
                current != null && current.connected -> {
                    Text("Connected", color = CamColors.Text, fontSize = 14.sp)
                    current.url?.let { Hint(it) }
                    Hint(current.lastSyncedAt?.let { "Last sync: $it (UTC)" } ?: "Waiting for the first sync…")
                    if (current.managedInEnv) {
                        Hint("Set in the device's .env file: change it there.")
                    }
                }
                current != null -> Text("Not connected", color = CamColors.Text, fontSize = 14.sp)
            }
        }

        progress?.let { current ->
            Section("Progress") {
                ProgressRow("Dashboard", current.dashboard, current.dashboardText)
                ProgressRow("Remote access", current.remoteAccess, current.remoteAccessText)
            }
        }

        if (status?.managedInEnv != true) {
            Section(if (status?.connected == true) "Pair again (new token)" else "Connect") {
                Hint(
                    "In the dashboard open Sites, press \"Pair device\" on this device's site and type the " +
                        "code it shows (valid once, for 10 minutes), or scan its QR with the phone's camera. " +
                        "The device fetches its own token and, if the dashboard offers it, its remote access: " +
                        "nothing secret is copied by hand.",
                )
                linkHost?.let { host ->
                    Text("Connect this device to $host?", color = CamColors.Text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Hint("Only continue if you opened this code from your own dashboard: it decides who can watch from outside.")
                }
                CamTextField("Dashboard address", url, { url = it }, keyboardType = KeyboardType.Uri)
                CamTextField(
                    "Pairing code",
                    code,
                    { code = PairingCode.clean(it) },
                    keyboardType = KeyboardType.Ascii,
                    placeholder = "ABCD-EFGH",
                    monospace = true,
                    visualTransformation = PairingCodeTransformation,
                )
                PrimaryButton(
                    if (status?.connected == true) "Pair again" else "Connect",
                    enabled = !busy && code.isNotBlank(),
                    busy = busy,
                    onClick = ::pair,
                )
                if (status?.connected == true) {
                    SecondaryButton("Disconnect", enabled = !busy, danger = true, onClick = ::unpair)
                }
            }
        }
        FormMessage(error, success)
    }
}

/** Shows the cleaned code with its dash, so the user never types it and the cursor skips over it. */
private object PairingCodeTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val shown = PairingCode.format(text.text)
        val dashed = shown.length > text.length
        return TransformedText(
            AnnotatedString(shown),
            object : OffsetMapping {
                override fun originalToTransformed(offset: Int) = if (dashed && offset > 4) offset + 1 else offset

                override fun transformedToOriginal(offset: Int) =
                    (if (dashed && offset > 4) offset - 1 else offset).coerceAtMost(text.length)
            },
        )
    }
}

@Composable
private fun ProgressRow(label: String, state: StepState, detail: String?) {
    val (mark, color) = when (state) {
        StepState.WAITING -> "○" to CamColors.Muted
        StepState.WORKING -> "…" to CamColors.Text
        StepState.DONE -> "✓" to CamColors.Success
        StepState.SKIPPED -> "–" to CamColors.Muted
        StepState.FAILED -> "✕" to CamColors.Danger
    }
    Text("$mark $label", color = color, fontSize = 14.sp)
    detail?.let { Hint(it) }
}
