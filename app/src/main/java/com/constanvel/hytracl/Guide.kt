package com.constanvel.hytracl

import android.content.Context
import androidx.annotation.StringRes

/**
 * Interactive guide (features/guide/guide.tsx): one hint per screen, remembered per device.
 * Stores preferences only, never visit data.
 */
enum class GuideStep(@StringRes val title: Int, @StringRes val text: Int) {
    PASIEN(R.string.guide_pasien_title, R.string.guide_pasien_text),
    POLI(R.string.guide_poli_title, R.string.guide_poli_text),
    DOKTER(R.string.guide_dokter_title, R.string.guide_dokter_text),
    TIKET(R.string.guide_tiket_title, R.string.guide_tiket_text),
    CHECKIN(R.string.guide_checkin_title, R.string.guide_checkin_text),
    ANTREAN(R.string.guide_antrean_title, R.string.guide_antrean_text),
    LAYANAN(R.string.guide_layanan_title, R.string.guide_layanan_text),
}

object Guide {
    private fun prefs(context: Context) = context.getSharedPreferences("hytrack-guide", Context.MODE_PRIVATE)

    fun off(context: Context) = prefs(context).getBoolean("off", false)

    fun visible(context: Context, step: GuideStep?) =
        step != null && !off(context) && step.name !in prefs(context).getStringSet("seen", emptySet())!!

    fun dismiss(context: Context, step: GuideStep) {
        val seen = prefs(context).getStringSet("seen", emptySet())!! + step.name
        prefs(context).edit().putStringSet("seen", seen).apply()
    }

    fun skip(context: Context) = prefs(context).edit().putBoolean("off", true).apply()

    fun restart(context: Context) = prefs(context).edit().clear().apply()
}
