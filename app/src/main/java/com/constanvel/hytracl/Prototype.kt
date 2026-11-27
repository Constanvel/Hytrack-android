package com.constanvel.hytracl

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Mode prototype (the web's /prototipe): Pasien, Pendamping, Petugas and Manajemen on invented
 * data, with no sign-in, database or network. While [active], [Api] hands every request to
 * [call], which answers like routes/api.php (StaffController, ClinicalBoard, QueueService,
 * ServiceInsights) from the on-device [Ops] visits, so nothing can reach the server or a real
 * patient, and a change by one role shows up for the others. [enter] sets the normal data aside
 * and starts fresh; [exit] puts it back.
 */
object Prototype {
    enum class As { PATIENT, COMPANION, STAFF, MANAGER }

    /** Petugas rooms of Figma 47:2, each with its sample account (ClinicalStaffSeeder). */
    val ROOMS = listOf("desk" to "Meja check-in · Dina", "doctor" to "Ruang dokter · dr. Rina",
        "support" to "Penunjang · Rani", "pharmacy" to "Farmasi · Apt. Dinda")

    private const val COMPANION_PHONE = "0812-5550-0843" // Ahmad, Local's second patient
    private const val RINA = 1
    private const val COUNTER = "Loket F2"
    private const val TARGET = 30
    private const val RESULT_MAX = 5 * 1024 * 1024
    private val UNITS = linkedMapOf("laboratorium" to "Laboratorium", "radiologi" to "Radiologi")
    private val CHECKS = mapOf(
        "received" to listOf("identity" to "Identitas dan nomor resep sesuai", "legible" to "Resep dokter terbaca lengkap", "dosage" to "Jumlah dan aturan pakai ditinjau"),
        "preparing" to listOf("items" to ":count item obat sesuai resep", "label" to "Jumlah dan label obat sesuai", "etiket" to "Aturan pakai pada etiket sesuai"),
        "ready" to listOf("identity" to "Cocokkan dua identitas pasien", "medicine" to "Konfirmasi obat dan jumlahnya", "explain" to "Jelaskan aturan penggunaan"),
    )
    private val RX_STAFF = mapOf("received" to "Belum diproses", "preparing" to "Diproses", "ready" to "Siap diambil",
        "completed" to "Diserahkan", "returned" to "Dikembalikan ke dokter")
    private val MEDICINES = listOf(
        listOf("Amlodipin", "Tablet 5 mg", "30 tablet", "1 × 1 tablet", "Pagi hari"),
        listOf("Metformin", "Tablet 500 mg", "60 tablet", "2 × 1 tablet", "Sesudah makan"),
        listOf("Omeprazol", "Kapsul 20 mg", "14 kapsul", "1 × 1 kapsul", "Sebelum makan"),
        listOf("Parasetamol", "Tablet 500 mg", "10 tablet", "3 × 1 tablet bila demam", "Sesudah makan"),
        listOf("Simvastatin", "Tablet 20 mg", "30 tablet", "1 × 1 tablet", "Malam hari"),
    )

    var active = false
        private set
    private var restore: () -> Unit = {}
    private var data = Data()

    fun enter() {
        if (active) return
        Api.logout()
        val local = Local.isolate()
        val ops = Ops.isolate()
        val invite = Demo.pendingInvite
        restore = { local(); ops(); Demo.pendingInvite = invite }
        active = true
        clearSession()
        data = Data().apply { seed() }
    }

    fun exit() {
        if (!active) return
        Api.logout()
        clearSession()
        restore()
        data = Data()
        active = false
    }

    private fun clearSession() {
        Demo.companion = false
        Demo.flash = null
        Demo.flashTone = Tone.GREEN
        Demo.pendingInvite = null
    }

    /** The four buttons of the login card: a session on the invented data, no credentials. */
    fun signIn(role: As) {
        check(active)
        Demo.signOut()
        when (role) {
            As.PATIENT -> {
                Local.login(Demo.DEMO_PHONE, Demo.DEMO_PASSWORD)
                Demo.flash = "Mode prototype: Anda masuk sebagai pasien contoh, ${Demo.patient}."
            }
            As.COMPANION -> follow()
            As.STAFF -> room("desk")
            As.MANAGER -> Api.become(json("role" to "manager", "name" to "Rahma", "unit" to "Manajemen operasional"))
        }
    }

    /** Signs the staff area in as the sample account of one room. */
    fun room(room: String) {
        check(active)
        val (name, unit) = when (room) {
            "doctor" -> Ops.doctors.first { it.id == RINA }.name to "Penyakit Dalam"
            "support" -> "Rani" to "Laboratorium"
            "pharmacy" -> "Apt. Dinda" to "Farmasi"
            else -> Ops.STAFF to "Poli Penyakit Dalam"
        }
        Api.become(json("role" to "staff", "name" to name, "unit" to unit, "room" to room))
    }

    /** Ahmad accompanies Siti's visit; without one yet, she is booked and checked in first. */
    private fun follow() {
        fun signInAs(phone: String) = check(Local.login(phone, Demo.DEMO_PASSWORD))
        signInAs(COMPANION_PHONE)
        if (Local.following().isEmpty()) {
            signInAs(Demo.DEMO_PHONE)
            if (Demo.activeVisit == null) {
                val siti = Local.patientByPhone(Demo.DEMO_PHONE)!!
                val doctor = Ops.doctors.first { Ops.quota(it, Ops.today()) > 0 && it.id != RINA }
                Ops.checkIn(Ops.book(siti, doctor, Ops.today(), by = null), by = null)
            }
            val token = Local.invite().substringAfterLast('/')
            signInAs(COMPANION_PHONE)
            Local.accept(token)
        }
        Demo.companion = true
    }

    // Clinical data the patient app does not keep ------------------------------------------------

    private class Exam {
        var notes: String? = null
        var allergy: String? = null
        var pharmacyNote: String? = null
        var items = listOf<JSONObject>()
        var confirmedAt: Instant? = null
    }

    private class Support(val id: Int, val queue: Ops.Queue, val unit: String, val test: String, val requestedAt: Instant) {
        var status = "waiting"
        var note: String? = null
        var file: Api.Upload? = null
        var startedAt: Instant? = null
        var completedAt: Instant? = null
        var by: String? = null
        val number get() = "PNJ-%03d".format(Locale.ROOT, id)
        val unitLabel get() = UNITS.getValue(unit)
    }

    private class Handling {
        var verifiedAt: Instant? = null
        var calledAt: Instant? = null
        var returnedAt: Instant? = null
        var reason: String? = null
        var returned = false
        var checks = listOf<String>()
    }

    private class Data {
        val exams = HashMap<Ops.Queue, Exam>()
        val supports = mutableListOf<Support>()
        val orders = HashMap<Int, Handling>()
        val upNext = HashSet<Ops.Queue>()

        fun handling(order: Ops.Order) = orders.getOrPut(order.id) { Handling() }

        fun refer(q: Ops.Queue, unit: String, test: String, at: Instant) =
            Support(supports.size + 1, q, unit, test, at).also { supports += it }

        /** DatabaseSeeder: only dr. Rina refers; every visit past its doctor has an examination. */
        fun seed() = Ops.queues.forEach { q ->
            val sequence = q.number.substringAfter('-').toInt()
            val pastLab = q.status == Ops.Status.PHARMACY || q.status == Ops.Status.COMPLETED
            val inLab = q.status == Ops.Status.LABORATORY
            val consulted = q.stage(Ops.StageKind.CONSULTATION).completed
            val lab = q.stage(Ops.StageKind.LABORATORY)
            val referred = q.doctor.id == RINA && (inLab || (pastLab && sequence % 4 == 0))
            if (pastLab && !referred) {
                lab.status = Ops.StageStatus.SKIPPED
                lab.started = null
                lab.completed = null
                lab.by = null
            }
            if (inLab || pastLab) exams[q] = sample(sequence).apply { confirmedAt = if (pastLab) q.order?.received else null }
            if (referred) refer(q, "laboratorium", LAB_TEST, consulted ?: Instant.now()).apply {
                if (pastLab || sequence % 2 == 0) {
                    status = if (pastLab) "done" else "processing"
                    startedAt = requestedAt.plus(Duration.ofMinutes(4))
                    completedAt = if (pastLab) lab.completed else null
                    by = "Rani"
                }
            }
            q.order?.takeIf { it.status != Ops.Rx.RECEIVED }?.let { handling(it).verifiedAt = it.received.plus(Duration.ofMinutes(4)) }
        }
    }

    private const val LAB_TEST = "Darah lengkap & gula darah puasa"

    private fun sample(sequence: Int) = Exam().apply {
        notes = "Kontrol rutin. Tanda vital dalam batas normal. Lanjutkan terapi."
        allergy = if (sequence % 5 == 0) "Penisilin" else null
        items = listOf(0, 1).map { position ->
            val (name, form, quantity, dosage, timing) = MEDICINES[(sequence + position * 2) % MEDICINES.size]
            json("name" to name, "form" to form, "quantity" to quantity, "dosage" to dosage, "timing" to timing)
        }
    }

    /** The patient's demo controls move a visit past the staff screens; give it what they read. */
    private fun reconcile() = Ops.queues.forEach { q ->
        val sequence = q.number.substringAfter('-').toInt()
        val request = support(q)
        if (q.status == Ops.Status.LABORATORY && request == null) {
            data.exams.getOrPut(q) { sample(sequence).apply { items = emptyList(); notes = null } }
            data.refer(q, "laboratorium", LAB_TEST, q.stage(Ops.StageKind.CONSULTATION).completed ?: Instant.now())
        }
        val order = q.order ?: return@forEach
        val exam = data.exams.getOrPut(q) { sample(sequence) }
        if (exam.confirmedAt == null && !returned(order)) {
            exam.confirmedAt = order.received
            if (exam.items.isEmpty()) exam.items = sample(sequence).items
        }
        if (request != null && request.status != "done") {
            request.status = "done"
            request.completedAt = q.stage(Ops.StageKind.LABORATORY).completed ?: Instant.now()
        }
    }

    // Requests (routes/api.php) -------------------------------------------------------------------

    /** Answers a request of the staff or management screens; refusals read like Laravel's. */
    @Synchronized
    fun call(method: String, path: String, body: JSONObject? = null, upload: Api.Upload? = null): JSONObject {
        val route = path.substringBefore('?').split('/')
        val query = path.substringAfter('?', "").split('&').filter { '=' in it }
            .associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        authorize(route[0])
        reconcile()
        val answer = try {
            when {
                route[0] == "manajemen" -> management(query)
                route.size == 1 -> board(query)
                else -> action(method, route.drop(1), query, body ?: JSONObject(), upload)
            }
        } catch (e: Ops.Refused) {
            refuse(e.message.orEmpty())
        }
        // A copy, as over the network: a screen never holds the stored objects.
        return JSONObject(answer.toString())
    }

    /** The support result (staff) or the per-clinic CSV (management). */
    @Synchronized
    fun download(path: String, into: File): File {
        val route = path.substringBefore('?').split('/')
        authorize(route[0])
        if (route[0] == "manajemen") {
            val query = path.substringAfter('?', "").split('&').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            into.writeText(csv(query["tanggal"]?.let(LocalDate::parse) ?: Ops.today(), query["poli"]?.toIntOrNull()))
            return into
        }
        val request = data.supports.firstOrNull { "${it.id}" == route.getOrNull(2) }
        if (request != null && room != "support" && !(room == "doctor" && request.queue.doctor.id == RINA)) forbidden()
        into.writeBytes(request?.file?.bytes ?: refuse("Data tidak ditemukan.", 404))
        return into
    }

    private fun authorize(area: String) {
        val needed = when (area) {
            "petugas" -> "staff"
            "manajemen" -> "manager"
            else -> refuse("Matikan Mode prototype untuk masuk dengan akun asli.", 403)
        }
        val role = Api.account?.optString("role") ?: refuse("Sesi berakhir. Masuk kembali untuk melanjutkan.", 401)
        if (role != needed) forbidden()
    }

    private val room get() = Api.account?.str("room")
    private val me get() = Api.account?.str("name").orEmpty()

    private fun refuse(message: String, status: Int = 422): Nothing = throw Api.Failure(status, message)
    private fun forbidden(): Nothing = refuse("Akun ini tidak memiliki akses ke halaman tersebut.", 403)
    private fun only(needed: String) {
        if (room != needed) forbidden()
    }

    private fun message(text: String) = json("message" to text)

    private fun action(method: String, route: List<String>, query: Map<String, String>, body: JSONObject, upload: Api.Upload?): JSONObject {
        val id = route.getOrNull(1)?.toIntOrNull()
        val step = route.getOrNull(2)
        return when (route[0]) {
            "dokter" -> callNext(id)
            "antrean" -> queueAction(find(Ops.queues.firstOrNull { it.id == id }), step, body)
            "pemeriksaan" -> examination(find(Ops.queues.firstOrNull { it.id == id }), step, body)
            "penunjang" -> supportAction(find(data.supports.firstOrNull { it.id == id }), step == "selesai", body, upload)
            "farmasi" -> pharmacyAction(find(Ops.queues.mapNotNull { it.order }.firstOrNull { it.id == id }), step, body)
            "scan" -> json("ticket" to ticket(identify(body.optString("code"))))
            "check-in" -> {
                val code = body.optString("code")
                val q = Ops.checkIn(identify(code), me, if (code.trim().startsWith("HT1.")) "qr" else "code")
                message("${q.number} berhasil check-in pukul ${Ops.clock(q.checkIn)}.")
            }
            "booking" -> book(body)
            "pasien" -> {
                val patient = Local.patientByPhone(query["telepon"].orEmpty()) ?: refuse("Nomor ponsel belum terdaftar sebagai akun pasien.", 404)
                json("patient" to json("name" to patient.name, "phone" to patient.masked, "active" to patient.active))
            }
            "jadwal" -> schedule(query["tanggal"]?.let(LocalDate::parse) ?: Ops.today())
            "poliklinik" -> clinic(method, id, body)
            else -> refuse("Data tidak ditemukan.", 404)
        }
    }

    private fun <T> find(value: T?): T = value ?: refuse("Data tidak ditemukan.", 404)

    private fun line(doctor: Ops.Doctor?, date: LocalDate = Ops.today()) =
        Ops.queues.filter { it.doctor === doctor && it.date == date }.sortedWith(compareBy<Ops.Queue, Instant?>(nullsFirst()) { it.checkIn }.thenBy { it.id })

    private fun callNext(doctorId: Int?): JSONObject {
        val line = line(find(Ops.doctors.firstOrNull { it.id == doctorId }))
        return summon(line.firstOrNull { it.status == Ops.Status.WAITING } ?: run {
            if (line.any { it.status == Ops.Status.SERVING }) refuse("Selesaikan pasien aktif sebelum memanggil berikutnya.")
            refuse("Tidak ada pasien yang menunggu.")
        })
    }

    /** One patient with the doctor at a time; the next in line is told once. */
    private fun summon(q: Ops.Queue): JSONObject {
        if (q.status != Ops.Status.WAITING) refuse("Hanya pasien yang sudah check-in dapat dipanggil.")
        if (q.date != Ops.today()) refuse("Pasien hanya dapat dipanggil pada tanggal layanan.")
        if (line(q.doctor).any { it.status == Ops.Status.SERVING }) refuse("Selesaikan pasien aktif sebelum memanggil berikutnya.")
        Ops.serve(q, me)
        line(q.doctor).firstOrNull { it.status == Ops.Status.WAITING }?.let {
            if (data.upNext.add(it)) Local.notify(it.phone, "Giliran Anda berikutnya.", "Tetap di dekat ruang pemeriksaan.")
        }
        return message("${q.number} dipanggil.")
    }

    private fun queueAction(q: Ops.Queue, step: String?, body: JSONObject): JSONObject = when (step) {
        "panggil" -> summon(q)
        "panggil-ulang" -> {
            if (q.status != Ops.Status.SERVING) refuse("Hanya pasien yang sedang dipanggil dapat dipanggil ulang.")
            q.calledCount++
            Local.notify(q.phone, "Panggilan ulang untuk nomor ${q.number}.", "Segera menuju ruang pemeriksaan.")
            message("${q.number} dipanggil ulang.")
        }
        "tidak-hadir" -> {
            val notice = when (q.status) {
                Ops.Status.BOOKED -> {
                    if (!closed(q)) refuse("Pasien belum melewati batas check-in pukul ${Ops.clock(q.deadline)}. Statusnya masih Belum check-in.")
                    Ops.touch(q, Ops.StageKind.CHECK_IN, Ops.StageStatus.SKIPPED, me)
                    "Anda tidak check-in sebelum batas waktu, sehingga ditandai tidak hadir."
                }
                Ops.Status.SERVING -> {
                    if (q.calledCount < 2) refuse("Tandai tidak hadir hanya setelah panggilan ulang.")
                    Ops.touch(q, Ops.StageKind.CONSULTATION, Ops.StageStatus.SKIPPED, me)
                    "Anda tidak hadir saat dipanggil."
                }
                else -> refuse("Tahap kunjungan tidak dapat dilewati atau diulang.")
            }
            q.status = Ops.Status.NO_SHOW
            q.noShowAt = Instant.now()
            q.noShowBy = me
            Local.notify(q.phone, notice, "Hubungi petugas di meja check-in.")
            message("${q.number} ditandai tidak hadir.")
        }
        "batal" -> {
            Ops.cancel(q, body.optString("reason"), me)
            message("Booking ${q.number} dibatalkan. Kuota dokter dibuka kembali.")
        }
        else -> refuse("Data tidak ditemukan.", 404)
    }

    private fun closed(q: Ops.Queue) = q.status == Ops.Status.BOOKED && (q.date < Ops.today() || q.checkInClosed)

    // Pemeriksaan dokter --------------------------------------------------------------------------

    private fun returned(order: Ops.Order) = order.status == Ops.Rx.RECEIVED && data.orders[order.id]?.returned == true

    private fun open(q: Ops.Queue) = when (q.status) {
        Ops.Status.SERVING, Ops.Status.LABORATORY -> data.exams[q]?.confirmedAt == null
        Ops.Status.PHARMACY -> q.order?.let(::returned) == true
        else -> false
    }

    private fun support(q: Ops.Queue) = data.supports.lastOrNull { it.queue === q }

    private fun text(body: JSONObject, key: String, max: Int, tooLong: String): String? {
        val value = body.str(key)?.trim()?.ifEmpty { null }
        if (value != null && value.length > max) refuse(tooLong)
        return value
    }

    private fun examination(q: Ops.Queue, step: String?, body: JSONObject): JSONObject {
        if (room != "doctor" || q.doctor.id != RINA) refuse("Hanya dokter pemeriksa yang dapat mengubah pemeriksaan ini.", 403)
        return when (step) {
            null -> {
                save(q, body)
                message("Draf pemeriksaan ${q.number} tersimpan.")
            }
            "penunjang" -> {
                val unit = body.optString("unit")
                val test = body.optString("test").trim()
                if (unit !in UNITS) refuse("Pilih unit penunjang.")
                if (test.isEmpty()) refuse("Tuliskan jenis pemeriksaan.")
                if (test.length !in 3..100) refuse(if (test.length < 3) "Jenis pemeriksaan minimal 3 karakter." else "Jenis pemeriksaan maksimal 100 karakter.")
                save(q, body)
                refer(q, unit, test)
            }
            "konfirmasi" -> {
                if (body.optString("examined") != "1") refuse("Tandai pasien sudah diperiksa sebelum mengonfirmasi.")
                save(q, body)
                confirm(q)
            }
            else -> refuse("Data tidak ditemukan.", 404)
        }
    }

    private fun save(q: Ops.Queue, body: JSONObject) {
        if (!open(q)) refuse("Pemeriksaan ini sudah dikonfirmasi dan tidak dapat diubah.")
        val notes = text(body, "notes", 2000, "Catatan pemeriksaan maksimal 2000 karakter.")
        val allergy = text(body, "allergy", 120, "Catatan alergi maksimal 120 karakter.")
        val pharmacy = text(body, "pharmacy_note", 255, "Catatan farmasi maksimal 255 karakter.")
        val items = body.optJSONArray("items")?.objects()?.also { list ->
            if (list.size > 20) refuse("Resep maksimal 20 obat.")
            list.forEach { item ->
                if (item.str("name") == null) refuse("Nama obat wajib diisi.")
                if (item.str("quantity") == null) refuse("Jumlah obat wajib diisi.")
                if (item.str("dosage") == null) refuse("Aturan pakai wajib diisi.")
            }
        }
        data.exams.getOrPut(q) { Exam() }.apply {
            this.notes = notes
            this.allergy = allergy
            pharmacyNote = pharmacy
            if (items != null) this.items = items
        }
    }

    private fun refer(q: Ops.Queue, unit: String, test: String): JSONObject {
        support(q)?.takeIf { it.status != "done" }?.let { refuse("Permintaan ${it.number} masih diproses.") }
        if (q.status != Ops.Status.SERVING && q.status != Ops.Status.LABORATORY) refuse("Permintaan penunjang dibuat selama pemeriksaan dokter.")
        Ops.touch(q, Ops.StageKind.CONSULTATION, Ops.StageStatus.DONE, me)
        Ops.touch(q, Ops.StageKind.LABORATORY, Ops.StageStatus.ACTIVE, me).apply { started = Instant.now(); completed = null }
        q.status = Ops.Status.LABORATORY
        val request = data.refer(q, unit, test, Instant.now())
        Local.notify(q.phone, "Dokter merujuk Anda ke ${request.unitLabel}.", if (unit == "radiologi") "Radiologi A-112 · Gedung A, lantai 1" else Demo.LABORATORY)
        return message("Permintaan ${request.number} dikirim ke ${request.unitLabel}.")
    }

    /** With medicine the visit moves to the pharmacy (a returned prescription reopens); without, it ends. */
    private fun confirm(q: Ops.Queue): JSONObject {
        val exam = data.exams.getValue(q)
        if (exam.notes == null) refuse("Tuliskan catatan pemeriksaan sebelum mengonfirmasi.")
        if (q.status == Ops.Status.LABORATORY && support(q)?.status != "done") refuse("Tunggu hasil penunjang sebelum mengonfirmasi pemeriksaan.")
        val prescribed = exam.items.isNotEmpty()
        val previous = q.order
        if (previous != null && returned(previous)) {
            if (!prescribed) refuse("Resep yang dikembalikan farmasi harus berisi obat.")
            exam.confirmedAt = Instant.now()
            q.order = Ops.Order(previous.id, q, Ops.Rx.RECEIVED, Instant.now()).also { it.by = me }
            data.orders.remove(previous.id)
            Local.notify(q.phone, "Resep diperbarui dokter. Nomor pengambilan ${previous.pickup}.", Demo.PHARMACY)
        } else {
            exam.confirmedAt = Instant.now()
            if (q.status == Ops.Status.SERVING) {
                Ops.touch(q, Ops.StageKind.CONSULTATION, Ops.StageStatus.DONE, me)
                Ops.touch(q, Ops.StageKind.LABORATORY, Ops.StageStatus.SKIPPED, me)
            }
            if (prescribed) {
                Ops.touch(q, Ops.StageKind.PHARMACY, Ops.StageStatus.ACTIVE, me)
                val order = Ops.prescribe(q, me)
                q.status = Ops.Status.PHARMACY
                Local.notify(q.phone, "Resep dikirim ke farmasi. Nomor pengambilan ${order.pickup}.", Demo.PHARMACY)
            } else {
                Ops.touch(q, Ops.StageKind.PHARMACY, Ops.StageStatus.SKIPPED, me)
                q.status = Ops.Status.COMPLETED
                Local.notify(q.phone, "Pemeriksaan selesai tanpa resep. Semoga lekas sehat.", null)
            }
        }
        return message(q.order?.let { "Pemeriksaan ${q.number} dikonfirmasi. Resep ${it.pickup} dikirim ke farmasi." }
            ?: "Pemeriksaan ${q.number} dikonfirmasi tanpa resep. Kunjungan selesai.")
    }

    // Pelayanan penunjang -------------------------------------------------------------------------

    private fun supportAction(request: Support, complete: Boolean, body: JSONObject, upload: Api.Upload?): JSONObject {
        only("support")
        if (request.status == "done") refuse("Hasil ${request.number} sudah dikirim ke dokter.")
        val status = body.optString("status")
        if (!complete && status != "waiting" && status != "processing") refuse("Pilih status pemeriksaan.")
        val note = text(body, "note", 1000, "Catatan petugas maksimal 1000 karakter.")
        if (upload != null) {
            if (upload.type !in setOf("application/pdf", "image/jpeg", "image/png")) refuse("Hasil pemeriksaan harus berupa PDF, JPG, atau PNG.")
            if (upload.bytes.size > RESULT_MAX) refuse("Ukuran hasil pemeriksaan maksimal 5 MB.")
        }
        if (complete && upload == null && request.file == null) refuse("Lampirkan hasil pemeriksaan sebelum mengirim ke dokter.")
        upload?.let { request.file = it }
        request.by = me
        if (!complete) {
            request.status = status
            request.note = note
            if (status == "processing" && request.startedAt == null) request.startedAt = Instant.now()
            return message("Status ${request.number} tersimpan.")
        }
        request.status = "done"
        request.note = note ?: request.note
        request.startedAt = request.startedAt ?: Instant.now()
        request.completedAt = Instant.now()
        Ops.touch(request.queue, Ops.StageKind.LABORATORY, Ops.StageStatus.DONE, me)
        Local.notify(request.queue.phone, "Hasil ${request.unitLabel} sudah dikirim ke dokter.", "Tunggu panggilan dokter untuk tindak lanjut.")
        return message("Hasil ${request.number} dikirim ke dokter pengirim.")
    }

    // Farmasi -------------------------------------------------------------------------------------

    /** "checking" is the older step between preparing and ready; the staff flow has no such stop. */
    private fun rx(order: Ops.Order) = when {
        returned(order) -> "returned"
        order.status == Ops.Rx.CHECKING -> "preparing"
        else -> order.status.name.lowercase()
    }

    private fun pharmacyAction(order: Ops.Order, step: String?, body: JSONObject): JSONObject {
        only("pharmacy")
        val handling = data.handling(order)
        val status = rx(order)
        val required = CHECKS[status].orEmpty().map { it.first }
        val ticked = body.optJSONArray("checks")?.strings().orEmpty()
        return when (step) {
            "lanjut" -> {
                if (!ticked.containsAll(required)) refuse("Centang semua pemeriksaan sebelum melanjutkan.")
                if (status == "returned") refuse("Resep sedang ditinjau ulang dokter.")
                Ops.advance(order, me)
                if (order.status == Ops.Rx.PREPARING) handling.verifiedAt = Instant.now()
                if (order.status == Ops.Rx.CHECKING) Ops.advance(order, me)
                handling.checks = emptyList()
                message("${order.pickup}: ${RX_STAFF.getValue(rx(order)).lowercase()}.")
            }
            "cek" -> {
                if (status == "completed" || status == "returned") refuse("Resep ini tidak sedang diproses farmasi.")
                handling.checks = ticked.filter { it in required }
                order.by = me
                message("Proses ${order.pickup} tersimpan.")
            }
            "panggil" -> {
                if (status != "ready") refuse("Pasien dipanggil setelah obat siap diambil.")
                handling.calledAt = Instant.now()
                order.by = me
                Local.notify(order.queue.phone, "Nomor ${order.pickup} dipanggil di $COUNTER.", Demo.PHARMACY)
                message("Pasien ${order.pickup} dipanggil ke $COUNTER.")
            }
            "kembalikan" -> {
                val reason = body.optString("reason").trim()
                if (reason.isEmpty()) refuse("Tuliskan alasan resep dikembalikan.")
                if (reason.length < 3) refuse("Alasan minimal 3 karakter.")
                if (status != "received") refuse("Resep hanya dapat dikembalikan sebelum obat diproses.")
                handling.returned = true
                handling.returnedAt = Instant.now()
                handling.reason = reason.take(255)
                handling.checks = emptyList()
                order.by = me
                data.exams[order.queue]?.confirmedAt = null
                Local.notify(order.queue.phone, "Resep Anda sedang ditinjau ulang oleh dokter.", "Tunggu di area farmasi.")
                message("Resep ${order.pickup} dikembalikan ke dokter.")
            }
            else -> refuse("Data tidak ditemukan.", 404)
        }
    }

    // Check-in, booking dan data poliklinik -------------------------------------------------------

    /** CheckInTicket::identify: the ticket behind a QR or typed number, if it is for today. */
    private fun identify(input: String): Ops.Queue {
        val code = input.trim()
        if (code.isEmpty()) refuse("Masukkan nomor check-in pasien.")
        val qr = Regex("^HT1\\.(\\d{1,9})\\.\\d{8}\\.[0-9a-f]{20}$").find(code)
        val typed = Regex("^HT-?(\\d{1,9})$", RegexOption.IGNORE_CASE).find(code)
        val id = (qr ?: typed ?: refuse("Kode tidak dikenali. Pindai QR tiket HyTrack atau ketik nomor check-in.")).groupValues[1].toInt()
        val q = Ops.queues.firstOrNull { it.id == id } ?: refuse("Nomor check-in tidak ditemukan.")
        if (qr != null && Ops.payload(q) != code) refuse("QR tidak valid. Minta pasien membuka ulang tiket di aplikasi.")
        when {
            q.status == Ops.Status.CANCELLED -> refuse("Booking ini sudah dibatalkan${q.cancelReason?.let { " ($it)" }.orEmpty()} dan tidak dapat dipakai check-in.")
            q.date < Ops.today() -> refuse("Tiket sudah kedaluwarsa. Tanggal kunjungannya ${Ops.dateLabel(q.date)}.")
            q.date > Ops.today() -> refuse("Tiket untuk ${Ops.dateLabel(q.date)}. Check-in dibuka pada tanggal tersebut.")
        }
        return q
    }

    private fun ticket(q: Ops.Queue) = json(
        "state" to when (q.status) {
            Ops.Status.BOOKED -> "booked"
            Ops.Status.NO_SHOW -> "no_show"
            else -> "checked_in"
        },
        "checkedInAt" to Ops.clock(q.checkIn), "checkedInBy" to q.checkInBy, "code" to q.code, "name" to q.patient,
        "ticket" to q.number, "clinic" to q.clinic.name, "doctor" to q.doctor.name,
        "schedule" to q.doctor.time + q.clinic.room?.let { " · ${it.substringBefore(" · ")}" }.orEmpty(),
        "date" to Ops.dateLabel(q.date), "deadline" to Ops.clock(q.deadline),
    )

    private fun book(body: JSONObject): JSONObject {
        val phone = body.str("phone") ?: refuse("Masukkan nomor ponsel pasien terdaftar.")
        val doctor = Ops.doctors.firstOrNull { it.id == body.int("doctor_id") } ?: refuse("Pilih dokter.")
        val date = body.str("date")?.let(LocalDate::parse) ?: refuse("Pilih tanggal kunjungan.")
        val patient = Local.patientByPhone(phone) ?: refuse("Nomor ponsel belum terdaftar sebagai akun pasien.")
        if (doctor.clinic.id != body.int("polyclinic_id")) refuse("Dokter tidak praktik di poliklinik yang dipilih.")
        val q = Ops.book(patient, doctor, date, me)
        return message("Booking ${q.number} dibuat untuk ${patient.name} · ${Ops.dateLabel(date)}.")
    }

    private fun clinics() = Ops.clinics.filter { !it.archived }

    private fun schedule(date: LocalDate): JSONObject {
        val open = date in Ops.bookingDates()
        return json("open" to open, "clinics" to clinics().map { clinic ->
            json("id" to clinic.id, "name" to clinic.name, "doctors" to Ops.doctors.filter { it.clinic === clinic }.map {
                json("id" to it.id, "name" to it.name, "time" to it.time, "quota" to if (open) Ops.quota(it, date) else 0)
            })
        })
    }

    private fun clinic(method: String, id: Int?, body: JSONObject): JSONObject {
        val current = id?.let { find(clinics().firstOrNull { it.id == id }) }
        if (method == "DELETE") {
            val clinic = find(current)
            val ongoing = Ops.queues.count { it.clinic === clinic && ongoing(it) }
            clinic.archived = true
            return message("Poliklinik ${clinic.name} dihapus dari daftar. Riwayat antreannya tetap tersimpan." +
                if (ongoing > 0) " $ongoing antrean yang masih berjalan tetap bisa diselesaikan di dashboard." else "")
        }
        val name = body.str("name")?.trim().orEmpty()
        val room = body.str("room")?.trim()?.ifEmpty { null }
        if (name.isEmpty()) refuse("Nama poliklinik wajib diisi.")
        if (name.length < 3) refuse("Nama poliklinik minimal 3 karakter.")
        if (name.length > 100) refuse("Nama poliklinik maksimal 100 karakter.")
        if (clinics().any { it !== current && it.name.equals(name, ignoreCase = true) }) refuse("Nama poliklinik sudah dipakai.")
        if (room != null && room.length > 100) refuse("Lokasi ruangan maksimal 100 karakter.")
        if (current == null) {
            Ops.addClinic(name, room)
            return message("Poliklinik $name ditambahkan.")
        }
        current.name = name
        current.room = room
        return message("Poliklinik $name diperbarui.")
    }

    private fun ongoing(q: Ops.Queue) = q.status.active && q.date >= Ops.today()

    // Staff board (StaffController::index) --------------------------------------------------------

    private val zone get() = ZoneId.systemDefault()
    private val ID = Locale.forLanguageTag("id")
    private fun stamp(at: Instant?) = at?.let { "${Ops.shortDate(it.atZone(zone).toLocalDate())} · ${Ops.clock(it)}" }
    private fun longStamp(at: Instant?) = at?.let { DateTimeFormatter.ofPattern("d MMMM yyyy, HH.mm", ID).format(it.atZone(zone)) }
    private fun minutes(from: Instant?, to: Instant? = Instant.now()) =
        if (from != null && to != null) Duration.between(from, to).seconds / 60.0 else null

    private fun board(query: Map<String, String>): JSONObject {
        val today = Ops.today()
        val shown = Ops.clinics.filter { clinic -> !clinic.archived || Ops.queues.any { it.clinic === clinic && ongoing(it) } }
        val own = Ops.doctors.firstOrNull { room == "doctor" && "poli" !in query && it.id == RINA }
        val clinic = shown.firstOrNull { it.id == (own?.clinic?.id ?: query["poli"]?.toIntOrNull()) } ?: shown.firstOrNull()
        val doctors = Ops.doctors.filter { it.clinic === clinic }
        val doctor = doctors.firstOrNull { it.id == (own?.id ?: query["dokter"]?.toIntOrNull()) } ?: doctors.firstOrNull()
        val line = line(doctor)
        val todays = Ops.queues.filter { it.date == today }
        val current = line.firstOrNull { it.status == Ops.Status.SERVING }
        fun count(vararg statuses: Ops.Status) = line.count { it.status in statuses }
        fun row(q: Ops.Queue) = json("id" to q.id, "number" to q.number, "name" to q.patient, "checkIn" to Ops.clock(q.checkIn))

        return json(
            "me" to json("name" to me, "unit" to Api.account?.str("unit"), "room" to room, "doctorId" to if (room == "doctor") RINA else null),
            "clinics" to shown.map { item ->
                json("id" to item.id, "name" to item.name + if (item.archived) " (dihapus)" else "",
                    "doctors" to Ops.doctors.filter { it.clinic === item }.map { json("id" to it.id, "name" to it.name) })
            },
            "clinicId" to clinic?.id,
            "doctor" to doctor?.let { json("id" to it.id, "room" to it.clinic.room, "time" to it.time) },
            "stats" to json(
                "waiting" to count(Ops.Status.WAITING),
                "examined" to count(Ops.Status.LABORATORY, Ops.Status.PHARMACY, Ops.Status.COMPLETED),
                "notCheckedIn" to count(Ops.Status.BOOKED),
                "registered" to line.count { it.status != Ops.Status.CANCELLED },
            ),
            "queue" to line.filter { it.status == Ops.Status.WAITING }.map(::row),
            "current" to current?.let {
                val started = it.stage(Ops.StageKind.CONSULTATION).started
                row(it).put("startedAt", Ops.clock(started)).put("minutes", minutes(started)?.toInt())
                    .put("calledCount", it.calledCount).put("confirmed", data.exams[it]?.confirmedAt != null)
            },
            "flow" to flow(todays),
            "examinations" to examinations(todays),
            "support" to supportRows(),
            "supportUnits" to JSONObject(UNITS as Map<*, *>),
            "pharmacy" to todays.mapNotNull { it.order }.filter { it.queue.status == Ops.Status.PHARMACY || it.queue.status == Ops.Status.COMPLETED }
                .sortedBy { it.id }.map(::prescription),
            "pharmacyStats" to json(
                "done" to todays.count { it.order?.status == Ops.Rx.COMPLETED },
                "average" to average(todays.mapNotNull { q -> q.order?.let { minutes(it.received, it.ready) } }),
            ),
            "history" to history(todays),
            "checkInDesk" to Ops.CHECK_IN_DESK,
            "clinicAdmin" to clinics().sortedBy { it.name }.map { item ->
                json("id" to item.id, "name" to item.name, "room" to item.room,
                    "doctors" to Ops.doctors.count { it.clinic === item }, "queues" to Ops.queues.count { it.clinic === item })
            },
        ).also { bookings(it, query) }
    }

    private fun bookings(into: JSONObject, query: Map<String, String>) {
        val today = Ops.today()
        val date = query["tanggal"]?.let { if (it.isEmpty()) null else runCatching { LocalDate.parse(it) }.getOrDefault(today) } ?: today.takeIf { "tanggal" !in query }
        val clinic = query["bpoli"]?.toIntOrNull()
        val status = Ops.Status.entries.firstOrNull { it.name.lowercase() == query["status"] }
        val search = query["cari"].orEmpty().trim()
        val matches = Ops.queues.filter { q ->
            (date == null || q.date == date) && (clinic == null || q.clinic.id == clinic) && (status == null || q.status == status) &&
                (search.isEmpty() || listOf(q.number, q.patient, q.code).any { it.contains(search, ignoreCase = true) })
        }.sortedWith(compareBy({ it.date }, { it.clinic.id }, { it.doctor.id }, { it.id }))

        into.put("bookingFilters", json("date" to (date?.toString() ?: ""), "clinic" to clinic, "status" to (status?.name?.lowercase() ?: ""), "search" to search))
            .put("bookingTotal", matches.size)
            .put("bookings", JSONArray(matches.take(200).map(::bookingRow)))
            .put("statuses", JSONArray(Ops.Status.entries.map { json("value" to it.name.lowercase(), "label" to it.label) }))
            .put("recentBookings", JSONArray(Ops.queues.sortedByDescending { it.id }.take(5).map { q ->
                json("id" to q.id, "number" to q.number, "name" to q.patient, "clinic" to q.clinic.name, "date" to q.date.toString(),
                    "dateLabel" to Ops.dateLabel(q.date), "status" to q.status.label, "bookedAt" to stamp(q.bookedAt))
            }))
            .put("bookingDates", json("min" to today.toString(), "max" to Ops.bookingDates().last().toString()))
    }

    private fun bookingRow(q: Ops.Queue) = json(
        "id" to q.id, "number" to q.number, "code" to q.code, "name" to q.patient, "phone" to q.phone?.let { "•••• " + it.takeLast(4) },
        "clinic" to q.clinic.name, "doctor" to q.doctor.name, "time" to q.doctor.time, "date" to q.date.toString(),
        "dateLabel" to Ops.dateLabel(q.date), "isToday" to (q.date == Ops.today()), "status" to q.status.name.lowercase(),
        "statusLabel" to q.status.label, "bookedAt" to stamp(q.bookedAt),
        "bookedBy" to (q.bookedBy ?: q.bookedAt?.let { "Pasien (aplikasi)" }), "deadline" to Ops.clock(q.deadline),
        "checkInClosed" to closed(q), "checkInAt" to stamp(q.checkIn), "checkInBy" to q.checkInBy,
        "checkInMethod" to when (q.checkInMethod) {
            "qr" -> "Pindai QR"
            "code" -> "Nomor check-in"
            "demo" -> "Simulasi demo (tidak resmi)"
            else -> null
        },
        "calledCount" to q.calledCount, "noShowAt" to stamp(q.noShowAt), "noShowBy" to q.noShowBy,
        "cancelledAt" to stamp(q.cancelledAt), "cancelledBy" to q.cancelledAt?.let { q.cancelledBy ?: "Pasien" },
        "cancelReason" to q.cancelReason,
        "stages" to q.stages.map { s ->
            json("name" to stageName(q, s.kind), "status" to s.status.label, "at" to stamp(s.completed ?: s.started), "by" to s.by)
        },
    )

    private fun stageName(q: Ops.Queue, kind: Ops.StageKind) =
        if (kind == Ops.StageKind.LABORATORY) support(q)?.unitLabel ?: kind.label else kind.label

    private fun flow(todays: List<Ops.Queue>): JSONObject {
        val visits = todays.filter { it.status.active }.sortedWith(compareBy<Ops.Queue, Instant?>(nullsLast()) { it.checkIn }.thenBy { it.id })
        return json(
            "visits" to visits.map { q ->
                val request = support(q)
                val order = q.order
                val sentBack = order != null && returned(order)
                val resultBack = q.status == Ops.Status.LABORATORY && request?.status == "done"
                json(
                    "id" to q.id, "number" to q.number, "name" to q.patient, "clinicId" to q.clinic.id,
                    "stage" to when {
                        q.status == Ops.Status.BOOKED -> "arrival"
                        resultBack || sentBack || q.status == Ops.Status.WAITING || q.status == Ops.Status.SERVING -> "clinic"
                        q.status == Ops.Status.LABORATORY -> "support"
                        else -> "pharmacy"
                    },
                    "note" to when {
                        q.status == Ops.Status.BOOKED -> "Belum check-in · batas ${Ops.clock(q.deadline)}"
                        sentBack -> "Resep dikembalikan · menunggu dokter"
                        resultBack -> "Hasil penunjang · menunggu dokter"
                        q.status == Ops.Status.SERVING -> "Diperiksa · belum dikonfirmasi"
                        q.status == Ops.Status.WAITING -> "Menunggu · ${q.clinic.name}"
                        q.status == Ops.Status.LABORATORY -> "${request?.unitLabel ?: "Laboratorium"} · " +
                            if (request?.status == "processing") "dalam pemeriksaan" else "menunggu hasil"
                        order?.status == Ops.Rx.RECEIVED -> "Resep diterima · belum diproses"
                        order?.status == Ops.Rx.READY -> "Obat siap diambil"
                        else -> "Obat sedang diproses"
                    },
                    "late" to (q.status == Ops.Status.WAITING && (minutes(q.checkIn)?.toInt() ?: 0) > TARGET),
                )
            },
            "completed" to JSONObject(todays.filter { it.status == Ops.Status.COMPLETED }.groupingBy { "${it.clinic.id}" }.eachCount() as Map<*, *>),
        )
    }

    private fun examState(q: Ops.Queue) = when {
        q.order?.let(::returned) == true -> "returned"
        data.exams[q]?.confirmedAt != null -> "confirmed"
        q.status == Ops.Status.SERVING -> "serving"
        q.status == Ops.Status.LABORATORY && support(q)?.status == "done" -> "review"
        q.status == Ops.Status.LABORATORY -> "support"
        else -> "confirmed"
    }

    private fun record(q: Ops.Queue) = Local.recordNumber(q.phone)

    private val ORDER = listOf("serving", "review", "returned", "support", "confirmed")

    /** The doctor's own visits (notes and prescription included); other rooms see status only. */
    private fun examinations(todays: List<Ops.Queue>): List<JSONObject> {
        val doctorRoom = room == "doctor"
        return todays.filter { q ->
            (!doctorRoom || q.doctor.id == RINA) && (q.status == Ops.Status.SERVING || q.status == Ops.Status.LABORATORY ||
                q.order?.let(::returned) == true || (doctorRoom && data.exams[q]?.confirmedAt != null))
        }.sortedBy { ORDER.indexOf(examState(it)) }.take(40).map { q ->
            val exam = data.exams[q]
            val mine = doctorRoom && q.doctor.id == RINA
            val started = q.stage(Ops.StageKind.CONSULTATION).started
            val request = support(q)
            val order = q.order
            json(
                "id" to q.id, "number" to q.number, "name" to q.patient, "record" to record(q), "clinic" to q.clinic.name,
                "room" to q.clinic.room?.substringBefore(" · "), "doctor" to q.doctor.name, "state" to examState(q),
                "startedAt" to Ops.clock(started), "minutes" to minutes(started)?.toInt(),
                "confirmedAt" to Ops.clock(exam?.confirmedAt),
                "confirmedStamp" to longStamp(exam?.confirmedAt)?.let { "$it WIB" },
                "mine" to mine, "open" to (mine && open(q)),
                "notes" to exam?.notes.takeIf { mine }, "allergy" to exam?.allergy.takeIf { mine },
                "pharmacyNote" to exam?.pharmacyNote.takeIf { mine }, "items" to if (mine) exam?.items.orEmpty() else emptyList<JSONObject>(),
                "itemCount" to (exam?.items?.size ?: 0),
                "support" to request?.let {
                    json("id" to it.id, "number" to it.number, "unitLabel" to it.unitLabel, "test" to it.test, "status" to it.status,
                        "statusLabel" to supportStatus(it.status), "completedAt" to Ops.clock(it.completedAt),
                        "note" to it.note.takeIf { _ -> mine }, "file" to it.file?.takeIf { _ -> mine }?.let { f -> file(it.id, f) })
                },
                "order" to order?.let {
                    json("id" to it.id, "pickup" to it.pickup, "status" to rx(it), "statusLabel" to RX_STAFF.getValue(rx(it)),
                        "returnReason" to data.orders[it.id]?.reason)
                },
                "trail" to trail(q),
            )
        }
    }

    private fun supportStatus(status: String) = when (status) {
        "waiting" -> "Menunggu"
        "processing" -> "Diproses"
        else -> "Selesai"
    }

    private fun file(id: Int, upload: Api.Upload) = json("name" to upload.name, "url" to "/petugas/penunjang/$id/hasil")

    private fun supportRows(): List<JSONObject> {
        val order = listOf("processing", "waiting", "done")
        return data.supports.filter { it.queue.date == Ops.today() }
            .sortedWith(compareBy({ order.indexOf(it.status) }, { it.requestedAt }))
            .map { s ->
                val own = room == "support" || (room == "doctor" && s.queue.doctor.id == RINA)
                json(
                    "id" to s.id, "number" to s.number, "queueId" to s.queue.id, "ticket" to s.queue.number, "name" to s.queue.patient,
                    "unit" to s.unit, "unitLabel" to s.unitLabel, "test" to s.test, "status" to s.status, "statusLabel" to supportStatus(s.status),
                    "listLabel" to when {
                        s.status == "waiting" -> "Menunggu"
                        s.status == "done" -> "Hasil terkirim"
                        s.file != null -> "Menunggu hasil"
                        else -> "Diproses"
                    },
                    "requestedAt" to Ops.clock(s.requestedAt), "completedAt" to Ops.clock(s.completedAt), "doctor" to s.queue.doctor.name,
                    "note" to s.note.takeIf { own }, "file" to s.file?.takeIf { own }?.let { file(s.id, it) }, "by" to s.by,
                    "byUnit" to s.by?.let { "Laboratorium" }, "updatedAt" to Ops.clock(s.completedAt ?: s.startedAt ?: s.requestedAt),
                    "canEdit" to (room == "support" && s.status != "done"),
                )
            }
    }

    private fun prescription(order: Ops.Order): JSONObject {
        val q = order.queue
        val exam = data.exams[q]
        val handling = data.handling(order)
        val pharmacy = room == "pharmacy"
        val status = rx(order)
        val items = exam?.items.orEmpty()
        return json(
            "id" to order.id, "pickup" to order.pickup, "number" to q.number, "name" to q.patient, "clinic" to q.clinic.name,
            "record" to record(q), "receivedAt" to Ops.clock(order.received), "status" to status, "statusLabel" to RX_STAFF.getValue(status),
            "handledBy" to order.by.takeIf { status != "received" },
            "doctor" to q.doctor.name, "confirmedAt" to Ops.clock(exam?.confirmedAt), "sentAt" to longStamp(exam?.confirmedAt),
            "itemCount" to items.size, "items" to if (pharmacy) items else emptyList<JSONObject>(),
            "allergy" to if (pharmacy) exam?.allergy ?: "tidak dilaporkan" else null,
            "doctorNote" to exam?.pharmacyNote.takeIf { pharmacy },
            "verifiedAt" to Ops.clock(handling.verifiedAt), "readyAt" to Ops.clock(order.ready), "calledAt" to Ops.clock(handling.calledAt),
            "completedAt" to Ops.clock(order.completed), "returnedAt" to Ops.clock(handling.returnedAt), "returnReason" to handling.reason,
            "checks" to handling.checks,
            "checklist" to CHECKS[status].orEmpty().map { (key, label) -> json("key" to key, "label" to label.replace(":count", "${items.size}")) },
            "counter" to COUNTER, "canAct" to pharmacy,
        )
    }

    /** Jejak pelayanan pasien (frame 32). */
    private fun trail(q: Ops.Queue): List<JSONObject> {
        val rows = mutableListOf<Pair<Instant, JSONObject>>()
        fun add(at: Instant?, title: String, detail: String?) {
            if (at != null) rows += at to json("time" to Ops.clock(at), "title" to title, "detail" to detail)
        }
        val request = support(q)
        val order = q.order
        add(q.checkIn, "Check-in terverifikasi", q.checkInBy?.let { "Petugas $it" } ?: "Meja check-in")
        add(q.stage(Ops.StageKind.CONSULTATION).started, "Pemeriksaan dimulai", q.doctor.name)
        if (request != null) {
            add(request.requestedAt, "Dirujuk ke ${request.unitLabel}", "${request.number} · ${request.test}")
            add(request.completedAt, "Hasil penunjang dikirim", request.by)
        }
        add(data.exams[q]?.confirmedAt, "Sudah diperiksa", "Konfirmasi dokter tersimpan")
        if (order != null) {
            if (returned(order)) add(data.orders[order.id]?.returnedAt, "Resep dikembalikan farmasi", data.orders[order.id]?.reason)
            add(order.received, "Resep diterima farmasi", "${order.pickup} · ${RX_STAFF.getValue(rx(order)).lowercase()}")
        }
        return rows.sortedBy { it.first }.map { it.second }
    }

    /** Riwayat aktivitas: today's actions, newest first. */
    private fun history(todays: List<Ops.Queue>): List<JSONObject> {
        val rows = mutableListOf<Pair<Instant, JSONObject>>()
        fun add(at: Instant?, q: Ops.Queue, activity: String, staff: String?, unit: String, result: String) {
            if (at != null) rows += at to json("time" to Ops.clock(at), "activity" to activity,
                "patient" to "${q.patient.substringBefore(' ')} · ${q.number}", "staff" to staff, "unit" to unit, "result" to result)
        }
        todays.forEach { q ->
            q.stages.filter { it.by != null }.forEach { s ->
                val done = s.status == Ops.StageStatus.DONE
                val unit = when (s.kind) {
                    Ops.StageKind.CHECK_IN -> "Kedatangan"
                    Ops.StageKind.CONSULTATION -> q.clinic.name
                    Ops.StageKind.LABORATORY -> "Laboratorium"
                    Ops.StageKind.PHARMACY -> "Farmasi"
                }
                val (activity, result) = if (s.status == Ops.StageStatus.SKIPPED) "${s.kind.label} dilewati" to "Dilewati" else when (s.kind) {
                    Ops.StageKind.CHECK_IN -> "Check-in dikonfirmasi" to "Berhasil"
                    Ops.StageKind.CONSULTATION -> if (done) "Pemeriksaan dokter selesai" to "Selesai" else "Pemeriksaan dimulai" to "Dilayani"
                    Ops.StageKind.LABORATORY -> if (done) "Laboratorium selesai" to "Selesai" else "Laboratorium dimulai" to "Diperiksa"
                    Ops.StageKind.PHARMACY -> if (done) "Obat diserahkan" to "Diserahkan" else "Resep diterima farmasi" to "Belum diproses"
                }
                add(s.completed ?: s.started, q, activity, s.by, unit, result)
            }
            q.order?.let { order ->
                val handling = data.handling(order)
                when (rx(order)) {
                    "ready" -> add(handling.calledAt ?: order.ready, q, if (handling.calledAt != null) "Pasien dipanggil ke loket" else "Obat siap diambil", order.by, "Farmasi", "Siap diambil")
                    "returned" -> add(handling.returnedAt, q, "Resep dikembalikan ke dokter", order.by, "Farmasi", "Dikembalikan")
                    "preparing" -> add(handling.verifiedAt, q, "Resep diverifikasi, obat diproses", order.by, "Farmasi", "Diproses")
                    else -> Unit
                }
            }
            add(data.exams[q]?.confirmedAt.takeIf { q.doctor.id == RINA }, q, "Pemeriksaan dikonfirmasi dokter", q.doctor.name, q.clinic.name, "Sudah diperiksa")
            data.supports.filter { it.queue === q }.forEach {
                add(it.requestedAt, q, "Dirujuk ke ${it.unitLabel}", q.doctor.name, it.unitLabel, "Dirujuk")
                add(it.completedAt, q, "Hasil penunjang dikirim ke dokter", it.by, it.unitLabel, "Selesai")
            }
            add(q.noShowAt.takeIf { q.noShowBy != null }, q, "Ditandai tidak hadir", q.noShowBy, q.clinic.name, "Tidak hadir")
        }
        return rows.sortedByDescending { it.first }.map { it.second }
    }

    // Service Insights (ManagementController, ServiceInsights) ------------------------------------

    private fun average(values: List<Double>) = if (values.isEmpty()) null else Math.round(values.average()).toInt()
    private fun percent(part: Int, whole: Int) = if (whole == 0) null else Math.round(part * 100.0 / whole).toInt()
    private fun doctorWait(q: Ops.Queue) = minutes(q.checkIn, q.stage(Ops.StageKind.CONSULTATION).started)

    private fun management(query: Map<String, String>): JSONObject {
        val today = Ops.today()
        val date = query["tanggal"]?.let(LocalDate::parse) ?: today
        val clinic = query["poli"]?.toIntOrNull()
        val dates = listOf(today) + Ops.queues.map { it.date }.filter { it < today }.distinct().sortedDescending().take(6)
        return json(
            "dates" to dates.map { json("value" to it.toString(), "label" to Ops.shortDate(it)) },
            "clinics" to clinics().map { json("id" to it.id, "name" to it.name) },
            "date" to date.toString(), "clinicId" to clinic, "report" to report(date, clinic),
        )
    }

    /** Aggregates only, never patient identities. */
    private fun report(date: LocalDate, clinic: Int?): JSONObject {
        val queues = Ops.queues.filter { it.date == date && it.status != Ops.Status.CANCELLED && (clinic == null || it.clinic.id == clinic) }
        val waits = queues.mapNotNull(::doctorWait)
        val arrived = queues.filter { it.checkIn != null }
        fun count(vararg statuses: Ops.Status) = queues.count { it.status in statuses }
        val spans: List<Pair<String, (Ops.Queue) -> Pair<Instant?, Instant?>>> = listOf(
            "Check-in → dokter" to { q -> q.checkIn to q.stage(Ops.StageKind.CONSULTATION).started },
            "Dokter → laboratorium" to { q -> q.stage(Ops.StageKind.LABORATORY).let { it.started to it.completed } },
            "Resep → obat siap" to { q -> q.order?.received to q.order?.ready },
        )
        val stages = spans.map { (label, span) ->
            val measured = queues.map(span).mapNotNull { (from, to) -> minutes(from, to)?.let { from!! to it } }
            val hourly = measured.groupBy { "%02d.00".format(Locale.ROOT, it.first.atZone(zone).hour) }.toSortedMap()
                .map { (hour, values) -> json("hour" to hour, "wait" to average(values.map { it.second })) }
            json("label" to label, "minutes" to average(measured.map { it.second }), "hourly" to hourly)
        }
        val latest = queues.flatMap { q ->
            listOf(q.checkIn, q.order?.received, q.order?.completed) + q.stages.flatMap { listOf(it.started, it.completed) }
        }.filterNotNull().maxOrNull()
        val perClinic = queues.groupBy { it.clinic }.map { (item, list) ->
            Triple(item.name, list.count { it.status == Ops.Status.COMPLETED }, average(list.mapNotNull(::doctorWait)))
        }.sortedByDescending { it.third ?: -1 }
        return json(
            "registered" to queues.size, "completed" to count(Ops.Status.COMPLETED),
            "active" to count(Ops.Status.WAITING, Ops.Status.SERVING, Ops.Status.LABORATORY, Ops.Status.PHARMACY),
            "notArrived" to count(Ops.Status.BOOKED), "averageWait" to average(waits),
            "withinTarget" to percent(waits.count { it <= TARGET }, waits.size),
            "onTime" to percent(arrived.count { it.checkIn!! <= it.deadline }, arrived.size),
            "target" to TARGET, "updatedAt" to Ops.clock(latest),
            "clinics" to perClinic.map { json("clinic" to it.first, "complete" to it.second, "wait" to it.third) },
            "stages" to stages,
        )
    }

    private fun csv(date: LocalDate, clinic: Int?): String {
        val lines = mutableListOf("Laporan layanan HyTrack $date - data contoh prototype tanpa identitas pasien",
            "Poliklinik,Selesai,Rata-rata tunggu (menit),Target $TARGET menit")
        report(date, clinic).list("clinics").forEach { row ->
            val wait = row.int("wait")
            lines += listOf("\"${row.optString("clinic").replace("\"", "\"\"")}\"", row.optInt("complete"), wait ?: "",
                if (wait == null) "" else if (wait <= TARGET) "Sesuai" else "Di atas").joinToString(",")
        }
        return "\uFEFF" + lines.joinToString("\r\n") + "\r\n"
    }

    /** A JSON object; a null value leaves its key out, which reads the same as Laravel's null. */
    private fun json(vararg pairs: Pair<String, Any?>) = JSONObject().apply {
        pairs.forEach { (key, value) -> put(key, if (value is List<*>) JSONArray(value) else value) }
    }
}
