package dev.minicam.mobile.ui

import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.minicam.mobile.ui.theme.CamColors
import dev.minicam.mobile.ui.theme.CamDimens

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

            Field("Server", viewModel.serverUrlText, { viewModel.serverUrlText = it }, KeyboardType.Uri)

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
                Field("Email", email, { email = it }, KeyboardType.Email)
                Field("Password", password, { password = it }, KeyboardType.Password, secret = true)
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

/** Mirrors the login inputs (`bg-slate-800 border-slate-700 focus:ring-sky-500`). */
@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    keyboardType: KeyboardType,
    secret: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = CamColors.Muted, fontSize = 12.sp)
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(CamDimens.Radius),
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
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

@Composable
private fun PrimaryButton(label: String, enabled: Boolean, busy: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
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
