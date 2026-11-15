package com.constanvel.hytracl

import android.content.Context

/**
 * Interactive guide (features/guide/guide.tsx): one hint per screen, remembered per device.
 * Stores preferences only, never visit data.
 */
enum class GuideStep(val title: String, val text: String) {
    PASIEN("Pastikan nama pasien benar",
        "Nama yang ditandai adalah pasien yang akan berobat. Jika sudah benar, tekan tombol Ambil antrean."),
    POLI("Pilih poliklinik tujuan",
        "Ketuk nama poliklinik yang ingin Anda tuju. Anda juga bisa mengetik namanya di kolom cari."),
    DOKTER("Pilih tanggal dan dokter",
        "Pilih tanggal, ketuk nama dokter, lalu tekan Ambil antrean. Nomor antrean baru keluar setelah tombol itu ditekan."),
    TIKET("Perhatikan waktu berangkat",
        "Kotak kuning memberi tahu kapan sebaiknya Anda berangkat. Setibanya di rumah sakit, tekan Tampilkan QR check-in."),
    CHECKIN("Tunjukkan QR ke petugas",
        "Bawa layar ini ke meja check-in. Jika QR sulit dipindai, tekan Nomor check-in manual."),
    ANTREAN("Tunggu giliran Anda",
        "Angka besar adalah nomor Anda. Di sini terlihat berapa pasien lagi sebelum Anda dan di ruang mana Anda menunggu."),
    LAYANAN("Ikuti layanan berikutnya",
        "Setelah diperiksa dokter, HyTrack menunjukkan tujuan berikutnya, misalnya laboratorium atau farmasi. Ikuti langkah di halaman ini."),
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
