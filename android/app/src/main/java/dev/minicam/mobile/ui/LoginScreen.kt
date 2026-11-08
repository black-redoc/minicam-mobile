package dev.minicam.mobile.ui

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.minicam.mobile.data.DeviceSetupState
import dev.minicam.mobile.data.SetupCode
import dev.minicam.mobile.ui.theme.CamColors
import kotlinx.coroutines.delay

@Composable
fun LoginScreen(viewModel: AppViewModel) {
    val context = LocalContext.current
    var email by rememberSaveable { mutableStateOf("") }
    // Not rememberSaveable: passwords and the setup code must not end up in saved instance state.
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var setupCode by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val discovered by viewModel.discovery.servers.collectAsState()

    // Ask a LAN server whether it still needs an admin once the address stops changing.
    LaunchedEffect(viewModel.serverUrlText) {
        delay(600)
        viewModel.checkSetup()
    }
    DisposableEffect(searching) {
        if (searching) viewModel.discovery.start()
        onDispose { viewModel.discovery.stop() }
    }

    val status = viewModel.setupStatus
    val needsCode = status?.needsLedCode == true

    Column(
        Modifier
            .fillMaxSize()
            .background(CamColors.Bg)
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(Modifier.padding(top = 32.dp)) { BrandTitle() }

        Card(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    if (viewModel.creatingAdmin) "Create admin account" else "Sign in",
                    color = CamColors.Text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                )
                Text(
                    if (viewModel.creatingAdmin) {
                        "No account exists on this device yet. The account you create becomes the administrator."
                    } else {
                        "Use your minicam account."
                    },
                    color = CamColors.Muted,
                    fontSize = 12.sp,
                )
            }

            CamTextField("Server", viewModel.serverUrlText, { viewModel.serverUrlText = it }, keyboardType = KeyboardType.Uri)
            DiscoveryList(
                searching = searching,
                servers = discovered.map { it.name to it.baseUrl },
                onToggle = { searching = !searching },
                onPick = { url ->
                    viewModel.serverUrlText = url
                    searching = false
                },
            )

            when {
                viewModel.needsAccessLogin -> {
                    PrimaryButton("Continue with Cloudflare Access", enabled = true) {
                        viewModel.startAccessLogin()?.let { url ->
                            CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse(url))
                        }
                    }
                    Text(
                        "This server is outside your network. Cloudflare Access verifies you first.",
                        color = CamColors.Muted,
                        fontSize = 12.sp,
                    )
                }
                viewModel.creatingAdmin && status?.state == DeviceSetupState.RESTART_NEEDED -> Text(
                    "Setup is only open for 30 minutes after the device starts. Unplug the device's " +
                        "power, plug it back in, wait a minute and try again.",
                    color = CamColors.Muted,
                    fontSize = 12.sp,
                )
                viewModel.creatingAdmin -> {
                    if (needsCode) {
                        Text(
                            "To prove you are next to the device, ask it for a code: its green LED blinks 4 digits.",
                            color = CamColors.Muted,
                            fontSize = 12.sp,
                        )
                        SecondaryButton("Blink a code on the device", enabled = !viewModel.busy) {
                            viewModel.requestSetupCode()
                        }
                        viewModel.setupCodeMessage?.let { Text(it, color = CamColors.Muted, fontSize = 12.sp) }
                        CamTextField(
                            "Code from the LED",
                            setupCode,
                            { setupCode = it.filter(Char::isDigit).take(4) },
                            keyboardType = KeyboardType.NumberPassword,
                            monospace = true,
                        )
                    }
                    CamTextField("Email", email, { email = it }, keyboardType = KeyboardType.Email)
                    CamTextField(
                        "Password (min. 8 characters)",
                        password,
                        { password = it },
                        keyboardType = KeyboardType.Password,
                        secret = true,
                    )
                    CamTextField("Confirm password", confirm, { confirm = it }, keyboardType = KeyboardType.Password, secret = true)
                    if (confirm.isNotEmpty() && confirm != password) {
                        Text("Passwords do not match.", color = CamColors.Danger, fontSize = 12.sp)
                    }
                    PrimaryButton(
                        label = "Create account",
                        enabled = !viewModel.busy && email.isNotBlank() && password.length >= 8 &&
                            password == confirm && (!needsCode || SetupCode.isValid(setupCode)),
                        busy = viewModel.busy,
                    ) {
                        viewModel.completeSetup(email.trim(), password, setupCode.takeIf { needsCode })
                    }
                }
                else -> {
                    CamTextField("Email", email, { email = it }, keyboardType = KeyboardType.Email)
                    CamTextField("Password", password, { password = it }, keyboardType = KeyboardType.Password, secret = true)
                    PrimaryButton(
                        label = "Sign in",
                        enabled = !viewModel.busy && email.isNotBlank() && password.isNotEmpty(),
                        busy = viewModel.busy,
                    ) { viewModel.login(email.trim(), password) }
                }
            }

            // Only a device with no account yet can be set up; switching lets the user sign in
            // instead (or go back to setup) without retyping the address.
            if (status?.setupRequired == true && !viewModel.needsAccessLogin) {
                TextButton(onClick = { viewModel.creatingAdmin = !viewModel.creatingAdmin }) {
                    Text(
                        if (viewModel.creatingAdmin) "I already have an account: sign in" else "Set up this device",
                        color = CamColors.AccentHover,
                        fontSize = 13.sp,
                    )
                }
            }

            viewModel.errorMessage?.let { Text(it, color = CamColors.Danger, fontSize = 12.sp) }
        }
    }
}

/** "Find minicam on my network": devices answering `_minicam._tcp` on the Wi-Fi. */
@Composable
private fun DiscoveryList(
    searching: Boolean,
    servers: List<Pair<String, String>>,
    onToggle: () -> Unit,
    onPick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SecondaryButton(if (searching) "Stop searching" else "Find minicam on my network", onClick = onToggle)
        if (searching && servers.isEmpty()) {
            Text(
                "Searching… The phone must be on the same Wi-Fi as the device.",
                color = CamColors.Muted,
                fontSize = 12.sp,
            )
        }
        if (searching) {
            servers.forEach { (name, url) ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(url) }
                        .padding(vertical = 6.dp),
                ) {
                    // Names come from the network: plain Text only (SECURITY.md).
                    Text(name, color = CamColors.Text, fontSize = 14.sp)
                    Text(url, color = CamColors.Muted, fontSize = 12.sp)
                }
            }
        }
    }
}
