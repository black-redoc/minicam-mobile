import Foundation
import Testing
@testable import MinicamKit

/// docs/specs/020 AC1/AC2: the app language and the Spanish dictionary.
@Suite struct I18nTests {
    @Test func english_isTheTextItself() {
        #expect(translate("Recordings", spanish: false, []) == "Recordings")
        #expect(translate("Closes in %d s", spanish: false, ["5"]) == "Closes in 5 s")
    }

    @Test func spanish_translatesAndFillsArguments() {
        #expect(translate("Recordings", spanish: true, []) == "Grabaciones")
        #expect(translate("Closes in %d s", spanish: true, ["5"]) == "Se cierra en 5 s")
        #expect(translate("%1$s of %2$s", spanish: true, ["0:05", "0:30"]) == "0:05 de 0:30")
    }

    @Test func aTextWithoutTranslationStaysInEnglish() {
        #expect(translate("Not in the dictionary", spanish: true, []) == "Not in the dictionary")
    }

    @Test func fill_positionalAndSequentialPlaceholders() {
        #expect(fill("%2$s, %1$s", ["a", "b"]) == "b, a")
        #expect(fill("%s and %d", ["x", "3"]) == "x and 3")
        #expect(fill("%s", []) == "")
    }

    /// Every text the app and the kit pass to tr() has Spanish with the same placeholders.
    @Test func everyTextHasASpanishTranslation() throws {
        let root = URL(filePath: #filePath).deletingLastPathComponent().appending(path: "../../..")
        let sources = ["MinicamApp/Sources", "MinicamKit/Sources"].map { root.appending(path: $0) }
        let literal = #""((?:[^"\\]|\\.)*)""#
        let call = try Regex(#"(?:^|[^\w.])tr\(\s*((?:"# + literal + #"\s*\+\s*)*"# + literal + ")")
        let part = try Regex(literal)
        var keys = Set<String>()
        for directory in sources {
            let files = FileManager.default.enumerator(at: directory, includingPropertiesForKeys: nil)
            while let file = files?.nextObject() as? URL {
                guard file.pathExtension == "swift" else { continue }
                let text = try String(contentsOf: file, encoding: .utf8)
                for match in text.matches(of: call) {
                    let joined = text[match.range].matches(of: part).map { piece in
                        String(text[match.range][piece.range].dropFirst().dropLast())
                            .replacingOccurrences(of: #"\""#, with: "\"")
                            .replacingOccurrences(of: #"\\"#, with: "\\")
                    }.joined()
                    keys.insert(joined)
                }
            }
        }
        #expect(keys.count > 200)
        let missing = keys.filter { Spanish.texts[$0] == nil }.sorted()
        #expect(missing == [])
        for key in keys {
            let spanish = Spanish.texts[key] ?? key
            #expect(placeholders(key) == placeholders(spanish), "placeholders of \(key)")
        }
    }

    private func placeholders(_ text: String) -> [String] {
        text.matches(of: /%(?:\d\$)?[sd]/).map { String(text[$0.range]) }.sorted()
    }
}
