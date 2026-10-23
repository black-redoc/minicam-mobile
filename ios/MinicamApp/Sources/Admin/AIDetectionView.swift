import MinicamKit
import SwiftUI

/// The desktop "AI Detection" page (minicam `ai_detection.html`): model catalog and engine
/// settings. Uploading an `.onnx` from the phone is out of scope (ADR 0004).
struct AIDetectionView: View {
    @Environment(AppModel.self) private var model
    @State private var catalog: ModelCatalog?
    @State private var catalogStatus: String?
    @State private var catalogError: String?
    @State private var installing: String?

    @State private var loaded = false
    @State private var backend = DetectionBackend.none
    @State private var confidence = ""
    @State private var fps = ""
    @State private var modelPath = ""
    @State private var modelURL = ""
    @State private var fetching = false
    @State private var saving = false
    @State private var error: String?
    @State private var success: String?

    var body: some View {
        Form {
            Section {
                Text("""
                    Ready-to-use YOLO models from minicam-models. Raspberry Pi 5: pick an n model \
                    (int8 is fastest). x86 PC: an s model. Installing verifies the file's checksum and activates it.
                    """)
                    .font(.caption).foregroundStyle(Theme.muted)
                if let catalogStatus {
                    Text(catalogStatus).font(.caption).foregroundStyle(Theme.muted)
                }
                if catalog == nil, catalogStatus == nil {
                    ForEach(0..<3, id: \.self) { SkeletonRow(index: $0, trailing: true) }
                }
                ForEach(catalog?.assets ?? []) { asset in catalogRow(asset) }
                if let catalogError { FormMessage(error: catalogError) }
            } header: {
                HStack {
                    Text("Model catalog")
                    Spacer()
                    if let release = catalog?.release, !release.isEmpty { Text("Release \(release)") }
                }
            }
            .adminRow()

            Section("AI detection") {
                if loaded {
                    Picker("Backend", selection: $backend) {
                        Text("None").tag(DetectionBackend.none)
                        Text("ONNX (CPU)").tag(DetectionBackend.onnx)
                    }
                    LabeledField(label: "Confidence threshold") {
                        TextField("0.5", text: $confidence).keyboardType(.decimalPad)
                    }
                    LabeledField(label: "Inference FPS") {
                        TextField("3", text: $fps).keyboardType(.decimalPad)
                    }
                    LabeledField(label: "Model path") {
                        TextField("/absolute/path/to/model.onnx", text: $modelPath).font(.body.monospaced())
                    }
                    LabeledField(label: "Or fetch from URL") {
                        HStack {
                            TextField("https://…/yolo11n-int8.onnx", text: $modelURL).keyboardType(.URL)
                            Button("Fetch") { Task { await fetch() } }
                                .buttonStyle(.bordered)
                                .disabled(fetching)
                        }
                    }
                    Text("1. Install a catalog model, or fetch one from a URL. 2. Set it as the active backend and save.")
                        .font(.caption).foregroundStyle(Theme.muted)
                } else if error == nil {
                    ForEach(0..<4, id: \.self) { FieldSkeletonRow(index: $0) }
                }
            }
            .adminRow()

            Section {
                FormMessage(error: error, success: success)
                if loaded { SaveButton(busy: saving) { Task { await save() } } }
            }
        }
        .adminForm()
        .navigationTitle("AI detection")
        .task {
            async let catalog: Void = loadCatalog()
            await loadSettings()
            await catalog
        }
    }

    private func catalogRow(_ asset: CatalogAsset) -> some View {
        HStack(spacing: 12) {
            // Remote manifest text: plain Text only (SECURITY.md).
            VStack(alignment: .leading, spacing: 2) {
                Text(asset.name).font(.subheadline.monospaced()).foregroundStyle(Theme.text).lineLimit(1)
                Text("\(asset.family) · \(asset.precision) · \(asset.profile) · \(String(format: "%.1f", Double(asset.bytes) / 1e6)) MB")
                    .font(.caption).foregroundStyle(Theme.muted)
            }
            Spacer()
            if asset.active {
                Badge(text: "Active", color: Theme.success)
            } else if installing == asset.name {
                ProgressView()
            } else {
                Button(asset.installed ? "Activate" : "Install & activate") { Task { await install(asset) } }
                    .buttonStyle(.bordered)
                    .font(.caption)
                    .disabled(installing != nil)
            }
        }
    }

    private func loadCatalog() async {
        guard let api = model.api else { return }
        if let result = await model.attempt({ catalogStatus = $0 }, { try await api.modelCatalog() }) {
            catalog = result
            catalogStatus = result.assets.isEmpty ? "The catalog is empty." : nil
        }
    }

    private func loadSettings() async {
        guard let api = model.api else { return }
        if let settings = await model.attempt({ error = $0 }, { try await api.detectionSettings() }) {
            populate(settings)
        }
    }

    private func populate(_ settings: DetectionSettings) {
        backend = settings.backend
        modelPath = settings.modelPath
        confidence = settings.confidenceThreshold.plain
        fps = settings.inferenceFPS.plain
        loaded = true
    }

    private func install(_ asset: CatalogAsset) async {
        guard let api = model.api else { return }
        installing = asset.name
        catalogError = nil
        defer { installing = nil }
        if await model.attempt({ catalogError = $0 }, { try await api.installCatalogModel(name: asset.name) }) != nil {
            await loadCatalog()
            await loadSettings()
        }
    }

    private func fetch() async {
        guard let api = model.api else { return }
        error = nil
        success = nil
        let url = modelURL.trimmingCharacters(in: .whitespaces)
        guard url.hasPrefix("https://") else {
            error = "Enter an https:// model URL first."
            return
        }
        fetching = true
        defer { fetching = false }
        if let path = await model.attempt({ error = $0 }, { try await api.fetchModel(url: url) }) {
            modelPath = path
            success = "Model downloaded. Tap Save to activate it."
        }
    }

    private func save() async {
        guard let api = model.api else { return }
        error = nil
        success = nil
        guard let threshold = Double(userInput: confidence), (0...1).contains(threshold) else {
            error = "Confidence threshold must be between 0 and 1."
            return
        }
        guard let inferenceFPS = Double(userInput: fps), inferenceFPS > 0 else {
            error = "Inference FPS must be greater than 0."
            return
        }
        let settings = DetectionSettings(
            backend: backend, modelPath: modelPath.trimmingCharacters(in: .whitespaces),
            confidenceThreshold: threshold, inferenceFPS: inferenceFPS
        )
        saving = true
        defer { saving = false }
        if let saved = await model.attempt({ error = $0 }, { try await api.saveDetectionSettings(settings) }) {
            populate(saved)
            success = "Settings saved."
            await loadCatalog()
        }
    }
}
