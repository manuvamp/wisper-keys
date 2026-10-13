package app.wisperkeys

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * The keyboard. Typing works like a normal phone keyboard; the mic key streams your voice to wisper on your PC and
 * types what comes back. Tap the mic for hands-free (tap again or ✓ to finish), or hold it and let go when done.
 */
class WisperIME : InputMethodService(), KeyboardView.Listener, VoiceClient.Listener, StripView.Listener,
    EmojiPanel.Listener {

    private lateinit var prefs: Prefs
    private lateinit var kb: KeyboardView
    private lateinit var strip: StripView
    private lateinit var frame: FrameLayout
    private var emoji: EmojiPanel? = null
    private val ui = Handler(Looper.getMainLooper())
    private val voice by lazy {
        VoiceClient(this, { tok, found -> PcFinder(this).find(tok, onFound = found) }, { prefs.urls = it })
    }
    private val recorder = MicRecorder({ b, n -> voice.audio(dictId, b, n) }, { level -> ui.post { strip.level(level) } })
    private var vibrator: Vibrator? = null
    private var haptics = true
    private var sound = false

    // the field being typed in
    private var info: EditorInfo? = null
    private var enterAction = EditorInfo.IME_ACTION_NONE
    private var selStart = -1
    private var swiping = false
    private var swipeAnchor = -1
    private var swipeText = ""
    private var autoSpace = false        // we added a space after dictated text: punctuation may replace it
    private var lastWasSpace = false
    private var lastShiftTap = 0L

    // dictation
    private enum class Dict { IDLE, RECORDING, BUSY }
    private var dict = Dict.IDLE
    @Volatile private var dictId = 0
    private val cancelled = HashSet<Int>()
    private var micDownAt = 0L
    private var startedOnThisPress = false
    private var partial = ""
    private var lastInsert: String? = null
    private val hideUndo = Runnable { lastInsert = null; if (::strip.isInitialized) strip.setUndo(false) }
    private val idleStrip = Runnable { refreshStrip() }

    /** Wi-Fi <-> mobile data: reconnect right away on the best path (the first callback is the current network). */
    private val netWatch = object : ConnectivityManager.NetworkCallback() {
        private var current: Network? = null
        override fun onAvailable(network: Network) {
            val changed = current != null && current != network
            current = network
            if (changed) ui.post { voice.networkChanged() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        vibrator = getSystemService(Vibrator::class.java)
        try { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(netWatch) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(netWatch) } catch (_: Exception) {}
        voice.close()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        kb = KeyboardView(this, this)
        strip = StripView(this, this)
        frame = FrameLayout(this).apply { clipChildren = false; addView(kb) }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.BG)
            clipChildren = false
            clipToPadding = false
            addView(strip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(46)))
            addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        root.setOnApplyWindowInsetsListener { v, insets ->   // keep the bottom row clear of the gesture bar
            val bottom = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            else @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            v.setPadding(0, 0, 0, bottom)
            insets
        }
        @Suppress("DEPRECATION")
        window?.window?.navigationBarColor = Palette.BG
        return root
    }

    override fun onEvaluateFullscreenMode() = false

    override fun onStartInputView(ei: EditorInfo, restarting: Boolean) {
        super.onStartInputView(ei, restarting)
        info = ei
        haptics = prefs.haptics
        sound = prefs.sound
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        kb.keyHeightDp = if (landscape) (prefs.keyHeight * 0.75f).toInt() else prefs.keyHeight
        kb.showHints = prefs.hints
        kb.layout = when (ei.inputType and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_DATETIME -> Layouts.number
            InputType.TYPE_CLASS_PHONE -> Layouts.phone
            else -> if (restarting && (kb.layout === Layouts.symbols || kb.layout === Layouts.symbols2)) kb.layout else Layouts.letters
        }
        kb.micEnabled = !isPassword(ei)
        val action = ei.imeOptions and EditorInfo.IME_MASK_ACTION
        val multiLine = ei.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        enterAction = if (ei.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0 || multiLine) EditorInfo.IME_ACTION_NONE else action
        kb.enterIcon = when (enterAction) {
            EditorInfo.IME_ACTION_SEARCH -> R.drawable.ic_search
            EditorInfo.IME_ACTION_SEND -> R.drawable.ic_send
            EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT -> R.drawable.ic_arrow_forward
            EditorInfo.IME_ACTION_DONE -> R.drawable.ic_check
            else -> R.drawable.ic_return
        }
        selStart = ei.initialSelStart
        autoSpace = false
        lastWasSpace = false
        closeEmoji()
        voice.configure(prefs.urls, prefs.token, prefs.rendezvous)
        voice.connect()
        if (dict == Dict.IDLE) refreshStrip()
        updateAutoCaps()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        if (dict == Dict.RECORDING) cancelDictation()
        if (::kb.isInitialized) kb.cancelAll()
        voice.release()
    }

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candStart: Int, candEnd: Int) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candStart, candEnd)
        selStart = newSelStart
        if (!swiping) updateAutoCaps()
    }

    private fun isPassword(ei: EditorInfo): Boolean {
        val cls = ei.inputType and InputType.TYPE_MASK_CLASS
        val v = ei.inputType and InputType.TYPE_MASK_VARIATION
        return (cls == InputType.TYPE_CLASS_TEXT && (v == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            v == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD || v == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (cls == InputType.TYPE_CLASS_NUMBER && v == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    private fun updateAutoCaps() {
        if (!::kb.isInitialized || !kb.layout.letters || kb.shift == Shift.LOCK) return
        val ei = info ?: return
        val caps = currentInputConnection?.getCursorCapsMode(ei.inputType) ?: 0
        kb.shift = if (caps != 0) Shift.ON else Shift.OFF
    }

    // ---------------------------------------------------------------- typing
    override fun onKeyFeedback(key: Key) {
        if (haptics) {
            val v = vibrator
            if (v != null && v.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= 29) v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                else v.vibrate(VibrationEffect.createOneShot(12, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }
        if (sound) {
            (getSystemService(AUDIO_SERVICE) as AudioManager).playSoundEffect(when (key.code) {
                Code.BACKSPACE -> AudioManager.FX_KEYPRESS_DELETE
                Code.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                Code.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }, -1f)
        }
        if (key.code != Code.MIC && lastInsert != null) hideUndo.run()
    }

    override fun onChar(text: String) {
        val ic = currentInputConnection ?: return
        val t = if (kb.layout.letters && kb.shift != Shift.OFF) text.uppercase() else text
        if (autoSpace && t.length == 1 && t[0] in ".,!?;:)" && ic.getTextBeforeCursor(1, 0) == " ") {
            ic.deleteSurroundingText(1, 0)   // "dictated words ." -> "dictated words. "
            ic.commitText("$t ", 1)
        } else {
            ic.commitText(t, 1)
            autoSpace = false
        }
        lastWasSpace = false
        if (kb.shift == Shift.ON) kb.shift = Shift.OFF
    }

    override fun onSpace() {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(2, 0)?.toString() ?: ""
        when {
            autoSpace && before.endsWith(" ") -> {}           // the space is already there
            lastWasSpace && before.length == 2 && before[1] == ' ' && (before[0].isLetterOrDigit() || before[0] in "\"')]") -> {
                ic.deleteSurroundingText(1, 0)                // double space -> ". "
                ic.commitText(". ", 1)
                lastWasSpace = false
                autoSpace = false
                return backToLetters()
            }
            else -> ic.commitText(" ", 1)
        }
        autoSpace = false
        lastWasSpace = true
        backToLetters()
    }

    /** On the symbol pages a space ends the symbol run, like the system keyboards. */
    private fun backToLetters() {
        if (kb.layout === Layouts.symbols || kb.layout === Layouts.symbols2) {
            kb.layout = Layouts.letters
            updateAutoCaps()
        }
    }

    override fun onSpaceLongPress() {
        getSystemService(InputMethodManager::class.java).showInputMethodPicker()
    }

    override fun onBackspace() {
        val ic = currentInputConnection ?: return
        autoSpace = false
        lastWasSpace = false
        if (!ic.getSelectedText(0).isNullOrEmpty()) ic.commitText("", 1)
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)   // the app deletes one character (emoji-safe)
    }

    /** Swipe left from backspace: select the last [n] words as you swipe, delete them when you let go. */
    override fun onDeleteWords(n: Int, commit: Boolean) {
        val ic = currentInputConnection ?: return
        if (!swiping) {                      // remember where the swipe started; selecting moves the cursor
            swiping = true
            swipeAnchor = selStart
            swipeText = ic.getTextBeforeCursor(1000, 0)?.toString() ?: ""
        }
        var i = swipeText.length
        repeat(n) {
            while (i > 0 && swipeText[i - 1].isWhitespace()) i--
            while (i > 0 && !swipeText[i - 1].isWhitespace()) i--
        }
        val len = swipeText.length - i
        if (commit) {
            if (swipeAnchor >= 0) ic.setSelection(swipeAnchor, swipeAnchor)
            if (len > 0) ic.deleteSurroundingText(len, 0)
            swiping = false
            updateAutoCaps()
        } else if (swipeAnchor >= 0) {
            ic.setSelection(swipeAnchor - len, swipeAnchor)
        }
    }

    override fun onShift() {
        val now = SystemClock.uptimeMillis()
        kb.shift = when (kb.shift) {
            Shift.OFF -> Shift.ON
            Shift.ON -> if (now - lastShiftTap < 350) Shift.LOCK else Shift.OFF
            Shift.LOCK -> Shift.OFF
        }
        lastShiftTap = now
    }

    override fun onCursorMove(steps: Int) {
        val key = if (steps < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
        repeat(kotlin.math.abs(steps)) { sendDownUpKeyEvents(key) }
    }

    override fun onEnter() {
        val ic = currentInputConnection ?: return
        autoSpace = false
        lastWasSpace = false
        if (enterAction != EditorInfo.IME_ACTION_NONE && enterAction != EditorInfo.IME_ACTION_UNSPECIFIED) ic.performEditorAction(enterAction)
        else sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
    }

    override fun onMode(code: Code) {
        kb.layout = when (code) {
            Code.SYMBOLS -> Layouts.symbols
            Code.SYMBOLS2 -> Layouts.symbols2
            else -> Layouts.letters
        }
        updateAutoCaps()
    }

    // ---------------------------------------------------------------- emoji
    override fun onEmoji() {
        val panel = emoji ?: EmojiPanel(this, prefs, this).also { emoji = it }
        if (panel.parent == null) frame.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, kb.height))
        panel.layoutParams.height = kb.height
        panel.open()
        kb.cancelAll()
        kb.visibility = View.INVISIBLE   // keeps its height
    }

    private fun closeEmoji() {
        emoji?.let { frame.removeView(it) }
        if (::kb.isInitialized) kb.visibility = View.VISIBLE
    }

    override fun onEmojiPicked(emoji: String) {
        currentInputConnection?.commitText(emoji, 1)
        autoSpace = false
    }

    override fun onEmojiBackspace() = onBackspace()
    override fun onEmojiClose() = closeEmoji()

    // ---------------------------------------------------------------- dictation
    private fun micAllowed() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    override fun onMicDown() {
        if (!micAllowed() || !prefs.paired) return openSetup()
        micDownAt = SystemClock.uptimeMillis()
        startedOnThisPress = dict != Dict.RECORDING && startDictation()
    }

    override fun onMicUp() {
        if (dict != Dict.RECORDING) return
        // a quick tap leaves it listening (hands-free); a hold ends when you let go
        if (!startedOnThisPress || SystemClock.uptimeMillis() - micDownAt >= HOLD_MS) stopDictation()
    }

    private fun startDictation(): Boolean {
        val id = dictId + 1
        dictId = id
        val before = currentInputConnection?.getTextBeforeCursor(300, 0)?.toString() ?: ""
        voice.start(id, before)
        if (!recorder.start()) {
            voice.cancel(id)
            cancelled += id
            flash("The microphone is busy (another app is using it)")
            return false
        }
        ui.removeCallbacks(idleStrip)
        hideUndo.run()
        dict = Dict.RECORDING
        partial = ""
        kb.mic = MicState.RECORDING
        strip.showRecording("")
        return true
    }

    private fun stopDictation() {
        if (dict != Dict.RECORDING) return
        recorder.stop()               // waits for the last frame, so the end of what you said goes out
        voice.stop(dictId)
        dict = Dict.BUSY
        kb.mic = MicState.BUSY
        strip.showBusy(partial)
    }

    private fun cancelDictation() {
        recorder.stop()
        voice.cancel(dictId)
        cancelled += dictId
        dict = Dict.IDLE
        refreshStrip()
    }

    override fun onCancelTap() = cancelDictation()
    override fun onDoneTap() = stopDictation()

    override fun onPartial(id: Int, text: String) {
        if (id != dictId || dict == Dict.IDLE) return
        partial = text
        if (dict == Dict.RECORDING) strip.showRecording(text) else strip.showBusy(text)
    }

    override fun onFinal(id: Int, text: String) {
        if (id in cancelled) return
        if (id == dictId && dict == Dict.BUSY) {
            dict = Dict.IDLE
            refreshStrip()
        }
        if (text.isBlank()) return flash("Didn't catch that")
        insertDictation(text)
    }

    override fun onFailed(id: Int, reason: String) {
        if (id != dictId || id in cancelled) return
        dict = Dict.IDLE
        flash(reason)
    }

    /** Dictated text lands with sensible spacing: a space before it if you were mid-word, and one after. */
    private fun insertDictation(text: String) {
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)?.toString() ?: ""
        val after = ic.getTextAfterCursor(1, 0)?.toString() ?: ""
        val lead = if (before.isNotEmpty() && !before[0].isWhitespace() && before[0] !in "([{“‘/" && text[0] !in ".,!?;:") " " else ""
        val trail = if (after.isEmpty() || !(after[0].isWhitespace() || after[0] in ".,!?;:)]}")) " " else ""
        val s = lead + text + trail
        ic.commitText(s, 1)
        autoSpace = trail.isNotEmpty()
        lastWasSpace = false
        lastInsert = s
        strip.setUndo(true)
        ui.removeCallbacks(hideUndo)
        ui.postDelayed(hideUndo, 6000)
    }

    override fun onUndoTap() {
        val s = lastInsert ?: return
        val ic = currentInputConnection ?: return
        if (ic.getTextBeforeCursor(s.length, 0)?.toString() == s) ic.deleteSurroundingText(s.length, 0)
        hideUndo.run()
        autoSpace = false
    }

    // ---------------------------------------------------------------- status strip
    override fun onStatus(status: VoiceClient.Status, detail: String) {
        if (!::kb.isInitialized) return
        if (dict == Dict.IDLE) refreshStrip()
    }

    private fun refreshStrip() {
        if (!::kb.isInitialized || dict != Dict.IDLE) return
        val pc = prefs.pcName.ifEmpty { "PC" }
        val s = voice.status
        kb.mic = if (s == VoiceClient.Status.READY) MicState.IDLE else MicState.OFFLINE
        when {
            !micAllowed() -> strip.showIdle(Palette.WARN, "Tap to allow the microphone")
            !prefs.paired || s == VoiceClient.Status.NOT_PAIRED -> strip.showIdle(Palette.WARN, "Tap to pair with your PC")
            s == VoiceClient.Status.READY -> strip.showIdle(Palette.OK, if (voice.detail.isNotEmpty()) "$pc · ${voice.detail}" else pc)
            s == VoiceClient.Status.CONNECTING -> strip.showIdle(Palette.WARN, "Connecting to $pc…")
            else -> strip.showIdle(Palette.REC, "$pc offline · ${voice.detail.ifEmpty { "tap to retry" }}")
        }
    }

    private fun flash(msg: String) {
        if (!::strip.isInitialized) return
        kb.mic = if (voice.status == VoiceClient.Status.READY) MicState.IDLE else MicState.OFFLINE
        strip.showMessage(msg, Palette.WARN)
        ui.removeCallbacks(idleStrip)
        ui.postDelayed(idleStrip, 2500)
    }

    override fun onStatusTap() {
        if (!micAllowed() || !prefs.paired) return openSetup()
        if (voice.status != VoiceClient.Status.READY) {
            voice.configure(prefs.urls, prefs.token, prefs.rendezvous)
            voice.connect()
        }
    }

    override fun onSettingsTap() = openSetup()
    override fun onEmojiTap() = onEmoji()

    private fun openSetup() {
        startActivity(Intent(this, SetupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    companion object {
        private const val HOLD_MS = 350L
    }
}
