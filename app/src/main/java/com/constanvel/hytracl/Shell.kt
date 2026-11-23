package com.constanvel.hytracl

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.annotation.LayoutRes
import androidx.annotation.StyleRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import androidx.core.view.updatePaddingRelative
import com.constanvel.hytracl.databinding.ActivityShellBinding
import com.constanvel.hytracl.databinding.ViewDemoControlsBinding
import com.constanvel.hytracl.databinding.ViewGuideHintBinding
import com.constanvel.hytracl.databinding.ViewNavItemBinding
import com.constanvel.hytracl.databinding.ViewRowLinkBinding
import com.constanvel.hytracl.databinding.ViewStepBinding

enum class Tab { HOME, VISIT, ACCOUNT }
enum class Decor { WAVES, CORNER }
enum class Tone { GREEN, WARNING, INFO }

/** Props of the web Shell component. */
class Spec(
    val title: String,
    val subtitle: String? = null,
    val back: Class<out Activity>? = null,
    val brand: Boolean = false,
    val hya: Boolean = false,
    val queue: Boolean = false,
    val bare: Boolean = false,
    val decor: Decor? = null,
    val guide: GuideStep? = null,
    /** The screen pins its own action bar in the footer in place of the bottom nav. */
    val action: Boolean = false,
)

/** Navigates like a web link: returns to the screen if it is already in the stack. */
fun Activity.go(target: Class<out Activity>, extras: Intent.() -> Unit = {}) {
    startActivity(Intent(this, target).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).apply(extras))
}

fun View.dp(value: Number) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

fun ViewGroup.add(@LayoutRes layout: Int): View = LayoutInflater.from(context).inflate(layout, this, false).also { addView(it) }

