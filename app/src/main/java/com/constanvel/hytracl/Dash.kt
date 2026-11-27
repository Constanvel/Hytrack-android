package com.constanvel.hytracl

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.setPadding
import androidx.core.view.updatePadding

/*
 * Staff and management dashboards (Figma 43:2 frames 11, 12, 18, 21–30), in their phone layout:
 * resources/css/dashboard.css below 768px, where the sidebar becomes a two-column section grid
 * above the page. Built in code like Figma.kt; colours are the dashboard.css values.
 */

object Hex {
    val TEXT = Color.parseColor("#173D3B")
    val MUTED = Color.parseColor("#5C706C")
    val PRIMARY = Color.parseColor("#086F65")
    val PALE = Color.parseColor("#E1EFE7")
    val PALE_BORDER = Color.parseColor("#CFE3D8")
    val CARD_BORDER = Color.parseColor("#DFE9E1")
    val FIELD_BORDER = Color.parseColor("#DCE8E1")
    val PAGE = Color.parseColor("#F3F7F3")
    val RULE = Color.parseColor("#DCE3DD")
    val WARNING = Color.parseColor("#FFF0D1")
    val WARNING_TEXT = Color.parseColor("#805216")
    val WARM = Color.parseColor("#8D5511")
    val WARM_BG = Color.parseColor("#FFF1D2")
    val INFO = Color.parseColor("#E5EDF5")
    val INFO_TEXT = Color.parseColor("#345978")
    val ABOVE = Color.parseColor("#BC6748")
    val MINT = Color.parseColor("#4C9B85")
    val SLATE = Color.parseColor("#6F9BA7")
    val DONE = Color.parseColor("#ECEEEA")
    val DANGER = Color.parseColor("#B02E29")
}

fun View.shape(fill: Int, radius: Number, stroke: Int? = null) = GradientDrawable().apply {
    setColor(fill)
    cornerRadius = dp(radius).toFloat()
    stroke?.let { setStroke(dp(1), it) }
}

fun View.gradient(start: Int, end: Int, radius: Number, orientation: GradientDrawable.Orientation = GradientDrawable.Orientation.TL_BR) =
    GradientDrawable(orientation, intArrayOf(start, end)).apply { cornerRadius = dp(radius).toFloat() }

/** Inter text in a raw colour; [size] in sp, line-height 1.4 as in dashboard.css. */
fun ViewGroup.label(value: CharSequence?, size: Float, weight: Weight = Weight.REGULAR, color: Int = Hex.TEXT, line: Float = size * 1.4f): TextView =
    TextView(context).also {
        it.text = value
        it.textSize = size
        it.setLineSpacing(0f, 1f)
        it.lineHeight = dp(line)
        it.includeFontPadding = false
        it.typeface = ResourcesCompat.getFont(context, weight.font)
        it.setTextColor(color)
        addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

fun ViewGroup.stack(gap: Number, padding: Number = 0): LinearLayout = LinearLayout(context).also {
    it.orientation = LinearLayout.VERTICAL
    it.showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
    it.dividerDrawable = GradientDrawable().apply { setSize(0, it.dp(gap)) }
    it.setPadding(it.dp(padding))
    addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
}

fun ViewGroup.row(gap: Number = 0, gravity: Int = Gravity.CENTER_VERTICAL): LinearLayout = LinearLayout(context).also {
    it.orientation = LinearLayout.HORIZONTAL
    it.gravity = gravity
    if (gap.toFloat() > 0) {
        it.showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        it.dividerDrawable = GradientDrawable().apply { setSize(it.dp(gap), 0) }
    }
    addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
}

/** Give a child of a row a share of its width. */
fun <T : View> T.weight(w: Float = 1f): T = also {
    it.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, w)
}

fun <T : View> T.wrap(): T = also {
    it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
}

/** .card: white, hairline, 20dp corners, 16dp padding on phones. */
fun ViewGroup.card(gap: Number = 12, padding: Number = 16, fill: Int = Color.WHITE, stroke: Int? = Hex.CARD_BORDER, radius: Number = 20) =
    stack(gap, padding).also { it.background = it.shape(fill, radius, stroke) }

