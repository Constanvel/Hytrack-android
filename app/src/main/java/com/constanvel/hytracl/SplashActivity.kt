package com.constanvel.hytracl

import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.constanvel.hytracl.databinding.ActivitySplashBinding

/**
 * Launcher screen: the lockup fades in, holds for a beat, then hands over to [LoginActivity]
 * with a cross-fade. It is the only launcher entry, so it shows once per cold start and never
 * when navigating (sign-out goes straight to the login screen). Nothing is loaded here.
 */
class SplashActivity : AppCompatActivity() {
    private lateinit var b: ActivitySplashBinding
    private var done = false
    private val advance = Runnable { next() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        b = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Recreated (rotation, font change) or animations turned off: go straight on.
        val noMotion = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        if (savedInstanceState != null || noMotion) return next()

        fun View.enter(delay: Long, duration: Long) = animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
            .setStartDelay(delay).setDuration(duration).setInterpolator(DecelerateInterpolator(2f))

        b.logo.scaleX = 0.88f
        b.logo.scaleY = 0.88f
        b.name.translationY = b.name.dp(12).toFloat()
        b.tagline.translationY = b.tagline.dp(12).toFloat()
        b.wave.translationY = b.wave.dp(24).toFloat()
        b.wave.enter(0, 700)
        b.logo.enter(0, 600)
        b.name.enter(200, 450)
        b.tagline.enter(320, 450).withEndAction { b.root.postDelayed(advance, HOLD) }
    }

    private fun next() {
        if (done || isFinishing) return
        done = true
        val fade = ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, android.R.anim.fade_out)
        startActivity(Intent(this, LoginActivity::class.java), fade.toBundle())
        finish()
    }

    override fun onStop() {
        super.onStop()
        b.root.removeCallbacks(advance)
    }

    private companion object {
        const val HOLD = 450L // ms the finished lockup stays before the hand-over
    }
}
