import SwiftUI

/// Design tokens mirrored from minicam `app/static/css/main.css`
/// (docs/ARCHITECTURE.md "Theme").
enum Theme {
    static let bg = Color(hex: 0x0F172A)
    static let surface = Color(hex: 0x1E293B)
    static let surface2 = Color(hex: 0x263348)
    static let border = Color(hex: 0x334155)
    static let accent = Color(hex: 0x0EA5E9)
    static let accentHover = Color(hex: 0x38BDF8)
    static let danger = Color(hex: 0xEF4444)
    static let success = Color(hex: 0x22C55E)
    static let text = Color(hex: 0xF8FAFC)
    static let muted = Color(hex: 0x94A3B8)
    /// `text-slate-950` on the sky primary button.
    static let onAccent = Color(hex: 0x020617)

    static let radiusSm: CGFloat = 6
    static let radius: CGFloat = 8
    static let radiusLg: CGFloat = 12
    static let ptzSize: CGFloat = 56
}

extension Color {
    init(hex: UInt32) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }
}

/// `.card` from main.css.
struct CardModifier: ViewModifier {
    func body(content: Content) -> some View {
        content
            .padding(16)
            .background(Theme.surface)
            .overlay(RoundedRectangle(cornerRadius: Theme.radiusLg).stroke(Theme.border))
            .clipShape(RoundedRectangle(cornerRadius: Theme.radiusLg))
    }
}

/// `.input-cfg` from main.css.
struct InputModifier: ViewModifier {
    func body(content: Content) -> some View {
        content
            .padding(.horizontal, 12)
            .padding(.vertical, 10)
            .foregroundStyle(Theme.text)
            .background(Theme.bg)
            .overlay(RoundedRectangle(cornerRadius: Theme.radius).stroke(Theme.border))
    }
}

struct PrimaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.semibold))
            .frame(maxWidth: .infinity, minHeight: 44)
            .foregroundStyle(Theme.onAccent)
            .background(configuration.isPressed ? Theme.accentHover : Theme.accent)
            .clipShape(RoundedRectangle(cornerRadius: Theme.radius))
    }
}

extension View {
    func card() -> some View { modifier(CardModifier()) }
    func minicamInput() -> some View { modifier(InputModifier()) }
}

/// Brand mark from the desktop header: glowing sky dot + "minicam".
struct BrandTitle: View {
    @State private var glow = false

    var body: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(Theme.accentHover)
                .frame(width: 8, height: 8)
                .shadow(color: Theme.accentHover.opacity(glow ? 0.15 : 1), radius: glow ? 1 : 6)
                .animation(.easeInOut(duration: 1.2).repeatForever(), value: glow)
                .onAppear { glow = true }
            Text("minicam")
                .font(.headline.weight(.bold))
                .tracking(-0.3)
                .foregroundStyle(Theme.accentHover)
        }
    }
}
