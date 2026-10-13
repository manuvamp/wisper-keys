package app.wisperkeys

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

fun Context.dp(v: Number) = (v.toFloat() * resources.displayMetrics.density).toInt()

/**
 * The bar above the keys. Idle: settings, emoji and the PC link status. While you talk: cancel, a level meter, the
 * text transcribed so far and a done button. After a dictation: an Undo chip for a few seconds.
 */
@SuppressLint("ViewConstructor")
class StripView(context: Context, private val listener: Listener) : LinearLayout(context) {

    interface Listener {
        fun onSettingsTap()
        fun onEmojiTap()
        fun onStatusTap()
        fun onCancelTap()
        fun onDoneTap()
        fun onUndoTap()
    }

    private fun iconButton(res: Int, tint: Int, desc: String, onTap: () -> Unit) = ImageView(context).apply {
        setImageResource(res)
        setColorFilter(tint)
        contentDescription = desc
        scaleType = ImageView.ScaleType.CENTER
        setPadding(context.dp(10), 0, context.dp(10), 0)
        val tv = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        if (tv.resourceId != 0) setBackgroundResource(tv.resourceId)
        setOnClickListener { onTap() }
    }

    private val settings = iconButton(R.drawable.ic_settings, Palette.TEXT_DIM, "Settings") { listener.onSettingsTap() }
    private val emoji = iconButton(R.drawable.ic_emoji, Palette.TEXT_DIM, "Emoji") { listener.onEmojiTap() }
    private val cancel = iconButton(R.drawable.ic_close, Palette.TEXT, "Cancel dictation") { listener.onCancelTap() }
    private val done = iconButton(R.drawable.ic_check, Palette.REC, "Finish dictation") { listener.onDoneTap() }
    private val dot = View(context)
    private val meter = LevelView(context)
    private val text = TextView(context).apply {
        setTextColor(Palette.TEXT_DIM)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        maxLines = 1
        gravity = Gravity.CENTER_VERTICAL
        setPadding(context.dp(8), 0, context.dp(8), 0)
        setOnClickListener { listener.onStatusTap() }
    }
    private val undo = TextView(context).apply {
        text = "Undo"
        setTextColor(Palette.TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        setCompoundDrawablesRelativeWithIntrinsicBounds(context.getDrawable(R.drawable.ic_undo)!!.mutate().apply {
            setTint(Palette.TEXT); setBounds(0, 0, context.dp(18), context.dp(18))
        }, null, null, null)
        compoundDrawablePadding = context.dp(4)
        setPadding(context.dp(10), 0, context.dp(12), 0)
        background = GradientDrawable().apply { setColor(Palette.KEY); cornerRadius = context.dp(16).toFloat() }
        setOnClickListener { listener.onUndoTap() }
        visibility = GONE
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(Palette.BG)
        addView(settings, LayoutParams(context.dp(44), LayoutParams.MATCH_PARENT))
        addView(emoji, LayoutParams(context.dp(44), LayoutParams.MATCH_PARENT))
        addView(cancel, LayoutParams(context.dp(44), LayoutParams.MATCH_PARENT))
        addView(dot, LayoutParams(context.dp(8), context.dp(8)).apply { marginStart = context.dp(8) })
        addView(meter, LayoutParams(context.dp(26), context.dp(22)).apply { marginStart = context.dp(8) })
        addView(text, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        addView(undo, LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(32)).apply { marginEnd = context.dp(8) })
        addView(done, LayoutParams(context.dp(48), LayoutParams.MATCH_PARENT))
        showIdle(Palette.TEXT_FAINT, "")
    }

    private fun mode(talking: Boolean) {
        settings.visibility = if (talking) GONE else VISIBLE
        emoji.visibility = if (talking) GONE else VISIBLE
        cancel.visibility = if (talking) VISIBLE else GONE
        dot.visibility = if (talking) GONE else VISIBLE
        meter.visibility = if (talking) VISIBLE else GONE
        if (talking) undo.visibility = GONE
    }

    fun showIdle(dotColor: Int, status: String) {
        mode(false)
        done.visibility = GONE
        dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(dotColor) }
        text.ellipsize = TextUtils.TruncateAt.END
        text.setTextColor(Palette.TEXT_DIM)
        text.text = status
    }

    fun showRecording(partial: String) {
        mode(true)
        done.visibility = VISIBLE
        meter.active = true
        text.ellipsize = TextUtils.TruncateAt.START   // keep the newest words in view
        text.setTextColor(if (partial.isEmpty()) Palette.TEXT_DIM else Palette.TEXT)
        text.text = partial.ifEmpty { "Listening…" }
    }

    fun showBusy(partial: String) {
        mode(true)
        done.visibility = GONE
        meter.active = false
        text.ellipsize = TextUtils.TruncateAt.START
        text.setTextColor(Palette.TEXT_DIM)
        text.text = partial.ifEmpty { "Transcribing…" }
    }

    fun showMessage(msg: String, color: Int) {
        showIdle(color, msg)
        text.setTextColor(Palette.TEXT)
    }

    fun setUndo(visible: Boolean) {
        undo.visibility = if (visible) VISIBLE else GONE
    }

    fun level(v: Float) = meter.push(v)
}

/** Four bars that follow your voice, so you can see the mic is really hearing you. */
class LevelView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.REC }
    private val bars = FloatArray(4)
    var active = true
        set(v) { field = v; paint.color = if (v) Palette.REC else Palette.TEXT_DIM; invalidate() }

    fun push(rms: Float) {
        // speech RMS is roughly 0.01 (soft) .. 0.2 (loud): map on a log-ish curve to 0..1
        val v = min(1f, max(0f, (Math.log10(max(rms, 1e-4f).toDouble()).toFloat() + 3.2f) / 2.4f))
        for (i in 0 until bars.size - 1) bars[i] = bars[i + 1]
        bars[bars.size - 1] = v
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width / (bars.size * 2f - 1)
        val r = w / 2
        bars.forEachIndexed { i, v ->
            val h = max(w, v * height)
            val x = i * 2 * w
            c.drawRoundRect(x, (height - h) / 2, x + w, (height + h) / 2, r, r, paint)
        }
    }
}
