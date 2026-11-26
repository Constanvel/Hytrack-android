package com.constanvel.hytracl

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Staff and management data of the web dashboards, kept in memory like Local and Demo:
 * a port of database/seeders/DatabaseSeeder.php (same clinics, doctors, patients and visits,
 * relative to the moment the app starts) and of the QueueService rules staff act through.
 * The numbers on every dashboard section are derived from these visits, as on the web
 * (StaffController, ServiceInsights).
 */
object Ops {
    class Refused(message: String) : Exception(message)

    enum class Status(val label: String) {
        BOOKED("Belum check-in"), WAITING("Sudah check-in"), SERVING("Dipanggil"), LABORATORY("Laboratorium"),
        PHARMACY("Farmasi"), COMPLETED("Selesai"), NO_SHOW("Tidak hadir"), CANCELLED("Dibatalkan");

        val active get() = this in setOf(BOOKED, WAITING, SERVING, LABORATORY, PHARMACY)
    }

    enum class Rx(val label: String, val short: String) {
        RECEIVED("Resep diterima", "Verifikasi"), PREPARING("Obat disiapkan", "Disiapkan"),
        CHECKING("Pemeriksaan akhir", "Pemeriksaan akhir"), READY("Siap diambil", "Siap diambil"),
        COMPLETED("Sudah diserahkan", "Diserahkan");

        val next get() = entries.getOrNull(ordinal + 1)
    }

    enum class StageKind(val label: String) { CHECK_IN("Check-in"), CONSULTATION("Pemeriksaan dokter"), LABORATORY("Laboratorium"), PHARMACY("Farmasi") }
    enum class StageStatus(val label: String) { PENDING("Belum"), ACTIVE("Berlangsung"), DONE("Selesai"), SKIPPED("Dilewati") }

    class Clinic(val id: Int, var name: String, var room: String?)

    class Doctor(val id: Int, val clinic: Clinic, val name: String, val start: LocalTime, val end: LocalTime, val prefix: Char) {
        val time get() = "${clock(start)}–${clock(end)}"
    }

    class Stage(val kind: StageKind, val journeyId: Int) {
        var status = StageStatus.PENDING
        var started: Instant? = null
        var completed: Instant? = null
        var by: String? = null
    }

    class Order(val id: Int, val queue: Queue, var status: Rx, val received: Instant) {
        var prepared: Instant? = null
        var ready: Instant? = null
        var completed: Instant? = null
        var by: String? = STAFF
        val pickup get() = "F-%03d".format(Locale.ROOT, id)
    }

    class Queue(val id: Int, val patient: String, val phone: String?, val doctor: Doctor, val date: LocalDate, sequence: Int, var status: Status) {
        val number = "${doctor.prefix}-%03d".format(Locale.ROOT, sequence)
        val code get() = "HT-%06d".format(Locale.ROOT, id)
        val clinic get() = doctor.clinic
        val deadline: Instant = at(date, doctor.start).minus(Duration.ofMinutes(CHECK_IN_LEAD))
        val stages = StageKind.entries.mapIndexed { i, kind -> Stage(kind, (id - 1) * 4 + i + 1) }
        var checkIn: Instant? = null
        var checkInBy: String? = null
        var checkInMethod: String? = null
        var calledCount = 0
        var order: Order? = null
        var bookedAt: Instant? = null
        var bookedBy: String? = null
        var noShowAt: Instant? = null
        var noShowBy: String? = null
        var cancelledAt: Instant? = null
        var cancelledBy: String? = null
        var cancelReason: String? = null
        fun stage(kind: StageKind) = stages[kind.ordinal]
        val checkInClosed get() = Instant.now() > deadline
    }

    /** Default actor of the local demo actions; staff work itself runs on the Laravel API (Api). */
    const val STAFF = "Dina"
    private const val CONSULT_MINUTES = 5L
    private const val CHECK_IN_LEAD = 15L
    private const val DAILY_QUOTA = 30
    private const val BOOKING_DAYS = 60L
    const val CHECK_IN_DESK = "Meja check-in · Gedung A, lantai 2"

