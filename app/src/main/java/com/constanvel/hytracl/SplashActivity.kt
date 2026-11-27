package com.constanvel.hytracl

import android.animation.ValueAnimator
import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import android.view.animation.PathInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.doOnPreDraw
import com.constanvel.hytracl.databinding.ActivitySplashBinding

/**
 * Launcher entry, after the Figma storyboard 43:2. The system splash (androidx SplashScreen) shows
 * the mark while the app starts; this screen takes over with the same mark in the same place and
 * plays the storyboard, then opens where the session belongs: sign-in, or the patient or companion
 * home when this process is still signed in. Staff accounts cannot sign in on Android, so there is
 * no staff home. Nothing is loaded here, so there is no loader and nothing waits beyond the motion.
 */
class SplashActivity : AppCompatActivity() {
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Already signed in (the app was only closed): no brand moment, straight back in.
        if (Demo.inSession) return route()
        lightEdgeToEdge()
        val b = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Recreated (rotation, font change) or animations turned off: go straight on.
        val noMotion = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        if (savedInstanceState != null || noMotion) return route()
        // Laid out but not yet drawn: the first frame already shows the system splash's pose.
        b.root.doOnPreDraw { play(b) }
    }

    private fun play(b: ActivitySplashBinding) {
        val root = b.root
        val mark = b.mark
        // The mark's centre at rest, in the root.
        val cx = b.lockup.left + mark.left + mark.width / 2f
        val cy = b.lockup.top + mark.top + mark.height / 2f
        // Where the system splash drew it (Figma 43:44): the window centre, 120dp wide, hands at
        // 12 and about 4 o'clock.
        val big = root.dp(120) / mark.width.toFloat()
        mark.translationX = root.width / 2f - cx
        mark.translationY = root.height / 2f - cy
        mark.scaleX = big
        mark.scaleY = big
        b.minute.rotation = 0f
        b.hour.rotation = 121f
        b.name.alpha = 0f
        b.tagline.alpha = 0f
        b.tagline.translationY = root.dp(6).toFloat()

        // 01: the light-blue band crosses behind the mark.
        val blue = ContextCompat.getColor(this, R.color.splash_sweep)
        b.sweepIn.run(blue, blue, cover = false, delay = 0, duration = 620, easing = SWEEP)
        // 02 → 03: the mark moves aside to its place as the wordmark comes out from behind it.
        mark.animate().translationX(0f).translationY(0f).scaleX(1f).scaleY(1f)
            .setStartDelay(MOVE).setDuration(460).setInterpolator(EASE)
        reveal(b, MOVE, 460)
        // 04 → 07: the clock keeps time, the minute hand turning once while the hour hand creeps.
        b.minute.animate().rotation(430f).setStartDelay(120).setDuration(900).setInterpolator(EASE)
        b.hour.animate().rotation(157f).setStartDelay(120).setDuration(900).setInterpolator(OUT)
        b.tagline.animate().alpha(1f).translationY(0f).setStartDelay(520).setDuration(360).setInterpolator(OUT)
            .withEndAction { leave(b, cx) }
    }

    /** The wordmark is uncovered from its left edge as it slides in, the way the web clips it. */
    private fun reveal(b: ActivitySplashBinding, delay: Long, duration: Long) {
        val name = b.name
        val shift = name.textSize * 0.4f
        val clip = Rect()
        ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = delay
            this.duration = duration
            interpolator = EASE
            addUpdateListener {
                val t = it.animatedValue as Float
                clip.set(0, 0, (name.width * t).toInt(), name.height)
                name.clipBounds = clip
                name.alpha = t
                name.translationX = -shift * (1 - t)
            }
            doOnEnd { name.clipBounds = null }
            start()
        }
    }

    /** 08: the text goes, the mark settles in the centre, larger, and the teal band covers the screen. */
    private fun leave(b: ActivitySplashBinding, cx: Float) {
        b.name.animate().alpha(0f).setStartDelay(0).setDuration(200).setInterpolator(OUT)
        b.tagline.animate().alpha(0f).setStartDelay(0).setDuration(200).setInterpolator(OUT)
        b.mark.animate().translationX(b.root.width / 2f - cx).scaleX(1.46f).scaleY(1.46f)
            .setStartDelay(0).setDuration(420).setInterpolator(EASE)
        b.minute.animate().rotation(505f).setStartDelay(0).setDuration(600).setInterpolator(EASE)
        val top = ContextCompat.getColor(this, R.color.splash_teal_top)
        val bottom = ContextCompat.getColor(this, R.color.splash_teal_bottom)
        b.sweepOut.run(top, bottom, cover = true, delay = 80, duration = 480, easing = EASE).doOnEnd { route() }
    }

    private fun route() {
        if (done || isFinishing) return
        done = true
        if (Demo.inSession) return enterSignedIn()
        // The next screen fades in over the splash, which holds still beneath it.
        val fade = ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, R.anim.splash_hold)
        startActivity(Intent(this, LoginActivity::class.java), fade.toBundle())
        finish()
    }

    private companion object {
        const val MOVE = 200L // ms before the mark moves aside
        val EASE = PathInterpolator(0.65f, 0f, 0.35f, 1f)
        val OUT = PathInterpolator(0.2f, 0.7f, 0.2f, 1f)
        val SWEEP = PathInterpolator(0.45f, 0f, 0.25f, 1f)
    }
}
