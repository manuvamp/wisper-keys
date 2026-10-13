import CoreGraphics

enum KCode { case char, shift, backspace, space, enter, symbols, symbols2, letters, mic, globe }

/// width is in tenths of the keyboard width; alts are the long-press choices (the first is drawn in the corner).
struct KKey {
    let code: KCode
    var label = ""
    var width: CGFloat = 1
    var alts: [String] = []
}

struct KLayout {
    let name: String
    let rows: [[KKey]]
    var letters = false
}

private func c(_ label: String, _ alts: String...) -> KKey { KKey(code: .char, label: label, alts: alts) }

private let periodAlts = ["?", "!", ",", "'", "\"", ":", ";", "-", "/", "@", "&", "%"]

/// The mic sits next to the space bar, where the thumb already is. iOS asks for a globe key when you have other
/// keyboards; it takes the comma's place (comma is on long-press of the period).
private func bottom(_ mode: KKey, globe: Bool) -> [KKey] {
    [mode, globe ? KKey(code: .globe) : c(","), KKey(code: .mic), KKey(code: .space, width: 3.75),
     KKey(code: .char, label: ".", alts: periodAlts), KKey(code: .enter, width: 2)]
}

enum Layouts {
    static func letters(globe: Bool) -> KLayout {
        KLayout(name: "letters", rows: [
            [c("q", "1"), c("w", "2"), c("e", "3", "é", "è", "ê", "ë", "ē"), c("r", "4"), c("t", "5"), c("y", "6", "ý", "ÿ"),
             c("u", "7", "ú", "ù", "û", "ü", "ū"), c("i", "8", "í", "ì", "î", "ï", "ī"), c("o", "9", "ó", "ò", "ô", "ö", "õ", "ø", "œ"), c("p", "0")],
            [c("a", "@", "á", "à", "â", "ä", "ã", "å", "æ"), c("s", "#", "ß", "ś", "š"), c("d", "$"), c("f", "_"), c("g", "&"),
             c("h", "-"), c("j", "+"), c("k", "("), c("l", ")")],
            [KKey(code: .shift, width: 1.5), c("z", "*"), c("x", "\""), c("c", "'", "ç", "ć", "č"), c("v", ":"), c("b", ";"),
             c("n", "!", "ñ", "ń"), c("m", "?"), KKey(code: .backspace, width: 1.5)],
            bottom(KKey(code: .symbols, label: "123", width: 1.25), globe: globe),
        ], letters: true)
    }

    static func symbols(globe: Bool) -> KLayout {
        KLayout(name: "symbols", rows: [
            [c("1", "¹", "½", "⅓", "¼"), c("2", "²", "⅔"), c("3", "³", "¾"), c("4", "⁴"), c("5"), c("6"), c("7"), c("8"), c("9"), c("0", "ⁿ", "∅")],
            [c("@"), c("#", "№"), c("$", "€", "£", "¥", "¢", "₹"), c("_"), c("&"), c("-", "–", "—", "·"), c("+", "±"),
             c("(", "<", "[", "{"), c(")", ">", "]", "}"), c("/", "\\")],
            [KKey(code: .symbols2, label: "#+=", width: 1.5), c("*", "†", "‡", "★"), c("\"", "“", "”", "„", "«", "»"),
             c("'", "‘", "’", "‚", "‹", "›"), c(":"), c(";"), c("!", "¡"), c("?", "¿"), KKey(code: .backspace, width: 1.5)],
            bottom(KKey(code: .letters, label: "ABC", width: 1.25), globe: globe),
        ])
    }

    static func symbols2(globe: Bool) -> KLayout {
        KLayout(name: "symbols2", rows: [
            [c("~"), c("`"), c("|"), c("•", "♪", "♥", "♠", "♦", "♣"), c("√"), c("π", "Π"), c("÷"), c("×"), c("§", "¶"), c("∆")],
            [c("£"), c("¢"), c("€"), c("¥"), c("^", "←", "↑", "↓", "→"), c("°", "′", "″"), c("=", "≠", "≈", "∞"), c("{"), c("}"), c("\\")],
            [KKey(code: .symbols, label: "123", width: 1.5), c("%", "‰"), c("©"), c("®"), c("™"), c("✓"), c("["), c("]"),
             KKey(code: .backspace, width: 1.5)],
            bottom(KKey(code: .letters, label: "ABC", width: 1.25), globe: globe),
        ])
    }

    private static func pad(_ labels: [String]) -> [KKey] {
        labels.map {
            switch $0 {
            case "⌫": return KKey(code: .backspace, width: 2.5)
            case "⏎": return KKey(code: .enter, width: 2.5)
            case "␣": return KKey(code: .space, width: 2.5)
            case "🌐": return KKey(code: .globe, width: 2.5)
            default: return KKey(code: .char, label: $0, width: 2.5)
            }
        }
    }

    static func number(globe: Bool) -> KLayout {
        KLayout(name: "number", rows: [pad(["1", "2", "3", "-"]), pad(["4", "5", "6", "␣"]), pad(["7", "8", "9", "⌫"]),
                                       pad([globe ? "🌐" : ",", "0", ".", "⏎"])])
    }

    static func phone(globe: Bool) -> KLayout {
        KLayout(name: "phone", rows: [pad(["1", "2", "3", "-"]), pad(["4", "5", "6", "+"]), pad(["7", "8", "9", "⌫"]),
                                      pad([globe ? "🌐" : "*", "0", "#", "⏎"])])
    }
}
