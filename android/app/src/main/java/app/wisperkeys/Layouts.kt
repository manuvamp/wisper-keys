package app.wisperkeys

/** What a key does. CHAR keys type their label (shifted by the IME when Shift is on). */
enum class Code { CHAR, SHIFT, BACKSPACE, SPACE, ENTER, SYMBOLS, SYMBOLS2, LETTERS, MIC }

/**
 * @param width in tenths of the keyboard width (a letter key is 1).
 * @param alts long-press choices; the first one is also drawn small in the key's corner (Gboard-style hints).
 */
class Key(val code: Code, val label: String = "", val width: Float = 1f, val alts: List<String> = emptyList())

class Row(val keys: List<Key>)

class Layout(val name: String, val rows: List<Row>, val letters: Boolean = false)

private fun c(label: String, vararg alts: String) = Key(Code.CHAR, label, 1f, alts.toList())
private fun row(vararg keys: Key) = Row(keys.toList())

private val PERIOD_ALTS = arrayOf("?", "!", ",", "'", "\"", ":", ";", "-", "/", "@", "&", "%")

/** Shared bottom row: the mic sits next to the space bar, where the thumb already is. */
private fun bottom(mode: Key) = row(
    mode, c(","), Key(Code.MIC), Key(Code.SPACE, width = 4f), c(".", *PERIOD_ALTS), Key(Code.ENTER, width = 1.5f),
)

object Layouts {
    val letters = Layout(
        "letters", listOf(
            row(
                c("q", "1"), c("w", "2"), c("e", "3", "é", "è", "ê", "ë", "ē"), c("r", "4"), c("t", "5"),
                c("y", "6", "ý", "ÿ"), c("u", "7", "ú", "ù", "û", "ü", "ū"), c("i", "8", "í", "ì", "î", "ï", "ī"),
                c("o", "9", "ó", "ò", "ô", "ö", "õ", "ø", "œ"), c("p", "0"),
            ),
            row(
                c("a", "@", "á", "à", "â", "ä", "ã", "å", "æ"), c("s", "#", "ß", "ś", "š"), c("d", "$"), c("f", "_"),
                c("g", "&"), c("h", "-"), c("j", "+"), c("k", "("), c("l", ")"),
            ),
            row(
                Key(Code.SHIFT, width = 1.5f), c("z", "*"), c("x", "\""), c("c", "'", "ç", "ć", "č"), c("v", ":"),
                c("b", ";"), c("n", "!", "ñ", "ń"), c("m", "?"), Key(Code.BACKSPACE, width = 1.5f),
            ),
            bottom(Key(Code.SYMBOLS, "?123", 1.5f)),
        ), letters = true,
    )

    val symbols = Layout(
        "symbols", listOf(
            row(
                c("1", "¹", "½", "⅓", "¼"), c("2", "²", "⅔"), c("3", "³", "¾"), c("4", "⁴"), c("5"), c("6"), c("7"),
                c("8"), c("9"), c("0", "ⁿ", "∅"),
            ),
            row(
                c("@"), c("#", "№"), c("$", "€", "£", "¥", "¢", "₹"), c("_"), c("&"), c("-", "–", "—", "·"),
                c("+", "±"), c("(", "<", "[", "{"), c(")", ">", "]", "}"), c("/", "\\"),
            ),
            row(
                Key(Code.SYMBOLS2, "=\\<", 1.5f), c("*", "†", "‡", "★"), c("\"", "“", "”", "„", "«", "»"),
                c("'", "‘", "’", "‚", "‹", "›"), c(":"), c(";"), c("!", "¡"), c("?", "¿"),
                Key(Code.BACKSPACE, width = 1.5f),
            ),
            bottom(Key(Code.LETTERS, "ABC", 1.5f)),
        ),
    )

    val symbols2 = Layout(
        "symbols2", listOf(
            row(c("~"), c("`"), c("|"), c("•", "♪", "♥", "♠", "♦", "♣"), c("√"), c("π", "Π"), c("÷"), c("×"), c("§", "¶"), c("∆")),
            row(c("£"), c("¢"), c("€"), c("¥"), c("^", "←", "↑", "↓", "→"), c("°", "′", "″"), c("=", "≠", "≈", "∞"), c("{"), c("}"), c("\\")),
            row(
                Key(Code.SYMBOLS, "?123", 1.5f), c("%", "‰"), c("©"), c("®"), c("™"), c("✓"), c("["), c("]"),
                Key(Code.BACKSPACE, width = 1.5f),
            ),
            bottom(Key(Code.LETTERS, "ABC", 1.5f)),
        ),
    )

    private fun pad(vararg labels: String) = Row(labels.map {
        when (it) {
            "⌫" -> Key(Code.BACKSPACE, width = 2.5f)
            "⏎" -> Key(Code.ENTER, width = 2.5f)
            "␣" -> Key(Code.SPACE, width = 2.5f)
            else -> Key(Code.CHAR, it, 2.5f)
        }
    })

    /** Number and phone fields get a big numpad, like the system keyboards. */
    val number = Layout("number", listOf(pad("1", "2", "3", "-"), pad("4", "5", "6", "␣"), pad("7", "8", "9", "⌫"), pad(",", "0", ".", "⏎")))
    val phone = Layout("phone", listOf(pad("1", "2", "3", "-"), pad("4", "5", "6", "+"), pad("7", "8", "9", "⌫"), pad("*", "0", "#", "⏎")))
}
