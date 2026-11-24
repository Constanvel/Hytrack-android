package com.constanvel.hytracl

import java.util.Calendar
import java.util.Locale

/**
 * In-memory demo data and visit state. Values follow the Laravel seeder of the web app
 * (DatabaseSeeder + config/hytrack.php); nothing here talks to a hospital system or a server.
 * State lives for the app process only, like a fresh `migrate --seed` on each launch. The visit
 * and notifications belong to the signed-in account ([Local.current]).
 */
object Demo {
    const val HOSPITAL = "RS Harapan Sehat"
    const val DEMO_PHONE = "0812-5550-0842"
    const val DEMO_PASSWORD = "hytrack-demo"
    const val LABORATORY = "Laboratorium A-108 · Gedung A, lantai 1"
    const val PHARMACY = "Farmasi Loket F2 · Gedung A, lantai 1"

    val patient get() = Local.current?.self?.name.orEmpty()
    val phone get() = "•••• " + Local.current?.self?.phone.orEmpty().takeLast(4)

    /** Patient::normalizePhone: digits only, local 0 prefix becomes 62. */
    fun normalizePhone(input: String): String {
        val digits = input.filter { it.isDigit() }
        return if (digits.startsWith("0")) "62" + digits.drop(1) else digits
    }

    /** An invitation link opened before signing in; [LoginActivity] continues to it. */
    var pendingInvite: String? = null
    private const val CHECK_IN_LEAD = 15
    private const val TRAVEL = 25

    class Clinic(val id: Int, val name: String, val room: String?, val wait: String)

    class Doctor(
        val id: Int,
        val clinicId: Int,
        val name: String,
        val start: Int, // minutes after midnight
        val end: Int,
        val quotaToday: Int,
        val waitToday: String,
        val issued: Int, // tickets already issued today
        val ahead: Int,
        val serving: String?,
        val delay: String? = null,
    ) {
        val time get() = "${clock(start)}–${clock(end)}"
        fun quota(day: Day) = if (day.offset == 0) quotaToday else 30
        fun wait(day: Day) = if (day.offset == 0) waitToday else "0–10"
    }

    val clinics = listOf(
        Clinic(1, "Penyakit Dalam", "Ruang A-204 · Gedung A, lantai 2", "15–25"),
        Clinic(2, "Jantung", "Ruang A-210 · Gedung A, lantai 2", "10–20"),
        Clinic(3, "Anak", "Ruang B-105 · Gedung B, lantai 1", "0–10"),
        Clinic(4, "Saraf", null, ""),
    )

    val doctors = listOf(
        Doctor(1, 1, "dr. Rina Kusuma, Sp.PD", 9 * 60, 12 * 60, 4, "15–25", 26, 3, "A-023"),
        Doctor(2, 1, "dr. Budi Santoso, Sp.PD", 13 * 60, 16 * 60, 17, "10–20", 13, 0, null),
        Doctor(3, 2, "dr. Maya Pratiwi, Sp.JP", 8 * 60, 11 * 60, 22, "10–20", 8, 2, "A-006"),
        Doctor(4, 3, "dr. Andi Wirawan, Sp.A", 10 * 60, 13 * 60, 26, "0–10", 4, 0, null),
    )

    fun clinic(id: Int) = clinics.firstOrNull { it.id == id } ?: clinics.first()
    fun doctorsOf(clinic: Clinic) = doctors.filter { it.clinicId == clinic.id }

    // Dates ---------------------------------------------------------------------
    private val weekdays = listOf("Minggu", "Senin", "Selasa", "Rabu", "Kamis", "Jumat", "Sabtu")
    private val weekdaysShort = listOf("Min", "Sen", "Sel", "Rab", "Kam", "Jum", "Sab")
    val MONTHS = listOf("Januari", "Februari", "Maret", "April", "Mei", "Juni", "Juli",
        "Agustus", "September", "Oktober", "November", "Desember")
    private val monthsShort = listOf("Jan", "Feb", "Mar", "Apr", "Mei", "Jun", "Jul", "Agu",
        "Sep", "Okt", "Nov", "Des")

