package com.constanvel.hytracl

import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

/**
 * The app's own data, in place of the Laravel API: accounts, data pasien, riwayat and Family
 * Companion invitations, with the web's rules and messages (PatientRecords, SignupVerification,
 * CompanionInvitations, VisitView). Seeded like DatabaseSeeder; it lives for the app process,
 * like [Demo]'s visit state. Several accounts can sign in on one device, so a companion account
 * follows the visit of the patient who invited it.
 */
object Local {
    /** A refused action: Laravel's message, and per-field messages for a form. */
    class Refused(message: String, val fields: Map<String, String> = emptyMap()) : Exception(message)

    class Record(val id: Int, var name: String, var phone: String?, val medicalRecord: String? = null) {
        var birthDate: String? = null // yyyy-MM-dd
        var gender: String? = null
        var address: String? = null
        var city: String? = null
        var postalCode: String? = null
    }

    class Account(val phone: String, val password: String, val self: Record) {
        val family = mutableListOf<Record>()
        var visit: Demo.Visit? = null
        val visits = mutableListOf<Demo.Visit>() // riwayat, newest first
        val notifications = mutableListOf<Demo.Notification>()
    }

    class Share(val id: Int, val token: String, val owner: Account, val visit: Demo.Visit, val sentAt: String) {
        var status = "pending"
        var companion: Account? = null
        var acceptedAt: String? = null

        /** Stored status, or expired once the visit is over (CompanionShare::currentStatus). */
        val current get() = if (status in LIVE && !visit.active) "expired" else status
    }

    private val LIVE = setOf("pending", "accepted")
    private val LABELS = mapOf("pending" to "Menunggu diterima", "accepted" to "Aktif", "revoked" to "Dicabut", "expired" to "Berakhir")
    private val PHONE = Regex("^628\\d{7,11}$")
    private var ids = 0
    private val accounts = mutableListOf<Account>()
    private val shares = mutableListOf<Share>()

    var current: Account? = null
        private set

    /** Staff and managers sign in through the Laravel API (InternalLoginActivity); they have no patient account. */
    enum class Role { STAFF, MANAGER }
    val role: Role?
        get() = when (Api.account?.optString("role")) {
            "staff" -> Role.STAFF
            "manager" -> Role.MANAGER
            else -> null
        }

    init {
        seedAccounts()
    }

    private fun seedAccounts() {
        seed("Siti Aminah", "6281255500842", "00-08-42", "1988-05-12", "female")
        // A second patient account to accept Siti's companion invitation in the demo.
        seed("Ahmad Fauzi", "6281255500843", null, "1985-02-20", "male")
    }

    /** Mode prototype: sets the accounts aside and seeds fresh ones; the result puts them back. */
    fun isolate(): () -> Unit {
        val keptAccounts = accounts.toList()
        val keptShares = shares.toList()
        val keptIds = ids
        val signedIn = current
        accounts.clear()
        shares.clear()
        ids = 0
        current = null
        seedAccounts()
        return {
            accounts.clear()
            accounts += keptAccounts
            shares.clear()
            shares += keptShares
            ids = keptIds
            current = signedIn
        }
    }

    /** "•••• 0842": the end of the medical record number, for a patient with an account here. */
    fun recordNumber(phone: String?) = accounts.firstOrNull { it.phone == phone }?.self?.medicalRecord
        ?.filter(Char::isDigit)?.takeLast(4)?.let { "•••• $it" }

    private fun seed(name: String, phone: String, rm: String?, birth: String, gender: String) {
        val record = Record(++ids, name, phone, rm).apply {
            birthDate = birth
            this.gender = gender
            address = "Jl. Melati No. 12, RT 02/RW 03"
            city = "Bandung"
            postalCode = "40132"
        }
        accounts += Account(phone, Demo.DEMO_PASSWORD, record)
    }

    /** "Siti Aminah" -> "Siti A." (Patient::shortName: what a companion may see). */
    fun shortName(name: String) = name.trim().split(Regex("\\s+")).let { p ->
        if (p.size > 1) "${p.first()} ${p.last().first()}." else p.first()
    }

    /** "6281255500842" -> "0812 5550 0842" (Patient::localPhone). */
    private fun localPhone(phone: String) = ("0" + phone.drop(2)).let { "${it.take(4)} ${it.drop(4).take(4)} ${it.drop(8)}".trim() }

