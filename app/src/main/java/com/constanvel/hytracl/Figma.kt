package com.constanvel.hytracl

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.FontRes
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.setPadding
import androidx.core.view.updatePadding

/*
 * Building blocks for the Inter frames of the Figma file (27:61 data pasien, 27:110/27:179 form,
 * 27:248 akun, 27:303 pantauan pendamping): same values as resources/css/patient-data.css and
 * companion.css on the web. Built in code like Shell's text()/notice() helpers.
 */

enum class Weight(@param:FontRes val font: Int) {
    REGULAR(R.font.inter_regular), MEDIUM(R.font.inter_medium), SEMIBOLD(R.font.inter_semibold), BOLD(R.font.inter_bold)
}

/** Inter text with a CSS line-height (cssLeading adds the half-leading above and below). */
fun ViewGroup.inter(
    value: CharSequence?,
    size: Float,
    line: Float,
    weight: Weight = Weight.REGULAR,
    @ColorRes color: Int = R.color.auth_text,
): TextView = TextView(context).also {
    it.text = value
    it.textSize = size
    it.lineHeight = dp(line)
    it.includeFontPadding = false
    it.typeface = ResourcesCompat.getFont(context, weight.font)
    it.setTextColor(ContextCompat.getColor(context, color))
    addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
}

fun View.rounded(@ColorRes fill: Int, radius: Number, @ColorRes stroke: Int? = null) = GradientDrawable().apply {
    setColor(ContextCompat.getColor(context, fill))
    cornerRadius = dp(radius).toFloat()
    stroke?.let { setStroke(dp(1), ContextCompat.getColor(context, it)) }
}

/** Vertical stack with a fixed gap between children (CSS flex column + gap). */
fun ViewGroup.column(gap: Number, @ColorRes fill: Int? = null, radius: Number = 0, padding: Number = 0, @ColorRes stroke: Int? = null): LinearLayout =
    LinearLayout(context).also {
        it.orientation = LinearLayout.VERTICAL
        it.showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        it.dividerDrawable = GradientDrawable().apply { setSize(0, it.dp(gap)) }
        if (fill != null) it.background = it.rounded(fill, radius, stroke)
        it.setPadding(it.dp(padding))
        addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

enum class Look { PRIMARY, OUTLINE, PALE, GHOST }

/** .button variants of the Inter frames: 16sp semibold, 10–14dp corners. */
fun ViewGroup.button(label: String, look: Look = Look.PRIMARY, height: Number = 50, radius: Number = 10, onClick: () -> Unit): TextView =
    inter(label, 16f, 22f, Weight.SEMIBOLD, if (look == Look.PRIMARY) R.color.white else R.color.brand).also {
        it.gravity = Gravity.CENTER
        it.minHeight = dp(height)
        it.updatePadding(left = dp(14), right = dp(14))
        it.background = when (look) {
            Look.PRIMARY -> pressed(R.color.brand, R.color.primary_hover, radius)
            Look.OUTLINE -> pressed(R.color.white, R.color.white_hover, radius, R.color.brand)
            Look.PALE -> pressed(R.color.auth_benefit, R.color.pale_hover, radius)
            Look.GHOST -> pressed(android.R.color.transparent, R.color.auth_benefit, radius)
        }
        it.isClickable = true
        it.isFocusable = true
        it.setOnClickListener { onClick() }
    }

private fun View.pressed(@ColorRes fill: Int, @ColorRes down: Int, radius: Number, @ColorRes stroke: Int? = null) = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_pressed), rounded(down, radius, stroke))
    addState(intArrayOf(), rounded(fill, radius, stroke))
}

/** .tip-card: pale mint, green title, muted text. */
fun ViewGroup.tipCard(title: String, text: String, padding: Number = 16, titleSize: Float = 14f, textSize: Float = 13f) =
    column(if (padding == 16) 6 else 4, R.color.auth_benefit, if (padding == 16) 12 else 14, padding).apply {
        inter(title, titleSize, titleSize + 6, Weight.SEMIBOLD, R.color.brand)
        inter(text, textSize, textSize + 6, color = R.color.muted) // #5C706C on the mint card is 4.4:1
    }

/** .lead: page heading (24/30 bold) and intro (15/21 muted). */
fun ViewGroup.lead(title: String, text: String, gap: Number = 8) = column(gap).apply {
    inter(title, 24f, 30f, Weight.BOLD)
    inter(text, 15f, 21f, color = R.color.brand_muted)
}

/** Input styled like .form-field input: white, 1dp border, 10dp corners, 14sp Inter. */
fun ViewGroup.input(hint: String, type: Int = InputType.TYPE_CLASS_TEXT, lines: Int = 1): EditText = EditText(context).also {
    it.hint = hint
    it.inputType = type
    it.textSize = 14f
    it.typeface = ResourcesCompat.getFont(context, R.font.inter_regular)
    it.setTextColor(ContextCompat.getColor(context, R.color.auth_text))
    it.setHintTextColor(ContextCompat.getColor(context, R.color.brand_muted))
    it.background = it.fieldBackground(error = false)
    it.setPadding(it.dp(14))
    it.minHeight = it.dp(if (lines > 1) 88 else 50)
    if (lines > 1) {
        it.minLines = lines
        it.gravity = Gravity.TOP or Gravity.START
    } else {
        it.maxLines = 1
    }
    addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
}

fun View.fieldBackground(error: Boolean) = StateListDrawable().apply {
    addState(intArrayOf(android.R.attr.state_focused), rounded(R.color.white, 10, if (error) R.color.error else R.color.brand))
    addState(intArrayOf(), rounded(R.color.white, 10, if (error) R.color.error else R.color.border))
}

/** Thin rule between a card's header and its details (<hr>). */
fun ViewGroup.rule() = View(context).also {
    it.setBackgroundColor(ContextCompat.getColor(context, R.color.border))
    addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
}

/** Inter header (20sp bold title, 10sp medium subtitle), as on every Inter frame. */
fun ShellActivity.interHeader(titleSize: Float = 20f, titleLine: Float = 28f) {
    shell.title.typeface = ResourcesCompat.getFont(this, R.font.inter_bold)
    shell.title.textSize = titleSize
    shell.title.lineHeight = shell.root.dp(titleLine)
    shell.subtitle.typeface = ResourcesCompat.getFont(this, R.font.inter_medium)
    shell.subtitle.textSize = 10f
}
