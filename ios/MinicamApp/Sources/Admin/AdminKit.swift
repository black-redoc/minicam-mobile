import MinicamKit
import SwiftUI

/// Admin-only sections reached from the camera list menu (ADR 0004).
enum AdminRoute: Hashable {
    case cameraSettings, aiDetection, admin
}

/// Pushes the add (`camera == nil`) or edit form from Camera settings.
struct CameraFormRoute: Hashable {
    let camera: CameraConfig?
}

extension View {
    /// A `Form` styled with the desktop tokens: dark background, `surface` rows.
    func adminForm() -> some View {
        scrollContentBackground(.hidden)
            .background(Theme.bg.ignoresSafeArea())
            .autocorrectionDisabled()
            .textInputAutocapitalization(.never)
            .navigationBarTitleDisplayMode(.inline)
    }

    func adminRow() -> some View { listRowBackground(Theme.surface) }
}

/// Error (red) or success (green) line under a form, like the desktop's `role=alert` paragraphs.
struct FormMessage: View {
    var error: String?
    var success: String?

    var body: some View {
        if let error {
            Text(error).font(.caption).foregroundStyle(Theme.danger)
        } else if let success {
            Text(success).font(.caption).foregroundStyle(Theme.success)
        }
    }
}

/// A labeled text field row: muted label above, like the desktop forms.
struct LabeledField<Field: View>: View {
    let label: String
    @ViewBuilder var field: Field

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(.caption).foregroundStyle(Theme.muted)
            field.foregroundStyle(Theme.text)
        }
    }
}

/// `.badge`: small rounded label (admin, active).
struct Badge: View {
    let text: String
    var color: Color = Theme.accentHover

    var body: some View {
        Text(text)
            .font(.caption2.weight(.medium))
            .foregroundStyle(color)
            .padding(.horizontal, 8)
            .padding(.vertical, 2)
            .background(color.opacity(0.1), in: Capsule())
            .overlay(Capsule().stroke(color.opacity(0.3)))
    }
}

/// A primary action row: full-width sky button with a spinner while busy.
struct SaveButton: View {
    var label = "Save"
    let busy: Bool
    var enabled = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            if busy { ProgressView().tint(Theme.onAccent) } else { Text(label) }
        }
        .buttonStyle(PrimaryButtonStyle())
        .disabled(busy || !enabled)
        .listRowBackground(Color.clear)
        .listRowInsets(EdgeInsets())
    }
}

extension Double {
    /// `0.5` → "0.5", `3.0` → "3": the number as the desktop's `<input type=number>` shows it.
    var plain: String {
        truncatingRemainder(dividingBy: 1) == 0 ? String(Int(self)) : String(self)
    }

    /// Parses user input, accepting a decimal comma.
    init?(userInput: String) {
        self.init(userInput.trimmingCharacters(in: .whitespaces).replacingOccurrences(of: ",", with: "."))
    }
}