    // DatabaseSeeder: patients 3.. of the seeded table, in creation order.
    private val NAMES = listOf(
        "Hendra Wijaya", "Agus Setiawan", "Rudi Hartono", "Ratna Sari", "Eko Prasetyo", "Dewi Lestari", "Louisa Fadel",
        "Mack Reilly", "Polly Bergnaum", "Roslyn Steuber", "Chauncey Crooks", "Zander Labadie", "Gwendolyn Zieme",
        "Eva Kshlerin", "Julien Baumbach", "Arlene Hammes", "Chyna Deckow", "Cristal Bode", "Shaniya Murray",
        "Zackery Lindgren", "Lyda Aufderhar", "Elta O'Hara", "Merl Konopelski", "Bridget Barrows", "Ressie Nienow",
        "Roxane Abernathy", "Kathlyn Goldner", "Christelle Kassulke", "Destany Johnston", "Stephanie Kulas",
        "Letha Kautzer", "Otilia Bogisich", "Mercedes Koch", "Madeline Dooley", "Annabell Harris", "Leo Turner",
        "Wilma Lesch", "Sylvia King", "Sonya Rowe", "Camila Rice", "Mireya Rogahn", "Bridgette Mertz", "Davon Weissnat",
        "Madonna Keebler", "Lindsay Heidenreich", "Stan Torp", "Hermina Wuckert", "Angel Lesch", "Remington McKenzie",
        "Blair Schulist", "Henri Kub", "Jayme Weimann", "Curtis Fritsch", "Marty Rogahn", "Levi Mann", "Jayde Corkery",
        "Margarita Dare", "Tyson Beer", "Corene Osinski", "Laurine Harvey", "Anissa Bednar", "Dallin Witting",
        "Dylan Wilkinson", "Sunny Wolf", "Bethany Paucek", "Mike Runolfsson", "Gisselle Russel", "Tyshawn Ortiz",
        "Ceasar Schaefer", "Chesley Boyle", "Nikko Wiza", "Mafalda Ward", "Rudolph Satterfield", "Norwood Kulas",
    )

    // ponytail: the device zone stands in for hytrack.timezone (Asia/Jakarta), as in Demo.
    private val zone get() = ZoneId.systemDefault()
    private val ID = Locale.forLanguageTag("id")
    fun today(): LocalDate = LocalDate.now(zone)
    fun clock(at: Instant?) = at?.let { DateTimeFormatter.ofPattern("HH.mm", Locale.ROOT).format(it.atZone(zone)) }
    fun clock(time: LocalTime) = DateTimeFormatter.ofPattern("HH.mm", Locale.ROOT).format(time)
    private fun at(date: LocalDate, time: LocalTime) = date.atTime(time).atZone(zone).toInstant()

    /** "Selasa, 24 November 2026" (HospitalTime::dateLabel). */
    fun dateLabel(date: LocalDate): String = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", ID).format(date)

    /** "24 Nov 2026" (dashboard-shell shortDate). */
    fun shortDate(date: LocalDate): String = DateTimeFormatter.ofPattern("d MMM yyyy", ID).format(date).replace(".", "")

    val clinics = mutableListOf<Clinic>()
    val doctors = mutableListOf<Doctor>()
    val queues = mutableListOf<Queue>()
    private var queueIds = 0
    private var orderIds = 0
    private var clinicIds = 0

    init {
        seed()
    }

    private fun seed() {
        val clinic = { name: String, room: String? -> Clinic(++clinicIds, name, room).also { clinics += it } }
        val internal = clinic("Penyakit Dalam", "Ruang A-204 · Gedung A, lantai 2")
        val heart = clinic("Jantung", "Ruang A-210 · Gedung A, lantai 2")
        val children = clinic("Anak", "Ruang B-105 · Gedung B, lantai 1")
        clinic("Saraf", null) // no doctor yet
        val doctor = { c: Clinic, name: String, start: Int, end: Int ->
            Doctor(doctors.size + 1, c, name, LocalTime.of(start, 0), LocalTime.of(end, 0), 'A' + doctors.count { it.clinic === c })
                .also { doctors += it }
        }
        val rina = doctor(internal, "dr. Rina Kusuma, Sp.PD", 9, 12)
        val budi = doctor(internal, "dr. Budi Santoso, Sp.PD", 13, 16)
        val maya = doctor(heart, "dr. Maya Pratiwi, Sp.JP", 8, 11)
        val andi = doctor(children, "dr. Andi Wirawan, Sp.A", 10, 13)

        val today = today()
        history(today.minusDays(1), listOf(Triple(rina, 26, 6), Triple(budi, 18, 6), Triple(maya, 16, 0), Triple(andi, 14, -6)))
        liveMorning(rina, today)
        var cursor = 28
        val now = Instant.now()
        fun session(d: Doctor, booked: Int = 0, examined: Int = 0, serving: Boolean = false, waiting: Int = 0) {
            var sequence = 0
            for (i in 1..examined) {
                val called = now.minusMinutes(70 + (examined - i) * CONSULT_MINUTES)
                visit(NAMES[cursor++], d, today, ++sequence, Status.COMPLETED, called.minusMinutes(20), called)
            }
            if (serving) visit(NAMES[cursor++], d, today, ++sequence, Status.SERVING, now.minusMinutes(30), now.minusMinutes(3))
            for (i in 1..waiting) visit(NAMES[cursor++], d, today, ++sequence, Status.WAITING, now.minusMinutes(25L - i))
            for (i in 1..booked) visit(NAMES[cursor++], d, today, ++sequence, Status.BOOKED)
        }
        session(budi, booked = 13)
        session(maya, examined = 5, serving = true, waiting = 2)
        session(andi, booked = 4)
    }

