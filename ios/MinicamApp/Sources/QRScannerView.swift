import AVFoundation
import SwiftUI

/// Full-screen QR scanner (docs/adr/0012): AVFoundation, QR only, decoded on the phone, no network.
/// Asks for the camera the first time; calls `onResult` once with the decoded text.
struct QRScannerSheet: View {
    let onResult: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var access = AVCaptureDevice.authorizationStatus(for: .video)

    var body: some View {
        ZStack(alignment: .bottom) {
            Theme.bg.ignoresSafeArea()
            switch access {
            case .authorized:
                QRCameraView { text in
                    onResult(text)
                    dismiss()
                }
                .ignoresSafeArea()
            case .notDetermined:
                ProgressView()
            default:
                Text("minicam needs the camera only to read the dashboard's QR. Allow it in Settings → minicam → Camera, or type the code instead.")
                    .font(.callout).foregroundStyle(Theme.text).padding(24)
                    .frame(maxHeight: .infinity)
            }
            VStack(spacing: 12) {
                if access == .authorized {
                    Text("Point the camera at the QR in the dashboard").font(.callout).foregroundStyle(Theme.text)
                }
                Button("Cancel") { dismiss() }.buttonStyle(.bordered)
            }
            .padding(24)
        }
        .task {
            if access == .notDetermined {
                _ = await AVCaptureDevice.requestAccess(for: .video)
                access = AVCaptureDevice.authorizationStatus(for: .video)
            }
        }
    }
}

private struct QRCameraView: UIViewControllerRepresentable {
    let onResult: (String) -> Void

    func makeUIViewController(context: Context) -> QRCameraController {
        let controller = QRCameraController()
        controller.onResult = onResult
        return controller
    }

    func updateUIViewController(_ controller: QRCameraController, context: Context) {}
}

/// Starts and stops the capture session on its own serial queue, as AVFoundation recommends;
/// the session is only touched there or before it first runs.
private final class CaptureRunner: @unchecked Sendable {
    let session = AVCaptureSession()
    private let queue = DispatchQueue(label: "dev.minicam.qr-scanner")

    func start() { queue.async { self.session.startRunning() } }
    func stop() { queue.async { self.session.stopRunning() } }
}

final class QRCameraController: UIViewController {
    var onResult: ((String) -> Void)?
    private let runner = CaptureRunner()
    private var session: AVCaptureSession { runner.session }
    private var preview: AVCaptureVideoPreviewLayer?
    private var done = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        guard let camera = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: camera), session.canAddInput(input)
        else { return }
        session.addInput(input)
        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else { return }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        output.metadataObjectTypes = [.qr]
        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        view.layer.addSublayer(layer)
        preview = layer
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview?.frame = view.bounds
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        runner.start()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        runner.stop()
    }
}

// The delegate queue is `.main`, so callbacks arrive on the main actor.
extension QRCameraController: @preconcurrency AVCaptureMetadataOutputObjectsDelegate {
    func metadataOutput(
        _ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection
    ) {
        guard !done,
              let code = metadataObjects.compactMap({ $0 as? AVMetadataMachineReadableCodeObject }).first,
              let text = code.stringValue
        else { return }
        done = true
        runner.stop()
        onResult?(text)
    }
}
