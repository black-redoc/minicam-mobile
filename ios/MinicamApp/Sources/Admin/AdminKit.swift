import MinicamKit
import SwiftUI

/// Admin-only sections reached from the camera list menu (ADR 0004).
enum AdminRoute: Hashable {
    case cameraSettings, aiDetection, admin, remoteAccess
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

private let skeletonWidths: [CGFloat] = [140, 104, 124, 88]

/// Form row placeholder: title and subtitle lines, with an optional button-sized block
/// (spec 007 AC3).
struct SkeletonRow: View {
    var index = 0
    var trailing = false

    var body: some View {
        HStack(spacing: 12) {
            VStack(alignment: .leading, spacing: 6) {
                SkeletonBlock(width: skeletonWidths[index % skeletonWidths.count], height: 15)
                SkeletonBlock(width: 160, height: 11)
            }
            Spacer()
            if trailing { SkeletonBlock(width: 64, height: 30, cornerRadius: Theme.radius) }
        }
        .skeletonGroup()
    }
}

/// Placeholder for a `Toggle` row (spec 007 AC3).
struct ToggleSkeletonRow: View {
    var index = 0

    var body: some View {
        HStack {
            SkeletonBlock(width: skeletonWidths[index % skeletonWidths.count] + 40, height: 14)
            Spacer()
            SkeletonBlock(width: 51, height: 31, cornerRadius: 16)
        }
        .skeletonGroup()
    }
}

/// Placeholder for a `LabeledField` row (spec 007 AC3).
struct FieldSkeletonRow: View {
    var index = 0

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            SkeletonBlock(width: skeletonWidths[index % skeletonWidths.count], height: 11)
            SkeletonBlock(width: nil, height: 36, cornerRadius: Theme.radius)
        }
        .skeletonGroup()
    }
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
