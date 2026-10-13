package app.wisperkeys

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** A compact emoji picker in the keyboard's own space: recents first, then the usual categories. */
@SuppressLint("ViewConstructor")
class EmojiPanel(context: Context, private val prefs: Prefs, private val listener: Listener) : LinearLayout(context) {

    interface Listener {
        fun onEmojiPicked(emoji: String)
        fun onEmojiBackspace()
        fun onEmojiClose()
    }

    private val tabs = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val grid = GridLayout(context).apply { columnCount = COLS }
    private val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(grid) }
    private var current = 0

    init {
        orientation = VERTICAL
        setBackgroundColor(Palette.BG)
        addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabs)
        }, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(40)))
        addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        val bottom = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(context.dp(4), context.dp(4), context.dp(4), context.dp(4))
        }
        bottom.addView(TextView(context).apply {
            text = "ABC"
            setTextColor(Palette.TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Palette.MOD); cornerRadius = context.dp(7).toFloat() }
            setOnClickListener { listener.onEmojiClose() }
        }, LayoutParams(context.dp(76), LayoutParams.MATCH_PARENT))
        bottom.addView(android.view.View(context), LayoutParams(0, 1, 1f))
        bottom.addView(ImageView(context).apply {
            setImageResource(R.drawable.ic_backspace)
            setColorFilter(Palette.TEXT)
            scaleType = ImageView.ScaleType.CENTER
            background = GradientDrawable().apply { setColor(Palette.MOD); cornerRadius = context.dp(7).toFloat() }
            setOnClickListener { listener.onEmojiBackspace() }
        }, LayoutParams(context.dp(76), LayoutParams.MATCH_PARENT))
        addView(bottom, LayoutParams(LayoutParams.MATCH_PARENT, context.dp(50)))

        CATEGORIES.forEachIndexed { i, (icon, _) ->
            tabs.addView(TextView(context).apply {
                text = icon
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                gravity = Gravity.CENTER
                setOnClickListener { select(i) }
            }, LayoutParams(context.dp(46), LayoutParams.MATCH_PARENT))
        }
    }

    fun open() = select(if (prefs.recentEmoji.isEmpty()) 1 else 0)

    private fun select(i: Int) {
        current = i
        for (t in 0 until tabs.childCount) {
            tabs.getChildAt(t).background = if (t == i)
                GradientDrawable().apply { setColor(Palette.KEY); cornerRadius = context.dp(8).toFloat() } else null
        }
        grid.removeAllViews()
        val list = if (i == 0) prefs.recentEmoji else CATEGORIES[i].second.split(' ').filter { it.isNotBlank() }
        val cell = (resources.displayMetrics.widthPixels / COLS)
        for (e in list) {
            grid.addView(TextView(context).apply {
                text = e
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 25f)
                gravity = Gravity.CENTER
                setTextColor(Palette.TEXT)
                setOnClickListener { pick(e) }
            }, GridLayout.LayoutParams().apply { width = cell; height = context.dp(48) })
        }
        scroll.scrollTo(0, 0)
    }

    private fun pick(e: String) {
        listener.onEmojiPicked(e)
        prefs.recentEmoji = listOf(e) + prefs.recentEmoji.filter { it != e }
    }

    companion object {
        private const val COLS = 8
        private val CATEGORIES = listOf(
            "🕘" to "",
            "😀" to "😀 😃 😄 😁 😆 😅 🤣 😂 🙂 🙃 😉 😊 😇 🥰 😍 🤩 😘 😗 😚 😋 😛 😜 🤪 😝 🤑 🤗 🤭 🤫 🤔 🤐 🤨 😐 😑 😶 😏 😒 🙄 😬 😮‍💨 🤥 😌 😔 😪 🤤 😴 😷 🤒 🤕 🤢 🤮 🥵 🥶 🥴 😵 🤯 🤠 🥳 😎 🤓 🧐 😕 😟 🙁 😮 😯 😲 😳 🥺 😦 😧 😨 😰 😥 😢 😭 😱 😖 😣 😞 😓 😩 😫 🥱 😤 😡 😠 🤬 😈 👿 💀 💩 🤡 👻 👽 🤖",
            "👍" to "👍 👎 👌 🤌 🤏 ✌️ 🤞 🤟 🤘 🤙 👈 👉 👆 👇 ☝️ ✋ 🤚 🖐️ 🖖 👋 🤝 🙏 👏 🙌 👐 🤲 💪 🫶 ✍️ 💅 🤳 👀 🧠 🫡 🤷 🤦 🙋 🙇 💁 🙆 🙅 🏃 🚶 💃 🕺 🧘",
            "❤️" to "❤️ 🧡 💛 💚 💙 💜 🖤 🤍 🤎 💔 ❣️ 💕 💞 💓 💗 💖 💘 💝 ✨ ⭐ 🌟 💫 🔥 💯 ✅ ❌ ❗ ❓ ‼️ ⚠️ 🚫 💤 💥 💢 💬 💭 🎉 🎊 🎁 🏆 🥇 🔔 📌 📍 ➡️ ⬅️ ⬆️ ⬇️ 🔄 ➕ ➖ ✔️ ☑️ 🆗 🆕 🆒",
            "🐶" to "🐶 🐱 🐭 🐹 🐰 🦊 🐻 🐼 🐨 🐯 🦁 🐮 🐷 🐸 🐵 🙈 🙉 🙊 🐔 🐧 🐦 🦆 🦅 🦉 🐺 🐴 🦄 🐝 🦋 🐌 🐞 🐢 🐍 🐙 🦀 🐠 🐬 🐳 🦈 🐘 🦒 🌸 🌹 🌻 🌷 🌱 🌿 🍀 🍁 🌵 🌴 🌲 🌍 🌙 ☀️ ⛅ 🌧️ ⛈️ ❄️ 🌈 🌊",
            "🍔" to "🍏 🍎 🍐 🍊 🍋 🍌 🍉 🍇 🍓 🫐 🍒 🍑 🥭 🍍 🥥 🥝 🍅 🥑 🥦 🌽 🥕 🧄 🥔 🍞 🥐 🧀 🥚 🍳 🥞 🥓 🍗 🍖 🌭 🍔 🍟 🍕 🌮 🌯 🥗 🍝 🍜 🍲 🍛 🍣 🍱 🥟 🍤 🍚 🍰 🎂 🧁 🍩 🍪 🍫 🍿 🍦 ☕ 🍵 🧃 🥤 🍺 🍻 🥂 🍷 🥃 🍸",
            "⚽" to "⚽ 🏀 🏈 ⚾ 🎾 🏐 🏓 🏸 🥊 🏋️ 🚴 🏊 🎮 🎯 🎲 🎸 🎹 🎧 🎤 🎬 📷 📱 💻 ⌨️ 🖥️ 💡 🔋 🔌 📞 ⏰ ⌛ 💰 💵 💳 💎 🔑 🔒 🔓 🛠️ 🔧 🔨 ⚙️ 🧲 📦 ✉️ 📝 📄 📊 📈 📉 📅 📎 ✂️ 🖊️ 📚 📖 🔍 🔗 🧪 💊 🛒",
            "🚗" to "🚗 🚕 🚙 🚌 🏎️ 🚓 🚑 🚒 🚚 🚲 🛵 🏍️ ✈️ 🚀 🛸 🚁 ⛵ 🚢 🚆 🚇 🗺️ 🧭 🏠 🏡 🏢 🏬 🏥 🏦 🏨 🏫 🕌 🏰 🗽 🗼 🌉 🏖️ 🏝️ 🏔️ ⛰️ 🌋 🏕️ 🎡 🎢 🌃 🌆 🌇 🌅 🚦 ⛽",
            "🏁" to "🏁 🚩 🏳️ 🏴 🏳️‍🌈 🇮🇳 🇦🇪 🇺🇸 🇬🇧 🇨🇦 🇦🇺 🇩🇪 🇫🇷 🇪🇸 🇮🇹 🇳🇱 🇨🇭 🇸🇪 🇮🇪 🇯🇵 🇰🇷 🇨🇳 🇸🇬 🇧🇷 🇲🇽 🇸🇦 🇶🇦",
        )
    }
}