    // Accounts ----------------------------------------------------------------------

    fun login(phone: String, password: String): Boolean {
        current = accounts.firstOrNull { it.phone == Demo.normalizePhone(phone) && it.password == password }
        return current != null
    }

    /** A registered patient found by phone at the booking desk (StaffController::findPatient). */
    class Patient(val name: String, val phone: String, val active: String?) {
        val masked get() = "•••• " + phone.takeLast(4)
    }

    fun patientByPhone(input: String): Patient? {
        val digits = Demo.normalizePhone(input)
        if (digits.length < 9) return null
        val a = accounts.firstOrNull { it.phone == digits } ?: return null
        val v = a.visit?.takeIf { it.active }
        return Patient(a.self.name, a.phone, v?.let { "${it.ticket} · ${it.clinic.name} · ${Ops.dateLabel(Ops.today().plusDays(it.day.offset.toLong()))}" })
    }

    /** A booking in [Ops], from the app or the desk, becomes that patient's visit (Patient::queues). */
    fun attach(q: Ops.Queue) {
        val a = accounts.firstOrNull { it.phone == q.phone } ?: return
        val v = Demo.Visit(q)
        a.visit = v
        a.visits.add(0, v)
    }

    /** QueueService::notify, for a patient who has an account on this device. */
    fun notify(phone: String?, message: String, action: String?) {
        accounts.firstOrNull { it.phone == phone }?.notifications?.add(0, Demo.Notification(message, action, Demo.stamp()))
    }

    fun phoneTaken(normalized: String) = accounts.any { it.phone == normalized || it.self.phone == normalized }

    /** Sign-up after the right code: the account and its own record (name and phone only). */
    fun register(name: String, phone: String, password: String) {
        current = Account(phone, password, Record(++ids, name.trim(), phone)).also { accounts += it }
    }

    fun signOut() {
        current = null
        Api.logout()
    }

    // Data pasien (PatientRecords) ------------------------------------------------------

    private fun account() = current ?: throw Refused("Masuk terlebih dahulu.")

    /** The owner first, then the family by id (Patient::record). */
    fun records(): List<JSONObject> = account().let { a -> listOf(a.self) + a.family }.map { record(it) }

    private fun record(r: Record) = JSONObject()
        .put("id", r.id)
        .put("self", r === current?.self)
        .put("name", r.name)
        .put("recordNumber", r.medicalRecord?.filter(Char::isDigit)?.takeLast(4)?.let { "•••• $it" } ?: JSONObject.NULL)
        .put("birthDate", r.birthDate ?: JSONObject.NULL)
        .put("birthLabel", r.birthDate?.let(::dateLabel) ?: JSONObject.NULL)
        .put("age", r.birthDate?.let(::age) ?: JSONObject.NULL)
        .put("gender", r.gender ?: JSONObject.NULL)
        .put("phone", r.phone?.let(::localPhone) ?: JSONObject.NULL)
        .put("address", r.address ?: JSONObject.NULL)
        .put("city", r.city ?: JSONObject.NULL)
        .put("postalCode", r.postalCode ?: JSONObject.NULL)

    private fun parts(date: String) = date.split("-").map { it.toInt() }

    private fun dateLabel(date: String) = parts(date).let { (y, m, d) -> "$d ${Demo.MONTHS[m - 1]} $y" }

    private fun age(date: String): Int {
        val (y, m, d) = parts(date)
        val now = Calendar.getInstance()
        val month = now.get(Calendar.MONTH) + 1
        return now.get(Calendar.YEAR) - y - if (month < m || (month == m && now.get(Calendar.DAY_OF_MONTH) < d)) 1 else 0
    }

    private fun today() = Calendar.getInstance().let {
        String.format(Locale.ROOT, "%04d-%02d-%02d", it.get(Calendar.YEAR), it.get(Calendar.MONTH) + 1, it.get(Calendar.DAY_OF_MONTH))
    }

    /** A real yyyy-MM-dd date (no 31 February). */
    private fun validDate(date: String) = Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(date) && Calendar.getInstance().run {
        val (y, m, d) = parts(date)
        isLenient = false
        set(y, m - 1, d)
        runCatching { timeInMillis }.isSuccess
    }

