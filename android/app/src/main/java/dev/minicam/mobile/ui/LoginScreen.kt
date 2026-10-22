package dev.minicam.mobile.ui

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.minicam.mobile.ui.theme.CamColors

@Composable
fun LoginScreen(viewModel: AppViewModel) {
    val context = LocalContext.current
    var email by rememberSaveable { mutableStateOf("") }
    // Not rememberSaveable: the password must not end up in saved instance state.
    var password by androidx.compose.runtime.remember { mutableStateOf("") }

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
                Text("Sign in", color = CamColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Text("Use your minicam account.", color = CamColors.Muted, fontSize = 12.sp)
            }

            CamTextField("Server", viewModel.serverUrlText, { viewModel.serverUrlText = it }, keyboardType = KeyboardType.Uri)

            if (viewModel.needsAccessLogin) {
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
            } else {
                CamTextField("Email", email, { email = it }, keyboardType = KeyboardType.Email)
                CamTextField("Password", password, { password = it }, keyboardType = KeyboardType.Password, secret = true)
                PrimaryButton(
                    label = "Sign in",
                    enabled = !viewModel.busy && email.isNotBlank() && password.isNotEmpty(),
                    busy = viewModel.busy,
                ) { viewModel.login(email.trim(), password) }
            }

            viewModel.errorMessage?.let { Text(it, color = CamColors.Danger, fontSize = 12.sp) }
        }
    }
}
