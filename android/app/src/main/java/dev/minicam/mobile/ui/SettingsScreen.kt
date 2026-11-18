package dev.minicam.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.minicam.mobile.BuildConfig
import dev.minicam.mobile.data.SystemUpdate
import dev.minicam.mobile.data.UpdateProgress
import dev.minicam.mobile.i18n.AppLanguage
import dev.minicam.mobile.i18n.I18n
import dev.minicam.mobile.i18n.tr
import dev.minicam.mobile.ui.admin.AdminScaffold
import dev.minicam.mobile.ui.admin.Badge
import dev.minicam.mobile.ui.admin.FormMessage
import dev.minicam.mobile.ui.admin.Hint
import dev.minicam.mobile.ui.admin.OptionPicker
import dev.minicam.mobile.ui.admin.Section
import dev.minicam.mobile.ui.admin.Spinner
import dev.minicam.mobile.ui.theme.CamColors

/**
 * Settings (docs/adr/0017): the app language for everyone; for admins also the device's minicam
 * version and, when a newer release is out, the button that installs it.
 */
@Composable
fun SettingsScreen(viewModel: AppViewModel) {
    val context = LocalContext.current
    val isAdmin = viewModel.user?.isAdmin == true
    LaunchedEffect(isAdmin) { if (isAdmin) viewModel.checkForUpdate() }

    AdminScaffold(tr("Settings"), onBack = { viewModel.screen = Screen.CAMERAS }) {
        Section(tr("Language")) {
            OptionPicker(
                label = tr("App language"),
                options = listOf(
                    AppLanguage.SYSTEM to tr("Phone language"),
                    // Each language's own name, so it can be found in either language.
                    AppLanguage.ENGLISH to "English",
                    AppLanguage.SPANISH to "Español",
                ),
                selected = I18n.language,
            ) { I18n.select(context, it) }
        }

        if (isAdmin) DeviceSection(viewModel)

        Section(tr("App")) {
            Text(tr("minicam for Android %s", BuildConfig.VERSION_NAME), color = CamColors.Text, fontSize = 14.sp)
        }
    }
}

@Composable
private fun DeviceSection(viewModel: AppViewModel) {
    val status = viewModel.systemUpdate
    val progress = viewModel.updateProgress
    Section(tr("minicam on this device")) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                status == null && viewModel.checkingUpdate -> Spinner(tr("Checking for updates…"))
                status == null -> Hint(tr("This minicam can't be updated from the app. Install the latest image on its SD card."))
                else -> VersionRows(status)
            }
            when (progress) {
                is UpdateProgress.Running -> Spinner(progressText(progress))
                is UpdateProgress.Done -> FormMessage(null, tr("minicam %s is running.", progress.version))
                is UpdateProgress.Failed -> FormMessage(
                    progress.message?.let { tr("The update failed: %s", it) }
                        ?: tr("The update did not finish. Check the device and try again."),
                )
                null -> {}
            }
            FormMessage(viewModel.updateError)
            if (status != null && status.available && progress !is UpdateProgress.Running) {
                val latest = status.latest
                if (status.updateAvailable && latest != null) {
                    Hint(tr("The device restarts minicam: live views and recording stop for about a minute."))
                    PrimaryButton(tr("Update to %s", latest), modifier = Modifier.fillMaxWidth()) { viewModel.installUpdate() }
                }
                SecondaryButton(tr("Check for updates"), busy = viewModel.checkingUpdate) { viewModel.checkForUpdate(refresh = true) }
            }
        }
    }
}

@Composable
private fun VersionRows(status: SystemUpdate) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(tr("Installed: %s", status.current), color = CamColors.Text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        if (status.available && !status.updateAvailable && status.latest != null) Badge(tr("Up to date"))
    }
    val latest = status.latest
    when {
        !status.available -> Hint(tr("This minicam was not installed from the minicam image, so it can't update itself."))
        status.updateAvailable && latest != null -> Badge(tr("%s available", latest), CamColors.Success)
        latest == null -> Hint(tr("Couldn't check GitHub for new releases yet."))
    }
}

private fun progressText(progress: UpdateProgress.Running): String = when (progress.state) {
    "downloading" -> tr("Downloading minicam %s…", progress.target)
    "verifying" -> tr("Checking the signature…")
    "installing" -> tr("Installing minicam %s…", progress.target)
    "restarting" -> tr("Restarting minicam…")
    else -> tr("Starting the update…")
}