    /** Adds ([id] null) or updates a record; throws [Refused] with Laravel's field messages. */
    fun saveRecord(id: Int?, input: Map<String, String>): JSONObject {
        val a = account()
        val record = id?.let { key -> (listOf(a.self) + a.family).firstOrNull { it.id == key } ?: throw Refused("Data tidak ditemukan.") }
        val v = input.mapValues { it.value.trim() }
        val name = v["name"].orEmpty()
        val birth = v["birth_date"].orEmpty()
        val phone = Demo.normalizePhone(v["phone"].orEmpty())
        val errors = linkedMapOf<String, String>()
        when {
            name.isEmpty() -> errors["name"] = "Nama lengkap wajib diisi."
            name.length > 100 -> errors["name"] = "Nama lengkap maksimal 100 karakter."
        }
        when {
            birth.isEmpty() -> errors["birth_date"] = "Tanggal lahir wajib diisi."
            !validDate(birth) || birth < "1900-01-01" -> errors["birth_date"] = "Masukkan tanggal lahir yang valid."
            birth > today() -> errors["birth_date"] = "Tanggal lahir tidak boleh di masa depan."
        }
        if (v["gender"] !in setOf("female", "male")) errors["gender"] = "Pilih jenis kelamin."
        when {
            v["phone"].isNullOrEmpty() -> errors["phone"] = "Nomor ponsel wajib diisi."
            !PHONE.matches(phone) -> errors["phone"] = "Gunakan nomor aktif dengan awalan 08 atau +62."
            record === a.self && accounts.any { it !== a && it.phone == phone } -> errors["phone"] = "Nomor ponsel sudah dipakai akun lain."
        }
        when {
            v["address"].isNullOrEmpty() -> errors["address"] = "Alamat lengkap wajib diisi."
            v.getValue("address").length > 255 -> errors["address"] = "Alamat maksimal 255 karakter."
        }
        when {
            v["city"].isNullOrEmpty() -> errors["city"] = "Kota / kabupaten wajib diisi."
            v.getValue("city").length > 100 -> errors["city"] = "Kota / kabupaten maksimal 100 karakter."
        }
        if (!v["postal_code"].isNullOrEmpty() && !Regex("^\\d{5}$").matches(v.getValue("postal_code"))) {
            errors["postal_code"] = "Kode pos terdiri dari 5 angka."
        }
        val duplicate = (listOf(a.self) + a.family).any { it !== record && it.name.equals(name, ignoreCase = true) && it.birthDate == birth }
        if (errors.isEmpty() && duplicate) errors["name"] = "Data pasien ini sudah tersimpan. Ubah data yang ada bila perlu."
        if (errors.isNotEmpty()) throw Refused(errors.values.first(), errors)

        val saved = record ?: Record(++ids, name, phone).also { a.family += it }
        saved.name = name
        saved.phone = phone
        saved.birthDate = birth
        saved.gender = v["gender"]
        saved.address = v["address"]
        saved.city = v["city"]
        saved.postalCode = v["postal_code"]?.takeIf { it.isNotEmpty() }
        return record(saved)
    }

    fun deleteRecord(id: Int) {
        val a = account()
        if (a.self.id == id) throw Refused("Data pasien pemilik akun tidak dapat dihapus.")
        a.family.removeAll { it.id == id }
    }

    // Riwayat (VisitView::history) ------------------------------------------------------

    fun history(): List<JSONObject> = account().visits.map { v ->
        JSONObject()
            .put("id", v.id)
            .put("number", v.ticket)
            .put("clinic", v.clinic.name)
            .put("doctor", v.doctor.name)
            .put("date", v.day.long)
            .put("time", v.time)
            .put("day", v.day.full)
            .put("clock", v.checkedInAt ?: Demo.clock(v.doctor.start))
            .put("status", v.status)
            .put("statusLabel", v.statusLabel)
            .put("checkInAt", v.checkedInAt?.let { "${v.day.short} · $it" } ?: JSONObject.NULL)
            .put("cancelReason", v.cancelReason ?: JSONObject.NULL)
    }

    // Family Companion (CompanionInvitations) ----------------------------------------------

    private fun clockNow() = Demo.now()

    private fun liveVisit() = account().visit?.takeIf { it.active } ?: throw Refused("Tidak ada kunjungan aktif untuk dibagikan.")

