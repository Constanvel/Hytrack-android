package com.constanvel.hytracl

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View

/**
 * The splash storyboard's slanted band (Figma 43:2): a parallelogram 660 of 916 wide at the top,
 * leaning right by 300 of 916 of the height. [run] carries it in from the left; with `cover` it is
 * wide enough to cover the screen and stops once it does.
 */
class Sweep(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var cover = false

    /** The band's top-left corner in px; NaN until it starts moving. */
    private var edge = Float.NaN

    private val slant get() = height * 300f / 916f
    private val span get() = if (cover) width + 2 * slant else height * 660f / 916f

    fun run(top: Int, bottom: Int, cover: Boolean, delay: Long, duration: Long, easing: TimeInterpolator): ValueAnimator {
        this.cover = cover
        paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        val end = if (cover) -slant else width.toFloat()
        return ValueAnimator.ofFloat(-(span + slant), end).apply {
            startDelay = delay
            this.duration = duration
            interpolator = easing
            addUpdateListener { edge = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (edge.isNaN()) return
        val h = height.toFloat()
        path.rewind()
        path.moveTo(edge, 0f)
        path.lineTo(edge + span, 0f)
        path.lineTo(edge + span + slant, h)
        path.lineTo(edge + slant, h)
        path.close()
        canvas.drawPath(path, paint)
    }
}