/** .eyebrow: 11sp bold caps. */
fun ViewGroup.eyebrow(value: String, color: Int = Hex.MUTED) = label(value, 11f, Weight.BOLD, color)

/** .text-button: teal, semibold, at least 44dp tall. */
fun ViewGroup.link(value: String, color: Int = Hex.PRIMARY, enabled: Boolean = true, onClick: () -> Unit): TextView =
    label(value, 14f, Weight.SEMIBOLD, color).also {
        it.minHeight = dp(44)
        it.gravity = Gravity.CENTER_VERTICAL
        it.isEnabled = enabled
        it.alpha = if (enabled) 1f else 0.45f
        it.setOnClickListener { onClick() }
    }

enum class Btn { PRIMARY, SECONDARY, WHITE, DANGER }

/** .button variants of dashboard.css: 46dp, 12dp corners, 14sp semibold. */
fun ViewGroup.action(value: String, kind: Btn = Btn.PRIMARY, enabled: Boolean = true, onClick: () -> Unit): TextView =
    label(value, 14f, Weight.SEMIBOLD, when (kind) {
        Btn.PRIMARY -> Color.WHITE
        Btn.DANGER -> Hex.DANGER
        else -> Hex.PRIMARY
    }).also {
        it.gravity = Gravity.CENTER
        it.minHeight = dp(46)
        it.updatePadding(left = dp(12), right = dp(12), top = dp(8), bottom = dp(8))
        it.background = when (kind) {
            Btn.PRIMARY -> it.gradient(ContextCompat.getColor(context, R.color.gradient_start), ContextCompat.getColor(context, R.color.gradient_end), 12)
            Btn.SECONDARY -> it.shape(Hex.PALE, 12)
            Btn.WHITE -> it.shape(Color.WHITE, 12, Hex.FIELD_BORDER)
            Btn.DANGER -> it.shape(Color.parseColor("#FFF4F2"), 12)
        }
        it.isEnabled = enabled
        it.alpha = if (enabled) 1f else 0.55f
        it.setOnClickListener { onClick() }
    }

/** Field look of .dashboard-filters: white, #dce8e1 hairline, 12dp corners. */
fun ViewGroup.field(hint: String, value: String = "", type: Int = InputType.TYPE_CLASS_TEXT, onChange: (String) -> Unit = {}): EditText =
    EditText(context).also {
        it.hint = hint
        it.setText(value)
        it.inputType = type
        it.textSize = 14f
        it.typeface = ResourcesCompat.getFont(context, R.font.inter_regular)
        it.setTextColor(Hex.TEXT)
        it.setHintTextColor(Hex.MUTED)
        it.background = it.shape(Color.WHITE, 12, Hex.FIELD_BORDER)
        it.setPadding(it.dp(12))
        it.minHeight = it.dp(46)
        if (type and InputType.TYPE_TEXT_FLAG_MULTI_LINE == 0) it.maxLines = 1
        it.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = onChange(s.toString())
        })
        addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