    private fun Instant.minusMinutes(m: Long): Instant = minus(Duration.ofMinutes(m))
    private fun Instant.plusMinutes(m: Long): Instant = plus(Duration.ofMinutes(m))

    /** The scene of the original design: A-023 with the doctor, three patients waiting. */
    private fun liveMorning(rina: Doctor, today: LocalDate) {
        val now = Instant.now()
        val waits = longArrayOf(12, 18, 24, 31, 36, 28, 22)
        val inPharmacy = mapOf(15 to Rx.READY, 16 to Rx.CHECKING, 17 to Rx.PREPARING, 19 to Rx.RECEIVED)
        for (sequence in 1..22) {
            val called = now.minusMinutes(8 + (23L - sequence) * CONSULT_MINUTES)
            val status = when {
                sequence in listOf(7, 13, 18) -> Status.NO_SHOW
                sequence in inPharmacy -> Status.PHARMACY
                sequence >= 20 -> Status.LABORATORY
                else -> Status.COMPLETED
            }
            visit(NAMES[5 + sequence], rina, today, sequence, status, called.minusMinutes(waits[sequence % 7]), called, inPharmacy[sequence])
        }
        visit(NAMES[0], rina, today, 23, Status.SERVING, now.minusMinutes(45), now.minusMinutes(8))
        mapOf(24 to 40L, 25 to 36L, 26 to 32L).forEach { (sequence, ago) ->
            visit(NAMES[sequence - 23], rina, today, sequence, Status.WAITING, now.minusMinutes(ago))
        }
    }

    /** A finished service day, with waits that peak mid-morning. */
    private fun history(date: LocalDate, sessions: List<Triple<Doctor, Int, Int>>) {
        val waitByHour = mapOf(7 to 18, 8 to 24, 9 to 32, 10 to 48, 11 to 42, 12 to 35, 13 to 28, 14 to 21, 15 to 17)
        var offset = 0
        for ((d, visits, extra) in sessions) {
            val starts = at(date, d.start)
            val opens = starts.minusMinutes(40)
            for (sequence in 1..visits) {
                val checkIn = opens.plusMinutes((sequence - 1) * 8L)
                val hour = checkIn.atZone(zone).hour
                val wait = maxOf(5, (waitByHour[hour] ?: 20) + extra + sequence % 5 - 2).toLong()
                val called = maxOf(checkIn.plusMinutes(wait), starts)
                visit(NAMES[offset++], d, date, sequence, if (sequence % 11 == 0) Status.NO_SHOW else Status.COMPLETED, checkIn, called)
            }
        }
    }

    /** One invented visit with stage timestamps consistent with its status (DatabaseSeeder::visit). */
    private fun visit(name: String, d: Doctor, date: LocalDate, sequence: Int, status: Status, checkIn: Instant? = null, called: Instant? = null, medicine: Rx? = null) {
        val examined = called?.plusMinutes(CONSULT_MINUTES)
        val labDone = examined?.plusMinutes(18)
        val ready = labDone?.plusMinutes(22)
        val pastDoctor = status in setOf(Status.LABORATORY, Status.PHARMACY, Status.COMPLETED)
        val pastLab = status in setOf(Status.PHARMACY, Status.COMPLETED)
        val done = status == Status.COMPLETED
        val q = Queue(++queueIds, name, null, d, date, sequence, status)
        q.checkIn = checkIn
        q.calledCount = if (status == Status.NO_SHOW) 2 else if (called != null) 1 else 0
        fun set(kind: StageKind, state: StageStatus, from: Instant?, to: Instant?) = q.stage(kind).apply {
            this.status = state
            started = from
            completed = to
            by = if (from != null) STAFF else null
        }
        fun state(started: Boolean, finished: Boolean) = if (finished) StageStatus.DONE else if (started) StageStatus.ACTIVE else StageStatus.PENDING
        set(StageKind.CHECK_IN, state(checkIn != null, checkIn != null), checkIn, checkIn)
        set(StageKind.CONSULTATION, if (status == Status.NO_SHOW) StageStatus.SKIPPED else state(called != null, pastDoctor), called, if (pastDoctor) examined else null)
        set(StageKind.LABORATORY, state(pastDoctor, pastLab), if (pastDoctor) examined else null, if (pastLab) labDone else null)
        set(StageKind.PHARMACY, state(pastLab, done), if (pastLab) labDone else null, if (done) ready!!.plusMinutes(5) else null)
        if (pastLab) {
            val rx = medicine ?: Rx.COMPLETED
            q.order = Order(++orderIds, q, rx, labDone!!).apply {
                if (rx >= Rx.CHECKING) prepared = labDone.plusMinutes(12)
                if (rx >= Rx.READY) this.ready = ready
                if (done) completed = ready!!.plusMinutes(5)
            }
        }
        queues += q
    }

