import Foundation

// App language (docs/adr/0017): the English text in the code is the key, and the Spanish
// dictionary (Spanish.swift, the same keys as Android) holds its translation. A key with no
// entry shows in English.

/// The user's choice in Settings; `.system` follows the phone's language.
public enum AppLanguage: String, CaseIterable, Sendable {
    case system
    case english = "en"
    case spanish = "es"
}

public enum I18n {
    private static let defaultsKey = "minicam.language"
    private static let lock = NSLock()
    nonisolated(unsafe) private static var stored: AppLanguage?

    /// The saved choice (`.system` until one is picked).
    public static var language: AppLanguage {
        get {
            lock.lock()
            defer { lock.unlock() }
            if let stored { return stored }
            let saved = UserDefaults.standard.string(forKey: defaultsKey).flatMap(AppLanguage.init(rawValue:))
            stored = saved ?? .system
            return stored ?? .system
        }
        set {
            lock.lock()
            stored = newValue
            lock.unlock()
            UserDefaults.standard.set(newValue.rawValue, forKey: defaultsKey)
        }
    }

    /// The locale texts and formats use now.
    public static var locale: Locale {
        switch language {
        // The best of the app's own localizations (Info.plist CFBundleLocalizations) for the
        // phone's languages, so iOS's per-app language setting applies too.
        case .system: Locale(identifier: Bundle.main.preferredLocalizations.first ?? "en")
        case .english: Locale(identifier: "en")
        case .spanish: Locale(identifier: "es")
        }
    }

    public static var isSpanish: Bool { locale.language.languageCode?.identifier == "es" }

}

/// `text` in the app language. With `args`, `text` is a pattern with `%s` / `%d` placeholders
/// (or `%1$s`, `%2$d` …), filled after translating so a translation can reorder them. The keys
/// are the same as Android's.
public func tr(_ text: String, _ args: any CustomStringConvertible...) -> String {
    translate(text, spanish: I18n.isSpanish, args.map(\.description))
}

func translate(_ text: String, spanish: Bool, _ args: [String]) -> String {
    let pattern = spanish ? (Spanish.texts[text] ?? text) : text
    return args.isEmpty ? pattern : fill(pattern, args)
}

func fill(_ pattern: String, _ args: [String]) -> String {
    var next = 0
    return pattern.replacing(/%(?:(\d)\$)?[sd]/) { match in
        let index: Int
        if let position = match.output.1, let number = Int(position) {
            index = number - 1
        } else {
            index = next
            next += 1
        }
        return args.indices.contains(index) ? args[index] : ""
    }
}
