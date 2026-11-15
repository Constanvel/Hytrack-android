package com.constanvel.hytracl

import com.constanvel.hytracl.Demo.Rx
import com.constanvel.hytracl.Demo.Stage

/**
 * Hya's replies: a port of app/Services/HyaResponder.php. Rule-based, not an AI model,
 * and never gives medical advice.
 */
object Hya {
    private const val LAB = "Laboratorium A-108 · Gedung A, lantai 1"
    private const val PHARMACY = "Farmasi Loket F2 · Gedung A, lantai 1"
    private const val CHECK_IN = "Meja check-in · Gedung A, lantai 2"

    private fun String.has(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(this)

    fun reply(question: String, visit: Demo.Visit?): String {
        if (question.has("medis|sakit|obat|diagnos|resep|dosis")) {
            return "Untuk keluhan atau pertanyaan medis, silakan hubungi tenaga kesehatan. Hya hanya membantu informasi layanan rumah sakit."
        }
        if (question.has("check|qr|terlambat")) {
            return "Buka tiket kunjungan, lalu tekan “Tampilkan QR check-in”. Tunjukkan QR atau nomor check-in kepada petugas di meja check-in. Jika terlambat, minta bantuan petugas."
        }
        if (visit == null || !visit.active) {
            return "Belum ada kunjungan aktif. Mulai dari Beranda untuk memilih poliklinik dan jadwal dokter. Ini respons terstruktur, bukan layanan AI."
        }
        val clinicRoom = visit.clinic.room ?: "ruang poliklinik"
        if (question.has("ruang|mana|lokasi|arah")) {
            return when (visit.stage) {
                Stage.PHARMACY -> "Tunggu di area $PHARMACY."
                Stage.LABORATORY -> "Lokasi berikutnya: $LAB. Konfirmasikan petunjuk kepada petugas."
                Stage.BOOKED -> "Check-in lebih dulu di $CHECK_IN, lalu tunggu di $clinicRoom."
                else -> "Tempat menunggu Anda: $clinicRoom. Ikuti petunjuk rumah sakit dan arahan petugas."
            }
        }
        if (question.has("giliran|antre|tunggu|kapan")) {
            return when (visit.stage) {
                Stage.BOOKED -> "Anda belum check-in. Tunjukkan QR atau nomor check-in kepada petugas saat tiba di rumah sakit."
                Stage.SERVING -> "Nomor ${visit.ticket} sedang dipanggil. Silakan menuju $clinicRoom."
                Stage.LABORATORY -> "Layanan berikutnya adalah $LAB. Lapor di meja laboratorium dengan tiket ${visit.ticket}."
                Stage.PHARMACY -> if (visit.rx == Rx.READY) {
                    "Obat siap diambil. Tunjukkan nomor ${visit.pickupNumber} di loket farmasi."
                } else {
                    "Status obat: ${visit.rx?.label ?: "Resep diterima"}. Tunggu nomor ${visit.pickupNumber} dipanggil di loket farmasi."
                }
                else -> "Ada ${visit.ahead} pasien di depan Anda." +
                    (visit.waitRange?.let { " Perkiraan menunggu $it menit." } ?: "") +
                    " Estimasi dapat berubah mengikuti pelayanan."
            }
        }
        return "Hya dapat membantu informasi antrean, lokasi layanan, dan cara check-in. Pilih topik tersebut atau hubungi petugas rumah sakit untuk bantuan lain."
    }
}
