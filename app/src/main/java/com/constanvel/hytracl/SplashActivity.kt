package com.constanvel.hytracl

import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.constanvel.hytracl.databinding.ActivitySplashBinding

/**
 * Launcher entry. The system splash (androidx SplashScreen) shows the logo while the app starts;
 * this screen continues it with the same logo in the same place while the name and tagline fade
 * in, then opens where the session belongs: sign-in, or the patient or companion home when this
 * process is still signed in. Staff accounts cannot sign in on Android, so there is no staff home.
 * Nothing is loaded here and nothing waits beyond the short entrance.
 */
class SplashActivity : AppCompatActivity() {
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // Already signed in (the app was only closed): no brand moment, straight back in.
        if (Demo.signedIn) return route()
        enableEdgeToEdge()
        val b = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(b.root)

        // Recreated (rotation, font change) or animations turned off: go straight on.
        val noMotion = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        if (savedInstanceState != null || noMotion) return route()

        fun View.enter(delay: Long) = animate().alpha(1f).translationY(0f)
            .setStartDelay(delay).setDuration(ENTER).setInterpolator(DecelerateInterpolator(2f))

        b.name.translationY = b.name.dp(8).toFloat()
        b.tagline.translationY = b.tagline.dp(8).toFloat()
        b.wave.translationY = b.wave.dp(16).toFloat()
        b.wave.enter(0)
        b.name.enter(0)
        b.tagline.enter(STAGGER).withEndAction { route() }
    }

    private fun route() {
        if (done || isFinishing) return
        done = true
        if (Demo.signedIn) return enterSignedIn()
        val fade = ActivityOptions.makeCustomAnimation(this, android.R.anim.fade_in, android.R.anim.fade_out)
        startActivity(Intent(this, LoginActivity::class.java), fade.toBundle())
        finish()
    }

    private companion object {
        const val ENTER = 320L // ms each line takes to fade in
        const val STAGGER = 80L // ms the tagline follows the name
    }
}