    /** One bookable day, [offset] days after today. */
    class Day(val offset: Int) {
        private val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, offset) }
        private val dow = cal.get(Calendar.DAY_OF_WEEK) - 1
        val dom = cal.get(Calendar.DAY_OF_MONTH)
        val month = cal.get(Calendar.MONTH)
        val year = cal.get(Calendar.YEAR)
        val day get() = weekdaysShort[dow]
        val weekday get() = weekdays[dow]
        val label get() = "$dom ${monthsShort[month]}"
        val medium get() = "$weekday, $dom ${monthsShort[month]} $year"
        val long get() = "$weekday, $dom ${MONTHS[month]} $year"
        /** "28 Oktober 2026" and "28 Okt 2026" (riwayat). */
        val full get() = "$dom ${MONTHS[month]} $year"
        val short get() = "$dom ${monthsShort[month]} $year"
        val monthTitle get() = "${MONTHS[month]} $year"
        val monthKey get() = year * 12 + month
        val millis: Long get() = cal.timeInMillis
    }

    /**
     * Booking window: today and the next 59 days, like the web (config/hytrack.php booking_days = 60).
     * Every doctor practises daily at the same hours, so a later day only differs in quota/estimate,
     * which follow the same rules as today + 1.
     */
    const val BOOKING_DAYS = 60
    val days = List(BOOKING_DAYS) { Day(it) }

    fun clock(minutes: Int) = String.format(Locale.ROOT, "%02d.%02d", minutes / 60 % 24, minutes % 60)
    fun now(): String = Calendar.getInstance().let { clock(it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)) }
    /** Minutes since midnight, now. */
    fun minuteOfDay(): Int = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }
    private fun stamp() = "${days[0].label} · ${now()}"

    // Visit ---------------------------------------------------------------------
    /** QueueStatus, with its labels. A cancelled visit keeps its stage and gets [Visit.cancelReason]. */
    enum class Stage(val label: String) {
        BOOKED("Belum check-in"), WAITING("Sudah check-in"), SERVING("Dipanggil"), LABORATORY("Laboratorium"),
        PHARMACY("Farmasi"), COMPLETED("Selesai"), NO_SHOW("Tidak hadir"),
    }

    enum class Rx(val label: String) {
        RECEIVED("Resep diterima"), PREPARING("Obat disiapkan"), CHECKING("Pemeriksaan akhir"),
        READY("Siap diambil"), COMPLETED("Sudah diambil"),
    }

    class Visit(val id: Int, val doctor: Doctor, val day: Day) {
        val clinic = clinic(doctor.clinicId)
        var stage = Stage.BOOKED
        var rx: Rx? = null
        var checkedInAt: String? = null
        var cancelReason: String? = null
        /** Clock when the doctor finished (Figma 43:2 frame 07 notice). */
        var examinedAt: String? = null
        /** Minute of the day the pharmacy received the prescription (frame 08). */
        var rxReceived: Int? = null
        var laboratoryDone = false
        /** Figma 43:2 frame 08: "15–25 menit · Perkiraan siap 10.30–10.40", as VisitView::readyEstimate.
         *  ponytail: a fixed 25–35 minute preparation window from receipt, like the web. */
        val readyIn get() = rxReceived?.let { from -> "${maxOf(0, from + 25 - minuteOfDay())}–${maxOf(0, from + 35 - minuteOfDay())}" }
        val readyBetween get() = rxReceived?.let { "${clock(it + 25)}–${clock(it + 35)}" }
        // The seeded queue is today's; a later day has no tickets yet (same rule as quota()).
        val ticket = "A-%03d".format(Locale.ROOT, (if (day.offset == 0) doctor.issued else 0) + 1)
        val ahead = if (day.offset == 0) doctor.ahead else 0
        val waitRange: String? = doctor.wait(day).takeIf { it.isNotEmpty() }
        val serving = if (day.offset == 0) doctor.serving else null
        val checkInCode = "HT-000126"
        val pickupNumber = "F-091"
        val isToday get() = day.offset == 0
        val time get() = doctor.time
        val checkInBy get() = clock(doctor.start - CHECK_IN_LEAD)
        val leaveBy get() = clock(doctor.start - CHECK_IN_LEAD - TRAVEL)
        val leadMinutes = CHECK_IN_LEAD
        val travelMinutes = TRAVEL
        val active get() = stage != Stage.COMPLETED && stage != Stage.NO_SHOW && cancelReason == null
        val status get() = if (cancelReason != null) "cancelled" else stage.name.lowercase()
        val statusLabel get() = if (cancelReason != null) "Dibatalkan" else stage.label
    }

    class Notification(val message: String, val action: String?, val time: String, var unread: Boolean = true)

    val signedIn get() = Local.current != null
    /** A patient, staff or manager session. */
    val inSession get() = signedIn || Local.role != null
    /** Signed in as Pendamping: reopening the app returns to the companion view. */
    var companion = false
    val visit get() = Local.current?.visit
    val notifications: MutableList<Notification> get() = Local.current?.notifications ?: mutableListOf()
    val unread get() = notifications.count { it.unread }

    /** One-shot status line shown at the top of the next screen (Inertia flash). */
    var flash: String? = null
    var flashTone = Tone.GREEN

    /** A failed request: shown like a flash, in the warning tone. */
    fun warn(message: String?) {
        flash = message
        flashTone = Tone.WARNING
    }
    val activeVisit get() = visit?.takeIf { it.active }

    private fun notify(message: String, action: String? = null) {
        notifications.add(0, Notification(message, action, stamp()))
    }

    private var visits = 0

    fun book(doctor: Doctor, day: Day) {
        val account = Local.current ?: return
        val v = Visit(++visits, doctor, day)
        account.visit = v
        account.visits.add(0, v)
        notify("Antrean ${v.ticket} berhasil diambil.", "Check-in saat tiba di rumah sakit.")
    }

    fun cancel() {
        visit?.cancelReason = "Dibatalkan oleh pasien"
        Local.current?.visit = null
        flash = "Kunjungan dibatalkan."
    }

    /** The account's data stays (like the server's) for when it signs in again. */
    fun signOut() {
        Local.signOut()
        companion = false
    }

    /** Demo controls: the patient plays the staff's part (VisitController::simulate). */
    fun simulate(action: String) {
        val v = visit ?: return
        when (action) {
            "check-in" -> {
                v.stage = Stage.WAITING
                v.checkedInAt = now()
                notify("Check-in berhasil. Anda masuk antrean dokter.", "Tunggu di dekat ruang poliklinik.")
            }
            "examined" -> {
                if (v.stage == Stage.WAITING) notify("Nomor ${v.ticket} dipanggil.", "Silakan masuk ke ruang pemeriksaan.")
                v.stage = Stage.LABORATORY
                v.examinedAt = now()
                notify("Pemeriksaan dokter selesai. Selanjutnya ke laboratorium.", LABORATORY)
            }
            "laboratory" -> {
                v.stage = Stage.PHARMACY
                v.rx = Rx.RECEIVED
                v.laboratoryDone = true
                v.rxReceived = minuteOfDay()
                notify("Resep diterima farmasi. Obat sedang diproses.", PHARMACY)
            }
            "medicine-ready" -> {
                v.rx = Rx.READY
                notify("Obat siap diambil. Nomor pengambilan ${v.pickupNumber}.", PHARMACY)
            }
            "picked-up" -> {
                v.rx = Rx.COMPLETED
                v.stage = Stage.COMPLETED
                notify("Kunjungan selesai. Semoga lekas sehat.")
            }
        }
    }


    /** "Ruang A-204 · Gedung A, lantai 2" -> ("Ruang A-204", "Gedung A, lantai 2"). */
    fun splitRoom(room: String?): Pair<String, String> {
        val parts = (room ?: "Lokasi dikonfirmasi petugas").split(" · ")
        return parts.first() to parts.drop(1).joinToString(" · ")
    }
}
