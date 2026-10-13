package app.wisperkeys

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class Shift { OFF, ON, LOCK }
enum class MicState { IDLE, RECORDING, BUSY, OFFLINE }

/**
 * Draws a [Layout] and turns touches into key actions. Multi-touch aware: a second finger landing while the first is
 * still down types the first key at once (rollover), so fast two-thumb typing never drops letters.
 * Gaps between keys belong to the nearest key, so there are no dead zones.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(context: Context, private val listener: Listener) : View(context) {

    interface Listener {
        fun onKeyFeedback(key: Key)
        fun onChar(text: String)
        fun onBackspace()
        fun onDeleteWords(n: Int, commit: Boolean)
        fun onShift()
        fun onSpace()
        fun onSpaceLongPress()
        fun onCursorMove(steps: Int)
        fun onEnter()
        fun onMode(code: Code)
        fun onMicDown()
        fun onMicUp()
        fun onEmoji()
    }

    private val dp = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity

    var layout: Layout = Layouts.letters
        set(v) { field = v; cancelAll(); requestLayout(); placeKeys(); invalidate() }
    var shift = Shift.OFF
        set(v) { if (field != v) { field = v; invalidate() } }
    var mic = MicState.IDLE
        set(v) { if (field != v) { field = v; invalidate() } }
    var enterIcon = R.drawable.ic_return
        set(v) { if (field != v) { field = v; invalidate() } }
    var keyHeightDp = 54
        set(v) { if (field != v) { field = v; requestLayout() } }
    var showHints = true
        set(v) { if (field != v) { field = v; invalidate() } }
    var micEnabled = true
        set(v) { if (field != v) { field = v; invalidate() } }

    private class Box(val key: Key, val rect: RectF, val hit: RectF, val row: Int)

    private var boxes = listOf<Box>()
    private val gapH get() = 5 * dp
    private val gapV get() = 9 * dp
    private val side get() = 3 * dp
    private val rowH get() = keyHeightDp * dp

    // ---------------------------------------------------------------- paints and icons
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.TEXT; textAlign = Paint.Align.CENTER }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.TEXT_DIM; textAlign = Paint.Align.CENTER; textSize = 10.5f * sp
    }
    private val icons = HashMap<Int, Drawable>()
    private fun icon(res: Int): Drawable = icons.getOrPut(res) { context.getDrawable(res)!!.mutate() }

    // ---------------------------------------------------------------- geometry
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (layout.rows.size * rowH + 6 * dp).toInt()
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = placeKeys()

    private fun placeKeys() {
        if (width == 0) return
        val unit = (width - 2 * side) / 10f
        val out = ArrayList<Box>()
        layout.rows.forEachIndexed { r, row ->
            val total = row.keys.sumOf { it.width.toDouble() }.toFloat()
            var x = side + (10f - total) * unit / 2f
            val top = 3 * dp + r * rowH
            row.keys.forEachIndexed { i, k ->
                val w = k.width * unit
                val rect = RectF(x + gapH / 2, top + gapV / 2, x + w - gapH / 2, top + rowH - gapV / 2)
                val hit = RectF(if (i == 0) 0f else x, top, if (i == row.keys.lastIndex) width.toFloat() else x + w, top + rowH)
                out += Box(k, rect, hit, r)
                x += w
            }
        }
        boxes = out
    }

    private fun boxAt(x: Float, y: Float): Box? {
        if (boxes.isEmpty()) return null
        val r = ((y - 3 * dp) / rowH).toInt().coerceIn(0, layout.rows.size - 1)
        val inRow = boxes.filter { it.row == r }
        return inRow.firstOrNull { x >= it.hit.left && x < it.hit.right }
            ?: inRow.minByOrNull { min(abs(x - it.hit.left), abs(x - it.hit.right)) }
    }

    // ---------------------------------------------------------------- touch
    private enum class Mode { NONE, CURSOR, SWIPE_DELETE }

    private inner class Ptr(var box: Box, val downX: Float) {
        var typed = false          // already typed (rollover) or consumed by a long press
        var popup: List<String>? = null
        var popupSel = 0
        var popupRect = RectF()
        var mode = Mode.NONE
        var anchorX = downX
        var words = 0
        var longPress: Runnable? = null
        var repeat: Runnable? = null
    }

    private val ptrs = LinkedHashMap<Int, Ptr>()
    private val ui = Handler(Looper.getMainLooper())
    private val slop get() = 14 * dp

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                down(e.getPointerId(e.actionIndex), e.getX(e.actionIndex), e.getY(e.actionIndex))
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) move(e.getPointerId(i), e.getX(i), e.getY(i))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> up(e.getPointerId(e.actionIndex))
            MotionEvent.ACTION_CANCEL -> cancelAll()
        }
        return true
    }

    private fun down(id: Int, x: Float, y: Float) {
        val box = boxAt(x, y) ?: return
        for (p in ptrs.values) if (p.box.key.code == Code.CHAR && !p.typed && p.popup == null) {   // rollover
            p.typed = true
            cancelTimers(p)
            listener.onChar(p.box.key.label)
        }
        val p = Ptr(box, x)
        ptrs[id] = p
        val k = box.key
        if (k.code == Code.MIC && !micEnabled) { p.typed = true; invalidate(); return }
        listener.onKeyFeedback(k)
        when (k.code) {
            Code.CHAR -> if (k.alts.isNotEmpty() || k.label == ",") armLongPress(p)
            Code.BACKSPACE -> {
                listener.onBackspace()
                var n = 0
                p.repeat = object : Runnable {
                    override fun run() { listener.onBackspace(); n++; ui.postDelayed(this, if (n < 12) 60L else 30L) }
                }.also { ui.postDelayed(it, 420) }
            }
            Code.SPACE -> armLongPress(p)
            Code.SHIFT -> listener.onShift()
            Code.SYMBOLS, Code.SYMBOLS2, Code.LETTERS -> listener.onMode(k.code)
            Code.MIC -> listener.onMicDown()
            Code.ENTER -> {}
        }
        invalidate()
    }

    private fun armLongPress(p: Ptr) {
        p.longPress?.let { ui.removeCallbacks(it) }
        p.longPress = Runnable { longPress(p) }.also { ui.postDelayed(it, if (p.box.key.code == Code.SPACE) 550L else 330L) }
    }

    private fun longPress(p: Ptr) {
        if (!ptrs.containsValue(p) || p.typed) return
        val k = p.box.key
        when {
            k.code == Code.SPACE -> { p.typed = true; listener.onSpaceLongPress() }
            k.label == "," -> { p.typed = true; listener.onEmoji() }
            k.alts.isNotEmpty() -> {
                val alts = if (layout.letters && shift != Shift.OFF) k.alts.map { it.uppercase() } else k.alts
                val cell = min(p.box.rect.width() + gapH, (width - 2 * side) / alts.size)
                val w = cell * alts.size
                val left = (p.box.rect.centerX() - cell / 2).coerceIn(side, width - side - w)
                val top = p.box.rect.top - rowH - 2 * dp
                p.popup = alts
                p.popupSel = 0
                p.popupRect = RectF(left, top, left + w, top + rowH - gapV / 2)
                listener.onKeyFeedback(k)
            }
        }
        invalidate()
    }

    private fun move(id: Int, x: Float, y: Float) {
        val p = ptrs[id] ?: return
        when (p.box.key.code) {
            Code.CHAR -> {
                val popup = p.popup
                if (popup != null) {
                    val cell = p.popupRect.width() / popup.size
                    val sel = ((x - p.popupRect.left) / cell).toInt().coerceIn(0, popup.size - 1)
                    if (sel != p.popupSel) { p.popupSel = sel; invalidate() }
                } else if (!p.typed) {
                    val b = boxAt(x, y)                     // slid onto a neighbouring letter: that one counts
                    if (b != null && b !== p.box && b.key.code == Code.CHAR) {
                        p.box = b
                        if (b.key.alts.isNotEmpty() || b.key.label == ",") armLongPress(p) else cancelTimers(p)
                        invalidate()
                    }
                }
            }
            Code.SPACE -> if (p.mode == Mode.CURSOR || (!p.typed && abs(x - p.downX) > slop)) {
                if (p.mode != Mode.CURSOR) { p.mode = Mode.CURSOR; cancelTimers(p); p.anchorX = x }
                val step = 9 * dp
                val n = ((x - p.anchorX) / step).toInt()
                if (n != 0) { listener.onCursorMove(n); p.anchorX += n * step }
            }
            Code.BACKSPACE -> if (p.mode == Mode.SWIPE_DELETE || p.downX - x > 28 * dp) {
                if (p.mode != Mode.SWIPE_DELETE) { p.mode = Mode.SWIPE_DELETE; cancelTimers(p) }
                val dx = p.downX - x - 28 * dp
                val words = if (dx < 0) 0 else 1 + (dx / (24 * dp)).toInt()
                if (words != p.words) { p.words = words; listener.onDeleteWords(words, false); invalidate() }
            }
            else -> {}
        }
    }

    private fun up(id: Int) {
        val p = ptrs.remove(id) ?: return
        cancelTimers(p)
        val k = p.box.key
        when (k.code) {
            Code.CHAR -> {
                val popup = p.popup
                if (popup != null) listener.onChar(k.alts[p.popupSel])
                else if (!p.typed) listener.onChar(k.label)
            }
            Code.SPACE -> if (p.mode == Mode.NONE && !p.typed) listener.onSpace()
            Code.BACKSPACE -> if (p.mode == Mode.SWIPE_DELETE) listener.onDeleteWords(p.words, true)
            Code.ENTER -> listener.onEnter()
            Code.MIC -> if (!p.typed) listener.onMicUp()
            else -> {}
        }
        invalidate()
    }

    private fun cancelTimers(p: Ptr) {
        p.longPress?.let { ui.removeCallbacks(it) }
        p.repeat?.let { ui.removeCallbacks(it) }
        p.longPress = null
        p.repeat = null
    }

    fun cancelAll() {
        for (p in ptrs.values) {
            cancelTimers(p)
            if (p.box.key.code == Code.BACKSPACE && p.mode == Mode.SWIPE_DELETE) listener.onDeleteWords(0, true)
        }
        ptrs.clear()
        invalidate()
    }

    // ---------------------------------------------------------------- drawing
    override fun onDraw(c: Canvas) {
        keyPaint.color = Palette.BG   // own bounds only: the canvas isn't clipped (previews draw over the strip)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), keyPaint)
        val pressed = ptrs.values.map { it.box }.toSet()
        for (b in boxes) drawKey(c, b, b in pressed)
        for (p in ptrs.values) {
            val popup = p.popup
            if (popup != null) drawPopup(c, p, popup)
            else if (p.box.key.code == Code.CHAR && !p.typed) drawPreview(c, p.box)
        }
    }

    private fun keyText(k: Key) = if (layout.letters && shift != Shift.OFF && k.label.length == 1) k.label.uppercase() else k.label

    private fun drawKey(c: Canvas, b: Box, down: Boolean) {
        val k = b.key
        val r = 7 * dp
        val (bg, bgDown) = when {
            k.code == Code.ENTER -> Palette.ACCENT to Palette.ACCENT_DOWN
            k.code == Code.MIC && mic == MicState.RECORDING -> Palette.REC to Palette.REC
            k.code == Code.CHAR || k.code == Code.SPACE -> Palette.KEY to Palette.KEY_DOWN
            k.code == Code.SHIFT && shift != Shift.OFF -> Palette.KEY_DOWN to Palette.KEY_DOWN
            else -> Palette.MOD to Palette.MOD_DOWN
        }
        keyPaint.color = if (down) bgDown else bg
        c.drawRoundRect(b.rect, r, r, keyPaint)
        val cx = b.rect.centerX()
        val cy = b.rect.centerY()
        when (k.code) {
            Code.CHAR -> {
                val t = keyText(k)
                label.textSize = (if (t.length > 1) 18f else if (layout.letters) 23f else 21f) * sp
                label.typeface = Typeface.DEFAULT
                label.color = Palette.TEXT
                c.drawText(t, cx, cy - (label.descent() + label.ascent()) / 2, label)
                if (showHints && k.alts.isNotEmpty() && layout.letters) {
                    c.drawText(k.alts[0], b.rect.right - 7 * dp, b.rect.top + 13 * dp, hint)
                }
            }
            Code.SYMBOLS, Code.SYMBOLS2, Code.LETTERS -> {
                label.textSize = 15f * sp
                label.typeface = Typeface.DEFAULT_BOLD
                label.color = Palette.TEXT
                c.drawText(k.label, cx, cy - (label.descent() + label.ascent()) / 2, label)
            }
            Code.SPACE -> {}
            Code.SHIFT -> drawIcon(c, when (shift) {
                Shift.OFF -> R.drawable.ic_shift
                Shift.ON -> R.drawable.ic_shift_on
                Shift.LOCK -> R.drawable.ic_caps_lock
            }, cx, cy, if (shift == Shift.OFF) Palette.TEXT else Palette.ACCENT_LIGHT)
            Code.BACKSPACE -> drawIcon(c, R.drawable.ic_backspace, cx, cy, Palette.TEXT)
            Code.ENTER -> drawIcon(c, enterIcon, cx, cy, Palette.ON_ACCENT)
            Code.MIC -> drawIcon(c, R.drawable.ic_mic, cx, cy, when {
                !micEnabled -> Palette.TEXT_FAINT
                mic == MicState.RECORDING -> Palette.ON_ACCENT
                mic == MicState.BUSY -> Palette.TEXT_DIM
                mic == MicState.OFFLINE -> Palette.TEXT_DIM
                else -> Palette.ACCENT_LIGHT
            })
        }
    }

    private fun drawIcon(c: Canvas, res: Int, cx: Float, cy: Float, color: Int) {
        val d = icon(res)
        val s = (24 * dp).toInt() / 2
        d.setTint(color)
        d.setBounds(cx.toInt() - s, cy.toInt() - s, cx.toInt() + s, cy.toInt() + s)
        d.draw(c)
    }

    /** The letter, enlarged above your finger (clipped at the top edge of the strip for the top row). */
    private fun drawPreview(c: Canvas, b: Box) {
        val w = b.rect.width() + 10 * dp
        val h = rowH * 1.05f
        val left = (b.rect.centerX() - w / 2).coerceIn(0f, width - w)
        val top = max(b.rect.top - h - 4 * dp, -48 * dp)
        val rr = RectF(left, top, left + w, top + h)
        keyPaint.color = Palette.POPUP
        c.drawRoundRect(rr, 9 * dp, 9 * dp, keyPaint)
        label.textSize = 30f * sp
        label.typeface = Typeface.DEFAULT
        label.color = Palette.TEXT
        c.drawText(keyText(b.key), rr.centerX(), rr.centerY() - (label.descent() + label.ascent()) / 2, label)
    }

    private fun drawPopup(c: Canvas, p: Ptr, alts: List<String>) {
        val rr = RectF(p.popupRect)
        if (rr.top < -48 * dp) rr.offset(0f, -48 * dp - rr.top)
        keyPaint.color = Palette.POPUP
        c.drawRoundRect(rr, 9 * dp, 9 * dp, keyPaint)
        val cell = rr.width() / alts.size
        label.textSize = 22f * sp
        label.typeface = Typeface.DEFAULT
        alts.forEachIndexed { i, a ->
            val cr = RectF(rr.left + i * cell, rr.top, rr.left + (i + 1) * cell, rr.bottom)
            if (i == p.popupSel) {
                keyPaint.color = Palette.ACCENT
                c.drawRoundRect(RectF(cr.left + 2 * dp, cr.top + 2 * dp, cr.right - 2 * dp, cr.bottom - 2 * dp), 7 * dp, 7 * dp, keyPaint)
            }
            label.color = if (i == p.popupSel) Palette.ON_ACCENT else Palette.TEXT
            c.drawText(a, cr.centerX(), cr.centerY() - (label.descent() + label.ascent()) / 2, label)
        }
    }
}