    // Queue actions (QueueService) behind the patient's demo controls and bookings ------------------

    private fun line(d: Doctor, date: LocalDate = today()) = queues.filter { it.doctor === d && it.date == date }

    /** QueueService::notify: the message lands in the patient's account, if the visit has one. */
    private fun notify(q: Queue, message: String, action: String? = null) = Local.notify(q.phone, message, action)

    private fun touch(q: Queue, kind: StageKind, status: StageStatus, by: String? = STAFF) = q.stage(kind).apply {
        this.status = status
        this.by = by
        started = started ?: Instant.now()
        if (status == StageStatus.DONE) completed = Instant.now()
    }

    /** QueueService::call. The patient's demo controls call out of turn ([by] null), as on the web. */
    fun serve(q: Queue, by: String? = STAFF): Queue {
        if (q.status != Status.WAITING) throw Refused("Status kunjungan tidak dapat diubah ke Dipanggil.")
        q.status = Status.SERVING
        q.calledCount = 1
        touch(q, StageKind.CONSULTATION, StageStatus.ACTIVE, by)
        notify(q, "Nomor ${q.number} dipanggil.", "Silakan masuk ke ruang pemeriksaan.")
        return q
    }

    fun examined(q: Queue, by: String? = STAFF) {
        if (q.status != Status.SERVING) throw Refused("Status kunjungan tidak dapat diubah ke Laboratorium.")
        q.status = Status.LABORATORY
        touch(q, StageKind.CONSULTATION, StageStatus.DONE, by)
        touch(q, StageKind.LABORATORY, StageStatus.ACTIVE, by)
        notify(q, "Pemeriksaan dokter selesai. Selanjutnya ke laboratorium.", Demo.LABORATORY)
    }

    fun laboratoryDone(q: Queue, by: String? = STAFF) {
        if (q.status != Status.LABORATORY) throw Refused("Status kunjungan tidak dapat diubah ke Farmasi.")
        q.status = Status.PHARMACY
        touch(q, StageKind.LABORATORY, StageStatus.DONE, by)
        touch(q, StageKind.PHARMACY, StageStatus.ACTIVE, by)
        q.order = Order(++orderIds, q, Rx.RECEIVED, Instant.now()).apply { this.by = by }
        notify(q, "Resep diterima farmasi. Obat sedang diproses.", Demo.PHARMACY)
    }

    /** Move a prescription one step forward; handing over the medicine completes the visit. */
    fun advance(order: Order, by: String? = STAFF): Order {
        val next = order.status.next ?: throw Refused("Obat sudah diserahkan.")
        order.status = next
        order.by = by
        when (next) {
            Rx.CHECKING -> order.prepared = Instant.now()
            Rx.READY -> {
                order.ready = Instant.now()
                notify(order.queue, "Obat siap diambil. Nomor pengambilan ${order.pickup}.", Demo.PHARMACY)
            }
            Rx.COMPLETED -> {
                order.completed = Instant.now()
                order.queue.status = Status.COMPLETED
                touch(order.queue, StageKind.PHARMACY, StageStatus.DONE, by)
                notify(order.queue, "Kunjungan selesai. Semoga lekas sehat.")
            }
            else -> Unit
        }
        return order
    }

    /**
     * CheckInTicket: the QR carries "HT1.<id>.<yyyyMMdd>.<signature>", the typed number "HT-000126".
     * ponytail: the signing key ships in the app, so it only rejects a hand-made QR or one for another
     * visit; on the web the key stays on the server.
     */
    private const val QR_KEY = "hytrack-demo-device-key"