fun ViewGroup.text(@StyleRes style: Int, value: CharSequence): TextView =
    TextView(context, null, 0, style).also {
        it.text = value
        addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

/** Notice component. */
fun ViewGroup.notice(value: CharSequence, tone: Tone = Tone.GREEN) = text(
    when (tone) {
        Tone.GREEN -> R.style.Widget_HyTrack_Notice
        Tone.WARNING -> R.style.Widget_HyTrack_Notice_Warning
        Tone.INFO -> R.style.Widget_HyTrack_Notice_Info
    }, value,
)

/**
 * Steps component (components/ui.tsx Steps): marked card; steps before [done] ticked, [current] filled teal.
 * [numbered] is the guide variant (Figma 23): teal "01" in place of the circle, text aligned to the top.
 */
fun ViewGroup.steps(vararg items: Pair<String, String>, done: Int = 0, current: Int = -1, numbered: Boolean = false) {
    removeAllViews()
    if (numbered) {
        setBackgroundResource(R.drawable.bg_card)
        updatePadding(left = dp(20), top = dp(22), right = dp(20), bottom = dp(22))
        (this as? LinearLayout)?.dividerDrawable = ContextCompat.getDrawable(context, R.drawable.gap_22)
    } else {
        setBackgroundResource(R.drawable.bg_card_marked)
    }
    items.forEachIndexed { index, (title, detail) ->
        ViewStepBinding.inflate(LayoutInflater.from(context), this, true).apply {
            val isDone = index < done
            val isCurrent = !isDone && index == current
            stepNumber.text = when {
                isDone -> "✓"
                numbered -> "%02d".format(index + 1)
                else -> "${index + 1}"
            }
            stepTitle.text = title
            stepDetail.text = detail
            if (isDone) {
                stepNumber.textSize = 13f
                stepNumber.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                stepTitle.contentDescription = context.getString(R.string.selesai_step, title)
            }
            if (isCurrent) {
                stepNumber.setBackgroundResource(R.drawable.bg_step_current)
                stepNumber.setTextColor(ContextCompat.getColor(context, R.color.white))
                ViewCompat.setStateDescription(root, context.getString(R.string.langkah_saat_ini))
            }
            if (numbered) {
                root.gravity = android.view.Gravity.TOP
                stepNumber.background = null
                stepNumber.updateLayoutParams<LinearLayout.LayoutParams> { width = dp(24); height = dp(22) }
                (stepTitle.parent as View).updateLayoutParams<LinearLayout.LayoutParams> { marginStart = dp(16) }
                stepDetail.textSize = 13f
                stepDetail.lineHeight = dp(20.15)
                stepDetail.updateLayoutParams<LinearLayout.LayoutParams> { topMargin = dp(4) }
            }
        }
    }
}

/** `<ol class="plain-list">`: muted numbered lines, markers in an 18px gutter. */
fun ViewGroup.plainList(vararg items: String) {
    removeAllViews()
    items.forEachIndexed { index, item ->
        val row = LinearLayout(context)
        listOf("${index + 1}.", item).forEachIndexed { column, value ->
            TextView(context, null, 0, R.style.Text).apply {
                text = value
                setTextColor(ContextCompat.getColor(context, R.color.muted))
                lineHeight = dp(21.7) // line-height: 1.55
                if (column == 0) {
                    gravity = android.view.Gravity.END
                    updatePadding(right = dp(4))
                    row.addView(this, LinearLayout.LayoutParams(dp(18), ViewGroup.LayoutParams.WRAP_CONTENT))
                } else {
                    row.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
            }
        }
        addView(row)
    }
}

/**
 * CSS puts half of a line-height's leading above the first line and half below the last;
 * Android only adds it between lines. Pads every TextView under [root] by that half-leading
 * so text blocks get their web height. Applied once per view (tagged).
 */
fun cssLeading(root: View) {
    if (root is TextView && root !is android.widget.EditText && root.getTag(R.id.leading) == null) {
        val fm = root.paint.fontMetricsInt
        val extra = root.lineHeight - (fm.descent - fm.ascent)
        if (extra > 0) root.updatePadding(top = root.paddingTop + extra / 2, bottom = root.paddingBottom + extra - extra / 2)
        root.setTag(R.id.leading, true)
    }
    if (root is ViewGroup) for (i in 0 until root.childCount) cssLeading(root.getChildAt(i))
}

fun ViewRowLinkBinding.bind(title: String, detail: String? = null, onClick: () -> Unit) {
    rowTitle.text = title
    rowDetail.text = detail
    rowDetail.isVisible = detail != null
    root.setOnClickListener { onClick() }
}

/** Demo controls fieldset: the patient plays the staff's part. */
fun ViewDemoControlsBinding.bind(legend: String, label: String, secondary: Boolean = false, onClick: () -> Unit) {
    demoLegend.text = legend
    demoAction.text = label
    demoAction.setBackgroundResource(if (secondary) R.drawable.bg_btn_secondary else R.drawable.bg_btn_primary)
    demoAction.setTextColor(ContextCompat.getColor(root.context, if (secondary) R.color.primary else R.color.white))
    demoAction.setOnClickListener { onClick() }
}

/**
 * Base for every patient screen: renders the shell (components/shell.tsx) and lets the
 * screen fill `content`. Screens re-render on resume and after actions, like Inertia props.
 */
abstract class ShellActivity : AppCompatActivity() {
    lateinit var shell: ActivityShellBinding
    protected open val tab = Tab.VISIT
    protected open val requiresSignIn = true
    protected abstract fun spec(): Spec
    protected abstract fun content(into: LinearLayout)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (requiresSignIn && !Demo.signedIn) {
            startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            finish()
            return
        }
        enableEdgeToEdge()
        shell = ActivityShellBinding.inflate(layoutInflater)
        setContentView(shell.root)
        applyInsets()
        nav(shell.navHome, R.drawable.ic_home, getString(R.string.beranda), Tab.HOME, HomeActivity::class.java)
        nav(shell.navVisit, R.drawable.ic_ticket, getString(R.string.kunjungan), Tab.VISIT, VisitActivity::class.java)
        nav(shell.navAccount, R.drawable.ic_user, getString(R.string.akun), Tab.ACCOUNT, AccountActivity::class.java)
        shell.fab.setOnClickListener { go(HyaActivity::class.java) }
        shell.bell.setOnClickListener { go(NotificationsActivity::class.java) }
        shell.close.setOnClickListener { go(VisitActivity::class.java) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A link to a screen already in the stack still opens it at the top, like a page load.
        if (::shell.isInitialized) shell.scroll.scrollTo(0, 0)
    }

    override fun onResume() {
        super.onResume()
        if (::shell.isInitialized) render()
    }

    private fun nav(item: ViewNavItemBinding, icon: Int, label: String, target: Tab, screen: Class<out Activity>) {
        item.icon.setImageResource(icon)
        item.label.text = label
        val current = tab == target
        item.marker.setBackgroundResource(if (current) R.drawable.bg_nav_marker else 0)
        item.label.typeface = ResourcesCompat.getFont(this, if (current) R.font.inter_semibold else R.font.inter_medium)
        item.label.setTextColor(ContextCompat.getColor(this, if (current) R.color.primary else R.color.muted))
        item.root.isSelected = current
        item.root.setOnClickListener { if (!current || this::class.java != screen) go(screen) }
    }

    private fun applyInsets() {
        val navBase = resources.getDimensionPixelSize(R.dimen.bottom_nav_height)
        ViewCompat.setOnApplyWindowInsetsListener(shell.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val bottom = maxOf(bars.bottom, ime.bottom)
            // Margin, not padding: content must not scroll under the transparent status bar.
            shell.scroll.updateLayoutParams<FrameLayout.LayoutParams> { topMargin = bars.top }
            // Room for the bottom nav or the pinned footer only, none for the Hya bubble: the last
            // section ends at the content's own 28dp above the bar. The bubble tucks away instead.
            val bar = when {
                shell.bottomNav.isVisible -> navBase + bars.bottom
                shell.footer.isVisible -> maxOf(shell.footer.height, bottom)
                else -> bars.bottom
            }
            shell.page.updatePadding(bottom = bar)
            shell.bottomNav.updateLayoutParams { height = navBase + bars.bottom }
            shell.bottomNav.updatePadding(bottom = shell.root.dp(6) + bars.bottom)
            shell.fab.updateLayoutParams<FrameLayout.LayoutParams> { bottomMargin = shell.root.dp(95) + bars.bottom }
            shell.footer.updatePadding(bottom = bottom)
            shell.scroll.updatePadding(bottom = if (shell.footer.isVisible) ime.bottom else 0)
            // .app-shell { max-width: 540px; margin: auto }: centre the column on wide screens.
            val side = maxOf(0, (shell.root.width - shell.root.dp(540)) / 2)
            shell.scroll.updatePadding(left = side, right = side)
            listOf(shell.bottomNav, shell.footer).forEach { v ->
                v.updateLayoutParams<FrameLayout.LayoutParams> { leftMargin = side; rightMargin = side }
            }
            shell.fab.updateLayoutParams<FrameLayout.LayoutParams> { marginEnd = shell.root.dp(15) + side }
            // The accent ends the page right above the bottom bar (web: bottom 80px + safe area).
            decorInset = bar
            placeDecor()
            insets
        }
        shell.root.addOnLayoutChangeListener { v, l, _, r, _, ol, _, or, _ -> if (r - l != or - ol) ViewCompat.requestApplyInsets(v) }
        shell.footer.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob -> if (b - t != ob - ot) ViewCompat.requestApplyInsets(shell.root) }
        shell.scroll.setOnScrollChangeListener { _: View, _: Int, _: Int, _: Int, _: Int -> tuckFab() }
        shell.content.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> tuckFab() }
    }

    private var tucked = false

    /** At the page end the Hya bubble gives way to the link or button under it. */
    private fun tuckFab() {
        val fab = shell.fab
        if (fab.visibility == View.GONE) return
        val box = fab.screenRect()
        val covers = !shell.scroll.canScrollVertically(1) &&
            shell.content.controls().any { Rect.intersects(it.screenRect(), box) }
        if (covers == tucked) return
        tucked = covers
        fab.animate().cancel()
        if (!covers) fab.visibility = View.VISIBLE
        fab.animate().alpha(if (covers) 0f else 1f).setDuration(180)
            .withEndAction { if (covers) fab.visibility = View.INVISIBLE }
    }

    private fun View.screenRect(): Rect {
        val at = IntArray(2).also(::getLocationOnScreen)
        return Rect(at[0], at[1], at[0] + width, at[1] + height)
    }

    private fun View.controls(): Sequence<View> = when {
        !isShown -> emptySequence()
        isClickable -> sequenceOf(this)
        this is ViewGroup -> (0 until childCount).asSequence().flatMap { getChildAt(it).controls() }
        else -> emptySequence()
    }

    private var decorInset = 0
    private var decor: Decor? = null

    private fun placeDecor() {
        val view = shell.decor
        view.isVisible = decor != null
        when (decor) {
            Decor.WAVES -> {
                view.setImageResource(R.drawable.wave_mint)
                view.scaleType = android.widget.ImageView.ScaleType.FIT_XY
                view.alpha = 1f
                view.updateLayoutParams<FrameLayout.LayoutParams> {
                    width = ViewGroup.LayoutParams.MATCH_PARENT
                    height = view.dp(170)
                    gravity = android.view.Gravity.BOTTOM
                    marginEnd = 0
                    bottomMargin = decorInset
                }
            }
            // right -32px bottom -12px / 250px 354px, opacity .8
            Decor.CORNER -> {
                view.setImageResource(R.drawable.decor_corner)
                view.scaleType = android.widget.ImageView.ScaleType.FIT_XY
                view.alpha = 0.8f
                view.updateLayoutParams<FrameLayout.LayoutParams> {
                    width = view.dp(250)
                    height = view.dp(354)
                    gravity = android.view.Gravity.BOTTOM or android.view.Gravity.END
                    marginEnd = -view.dp(32)
                    bottomMargin = decorInset - view.dp(12)
                }
            }
            null -> Unit
        }
    }

    /** A state-changing request (Inertia post/delete): re-render and start at the top again. */
    fun visit(action: () -> Unit) {
        action()
        render()
        shell.scroll.scrollTo(0, 0)
    }

    /** Rebuilds the screen from the current demo state. */
    fun render() {
        val s = spec()
        shell.back.isVisible = s.back != null
        s.back?.let { target -> shell.back.setOnClickListener { go(target) } }
        shell.headerAvatar.root.isVisible = s.hya
        // Hya (Figma 43:2 frames 19/21): back chip at x 11, Hya at 32dp from x 74, title at x 118.
        shell.header.updatePaddingRelative(
            start = shell.root.dp(if (s.hya) 11 else 24),
            end = shell.root.dp(if (s.hya) 12 else 24),
        )
        shell.back.updateLayoutParams<LinearLayout.LayoutParams> {
            marginStart = shell.root.dp(if (s.hya) 0 else -8)
            marginEnd = shell.root.dp(if (s.hya) 11 else 2)
        }
        shell.headerAvatar.root.updateLayoutParams<LinearLayout.LayoutParams> {
            width = shell.root.dp(if (s.hya) 32 else 44)
            height = width
        }
        // Hya's art keeps its crop at the smaller size (web: 49.6px wide, 9px left).
        (shell.headerAvatar.root as ViewGroup).getChildAt(0).updateLayoutParams<ViewGroup.MarginLayoutParams> {
            width = shell.root.dp(if (s.hya) 49.6 else 68)
            height = shell.root.dp(if (s.hya) 37.2 else 51)
            marginStart = shell.root.dp(if (s.hya) -9 else -12)
        }
        shell.heading.isVisible = !s.brand
        shell.lockup.isVisible = s.brand
        shell.title.text = s.title
        shell.subtitle.text = s.subtitle
        shell.subtitle.isVisible = s.subtitle != null
        // Sub-pages: a 21sp title beside the back chip and a 10sp medium caption (Figma 31:2).
        shell.title.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (s.back != null) 21f else 24f)
        shell.subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (s.back != null) 10f else 12f)
        shell.subtitle.typeface = ResourcesCompat.getFont(this, if (s.back != null) R.font.inter_medium else R.font.inter_regular)
        shell.bell.isVisible = !s.hya && s.back == null && !s.bare
        shell.unreadDot.isVisible = Demo.unread > 0
        shell.bell.contentDescription = if (Demo.unread > 0) getString(R.string.notifikasi_belum_dibaca, Demo.unread) else getString(R.string.notifications)
        shell.close.isVisible = s.hya
        shell.header.setBackgroundResource(if (s.hya) R.drawable.bg_header_rule else 0)
        shell.bottomNav.isVisible = !s.hya && !s.bare && !s.action
        shell.fab.animate().cancel()
        shell.fab.alpha = 1f
        tucked = false
        shell.fab.isVisible = !s.hya && !s.bare
        shell.footer.isVisible = s.hya || s.action
        ViewCompat.requestApplyInsets(shell.root)
        decor = s.decor
        placeDecor()

        val content = shell.content
        content.removeAllViews()
        content.dividerDrawable = if (s.queue) null else ContextCompat.getDrawable(this, R.drawable.gap_20)
        Demo.flash?.let { content.notice(it, Demo.flashTone) }
        Demo.flash = null
        Demo.flashTone = Tone.GREEN
        val guiding = Guide.visible(this, s.guide)
        if (guiding) guideHint(content, s.guide!!)
        content(content)
        content.findViewById<View>(R.id.hyaHelp)?.let { card ->
            card.setOnClickListener { go(HyaActivity::class.java) }
            // The Hya card already shows Hya (Figma 43:2 frame 04): no bubble on top of it.
            if (card.isVisible) shell.fab.isVisible = false
        }
        if (guiding) {
            highlight(content)
            highlight(shell.footer)
        }
        cssLeading(shell.root)
    }

    private fun guideHint(content: LinearLayout, step: GuideStep) {
        val hint = ViewGuideHintBinding.inflate(layoutInflater, content, true)
        val index = GuideStep.entries.indexOf(step)
        hint.guideStep.text = getString(R.string.panduan_langkah_dari, index + 1, GuideStep.entries.size)
        GuideStep.entries.forEachIndexed { i, _ ->
            hint.guideProgress.addView(View(this).apply {
                setBackgroundResource(if (i <= index) R.drawable.bg_progress_done else R.drawable.bg_progress_todo)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        hint.guideTitle.setText(step.title)
        hint.guideText.setText(step.text)
        hint.guideOk.setOnClickListener { Guide.dismiss(this, step); render() }
        hint.guideSkip.setOnClickListener { Guide.skip(this); render() }
    }

    /** `.guide-active [data-guide]`: orange outline that pulses three times. */
    private fun highlight(root: View) {
        if (root.tag == "guide") {
            // The outline sits 7dp outside the view (outline-offset), so ancestors must not clip it.
            var parent = root.parent
            while (parent is ViewGroup && parent !== shell.scroll && parent !== shell.root) {
                parent.clipChildren = false
                parent.clipToPadding = false
                parent = parent.parent
            }
            root.foreground = ContextCompat.getDrawable(this, R.drawable.fg_guide_outline)
            ObjectAnimator.ofInt(root.foreground, "alpha", 255, 0, 255).apply {
                duration = 1600
                repeatCount = 2
                start()
            }
        }
        if (root is ViewGroup) for (i in 0 until root.childCount) highlight(root.getChildAt(i))
    }
}
