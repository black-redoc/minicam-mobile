import SwiftUI

/// A secure field with an eye button that shows what was typed (like the Android fields).
struct PasswordField: View {
    let placeholder: String
    @Binding var text: String
    var contentType: UITextContentType? = .password
    /// UI tests find the text input itself by this identifier.
    var identifier: String?
    @State private var revealed = false

    var body: some View {
        HStack(spacing: 8) {
            Group {
                if revealed {
                    TextField(placeholder, text: $text)
                } else {
                    SecureField(placeholder, text: $text)
                }
            }
            .textContentType(contentType)
            .accessibilityIdentifier(identifier ?? "")
            .textInputAutocapitalization(.never)
            .autocorrectionDisabled()
            Button {
                revealed.toggle()
            } label: {
                Image(systemName: revealed ? "eye.slash" : "eye")
                    .foregroundStyle(Theme.muted)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(revealed ? "Hide password" : "Show password")
        }
    }
}