/** <select>: the chosen option with a chevron; tapping lists the options. */
fun ViewGroup.select(title: String, options: List<String>, chosen: Int, enabled: Boolean = true, onPick: (Int) -> Unit): LinearLayout =
    row(8).also { box ->
        box.background = box.shape(Color.WHITE, 12, Hex.FIELD_BORDER)
        box.setPadding(box.dp(12))
        box.minimumHeight = box.dp(46)
        box.contentDescription = title
        box.label(options.getOrNull(chosen) ?: "—", 14f, Weight.SEMIBOLD).weight()
        box.addView(android.widget.ImageView(context).apply {
            setImageResource(R.drawable.ic_chevron_down)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(box.dp(16), box.dp(16)))
        box.isEnabled = enabled
        box.alpha = if (enabled) 1f else 0.55f
        box.setOnClickListener {
            AlertDialog.Builder(context).setTitle(title)
                .setSingleChoiceItems(options.toTypedArray(), chosen) { d, which -> d.dismiss(); onPick(which) }
                .show()
        }
    }

/** .filter-box: a read-only value in the field look. */
fun ViewGroup.box(value: String) = label(value, 14f, Weight.MEDIUM).also {
    it.background = it.shape(Color.WHITE, 12, Hex.FIELD_BORDER)
    it.setPadding(it.dp(12))
    it.minHeight = it.dp(46)
    it.gravity = Gravity.CENTER_VERTICAL
}

/** Stat (dashboard-shell.tsx): accent bar on the top edge, label, value beside its detail. */
fun ViewGroup.stat(label: String, value: String, detail: String, accent: Int? = null, valueColor: Int = Hex.TEXT, stacked: Boolean = false) {
    val c = card(if (stacked) 4 else 2, 14)
    if (accent != null) c.background = LayerDrawable(arrayOf(c.shape(Color.WHITE, 20, Hex.CARD_BORDER), c.shape(accent, 2))).apply {
        setLayerGravity(1, Gravity.TOP or Gravity.START)
        setLayerInsetLeft(1, c.dp(18))
        setLayerSize(1, c.dp(42), c.dp(4))
    }
    c.label(label, 13f, Weight.MEDIUM, Hex.MUTED)
    if (stacked) {
        c.label(value, 24f, Weight.BOLD, valueColor)
        c.label(detail, 11f, color = Hex.MUTED)
    } else {
        val r = c.row(16)
        r.label(value, 28f, Weight.BOLD, valueColor).weight()
        r.label(detail, 12f, color = Hex.MUTED).weight()
    }
}

/** .table-scroll: a table wider than the phone scrolls sideways, cells on one line. */
fun ViewGroup.table(minWidth: Number = 440): LinearLayout {
    val scroller = android.widget.HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
    addView(scroller, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    val width = scroller.dp(minWidth)
    // The scroller measures its child unbounded, so weights would get no room: measure exactly.
    val inner = object : LinearLayout(context) {
        override fun onMeasure(widthSpec: Int, heightSpec: Int) =
            super.onMeasure(MeasureSpec.makeMeasureSpec(maxOf(width, scroller.width), MeasureSpec.EXACTLY), heightSpec)
    }.apply { orientation = LinearLayout.VERTICAL }
    scroller.addView(inner, ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
    return inner
}

/** A table row: cells share the width on one line; the first is bold. */
fun ViewGroup.cells(values: List<CharSequence>, weights: List<Float>, header: Boolean = false, highlight: Boolean = false): LinearLayout =
    row(6).also { r ->
        r.setPadding(r.dp(8), r.dp(if (header) 0 else 10), r.dp(8), r.dp(if (header) 8 else 10))
        if (highlight) r.background = r.shape(Hex.PALE, 8)
        values.forEachIndexed { i, v ->
            r.label(v, if (header) 12f else 13f, if (header) Weight.SEMIBOLD else if (i == 0) Weight.BOLD else Weight.REGULAR, if (header) Hex.MUTED else Hex.TEXT)
                .weight(weights[i]).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        }
    }

/** "←  1  2  3  …  16  →" (dashboard-shell Pager). */
fun ViewGroup.pager(page: Int, pages: Int, onPage: (Int) -> Unit) {
    if (pages < 2) return
    val shown = when {
        pages <= 5 -> (1..pages).toList()
        page <= 3 -> listOf(1, 2, 3, 0, pages)
        page >= pages - 2 -> listOf(1, 0, pages - 2, pages - 1, pages)
        else -> listOf(1, 0, page, 0, pages)
    }
    val r = row(4)
    fun cell(text: String, enabled: Boolean, current: Boolean = false, go: () -> Unit) = r.label(text, 13f, if (current) Weight.BOLD else Weight.SEMIBOLD, Hex.PRIMARY).also {
        it.wrap()
        it.minWidth = it.dp(36)
        it.minHeight = it.dp(36)
        it.gravity = Gravity.CENTER
        if (current) it.background = it.shape(Hex.PALE, 8)
        it.isEnabled = enabled
        it.alpha = if (enabled) 1f else 0.4f
        it.setOnClickListener { go() }
    }
    cell("←", page > 1) { onPage(page - 1) }
    shown.forEach { n -> if (n == 0) cell("…", false) {} else cell("$n", true, n == page) { onPage(n) } }
    cell("→", page < pages) { onPage(page + 1) }
}

/** Notice of the dashboard: mint, warning or info, 12dp corners. */
fun ViewGroup.flag(value: CharSequence, tone: Tone = Tone.GREEN) = label(value, 13f, Weight.SEMIBOLD, when (tone) {
    Tone.GREEN -> Hex.PRIMARY
    Tone.WARNING -> Hex.WARNING_TEXT
    Tone.INFO -> Hex.INFO_TEXT
}).also {
    it.background = it.shape(when (tone) {
        Tone.GREEN -> Hex.PALE
        Tone.WARNING -> Hex.WARNING
        Tone.INFO -> Hex.INFO
    }, 12)
    it.setPadding(it.dp(12), it.dp(9), it.dp(12), it.dp(9))
}

fun <T> List<T>.page(page: Int, size: Int): Triple<List<T>, Int, Int> {
    val pages = maxOf(1, (this.size + size - 1) / size)
    val current = page.coerceIn(1, pages)
    return Triple(drop((current - 1) * size).take(size), current, pages)
}

fun View.confirm(question: String, yes: () -> Unit) {
    AlertDialog.Builder(context).setMessage(question)
        .setPositiveButton("Ya") { _, _ -> yes() }
        .setNegativeButton("Batal", null)
        .show()
}

/**
 * DashboardShell: brand, section grid and Keluar on the gradient rail, then the topbar and the
 * chosen section. Every action re-renders the page from Ops, like an Inertia reload.
 */
abstract class DashboardActivity : AppCompatActivity() {
    protected abstract val role: Local.Role
    protected abstract val sections: List<String>
    protected abstract fun crumb(section: String): String
    protected abstract fun content(into: LinearLayout, section: String)
    /** The section a fresh session opens on. */
    protected open fun home() = sections[if (role == Local.Role.STAFF) 1 else 0]
    /** Rail eyebrow: the staff member's room or the management area. */
    protected open fun roomLabel() = if (role == Local.Role.STAFF) "RUANG PETUGAS" else "MANAJEMEN LAYANAN"

    protected var section = ""
    private lateinit var scroll: ScrollView
    private lateinit var rail: LinearLayout
    private lateinit var topbar: LinearLayout
    protected lateinit var main: LinearLayout

    /** One-shot status line after an action (Inertia flash), and a refused action's message. */
    protected var flash: String? = null
    protected var error: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Local.role != role) return signedOut()
        section = savedInstanceState?.getString("section") ?: home()
        lightEdgeToEdge()
        scroll = ScrollView(this).apply { setBackgroundColor(Hex.PAGE); isFillViewport = true }
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(page)
        rail = page.stack(16, 0).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#173D3B"), Color.parseColor("#235C50")))
        }
        val body = page.stack(16, 0).apply { setPadding(dp(16), dp(16), dp(16), dp(28)) }
        topbar = body.row(8).apply {
            background = shape(Color.WHITE, 20, Hex.PALE_BORDER)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        main = body.stack(16)
        // The rail's dark green stays behind the status bar while the page scrolls under it.
        val root = android.widget.FrameLayout(this)
        root.addView(scroll)
        val scrim = View(this).apply { setBackgroundColor(Color.parseColor("#173D3B")) }
        root.addView(scrim, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))
        setContentView(root)
        androidx.core.view.WindowCompat.getInsetsController(window, root).isAppearanceLightStatusBars = false
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            scrim.layoutParams.height = bars.top
            scrim.requestLayout()
            // Shrinking the scroller (not padding it) lets ScrollView bring the focused field above the keyboard.
            scroll.updatePadding(top = bars.top, bottom = if (ime > 0) 0 else bars.bottom)
            (scroll.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = ime
            scroll.requestLayout()
            rail.setPadding(v.dp(16), v.dp(18), v.dp(16), v.dp(18))
            insets
        }
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("section", section)
    }

    protected fun open(target: String) {
        section = target
        flash = null
        error = null
        render()
        scroll.post { scroll.scrollTo(0, 0) }
    }

    /** Runs a staff action: a refusal shows as a warning, success as the next flash line. */
    protected fun act(block: () -> String) {
        try {
            flash = block()
            error = null
        } catch (e: Ops.Refused) {
            error = e.message
        }
        render()
    }

    protected fun render() {
        rail.removeAllViews()
        rail.label("HyTrack", 25f, Weight.BOLD, Color.WHITE)
        rail.label(roomLabel(), 10f, Weight.SEMIBOLD, Color.parseColor("#B8DED1"))
        val grid = rail.stack(8)
        sections.chunked(2).forEach { pair ->
            val r = grid.row(8)
            pair.forEach { item ->
                val current = item == section
                r.label(item, 12f, if (current) Weight.SEMIBOLD else Weight.MEDIUM, if (current) Color.WHITE else Color.parseColor("#C9DAD4")).also {
                    it.weight()
                    it.minHeight = it.dp(44)
                    it.gravity = Gravity.CENTER_VERTICAL
                    it.setPadding(it.dp(10))
                    if (current) it.background = it.gradient(Color.parseColor("#086B62"), Color.parseColor("#188777"), 12)
                    it.isSelected = current
                    it.setOnClickListener { open(item) }
                }
            }
            if (pair.size == 1) r.addView(View(this).weight())
        }
        rail.label("Keluar", 12f, Weight.MEDIUM, Color.parseColor("#C9DAD4")).also {
            it.wrap()
            it.minHeight = it.dp(44)
            it.gravity = Gravity.CENTER_VERTICAL
            it.setOnClickListener {
                Demo.signOut()
                signedOut()
            }
        }

        topbar.removeAllViews()
        topbar.label(crumb(section), 10f, Weight.MEDIUM, Hex.MUTED).weight()
        // Below 400dp the date goes under the status: side by side they leave the crumb a letter per line.
        val meta = if (resources.configuration.screenWidthDp >= 400) topbar else topbar.stack(2).also { it.wrap(); it.gravity = Gravity.END }
        meta.label(status(), 10f, Weight.SEMIBOLD, Hex.PRIMARY).wrap()
        meta.label(Ops.dateLabel(Ops.today()).substringAfter(", "), 10f, Weight.MEDIUM, Hex.MUTED).wrap()

        main.removeAllViews()
        error?.let { main.flag(it, Tone.WARNING) }
        content(main, section)
        rendered()
    }

    /** Back to Login · Petugas dan Manajemen (signed out, or the API ended the session). */
    protected fun signedOut() {
        startActivity(Intent(this, InternalLoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    /** After each render: a section that holds a device resource (the camera) releases it here. */
    protected open fun rendered() = Unit

    protected open fun status() = "● Terhubung  ·  ${Ops.clock(java.time.Instant.now())} WIB"

    /** DashboardTitle: 25sp title, 12sp subtitle, and its pill or button. */
    protected fun LinearLayout.heading(title: String, subtitle: String, aside: (LinearLayout.() -> Unit)? = null) {
        val r = row(12, Gravity.TOP)
        val text = r.stack(4).weight()
        text.label(title, 25f, Weight.BOLD)
        text.label(subtitle, 12f, color = Hex.MUTED)
        aside?.invoke(r)
    }

    /** "Data contoh · 08.54" pill. */
    protected fun LinearLayout.sample(text: String = "Data contoh · ${Ops.clock(java.time.Instant.now())}") =
        label(text, 9f, Weight.SEMIBOLD, Hex.PRIMARY).also {
            it.wrap()
            it.background = it.shape(Hex.PALE, 16)
            it.setPadding(it.dp(10), it.dp(6), it.dp(10), it.dp(6))
        }

    protected fun LinearLayout.footnote(text: String) = label(text, 12f, color = Hex.MUTED)

    /** A list that re-renders alone, so typing in its search field keeps the keyboard. */
    protected fun LinearLayout.live(build: LinearLayout.() -> Unit): () -> Unit {
        val holder = stack(12)
        val redraw = { holder.removeAllViews(); holder.build() }
        redraw()
        return redraw
    }

}