    private fun sign(id: Int, date: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(QR_KEY.toByteArray(), "HmacSHA256"))
        return mac.doFinal("$id.$date".toByteArray()).joinToString("") { "%02x".format(Locale.ROOT, it) }.take(20)
    }

    fun payload(q: Queue): String {
        val date = q.date.format(DateTimeFormatter.BASIC_ISO_DATE)
        return "HT1.${q.id}.$date.${sign(q.id, date)}"
    }

    /** Staff confirm arrival by QR or number ([method]); the patient's demo control passes no staff. */
    fun checkIn(q: Queue, by: String? = STAFF, method: String = "code"): Queue {
        when {
            q.status == Status.NO_SHOW -> throw Refused("Tiket ditandai tidak hadir dan tidak dapat dipakai lagi.")
            q.status != Status.BOOKED -> throw Refused("Tiket ${q.number} sudah check-in pukul ${clock(q.checkIn)}.")
            q.date != today() -> throw Refused("Check-in hanya dapat dilakukan pada tanggal layanan.")
        }
        q.status = Status.WAITING
        q.checkIn = Instant.now()
        q.checkInBy = by
        q.checkInMethod = if (by == null) "demo" else method
        touch(q, StageKind.CHECK_IN, StageStatus.DONE, by)
        notify(q, "Check-in berhasil. Anda masuk antrean dokter.", "Tunggu di dekat ruang poliklinik.")
        return q
    }

    /** Cancelled, never removed: when, by whom and why. A patient's own cancellation ([by] null) needs no reason. */
    fun cancel(q: Queue, reason: String?, by: String? = STAFF) {
        val why = reason?.trim() ?: "Dibatalkan oleh pasien"
        when {
            why.isEmpty() -> throw Refused("Tuliskan alasan pembatalan.")
            why.length < 3 -> throw Refused("Alasan pembatalan minimal 3 karakter.")
            why.length > 255 -> throw Refused("Alasan pembatalan maksimal 255 karakter.")
            q.status !in setOf(Status.BOOKED, Status.WAITING) -> throw Refused(when (q.status) {
                Status.CANCELLED -> "Booking ini sudah dibatalkan."
                Status.SERVING, Status.LABORATORY, Status.PHARMACY -> "Pasien sudah dilayani; booking tidak dapat dibatalkan."
                else -> "Booking yang sudah selesai tidak dapat dibatalkan."
            })
            q.date < today() -> throw Refused("Booking tanggal yang sudah lewat tidak dapat dibatalkan.")
        }
        q.status = Status.CANCELLED
        q.cancelledAt = Instant.now()
        q.cancelledBy = by
        q.cancelReason = why
        q.stages.filter { it.status == StageStatus.PENDING || it.status == StageStatus.ACTIVE }.forEach {
            it.status = StageStatus.SKIPPED
            it.by = by
        }
        if (by != null) notify(q, "Booking ${q.number} dibatalkan petugas.", "Alasan: $why")
    }

    fun bookingDates(): List<LocalDate> = (0 until BOOKING_DAYS).map { today().plusDays(it) }

    fun quota(d: Doctor, date: LocalDate) = maxOf(0, DAILY_QUOTA - line(d, date).count { it.status != Status.CANCELLED })

    /** Patients ahead in the doctor's line (WaitEstimator::ahead). */
    fun ahead(q: Queue): Int {
        val waiting = line(q.doctor, q.date).filter { it.status == Status.WAITING }
        if (q.status != Status.WAITING) return waiting.size
        return waiting.count { it.checkIn!! < q.checkIn!! || (it.checkIn == q.checkIn && it.id < q.id) }
    }

    /** A booking from the patient app ([by] null) or by staff for a patient at the desk, under the same rules. */
    fun book(patient: Local.Patient, d: Doctor, date: LocalDate, by: String? = STAFF): Queue {
        when {
            date !in bookingDates() -> throw Refused("Tanggal kunjungan tidak tersedia.")
            patient.active != null || queues.any { it.phone == patient.phone && it.status.active } ->
                throw Refused("Pasien ini sudah memiliki satu kunjungan aktif.")
            quota(d, date) < 1 -> throw Refused("Kuota dokter pada tanggal ini sudah penuh.")
        }
        // Cancelled tickets keep their number, so the next number is simply the count + 1.
        val q = Queue(++queueIds, patient.name, patient.phone, d, date, line(d, date).size + 1, Status.BOOKED).apply {
            bookedAt = Instant.now()
            bookedBy = by
        }
        queues += q
        Local.attach(q)
        notify(q, "Antrean ${q.number} berhasil diambil.", "Check-in saat tiba di rumah sakit.")
        return q
    }

}
