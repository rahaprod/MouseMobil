package com.mousemobile.app

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.text.InputType
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Палитра: чистый чёрный (AMOLED) и тёмно-красный. */
private object Pal {
    val BG = Color.BLACK
    val CARD = Color.parseColor("#0D0D0D")
    val RED = Color.parseColor("#8B0000")
    val RED_HI = Color.parseColor("#B71C1C")
    val LINE = Color.parseColor("#3A0000")
    val TEXT = Color.parseColor("#E6E6E6")
    val MUTED = Color.parseColor("#8A8A8A")
}

class MainActivity : Activity() {

    private var inMain = false
    private var held = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Net.onLost = {
            runOnUiThread {
                if (inMain) showConnect("Соединение потеряно")
            }
        }
        showConnect()
    }

    override fun onDestroy() {
        Net.onLost = null
        Net.disconnect()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (inMain) showConnect() else super.onBackPressed()
    }

    // ---------- помощники оформления ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun shape(fill: Int, stroke: Int, radius: Int = 10): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), stroke)
        }

    private fun rippled(fill: Int): RippleDrawable = RippleDrawable(
        ColorStateList.valueOf(Color.parseColor("#66FF4040")),
        shape(fill, Pal.LINE),
        shape(Color.WHITE, Color.WHITE)
    )

    private fun label(t: String, size: Float, color: Int = Pal.TEXT): TextView =
        TextView(this).apply {
            text = t
            textSize = size
            setTextColor(color)
        }

    private fun button(t: String, fill: Int = Pal.RED, onClick: (Button) -> Unit): Button =
        Button(this).apply {
            text = t
            isAllCaps = false
            textSize = 15f
            setTextColor(Pal.TEXT)
            background = rippled(fill)
            stateListAnimator = null
            minWidth = 0
            minHeight = 0
            minimumWidth = 0
            minimumHeight = 0
            setPadding(dp(8), dp(12), dp(8), dp(12))
            setOnClickListener { onClick(this) }
        }

    private fun field(ph: String, type: Int, value: String = ""): EditText =
        EditText(this).apply {
            hint = ph
            setText(value)
            inputType = type
            setTextColor(Pal.TEXT)
            setHintTextColor(Pal.MUTED)
            background = shape(Pal.CARD, Pal.LINE)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

    private fun lp(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(top) }

    private fun weight(margin: Int = 2): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, WRAP, 1f).apply {
            setMargins(dp(margin), dp(margin), dp(margin), dp(margin))
        }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        currentFocus?.let { imm.hideSoftInputFromWindow(it.windowToken, 0) }
    }

    // ---------- экран подключения ----------

    private fun showConnect(msg: String = "") {
        inMain = false
        held = false
        Net.disconnect()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val prefs = getSharedPreferences("mm", Context.MODE_PRIVATE)

        val ip = field(
            "IP компьютера, например 192.168.1.10",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            prefs.getString("ip", "") ?: ""
        )
        val pin = field("PIN с экрана ПК", InputType.TYPE_CLASS_NUMBER)
        val status = label(msg, 14f, Pal.RED_HI)

        val find = button("Найти ПК в сети", Pal.CARD) {
            status.text = "Поиск…"
            Thread {
                val found = Net.discover()
                runOnUiThread {
                    if (found != null) {
                        ip.setText(found)
                        status.text = "Найден ПК: $found"
                    } else {
                        status.text = "ПК не найден. Введите IP вручную."
                    }
                }
            }.start()
        }

        val go = button("Подключиться") {
            val host = ip.text.toString().trim()
            val code = pin.text.toString().trim()
            if (host.isEmpty() || code.isEmpty()) {
                status.text = "Введите IP и PIN"
            } else {
                status.text = "Подключение…"
                Thread {
                    val err = Net.connect(host, code)
                    runOnUiThread {
                        if (err == null) {
                            prefs.edit().putString("ip", host).apply()
                            showMain()
                        } else {
                            status.text = err
                        }
                    }
                }.start()
            }
        }

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Pal.BG)
            setPadding(dp(24), dp(48), dp(24), dp(24))
            addView(label("MouseMobile", 32f, Pal.RED_HI).apply { typeface = Typeface.DEFAULT_BOLD })
            addView(label("Управление ПК по Wi-Fi", 14f, Pal.MUTED))
            addView(ip, lp(32))
            addView(pin, lp(12))
            addView(find, lp(12))
            addView(go, lp(12))
            addView(status, lp(16))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(Pal.BG)
            isFillViewport = true
            addView(col)
        })
    }

    // ---------- основной экран ----------

    private fun showMain() {
        inMain = true
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val pad = TouchView(this, false)
        val screen = TouchView(this, true)
        val keys = keyboardTab()
        val views = listOf<View>(pad, keys, screen)

        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        bar.addView(button("ЛКМ") { Net.send("CLICK left") }, weight())
        bar.addView(button("Удерж.", Pal.CARD) { b ->
            held = !held
            Net.send(if (held) "DOWN left" else "UP left")
            b.text = if (held) "Отпустить" else "Удерж."
            b.background = rippled(if (held) Pal.RED else Pal.CARD)
        }, weight())
        bar.addView(button("ПКМ") { Net.send("CLICK right") }, weight())

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tabBtns = ArrayList<Button>()

        fun showTab(i: Int) {
            for ((k, v) in views.withIndex()) {
                v.visibility = if (k == i) View.VISIBLE else View.GONE
            }
            bar.visibility = if (i == 1) View.GONE else View.VISIBLE
            for ((k, b) in tabBtns.withIndex()) {
                b.background = rippled(if (k == i) Pal.RED else Pal.CARD)
            }
            if (i == 2) {
                Net.startScreen(
                    { screen.showFrame(it) },
                    { m ->
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, m, Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            } else {
                Net.stopScreen()
            }
            hideKeyboard()
        }

        listOf("Тачпад", "Ввод", "Экран").forEachIndexed { i, name ->
            val b = button(name, Pal.CARD) { showTab(i) }
            tabBtns.add(b)
            tabs.addView(b, weight())
        }
        tabs.addView(
            button("✕", Pal.CARD) { showConnect() },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) }
        )

        val body = FrameLayout(this)
        for (v in views) body.addView(v, FrameLayout.LayoutParams(MATCH, MATCH))

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Pal.BG)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            addView(tabs, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        })
        showTab(0)
    }

    private fun keyboardTab(): View {
        val input = field(
            "Текст для ввода на ПК",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        )
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(8))
            addView(input, lp())
            addView(button("Отправить текст") {
                val s = input.text.toString()
                if (s.isNotEmpty()) {
                    Net.send("TEXT " + Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                    input.setText("")
                }
            }, lp(8))
        }
        val rows = listOf(
            listOf("Esc" to "esc", "Tab" to "tab", "⌫" to "backspace", "Enter" to "enter", "Del" to "delete"),
            listOf("←" to "left", "↑" to "up", "↓" to "down", "→" to "right", "Space" to "space"),
            listOf("Win" to "win", "Alt+Tab" to "alt+tab", "Win+D" to "win+d", "Ctrl+C" to "ctrl+c", "Ctrl+V" to "ctrl+v"),
            listOf("Гр.−" to "volume_down", "Гр.+" to "volume_up", "Mute" to "volume_mute", "Пауза" to "play_pause", "След." to "next_track")
        )
        for (r in rows) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((name, key) in r) {
                row.addView(
                    button(name, Pal.CARD) { Net.send("KEY $key") }.apply { textSize = 13f },
                    weight()
                )
            }
            col.addView(row, lp(6))
        }
        return ScrollView(this).apply { addView(col) }
    }
}
