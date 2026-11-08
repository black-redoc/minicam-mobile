package dev.minicam.mobile.ui.admin

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.sp
import dev.minicam.mobile.data.CentralStatus
import dev.minicam.mobile.data.CentralUrl
import dev.minicam.mobile.data.PairingCode
import dev.minicam.mobile.data.centralStatus
import dev.minicam.mobile.data.pairWithCentral
import dev.minicam.mobile.data.unpairFromCentral
import dev.minicam.mobile.ui.AppViewModel
import dev.minicam.mobile.ui.CamTextField
import dev.minicam.mobile.ui.PrimaryButton
import dev.minicam.mobile.ui.Screen
import dev.minicam.mobile.ui.SecondaryButton
import dev.minicam.mobile.ui.theme.CamColors
import kotlinx.coroutines.launch

/**
 * Admin → Central dashboard (minicam ADR 0009): connects the device to minicam-admin with the
 * one-time code shown by "Pair device" there. minicam exchanges the code for its token itself;
 * the code is only held in this screen's state.
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

    LaunchedEffect(api) {
        if (api.config.isWan) {
            error = "The dashboard can only be connected while you're on your home network."
            return@LaunchedEffect
        }
        viewModel.attempt({ error = it }) { api.centralStatus() }?.let {
            status = it
            it.url?.let { current -> url = current }
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
            viewModel.attempt({ error = it }) { api.pairWithCentral(dashboard, pairing) }?.let {
                status = it
                code = ""
                success = "Connected" + (it.site?.let { site -> " as ${site.name} (${site.hostname})" } ?: "") +
                    ". WAN access is now managed in the dashboard."
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

    AdminScaffold("Central dashboard", onBack = { viewModel.screen = Screen.CAMERAS }) {
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

        if (status?.managedInEnv != true) {
            Section(if (status?.connected == true) "Pair again (new token)" else "Connect") {
                Hint(
                    "In the dashboard open Sites, press \"Pair device\" on this device's site and type the " +
                        "code it shows (valid once, for 10 minutes). The device fetches its own token: " +
                        "nothing secret is copied by hand.",
                )
                CamTextField("Dashboard address", url, { url = it }, keyboardType = KeyboardType.Uri)
                CamTextField(
                    "Pairing code",
                    code,
                    { code = it.take(12) },
                    keyboardType = KeyboardType.Ascii,
                    placeholder = "ABCD-EFGH",
                    monospace = true,
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