    /** The patient's invitations for the active visit, newest first, every status. */
    fun invitations(): List<JSONObject> {
        val visit = current?.visit?.takeIf { it.active } ?: return emptyList()
        return shares.filter { it.visit === visit }.reversed().map { s ->
            JSONObject()
                .put("id", s.id)
                .put("status", s.current)
                .put("label", LABELS.getValue(s.current))
                .put("companion", s.companion?.let { shortName(it.self.name) } ?: JSONObject.NULL)
                .put("sentAt", s.sentAt)
                .put("acceptedAt", s.acceptedAt ?: JSONObject.NULL)
        }
    }

    /** A new single-use link; several may be live for one visit. */
    fun invite(): String {
        val token = (1..48).map { "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".random() }.joinToString("")
        shares += Share(++ids, token, account(), liveVisit(), clockNow())
        return "https://$INVITE_HOST/pantauan/$token"
    }

    fun revoke(id: Int) {
        val visit = liveVisit()
        shares.firstOrNull { it.id == id && it.visit === visit && it.current in LIVE }?.status = "revoked"
    }

    private fun pending(token: String) = shares.firstOrNull { it.token == token && it.current == "pending" }

    /** What the invitation shows before it is accepted; null once used, revoked or over. */
    fun preview(token: String): JSONObject? = pending(token)?.let { s ->
        JSONObject()
            .put("patientName", shortName(s.owner.self.name))
            .put("clinic", s.visit.clinic.name)
            .put("date", s.visit.day.long)
    }

    fun canAccept(token: String) = pending(token)?.owner.let { it != null && it !== current }

    /** Accepts with the signed-in account; returns the share id the monitor follows. */
    fun accept(token: String): Int {
        val share = pending(token) ?: throw Refused("Undangan ini sudah tidak berlaku.")
        if (share.owner === account()) throw Refused("Undangan ini untuk pendamping, bukan untuk pasien sendiri.")
        share.status = "accepted"
        share.companion = current
        share.acceptedAt = clockNow()
        return share.id
    }

    private fun followingShares() = shares.filter { it.companion === current && it.current == "accepted" }.reversed()

    /** Visits this account accompanies right now: "A-027 · Poli Penyakit Dalam". */
    fun following(): List<JSONObject> = followingShares().map { s ->
        JSONObject()
            .put("id", s.id)
            .put("name", shortName(s.owner.self.name))
            .put("detail", "${s.visit.ticket} · Poli ${s.visit.clinic.name}")
    }

    /** One followed visit (VisitView::forCompanion); progress is null once access has ended. */
    fun followed(id: Int): JSONObject {
        val s = shares.firstOrNull { it.id == id && it.companion === account() } ?: throw Refused("Data tidak ditemukan.")
        val v = s.visit
        val status = s.current
        val json = JSONObject().put("id", s.id).put("status", status)
            .put("ended", if (status == "accepted") JSONObject.NULL else LABELS.getValue(status))
        if (status != "accepted") return json.put("progress", JSONObject.NULL)
        val inLine = v.stage == Demo.Stage.BOOKED || v.stage == Demo.Stage.WAITING
        val (place, room) = when (v.stage) {
            Demo.Stage.BOOKED -> "Belum check-in" to null
            Demo.Stage.LABORATORY -> "Laboratorium" to Demo.LABORATORY
            Demo.Stage.PHARMACY -> "Farmasi" to Demo.PHARMACY
            else -> "Poli ${v.clinic.name}" to v.clinic.room
        }
        return json.put("progress", JSONObject()
            .put("patientName", shortName(s.owner.self.name))
            .put("stage", v.stage.name.lowercase())
            .put("ticket", v.ticket)
            .put("clinic", v.clinic.name)
            .put("serving", (if (inLine) v.serving else null) ?: JSONObject.NULL)
            .put("ahead", if (inLine) v.ahead else 0)
            .put("waitRange", v.waitRange ?: JSONObject.NULL)
            .put("day", if (v.isToday) "Hari ini" else v.day.long)
            .put("schedule", v.time)
            .put("place", place)
            .put("placeDetail", room ?: JSONObject.NULL))
    }

    /** The companion gives up their own access. */
    fun stop(id: Int) {
        shares.firstOrNull { it.id == id && it.companion === current && it.status == "accepted" }?.status = "revoked"
    }

    /** Invitation links open the app (AndroidManifest intent filter); nothing is served there. */
    const val INVITE_HOST = "hytrack.demo"
}

/** A JSON string field, or null when it is null or empty. */
fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
