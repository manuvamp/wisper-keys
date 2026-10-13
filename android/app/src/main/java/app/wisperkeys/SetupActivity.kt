package app.wisperkeys

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

/** Setup in four steps (turn on, switch to, microphone, pair with the PC), the settings, and a box to try it. */
class SetupActivity : Activity(), VoiceClient.Listener {

    private lateinit var prefs: Prefs
    private val test by lazy {
        VoiceClient(this, { tok, found -> PcFinder(this).find(tok, onFound = found) }, { prefs.urls = it })
    }
    private lateinit var stepOn: Step
    private lateinit var stepUse: Step
    private lateinit var stepMic: Step
    private lateinit var stepPair: Step
    private lateinit var manual: LinearLayout
    private lateinit var urlsField: EditText
    private lateinit var tokenField: EditText

    private inner class Step(val title: TextView, val detail: TextView, val action: TextView) {
        fun set(done: Boolean, detailText: String, actionText: String?) {
            title.setCompoundDrawablesRelativeWithIntrinsicBounds(dot(if (done) Palette.OK else Palette.TEXT_FAINT), null, null, null)
            detail.text = detailText
            action.visibility = if (actionText == null) View.GONE else View.VISIBLE
            if (actionText != null) action.text = actionText
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
        }
        val scroll = ScrollView(this).apply { setBackgroundColor(Palette.BG); addView(col) }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(0, b.top, 0, b.bottom)
            } else @Suppress("DEPRECATION") v.setPadding(0, insets.systemWindowInsetTop, 0, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(scroll)

        col.addView(text("wisper keys", 26f, Palette.TEXT, bold = true))
        col.addView(text("Your PC's voice typing, on your phone. Hold or tap the mic key; your PC transcribes it.",
            14f, Palette.TEXT_DIM).apply { setPadding(0, dp(4), 0, dp(14)) })

        stepOn = step(col, "1. Turn on the keyboard") { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) }
        stepUse = step(col, "2. Switch to it") { getSystemService(InputMethodManager::class.java).showInputMethodPicker() }
        stepMic = step(col, "3. Allow the microphone") { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) }
        stepPair = step(col, "4. Pair with your PC") { scan() }

        manual = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        urlsField = field("ws://192.168.0.10:47822   (one address per line)", multi = true)
        tokenField = field("token", multi = false)
        manual.addView(text("Addresses", 13f, Palette.TEXT_DIM))
        manual.addView(urlsField)
        manual.addView(text("Token", 13f, Palette.TEXT_DIM).apply { setPadding(0, dp(8), 0, 0) })
        manual.addView(tokenField)
        manual.addView(button("Save") {
            val urls = urlsField.text.toString().split('\n', ' ', ',').map { it.trim() }.filter { it.startsWith("ws://") || it.startsWith("wss://") }
            if (urls.isEmpty() || tokenField.text.isBlank()) return@button toast("Add at least one ws:// address and the token")
            prefs.urls = urls
            prefs.token = tokenField.text.toString().trim()
            manual.visibility = View.GONE
            refresh()
        }.apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(10) })
        (stepPair.action.parent as LinearLayout).apply {
            addView(link("Enter it by hand") {
                manual.visibility = if (manual.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                urlsField.setText(prefs.urls.joinToString("\n"))
                tokenField.setText(prefs.token)
            })
            addView(manual)
        }

        col.addView(text("Settings", 18f, Palette.TEXT, bold = true).apply { setPadding(0, dp(18), 0, dp(8)) })
        val settings = card(col)
        settings.addView(text("Key height", 15f, Palette.TEXT))
        val sizes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, dp(10)) }
        val choices = listOf("Compact" to 48, "Normal" to 54, "Tall" to 60)
        val chips = choices.map { (name, h) ->
            text(name, 14f, Palette.TEXT).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener { prefs.keyHeight = h; paintChips(sizes, choices.indexOfFirst { it.second == h }) }
            }
        }
        chips.forEach { sizes.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(6) }) }
        paintChips(sizes, choices.indexOfFirst { it.second == prefs.keyHeight }.coerceAtLeast(1))
        settings.addView(sizes)
        settings.addView(toggle("Vibrate on keypress", prefs.haptics) { prefs.haptics = it })
        settings.addView(toggle("Key sounds", prefs.sound) { prefs.sound = it })
        settings.addView(toggle("Show numbers and symbols on keys (long-press)", prefs.hints) { prefs.hints = it })

        col.addView(text("Try it", 18f, Palette.TEXT, bold = true).apply { setPadding(0, dp(18), 0, dp(8)) })
        col.addView(field("Type or dictate here…", multi = true).apply {
            minLines = 3
            val reveal = { postDelayed({ scroll.smoothScrollTo(0, bottom) }, 350) }   // once the keyboard is up
            setOnFocusChangeListener { _, has -> if (has) reveal() }
            setOnClickListener { reveal() }
        })
        col.addView(text("Tips: swipe on the space bar to move the cursor, swipe left from backspace to delete whole " +
            "words, long-press a key for numbers and accents, long-press comma for emoji, long-press space to switch " +
            "keyboards. Dictating needs your PC on with wisper running; away from home, use Tailscale.",
            13f, Palette.TEXT_DIM).apply { setPadding(0, dp(12), 0, 0) })

        handleLink(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    private fun handleLink(intent: Intent?) {
        val data = intent?.dataString ?: return
        pair(data)
    }

    private fun pair(link: String) {
        if (prefs.applyPairLink(link)) {
            toast("Paired with ${prefs.pcName.ifEmpty { "your PC" }}")
            refresh()
        } else toast("That isn't a wisper pairing code")
    }

    private fun scan() {
        val opts = GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        GmsBarcodeScanning.getClient(this, opts).startScan()
            .addOnSuccessListener { pair(it.rawValue ?: "") }
            .addOnFailureListener { toast("Couldn't open the scanner: enter it by hand instead") }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        test.close()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refresh()   // back from the keyboard picker
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun refresh() {
        val imm = getSystemService(InputMethodManager::class.java)
        val enabled = imm.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.startsWith("$packageName/") == true
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        stepOn.set(enabled, if (enabled) "On." else "Turn on \"wisper keys\" in your keyboard list.", if (enabled) null else "Open keyboard settings")
        stepUse.set(current, if (current) "It's your keyboard now." else "Pick \"wisper keys\" as the keyboard to use.", if (current) null else "Choose keyboard")
        stepMic.set(mic, if (mic) "Allowed." else "Needed to hear you when you press the mic key.", if (mic) null else "Allow microphone")
        if (prefs.paired) {
            test.configure(prefs.urls, prefs.token, prefs.rendezvous)
            test.connect()
        } else onStatus(VoiceClient.Status.NOT_PAIRED, "")
    }

    override fun onStatus(status: VoiceClient.Status, detail: String) {
        if (!::stepPair.isInitialized) return
        val pc = prefs.pcName.ifEmpty { "your PC" }
        when (status) {
            VoiceClient.Status.READY -> stepPair.set(true, "Connected to $pc${if (detail.isNotEmpty()) " · $detail" else ""}.", "Scan a new code")
            VoiceClient.Status.CONNECTING -> stepPair.set(false, "Connecting to $pc…", "Scan pairing code")
            VoiceClient.Status.OFFLINE -> stepPair.set(false, "Paired with $pc, but it isn't answering (${detail.ifEmpty { "offline" }}). " +
                "Is the PC on, wisper running and the phone on the same Wi-Fi (or Tailscale)?", "Scan pairing code")
            VoiceClient.Status.NOT_PAIRED -> stepPair.set(false, "On your PC run  python -I tools\\pair.py  in the wisper folder, " +
                "then scan the code it shows.", "Scan pairing code")
        }
    }

    override fun onPartial(id: Int, text: String) {}
    override fun onFinal(id: Int, text: String) {}
    override fun onFailed(id: Int, reason: String) {}

    // ---------------------------------------------------------------- small UI helpers (no UI library needed)
    private fun dot(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color); setSize(dp(10), dp(10))
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun card(parent: LinearLayout) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = GradientDrawable().apply { setColor(Palette.CARD); cornerRadius = dp(14).toFloat() }
        parent.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
    }

    private fun button(label: String, onTap: () -> Unit) = text(label, 15f, Palette.ON_ACCENT, bold = true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(11), dp(16), dp(11))
        background = GradientDrawable().apply { setColor(Palette.ACCENT); cornerRadius = dp(10).toFloat() }
        setOnClickListener { onTap() }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
    }

    private fun link(label: String, onTap: () -> Unit) = text(label, 14f, Palette.ACCENT_LIGHT).apply {
        setPadding(0, dp(12), 0, dp(4))
        setOnClickListener { onTap() }
    }

    private fun step(parent: LinearLayout, title: String, onTap: () -> Unit): Step {
        val c = card(parent)
        val t = text(title, 16f, Palette.TEXT, bold = true).apply { compoundDrawablePadding = dp(10) }
        val d = text("", 14f, Palette.TEXT_DIM).apply { setPadding(0, dp(4), 0, 0) }
        val a = button("", onTap)
        c.addView(t)
        c.addView(d)
        c.addView(a)
        return Step(t, d, a)
    }

    private fun field(hint: String, multi: Boolean) = EditText(this).apply {
        this.hint = hint
        setHintTextColor(Palette.TEXT_FAINT)
        setTextColor(Palette.TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        inputType = if (multi) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply { setColor(Palette.CARD); cornerRadius = dp(10).toFloat(); setStroke(dp(1), Palette.KEY) }
    }

    private fun toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        setTextColor(Palette.TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        isChecked = on
        setPadding(0, dp(8), 0, dp(8))
        setOnCheckedChangeListener { _, v -> onChange(v) }
    }

    private fun paintChips(row: LinearLayout, selected: Int) {
        for (i in 0 until row.childCount) {
            row.getChildAt(i).background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(if (i == selected) Palette.ACCENT else Palette.KEY)
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
