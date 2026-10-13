package app.wisperkeys

import android.content.Context
import android.net.Uri

/** Pairing and settings, shared by the keyboard and the setup screen. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("wisperkeys", Context.MODE_PRIVATE)

    /** ws://host:port addresses of the PC, tried together; the first to answer is used (home Wi-Fi, then Tailscale). */
    var urls: List<String>
        get() = (p.getString("urls", "") ?: "").split('\n').filter { it.isNotBlank() }
        set(v) = p.edit().putString("urls", v.joinToString("\n")).apply()
    var token: String
        get() = p.getString("token", "") ?: ""
        set(v) = p.edit().putString("token", v).apply()
    /** Where the PC publishes its current away-from-home address (a GitHub gist API URL). */
    var rendezvous: String
        get() = p.getString("rendezvous", "") ?: ""
        set(v) = p.edit().putString("rendezvous", v).apply()
    var pcName: String
        get() = p.getString("pc", "") ?: ""
        set(v) = p.edit().putString("pc", v).apply()
    var keyHeight: Int
        get() = p.getInt("keyHeight", 54)
        set(v) = p.edit().putInt("keyHeight", v).apply()
    var haptics: Boolean
        get() = p.getBoolean("haptics", true)
        set(v) = p.edit().putBoolean("haptics", v).apply()
    var sound: Boolean
        get() = p.getBoolean("sound", false)
        set(v) = p.edit().putBoolean("sound", v).apply()
    var hints: Boolean
        get() = p.getBoolean("hints", true)
        set(v) = p.edit().putBoolean("hints", v).apply()
    var recentEmoji: List<String>
        get() = (p.getString("recentEmoji", "") ?: "").split(' ').filter { it.isNotBlank() }
        set(v) = p.edit().putString("recentEmoji", v.take(32).joinToString(" ")).apply()

    val paired get() = urls.isNotEmpty() && token.isNotEmpty()

    /** wisperkeys://pair?u=ws://192.168.0.10:47822&u=ws://100.101.102.103:47822&t=TOKEN&n=PC-NAME (from tools/pair.py) */
    fun applyPairLink(link: String): Boolean {
        val uri = Uri.parse(link.trim())
        if (uri.scheme != "wisperkeys" || uri.host != "pair") return false
        val u = uri.getQueryParameters("u").filter { it.startsWith("ws://") || it.startsWith("wss://") }
        val t = uri.getQueryParameter("t") ?: return false
        if (u.isEmpty() || t.isBlank()) return false
        urls = u
        token = t
        pcName = uri.getQueryParameter("n") ?: ""
        rendezvous = uri.getQueryParameter("r")?.takeIf { it.startsWith("https://") } ?: ""
        return true
    }
}
