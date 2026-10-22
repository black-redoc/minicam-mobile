package dev.minicam.mobile.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.minicam.mobile.data.CatalogAsset
import dev.minicam.mobile.data.DetectionBackend
import dev.minicam.mobile.data.DetectionSettings
import dev.minicam.mobile.data.ModelCatalog
import dev.minicam.mobile.data.detectionSettings
import dev.minicam.mobile.data.fetchModel
import dev.minicam.mobile.data.installCatalogModel
import dev.minicam.mobile.data.modelCatalog
import dev.minicam.mobile.data.saveDetectionSettings
import dev.minicam.mobile.ui.AppViewModel
import dev.minicam.mobile.ui.CamTextField
import dev.minicam.mobile.ui.PrimaryButton
import dev.minicam.mobile.ui.Screen
import dev.minicam.mobile.ui.SecondaryButton
import dev.minicam.mobile.ui.theme.CamColors
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * The desktop "AI Detection" page (minicam `ai_detection.html`): model catalog and engine
 * settings. Uploading an `.onnx` from the phone is out of scope (ADR 0004).
 */
@Composable
fun AiDetectionScreen(viewModel: AppViewModel) {
    val api = viewModel.api ?: return
    val scope = rememberCoroutineScope()

    var catalog by remember { mutableStateOf<ModelCatalog?>(null) }
    var catalogStatus by remember { mutableStateOf<String?>("Loading catalog…") }
    var installing by remember { mutableStateOf<String?>(null) }
    var catalogError by remember { mutableStateOf<String?>(null) }

    var loaded by remember { mutableStateOf(false) }
    var backend by remember { mutableStateOf(DetectionBackend.NONE) }
    var confidence by remember { mutableStateOf("") }
    var fps by remember { mutableStateOf("") }
    var modelPath by remember { mutableStateOf("") }
    var modelUrl by remember { mutableStateOf("") }
    var fetching by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var success by remember { mutableStateOf<String?>(null) }

    fun populate(settings: DetectionSettings) {
        backend = settings.backend
        modelPath = settings.modelPath
        confidence = settings.confidenceThreshold.toPlain()
        fps = settings.inferenceFps.toPlain()
        loaded = true
    }

    suspend fun loadCatalog() {
        viewModel.attempt({ catalogStatus = it }) { api.modelCatalog() }?.let {
            catalog = it
            catalogStatus = if (it.assets.isEmpty()) "The catalog is empty." else null
        }
    }

    LaunchedEffect(api) {
        launch { loadCatalog() }
        viewModel.attempt({ error = it }) { api.detectionSettings() }?.let(::populate)
    }

    fun install(asset: CatalogAsset) {
        scope.launch {
            installing = asset.name
            catalogError = null
            val done = viewModel.attempt({ catalogError = it }) { api.installCatalogModel(asset.name) }
            if (done != null) {
                loadCatalog()
                viewModel.attempt({ error = it }) { api.detectionSettings() }?.let(::populate)
            }
            installing = null
        }
    }

    fun fetch() {
        error = null
        success = null
        val url = modelUrl.trim()
        if (!url.startsWith("https://")) {
            error = "Enter an https:// model URL first."
            return
        }
        scope.launch {
            fetching = true
            viewModel.attempt({ error = it }) { api.fetchModel(url) }?.let {
                modelPath = it
                success = "Model downloaded. Tap Save to activate it."
            }
            fetching = false
        }
    }

    fun save() {
        error = null
        success = null
        val threshold = confidence.trim().replace(',', '.').toDoubleOrNull()
        val inferenceFps = fps.trim().replace(',', '.').toDoubleOrNull()
        if (threshold == null || threshold !in 0.0..1.0) {
            error = "Confidence threshold must be between 0 and 1."
            return
        }
        if (inferenceFps == null || inferenceFps <= 0) {
            error = "Inference FPS must be greater than 0."
            return
        }
        val settings = DetectionSettings(backend, modelPath.trim(), threshold, inferenceFps)
        scope.launch {
            saving = true
            viewModel.attempt({ error = it }) { api.saveDetectionSettings(settings) }?.let {
                populate(it)
                success = "Settings saved."
                loadCatalog()
            }
            saving = false
        }
    }

    AdminScaffold("AI detection", onBack = { viewModel.screen = Screen.CAMERAS }) {
        Section("Model catalog", trailing = catalog?.release?.takeIf { it.isNotEmpty() }?.let { "Release $it" }) {
            Hint(
                "Ready-to-use YOLO models from minicam-models. Raspberry Pi 5: pick an n model " +
                    "(int8 is fastest). x86 PC: an s model. Installing verifies the file's checksum and activates it.",
            )
            catalogStatus?.let { Text(it, color = CamColors.Muted, fontSize = 12.sp) }
            catalog?.assets?.forEachIndexed { index, asset ->
                if (index > 0) HorizontalDivider(color = CamColors.Border)
                CatalogRow(asset, installing = installing == asset.name, enabled = installing == null) { install(asset) }
            }
            FormMessage(catalogError)
        }

        Section("AI detection") {
            if (!loaded && error == null) {
                Spinner("Loading settings…")
            } else {
                OptionPicker(
                    "Backend",
                    listOf(DetectionBackend.NONE to "None", DetectionBackend.ONNX to "ONNX (CPU)"),
                    backend,
                ) { backend = it }
                CamTextField("Confidence threshold", confidence, { confidence = it }, keyboardType = KeyboardType.Decimal)
                CamTextField("Inference FPS", fps, { fps = it }, keyboardType = KeyboardType.Decimal)
                CamTextField("Model path", modelPath, { modelPath = it }, placeholder = "/absolute/path/to/model.onnx", monospace = true)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CamTextField(
                        "Or fetch from URL",
                        modelUrl,
                        { modelUrl = it },
                        keyboardType = KeyboardType.Uri,
                        placeholder = "https://…/yolo11n-int8.onnx",
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton("Fetch", busy = fetching, modifier = Modifier.padding(bottom = 8.dp), onClick = ::fetch)
                }
                Hint("1. Install a catalog model, or fetch one from a URL. 2. Set it as the active backend and save.")
                FormMessage(error, success)
                PrimaryButton("Save", busy = saving, onClick = ::save)
            }
            if (!loaded) FormMessage(error)
        }
    }
}

@Composable
private fun CatalogRow(asset: CatalogAsset, installing: Boolean, enabled: Boolean, onInstall: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Remote manifest text: plain Text only (SECURITY.md).
        Column(Modifier.weight(1f)) {
            Text(asset.name, color = CamColors.Text, fontSize = 14.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${asset.family} · ${asset.precision} · ${asset.profile} · ${String.format(Locale.US, "%.1f", asset.bytes / 1e6)} MB",
                color = CamColors.Muted,
                fontSize = 12.sp,
            )
        }
        if (asset.active) {
            Badge("Active", CamColors.Success)
        } else {
            SecondaryButton(if (asset.installed) "Activate" else "Install & activate", enabled = enabled, busy = installing, onClick = onInstall)
        }
    }
}

/** `0.5` → "0.5", `3.0` → "3": the number as the desktop's `<input type=number>` shows it. */
private fun Double.toPlain(): String = if (this % 1.0 == 0.0) toLong().toString() else toString()
