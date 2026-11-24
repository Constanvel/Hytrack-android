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
        val waitToday: String,
        val delay: String? = null,
    ) {
        val time get() = "${clock(start)}–${clock(end)}"
        /** The same doctor in the staff data, where tickets and quota live. */
        val ops get() = Ops.doctors.first { it.id == id }
        fun quota(day: Day) = Ops.quota(ops, Ops.today().plusDays(day.offset.toLong()))
        fun wait(day: Day) = if (day.offset == 0) waitToday else "0–10"
    }

    val clinics = listOf(
        Clinic(1, "Penyakit Dalam", "Ruang A-204 · Gedung A, lantai 2", "15–25"),
        Clinic(2, "Jantung", "Ruang A-210 · Gedung A, lantai 2", "10–20"),
        Clinic(3, "Anak", "Ruang B-105 · Gedung B, lantai 1", "0–10"),
        Clinic(4, "Saraf", null, ""),
    )

    val doctors = listOf(
        Doctor(1, 1, "dr. Rina Kusuma, Sp.PD", 9 * 60, 12 * 60, "15–25"),
        Doctor(2, 1, "dr. Budi Santoso, Sp.PD", 13 * 60, 16 * 60, "10–20"),
        Doctor(3, 2, "dr. Maya Pratiwi, Sp.JP", 8 * 60, 11 * 60, "10–20"),
        Doctor(4, 3, "dr. Andi Wirawan, Sp.A", 10 * 60, 13 * 60, "0–10"),
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
    fun stamp() = "${days[0].label} · ${now()}"

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

    /** The patient's view of one booking in [Ops] (VisitView): staff actions show up here, and back. */
    class Visit(val queue: Ops.Queue) {
        val id = queue.id
        val doctor = doctors.first { it.id == queue.doctor.id }
        val day = Day(java.time.temporal.ChronoUnit.DAYS.between(Ops.today(), queue.date).toInt())
        val clinic = clinic(doctor.clinicId)
        /** A cancelled visit keeps the stage it had; [cancelReason] says it was cancelled. */
        val stage get() = when (queue.status) {
            Ops.Status.CANCELLED -> if (queue.checkIn != null) Stage.WAITING else Stage.BOOKED
            else -> Stage.valueOf(queue.status.name)
        }
        val rx get() = queue.order?.status?.let { Rx.entries[it.ordinal] }
        val checkedInAt get() = Ops.clock(queue.checkIn)
        val cancelReason get() = queue.cancelReason
        /** Clock when the doctor finished (Figma 43:2 frame 07 notice). */
        val examinedAt get() = Ops.clock(queue.stage(Ops.StageKind.CONSULTATION).completed)
        /** Minute of the day the pharmacy received the prescription (frame 08). */
        val rxReceived get() = queue.order?.received?.atZone(java.time.ZoneId.systemDefault())?.let { it.hour * 60 + it.minute }
        val laboratoryDone get() = queue.stage(Ops.StageKind.LABORATORY).status == Ops.StageStatus.DONE
        /** Figma 43:2 frame 08: "15–25 menit · Perkiraan siap 10.30–10.40", as VisitView::readyEstimate.
         *  ponytail: a fixed 25–35 minute preparation window from receipt, like the web. */
        val readyIn get() = rxReceived?.let { from -> "${maxOf(0, from + 25 - minuteOfDay())}–${maxOf(0, from + 35 - minuteOfDay())}" }
        val readyBetween get() = rxReceived?.let { "${clock(it + 25)}–${clock(it + 35)}" }
        val ticket = queue.number
        private val inLine get() = queue.status == Ops.Status.BOOKED || queue.status == Ops.Status.WAITING
        val ahead get() = if (inLine) Ops.ahead(queue) else 0
        val waitRange: String? = doctor.wait(day).takeIf { it.isNotEmpty() }
        val serving get() = if (inLine) Ops.queues.firstOrNull { it.doctor === queue.doctor && it.date == queue.date && it.status == Ops.Status.SERVING }?.number else null
        val checkInCode = queue.code
        /** What the check-in QR carries (CheckInTicket::payload). */
        val qrPayload get() = Ops.payload(queue)
        val pickupNumber get() = queue.order?.pickup.orEmpty()
        val isToday get() = day.offset == 0
        val time get() = doctor.time
        val checkInBy get() = clock(doctor.start - CHECK_IN_LEAD)
        val leaveBy get() = clock(doctor.start - CHECK_IN_LEAD - TRAVEL)
        val leadMinutes = CHECK_IN_LEAD
        val travelMinutes = TRAVEL
        val active get() = queue.status.active
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

    /** The ticket goes into the staff data, so the desk sees it at once ([Local.attach] makes it this account's visit). */
    fun book(doctor: Doctor, day: Day): Boolean {
        val account = Local.current ?: return false
        return try {
            Ops.book(Local.Patient(account.self.name, account.phone, null), doctor.ops, Ops.today().plusDays(day.offset.toLong()), by = null)
            true
        } catch (e: Ops.Refused) { // e.g. the desk booked this patient a moment ago
            warn(e.message)
            false
        }
    }

    fun cancel() {
        visit?.let { Ops.cancel(it.queue, null, by = null) }
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
        val q = visit?.queue ?: return
        try {
            when (action) {
                "check-in" -> Ops.checkIn(q, by = null)
                "examined" -> { // called out of turn, then examined at once (simulateExamination)
                    if (q.status == Ops.Status.WAITING) Ops.serve(q, by = null)
                    Ops.examined(q, by = null)
                }
                "laboratory" -> Ops.laboratoryDone(q, by = null)
                "medicine-ready", "picked-up" -> {
                    var order = q.order ?: throw Ops.Refused("Resep belum diterima farmasi.")
                    val target = if (action == "picked-up") Ops.Rx.COMPLETED else Ops.Rx.READY
                    if (target == Ops.Rx.COMPLETED && order.status != Ops.Rx.READY) throw Ops.Refused("Obat belum siap diambil.")
                    while (order.status != target && order.status.next != null) order = Ops.advance(order, by = null)
                }
            }
        } catch (e: Ops.Refused) {
            warn(e.message)
        }
    }


    /** "Ruang A-204 · Gedung A, lantai 2" -> ("Ruang A-204", "Gedung A, lantai 2"). */
    fun splitRoom(room: String?): Pair<String, String> {
        val parts = (room ?: "Lokasi dikonfirmasi petugas").split(" · ")
        return parts.first() to parts.drop(1).joinToString(" · ")
    }
}
