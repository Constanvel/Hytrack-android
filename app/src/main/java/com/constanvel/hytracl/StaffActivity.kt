package com.constanvel.hytracl

import android.Manifest
import android.app.DatePickerDialog
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.journeyapps.barcodescanner.camera.CameraSettings
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * Staff area (web features/operations/staff.tsx, clinical.tsx and staff-sections.tsx) on the
 * HyTrack Laravel API: GET /api/petugas is the board, every action posts to the same routes as
 * the web and then reloads the board, like an Inertia visit. The sections follow the account's
 * room (Figma 47:2): the desk, the doctor, support (laboratory/radiology) and pharmacy.
 */
class StaffActivity : DashboardActivity() {
    override val role = Local.Role.STAFF

    private var board: JSONObject? = null
    private var loadError: String? = null
    private var busy = false
    /** Board query: poli, dokter (Antrean poli), tanggal, bpoli, status, cari (Daftar booking). */
    private val params = linkedMapOf<String, Any?>()

    private val me get() = board?.optJSONObject("me")
    private val room get() = me?.str("room") ?: Api.account?.str("room") ?: "desk"

    override val sections: List<String>
        get() = if (room == "desk") listOf(SCAN, "Antrean poli", EXAM, SUPPORT, "Alur pelayanan", "Farmasi", "Riwayat aktivitas", BOOKINGS, CLINICS)
        else listOf("Antrean poli", EXAM, SUPPORT, "Alur pelayanan", "Farmasi", "Riwayat aktivitas")

    override fun home() = when (room) {
        "doctor" -> EXAM
        "support" -> SUPPORT
        "pharmacy" -> "Farmasi"
        else -> "Antrean poli"
    }

    override fun roomLabel() = when (room) {
        "doctor" -> "RUANG DOKTER"
        "support" -> "RUANG PENUNJANG"
        "pharmacy" -> "RUANG FARMASI"
        else -> "RUANG PETUGAS"
    }

    /** Breadcrumb under "Pelayanan" (Figma 43:2 frames 11, 18, 21–23; 47:2 frames 31–37). */
    override fun crumb(section: String) = "Pelayanan / " + when (section) {
        SCAN -> "Check-in kedatangan"
        "Antrean poli" -> "Rawat jalan"
        EXAM -> "Dokter"
        SUPPORT -> "Penunjang"
        else -> section
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isFinishing) load()
    }

    override fun content(into: LinearLayout, section: String) {
        if (board == null) return into.waiting()
        flash?.let { if (section != "Antrean poli" && section != "Alur pelayanan") into.flag("✓ $it") }
        when (section) {
            SCAN -> into.scanner()
            EXAM -> into.exam()
            SUPPORT -> into.support()
            "Alur pelayanan" -> into.flow()
            "Farmasi" -> orderPick?.let { id -> orders.firstOrNull { it.optInt("id") == id } }?.let { into.order(it) } ?: into.pharmacy()
            "Riwayat aktivitas" -> into.activity()
            BOOKINGS -> into.bookings()
            CLINICS -> into.clinicAdmin()
            else -> into.queue()
        }
    }

    /** Loading, or why the board could not be read. */
    private fun LinearLayout.waiting() {
        val c = card(10, 18)
        if (loadError == null) {
            c.label("Memuat data layanan…", 18f, Weight.BOLD)
            c.label("Mengambil antrean dan tugas ruangan Anda dari server HyTrack.", 13f, color = Hex.MUTED)
        } else {
            c.label("Data belum dapat dimuat", 18f, Weight.BOLD)
            c.label(loadError!!, 13f, color = Hex.MUTED)
            c.action("Coba lagi") { loadError = null; render(); load() }
        }
    }

    // Board and actions --------------------------------------------------------------------------

    private fun load() {
        request({ Api.call("GET", "petugas" + Api.query(*params.toList().toTypedArray())) }) { result ->
            result.onSuccess {
                board = it
                loadError = null
                if (section !in sections) section = home()
            }.onFailure { e ->
                if ((e as? Api.Failure)?.status == 401) return@request signedOut()
                loadError = e.message ?: Api.OFFLINE
            }
            render()
        }
    }

    /** A staff action: the same route as the web, then the board is read again. */
    private fun send(path: String, body: JSONObject = JSONObject(), upload: Api.Upload? = null, method: String = "POST", done: () -> Unit = {}) {
        if (busy) return
        busy = true
        request({ Api.call(method, "petugas/$path", body, upload) }) { result ->
            busy = false
            result.onSuccess {
                flash = it.str("message")
                error = null
                done()
                load()
            }.onFailure { e ->
                if ((e as? Api.Failure)?.status == 401) return@request signedOut()
                error = e.message ?: Api.OFFLINE
                render()
            }
        }
    }

    private fun arr(key: String) = board?.optJSONArray(key)?.objects().orEmpty()
    private val orders get() = arr("pharmacy")

    // Shared pieces of Figma 47:2 frames 31–37 ---------------------------------------------------

    /** Tahapan pelayanan: five steps, the current one in mint. */
    private fun LinearLayout.steps(current: Int) {
        val scroller = android.widget.HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false }
        addView(scroller, LinearLayout.LayoutParams(-1, -2))
        val r = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            dp(6).let { setPadding(it, it, it, it) }
            background = shape(Color.WHITE, 12)
        }
        scroller.addView(r)
        STEPS.forEachIndexed { i, step ->
            r.label(step, 13f, if (i == current) Weight.SEMIBOLD else Weight.MEDIUM, if (i == current) Hex.PRIMARY else Hex.MUTED).also {
                it.wrap()
                it.setPadding(dp(12), dp(10), dp(12), dp(10))
                if (i == current) {
                    it.background = it.shape(MINT, 8)
                    it.contentDescription = "$step, tahap saat ini"
                }
            }
        }
    }

    /** Patient card: number, name, detail and the warm or mint status. */
    private fun LinearLayout.patient(number: String, name: String, detail: String, status: String, mint: Boolean) {
        val c = card(6, 16, Color.WHITE, null, 16)
        val top = c.row(14)
        top.label(number, 26f, Weight.BOLD, Hex.PRIMARY).wrap()
        val who = top.stack(2).weight()
        who.label(name, 20f, Weight.SEMIBOLD)
        who.label(detail, 13f, color = Hex.MUTED)
        c.label(status, 14f, Weight.SEMIBOLD, if (mint) Hex.PRIMARY else WARM_TEXT).also {
            it.background = it.shape(if (mint) MINT else WARM_FILL, 10)
            it.setPadding(dp(14), dp(10), dp(14), dp(10))
        }
    }

    private fun LinearLayout.panel() = card(10, 18, Color.WHITE, null, 16)

    private fun LinearLayout.note(text: String, warm: Boolean = false) = label(text, 13f, Weight.SEMIBOLD, if (warm) WARM_TEXT else Hex.PRIMARY).also {
        it.background = it.shape(if (warm) WARM_FILL else MINT, 10)
        it.setPadding(dp(14), dp(11), dp(14), dp(11))
    }

    /** The two buttons of an action row: mint secondary, #086f65 primary. */
    private fun LinearLayout.buttons(footnote: String, secondary: Pair<String, Boolean>?, primary: Pair<String, Boolean>, onSecondary: () -> Unit = {}, onPrimary: () -> Unit) {
        label(footnote, 12f, color = Hex.MUTED)
        secondary?.let { (text, enabled) -> action(text, Btn.SECONDARY, enabled && !busy, onSecondary) }
        action(primary.first, Btn.PRIMARY, primary.second && !busy, onPrimary)
    }

    /** Medicine rows of a prescription (Nama obat & sediaan · Jumlah · Aturan pakai). */
    private fun LinearLayout.medicines(items: List<JSONObject>, onEdit: ((Int) -> Unit)? = null) {
        if (items.isEmpty()) {
            label("Belum ada obat. Tambahkan obat bila pasien perlu resep.", 13f, color = Hex.MUTED).also {
                it.background = it.shape(Hex.PAGE, 10)
                dp(14).let { p -> it.setPadding(p, p, p, p) }
            }
            return
        }
        items.forEachIndexed { i, item ->
            val c = card(4, 12, Hex.PAGE, null, 10)
            val top = c.row(8)
            top.label(item.str("name"), 15f, Weight.SEMIBOLD).weight()
            top.label(item.str("quantity"), 14f, Weight.MEDIUM).wrap()
            item.str("form")?.let { c.label(it, 12f, color = Hex.MUTED) }
            c.label(listOfNotNull(item.str("dosage"), item.str("timing")).joinToString(" · "), 13f)
            onEdit?.let { edit -> c.link("Ubah") { edit(i) }.wrap() }
        }
    }

    // Antrean poli (Figma 43:2 frame 11, revised by 47:2) ------------------------------------------

    private var query = ""
    private var page = 1
    private var queueRows: () -> Unit = {}

    private fun LinearLayout.queue() {
        val b = board!!
        heading("Antrean poli", "Kelola panggilan dan bantu pasien melanjutkan pelayanan.") { sample() }
        val clinics = b.list("clinics")
        val clinic = clinics.firstOrNull { it.optInt("id") == b.optInt("clinicId") } ?: clinics.firstOrNull()
        val doctors = clinic?.list("doctors").orEmpty()
        val doctor = b.optJSONObject("doctor")
        select("Poliklinik", clinics.map { it.optString("name") }, clinics.indexOf(clinic), room != "doctor") {
            params["poli"] = clinics[it].optInt("id")
            params.remove("dokter")
            load()
        }
        select("Dokter", doctors.map { it.optString("name") }, doctors.indexOfFirst { it.optInt("id") == doctor?.optInt("id") }, doctors.isNotEmpty() && room != "doctor") {
            params["dokter"] = doctors[it].optInt("id")
            load()
        }
        label(doctor?.let { "${it.str("room") ?: "Ruang belum diatur"} · ${it.str("time")}" } ?: "—", 14f, Weight.MEDIUM, Hex.MUTED)
        if (doctor == null) {
            card().apply {
                label("Belum ada dokter di poliklinik ini", 18f, Weight.BOLD)
                label("Pilih poliklinik lain untuk mengelola antrean.", 14f, color = Hex.MUTED)
            }
            return
        }
        val stats = b.optJSONObject("stats") ?: JSONObject()
        val waiting = b.list("queue")
        val current = b.optJSONObject("current")
        val desk = room == "desk"
        stat("Menunggu", "${stats.optInt("waiting")}", "Pasien sudah check-in", Hex.MINT)
        stat("Selesai", "${stats.optInt("examined")}", "Dari ${stats.optInt("registered")} pasien terdaftar")
        stat("Belum check-in", "${stats.optInt("notCheckedIn")}", "Periksa kedatangan pasien", Hex.SLATE)

        val table = card()
        table.label("Daftar antrean", 20f, Weight.BOLD)
        table.label("${waiting.size} menunggu", 13f, color = Hex.MUTED)
        table.field("Cari pasien", query) { query = it; page = 1; queueRows() }
        queueRows = table.live {
            val filtered = waiting.filter { "${it.optString("name")} ${it.optString("number")}".contains(query, ignoreCase = true) }
            val (rows, now, pages) = filtered.page(page, 6)
            val t = table()
            t.cells(listOf("Nomor", "Pasien", "Check-in", "Status", "Aksi"), W5, header = true)
            rows.forEach { q ->
                val next = q === waiting.first()
                val r = t.cells(listOf(q.optString("number"), q.optString("name"), q.str("checkIn") ?: "", if (next) "Berikutnya" else "Menunggu", ""), W5, highlight = next)
                r.removeViewAt(r.childCount - 1)
                if (desk) r.link(if (next) "Panggil" else "Panggil", enabled = current == null && !busy) {
                    if (next) send("dokter/${doctor.optInt("id")}/panggil") else send("antrean/${q.optInt("id")}/panggil")
                }.weight(W5.last())
                else r.label("—", 13f, color = Hex.MUTED).weight(W5.last())
            }
            if (filtered.isEmpty()) label(if (waiting.isNotEmpty()) "Tidak ada pasien yang cocok." else "Belum ada pasien menunggu.", 13f, color = Hex.MUTED)
            label(if (filtered.isNotEmpty()) "Menampilkan ${(now - 1) * 6 + 1}–${minOf(now * 6, filtered.size)} dari ${filtered.size} pasien" else "Menampilkan 0 pasien", 12f, color = Hex.MUTED)
            pager(now, pages) { page = it; queueRows() }
        }

        val hero = stack(6, 18).apply { background = gradient(Color.parseColor("#086B62"), Color.parseColor("#188777"), 22) }
        hero.eyebrow(current?.str("startedAt")?.let { "SEDANG DIPERIKSA · MULAI $it" } ?: "SEDANG DIPERIKSA", Color.WHITE)
        hero.label(current?.str("number") ?: "—", 48f, Weight.BOLD, Color.WHITE)
        hero.label(current?.str("name") ?: "Belum ada pasien", 19f, Weight.SEMIBOLD, Color.WHITE)
        hero.label(current?.let {
            "${it.int("minutes") ?: 0} mnt · " + if (it.optBoolean("confirmed")) "sudah dikonfirmasi dokter" else "menunggu konfirmasi dokter"
        } ?: "Panggil pasien berikutnya", 13f, color = Color.parseColor("#D9EAE2"))
        hero.action("Lihat pemeriksaan dokter", Btn.WHITE, current != null) { examPick = current?.optInt("id"); open(EXAM) }

        if (desk) {
            val next = card()
            next.label("Berikutnya ${waiting.firstOrNull()?.str("number") ?: "—"}", 19f, Weight.BOLD)
            next.label("Pastikan ruang siap sebelum memanggil.", 13f, color = Hex.MUTED)
            next.action("Panggil pasien berikutnya", enabled = current == null && waiting.isNotEmpty() && !busy) { send("dokter/${doctor.optInt("id")}/panggil") }
            val split = next.row(10)
            split.action("Panggil ulang", Btn.SECONDARY, current != null && !busy) { send("antrean/${current!!.optInt("id")}/panggil-ulang") }.weight()
            split.action("Tidak hadir", Btn.SECONDARY, current != null && current.optInt("calledCount") >= 2 && !busy) {
                split.confirm("Tandai ${current!!.optString("number")} tidak hadir setelah dipanggil?") { send("antrean/${current.optInt("id")}/tidak-hadir") }
            }.weight()
        }
        flag("Hanya dokter mengonfirmasi pemeriksaan & resep. Tidak hadir: tandai setelah panggilan ulang.", Tone.WARNING)
        val history = arr("history")
        footnote(flash?.let { "✓ $it" } ?: history.firstOrNull()?.let { "✓ ${it.optString("activity")} · ${it.optString("patient")}" } ?: "Belum ada aktivitas hari ini.")
        link("Lihat riwayat aktivitas →") { open("Riwayat aktivitas") }
    }

    // Scan QR kedatangan (Figma 27:360 / 43:2 frame 18) ----------------------------------------------

    private var typed = ""
    private var ticket: JSONObject? = null
    private var checkedIn: String? = null

    /** QrScanner's phases (scanner.tsx); the camera only reads the code, the server decides. */
    private enum class Phase { STARTING, SCANNING, VERIFYING, FOUND, DONE, INVALID, OFF, BLOCKED }
    private var phase = Phase.STARTING
    private var problem = ""
    private var scanned = "" // the QR text or typed number that identified [ticket]
    private var asked = false
    private var resumed = false
    private var barcode: BarcodeView? = null

    // The scanner library opens cameras by their Camera1 index, so they are counted and named the same way.
    @Suppress("DEPRECATION")
    private val cameras by lazy { runCatching { android.hardware.Camera.getNumberOfCameras() }.getOrDefault(0) }
    /** Starts on the back camera, as the web asks for facingMode "environment". */
    @Suppress("DEPRECATION")
    private var camera = runCatching {
        (0 until android.hardware.Camera.getNumberOfCameras()).first { i ->
            android.hardware.Camera.CameraInfo().also { android.hardware.Camera.getCameraInfo(i, it) }.facing == android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK
        }
    }.getOrDefault(0)

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) render() else block("Izin kamera ditolak. Izinkan kamera untuk HyTrack di pengaturan, atau ketik nomor check-in di bawah.")
    }

    private fun block(message: String) {
        problem = message
        phase = Phase.BLOCKED
        render()
    }

    /** Step 1: POST /api/petugas/scan identifies the ticket without changing anything. */
    private fun verify(code: String) {
        scanned = code
        checkedIn = null
        error = null
        phase = Phase.VERIFYING
        render()
        request({ Api.call("POST", "petugas/scan", JSONObject().put("code", code)) }) { result ->
            result.onSuccess {
                ticket = it.getJSONObject("ticket")
                phase = Phase.FOUND
            }.onFailure { e ->
                if ((e as? Api.Failure)?.status == 401) return@request signedOut()
                ticket = null
                problem = e.message.orEmpty()
                phase = Phase.INVALID
            }
            render()
        }
    }

    /** Step 2: POST /api/petugas/check-in records the arrival once. */
    private fun checkIn() {
        phase = Phase.VERIFYING
        render()
        request({ Api.call("POST", "petugas/check-in", JSONObject().put("code", scanned)) }) { result ->
            result.onSuccess {
                checkedIn = it.str("message")
                phase = Phase.DONE
                load()
            }.onFailure { e ->
                if ((e as? Api.Failure)?.status == 401) return@request signedOut()
                ticket = null
                problem = e.message.orEmpty()
                phase = Phase.INVALID
                render()
            }
        }
    }

    private fun rescan() {
        ticket = null
        checkedIn = null
        problem = ""
        phase = if (phase == Phase.OFF || phase == Phase.BLOCKED) Phase.STARTING else Phase.SCANNING
        render()
    }

    private fun preview() = barcode ?: BarcodeView(this).also { v ->
        // A TextureView survives being moved between renders and clips to the rounded preview.
        v.setUseTextureView(true)
        v.cameraSettings = CameraSettings().apply { requestedCameraId = camera }
        v.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
        v.addStateListener(object : CameraPreview.StateListener {
            override fun previewSized() = Unit
            override fun previewStarted() {
                if (phase == Phase.STARTING) { phase = Phase.SCANNING; render() }
            }
            override fun previewStopped() = Unit
            override fun cameraError(error: Exception) = block("Kamera tidak dapat dibuka. Coba lagi atau ketik nomor check-in di bawah.")
            override fun cameraClosed() = Unit
        })
        barcode = v
    }

    /** The camera runs only while the scan section is on screen with the session open. */
    override fun rendered() {
        val v = barcode ?: return
        if (resumed && section == SCAN && v.parent != null) {
            v.resume()
            if (phase == Phase.SCANNING) v.decodeSingle { verify(it.text) } else v.stopDecoding()
        } else v.pause()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        rendered()
    }

    override fun onPause() {
        resumed = false
        barcode?.pause()
        super.onPause()
    }

    private fun LinearLayout.scanner() {
        heading(SCAN, "Pindai tiket pasien, cocokkan data, lalu konfirmasi check-in.")
        if (phase == Phase.STARTING) when {
            !packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY) ->
                { problem = "Kamera tidak ditemukan. Hubungkan kamera, atau ketik nomor check-in di bawah."; phase = Phase.BLOCKED }
            ContextCompat.checkSelfPermission(this@StaffActivity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ->
                if (!asked) { asked = true; askCamera.launch(Manifest.permission.CAMERA) }
        }
        val cam = card(16, 18)
        val top = cam.row(12)
        top.label("Kamera meja check-in", 16f, Weight.SEMIBOLD).weight()
        top.link("Ganti kamera", enabled = cameras > 1 && phase != Phase.VERIFYING) {
            camera = (camera + 1) % cameras
            barcode?.pause()
            barcode?.cameraSettings = CameraSettings().apply { requestedCameraId = camera }
            ticket = null
            phase = Phase.STARTING
            render()
        }.wrap()
        val frame = android.widget.FrameLayout(this@StaffActivity).apply {
            background = shape(Hex.TEXT, 16)
            clipToOutline = true
        }
        cam.addView(frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(300)))
        val granted = ContextCompat.checkSelfPermission(this@StaffActivity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted && phase != Phase.OFF && phase != Phase.BLOCKED) {
            val v = preview()
            (v.parent as? android.view.ViewGroup)?.removeView(v)
            frame.addView(v, android.widget.FrameLayout.LayoutParams(-1, -1))
        }
        val live = phase !in setOf(Phase.STARTING, Phase.OFF, Phase.BLOCKED)
        if (live) frame.addView(Viewfinder(this@StaffActivity), android.widget.FrameLayout.LayoutParams(-1, -1))
        frame.addView(android.widget.FrameLayout(this@StaffActivity).apply {
            setPadding(dp(20), dp(18), dp(20), dp(16))
            label("PRATINJAU PEMINDAIAN", 10f, Weight.SEMIBOLD, Color.parseColor("#B8DED1"))
            label(when (phase) {
                Phase.STARTING -> "Membuka kamera…"
                Phase.SCANNING -> "Arahkan QR tiket pasien ke dalam bingkai"
                Phase.VERIFYING -> "Memeriksa tiket…"
                Phase.FOUND -> "QR terdeteksi · periksa data di bawah"
                Phase.OFF -> "Kamera ditutup"
                Phase.BLOCKED -> "Kamera tidak tersedia"
                else -> ""
            }, 12f, Weight.MEDIUM, Color.WHITE).apply {
                gravity = Gravity.CENTER
                layoutParams = android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM)
            }
        }, android.widget.FrameLayout.LayoutParams(-1, -1))
        val trouble = phase == Phase.INVALID || phase == Phase.BLOCKED
        val state = cam.card(4, 14, if (trouble) Hex.WARNING else Hex.PALE, null, 12)
        val (headline, body) = when (phase) {
            Phase.FOUND -> "✓ QR berhasil dibaca" to "Pemindaian dijeda hingga konfirmasi selesai."
            Phase.DONE -> "✓ $checkedIn" to "Pasien masuk antrean dokter. Pindai pasien berikutnya."
            Phase.INVALID -> "QR tidak dapat dipakai" to problem
            Phase.BLOCKED -> "Kamera tidak tersedia" to problem
            Phase.VERIFYING -> "Memeriksa tiket…" to "Data dicocokkan dengan antrean hari ini."
            Phase.OFF -> "Kamera ditutup" to "Buka kamera lagi dengan Pindai ulang, atau ketik nomor check-in."
            else -> "Siap memindai" to "Tiket dibaca otomatis saat QR terlihat jelas."
        }
        state.label(headline, 15f, Weight.SEMIBOLD, if (trouble) Hex.WARNING_TEXT else Hex.PRIMARY)
        state.label(body, 13f, color = if (trouble) Hex.WARNING_TEXT else Hex.MUTED)
        val controls = cam.row(12)
        controls.action(if (phase == Phase.OFF || phase == Phase.BLOCKED) "Buka kamera" else "Pindai ulang", Btn.SECONDARY, phase != Phase.VERIFYING) {
            if (phase == Phase.BLOCKED && !granted) asked = false
            rescan()
        }.weight()
        controls.action("Tutup kamera", Btn.SECONDARY, phase !in setOf(Phase.STARTING, Phase.OFF, Phase.BLOCKED)) {
            phase = Phase.OFF
            render()
        }.weight()

        val result = card(10, 18)
        result.eyebrow("HASIL PEMINDAIAN")
        val t = ticket
        if (t == null) {
            result.label("Data pasien tampil di sini setelah QR terbaca. Tidak ada yang dicatat sebelum Anda menekan Konfirmasi check-in.", 14f, color = Hex.MUTED)
        } else {
            result.label(t.optString("name"), 22f, Weight.BOLD)
            val chip = result.stack(2, 14).apply { background = shape(Hex.PALE, 14) }
            chip.eyebrow("NOMOR ANTREAN", Hex.PRIMARY)
            chip.label(t.optString("ticket"), 28f, Weight.BOLD, Hex.PRIMARY)
            chip.label(t.optString("code"), 12f, Weight.SEMIBOLD, Hex.MUTED)
            fun pair(k: String, v: String?) = result.stack(2).apply { label(k, 12f, color = Hex.MUTED); label(v ?: "—", 14f, Weight.SEMIBOLD) }
            pair("Poliklinik", t.str("clinic"))
            pair("Dokter dan jadwal", "${t.optString("doctor")}\n${t.optString("schedule")}")
            pair("Tanggal kunjungan", t.str("date"))
            val again = { typed = ""; error = null; rescan() }
            when {
                checkedIn != null -> {
                    result.flag("Sudah check-in · tercatat sekali")
                    result.action("Pindai pasien berikutnya") { again() }
                }
                t.optString("state") == "no_show" -> {
                    result.flag("Tidak hadir · tiket tidak dapat dipakai lagi", Tone.WARNING)
                    result.label("Arahkan pasien ke petugas pendaftaran.", 13f, color = Hex.MUTED)
                    result.action("Pindai pasien berikutnya") { again() }
                }
                t.optString("state") != "booked" -> {
                    result.flag("Sudah check-in pukul ${t.str("checkedInAt") ?: "—"}${t.str("checkedInBy")?.let { " · dikonfirmasi $it" } ?: ""}")
                    result.label("Kehadiran sudah tercatat. Tidak ada yang diubah.", 13f, color = Hex.MUTED)
                    result.action("Pindai pasien berikutnya") { again() }
                }
                else -> {
                    result.flag("Belum check-in · batas ${t.str("deadline") ?: "—"}", Tone.INFO)
                    result.label("Cocokkan nama dan tujuan poli dengan pasien sebelum konfirmasi.", 13f, color = Hex.MUTED)
                    result.action("Konfirmasi check-in", enabled = phase != Phase.VERIFYING) { checkIn() }
                    result.link("Batalkan pemindaian") { again() }.gravity = Gravity.CENTER
                }
            }
        }

        val manual = card(10, 18)
        manual.label("QR sulit terbaca?", 18f, Weight.BOLD)
        manual.label("Masukkan nomor check-in pada tiket pasien.", 13f, color = Hex.MUTED)
        lateinit var search: TextView
        manual.field("Contoh: HT-000027", typed, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) {
            typed = it
            search.isEnabled = it.isNotBlank()
            search.alpha = if (it.isNotBlank()) 1f else 0.55f
        }.contentDescription = "Nomor check-in"
        search = manual.action("Cari tiket", Btn.SECONDARY, typed.isNotBlank()) { verify(typed.trim()) }
        footnote("Check-in hanya dicatat setelah konfirmasi. Tiket yang sudah check-in tidak akan dicatat dua kali.")
    }

    // Pemeriksaan dokter (Figma 47:2 frames 31–32) -----------------------------------------------

    private var examPick: Int? = null
    private var formFor: Int? = null
    private var notes = ""
    private var allergy = ""
    private var pharmacyNote = ""
    private val items = mutableListOf<JSONObject>()
    private var examined = false
    private var toPharmacy = true

    private fun LinearLayout.exam() {
        val rows = arr("examinations")
        if (room != "doctor") return examOverview(rows)
        val row = rows.firstOrNull { it.optInt("id") == examPick } ?: rows.firstOrNull()
        if (row == null) {
            heading("Pemeriksaan & resep", "Catat hasil pemeriksaan, tentukan tindak lanjut, dan tulis resep pasien.") { sample() }
            val c = card(10, 18)
            c.label("Belum ada pasien di ruang dokter", 18f, Weight.BOLD)
            c.label("Panggil pasien berikutnya dari Antrean poli untuk memulai pemeriksaan.", 13f, color = Hex.MUTED)
            c.action("Buka antrean poli") { open("Antrean poli") }
            return
        }
        if (row.optString("state") == "confirmed") examConfirmed(row, rows) else examForm(row, rows)
    }

    /** The patient switcher (not in Figma; a doctor can have several patients today). */
    private fun LinearLayout.switcher(rows: List<JSONObject>, row: JSONObject) {
        if (rows.size < 2) return
        select("Pilih pasien", rows.map { "${it.optString("number")} · ${it.optString("name")} · ${EXAM_STATE[it.optString("state")]}" }, rows.indexOf(row)) {
            examPick = rows[it].optInt("id")
            render()
        }
    }

    private fun detailOf(row: JSONObject) = listOfNotNull(row.str("record")?.let { "No. RM $it" }, row.str("clinic"), row.str("room")).joinToString(" · ")

    /** Other rooms see who is with a doctor, never the notes or the prescription. */
    private fun LinearLayout.examOverview(rows: List<JSONObject>) {
        heading("Pemeriksaan dokter", "Pantau pasien yang sedang diperiksa dokter di setiap poliklinik.") { sample() }
        val c = card()
        c.label("Pasien di ruang dokter", 20f, Weight.BOLD)
        val t = c.table(420)
        t.cells(listOf("Nomor", "Pasien", "Poliklinik", "Status"), listOf(0.8f, 1.6f, 1.3f, 1.6f), header = true)
        rows.forEach { r -> t.cells(listOf(r.optString("number"), r.optString("name"), r.optString("clinic"), EXAM_STATE[r.optString("state")] ?: ""), listOf(0.8f, 1.6f, 1.3f, 1.6f)) }
        if (rows.isEmpty()) c.label("Belum ada pasien di ruang dokter.", 13f, color = Hex.MUTED)
        flag("Catatan pemeriksaan dan resep hanya dapat dibuka dokter pemeriksa.", Tone.INFO)
    }

    private fun examPayload(extra: JSONObject.() -> Unit = {}) = JSONObject().apply {
        put("notes", notes)
        put("allergy", allergy)
        put("pharmacy_note", pharmacyNote)
        put("items", JSONArray(if (toPharmacy) items.map { JSONObject(it.toString()) } else emptyList<JSONObject>()))
        extra()
    }

    private fun LinearLayout.examForm(row: JSONObject, rows: List<JSONObject>) {
        val id = row.optInt("id")
        if (formFor != id) {
            formFor = id
            notes = row.str("notes").orEmpty()
            allergy = row.str("allergy").orEmpty()
            pharmacyNote = row.str("pharmacyNote").orEmpty()
            items.clear()
            items += row.list("items")
            examined = false
            toPharmacy = true
        }
        val state = row.optString("state")
        val locked = !row.optBoolean("open")
        val support = row.optJSONObject("support")
        heading("Pemeriksaan & resep", "Catat hasil pemeriksaan, tentukan tindak lanjut, dan tulis resep pasien.") { sample() }
        switcher(rows, row)
        patient(row.optString("number"), row.optString("name"), detailOf(row), when (state) {
            "serving" -> "Sedang diperiksa${row.int("minutes")?.let { " · $it mnt" } ?: ""}"
            "support" -> "${support?.optString("unitLabel")} · ${support?.optString("statusLabel")?.lowercase()}"
            else -> EXAM_STATE[state].orEmpty()
        }, false)
        if (state == "review" && support != null) {
            note("Hasil ${support.optString("number")} · ${support.optString("test")} dari ${support.optString("unitLabel")} sudah dikirim ${support.optString("completedAt")}." +
                (support.str("note")?.let { " Catatan petugas: $it" } ?: ""))
            support.optJSONObject("file")?.let { file -> link("Unduh ${file.optString("name")}") { downloadResult(support.optInt("id"), file.optString("name")) } }
        }
        if (state == "returned") row.optJSONObject("order")?.let { note("Resep ${it.optString("pickup")} dikembalikan farmasi: ${it.str("returnReason") ?: "—"}. Perbaiki resep lalu konfirmasi ulang.", true) }
        if (state == "support" && support != null) note("Pasien sedang di ${support.optString("unitLabel")} (${support.optString("number")} · ${support.optString("test")}). Konfirmasi dapat dilakukan setelah hasil dikirim kembali.", true)

        val notesCard = card(8, 18, Color.WHITE, null, 16)
        notesCard.label("Catatan pemeriksaan", 20f, Weight.BOLD)
        notesCard.label("Keluhan dan hasil pemeriksaan · data contoh", 12f, Weight.MEDIUM, Hex.MUTED)
        notesCard.field("Tuliskan keluhan dan hasil pemeriksaan pasien.", notes, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES) { notes = it.take(2000) }
            .apply { minLines = 3; isEnabled = !locked; background = shape(Hex.PAGE, 10) }

        val recipe = card(10, 18, Color.WHITE, null, 16)
        val head = recipe.row(12)
        head.label("Resep obat", 20f, Weight.BOLD).weight()
        head.action("+ Tambah obat", Btn.SECONDARY, !locked && items.size < 20) { medicine(-1) }.wrap()
        val list = recipe.stack(10)
        list.medicines(items, if (locked) null else { i -> medicine(i) })
        recipe.label("Alergi: ${allergy.ifBlank { "tidak dilaporkan" }} · konfirmasi ulang sebelum resep dikirim.", 12f, color = Hex.MUTED)

        val panel = panel()
        panel.label("Konfirmasi dokter", 20f, Weight.BOLD)
        panel.label("Pemeriksaan pasien sudah selesai?", 14f, Weight.MEDIUM)
        listOf(true to "Sudah diperiksa", false to "Belum diperiksa").forEach { (value, text) ->
            val chosen = examined == value
            panel.label("${if (chosen) "●" else "○"}  $text", 14f, if (chosen) Weight.SEMIBOLD else Weight.MEDIUM, if (chosen) Hex.PRIMARY else Hex.MUTED).also {
                it.minHeight = dp(44)
                it.gravity = Gravity.CENTER_VERTICAL
                it.setPadding(dp(14), 0, dp(14), 0)
                it.background = it.shape(if (chosen) MINT else Hex.PAGE, 10)
                it.isEnabled = !locked
                it.contentDescription = "$text${if (chosen) ", dipilih" else ""}"
                it.setOnClickListener { examined = value; render() }
            }
        }
        panel.label("Tindak lanjut", 13f, Weight.SEMIBOLD, Hex.MUTED)
        val follow = listOf("Farmasi · kirim ${items.size} item obat", "Selesai · tanpa resep")
        val pharmacy = toPharmacy && items.isNotEmpty()
        panel.select("Tindak lanjut", if (items.isEmpty()) follow.drop(1) else follow, if (items.isEmpty()) 0 else if (pharmacy) 0 else 1, !locked) {
            toPharmacy = items.isNotEmpty() && it == 0
            render()
        }
        panel.label("Perlu pemeriksaan tambahan?", 14f, Weight.MEDIUM)
        panel.action("Buat permintaan penunjang", Btn.SECONDARY, (state == "serving" || state == "review") && !busy) { refer(row) }
        panel.label("Resep dikirim setelah dokter mengonfirmasi. Petugas poli tidak dapat mengesahkan resep.", 12f, color = Hex.MUTED)
        panel.label("Dokter: ${me?.str("name") ?: row.optString("doctor")}", 12f, Weight.MEDIUM, Hex.PRIMARY)

        buttons("*Penunjang bila dirujuk · resep contoh, bukan instruksi klinis.", "Simpan draf" to !locked,
            (if (pharmacy) "Konfirmasi & kirim resep" else "Konfirmasi pemeriksaan") to (!locked && examined && notes.isNotBlank() && (state == "serving" || state == "review" || state == "returned")),
            onSecondary = { examPick = id; send("pemeriksaan/$id", examPayload()) { formFor = null } },
        ) { confirmExam(row, pharmacy) }
        if (notes.isBlank() && !locked) footnote("Tuliskan catatan pemeriksaan terlebih dahulu untuk mengonfirmasi.")
    }

    /** Tambah / ubah obat: name, form, quantity, dosage and timing; three are required. */
    private fun medicine(index: Int) {
        val current = items.getOrNull(index)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val fields = listOf(
            Triple("name", "Nama obat", "Contoh: Paracetamol 500 mg"),
            Triple("form", "Sediaan", "Contoh: Tablet · oral"),
            Triple("quantity", "Jumlah", "Contoh: 10 tablet"),
            Triple("dosage", "Aturan pakai", "Contoh: 3 × 1 tablet"),
            Triple("timing", "Waktu minum", "Contoh: Sesudah makan"),
        ).associate { (key, label, hint) ->
            box.label(label, 13f, Weight.SEMIBOLD)
            key to box.field(hint, current?.str(key).orEmpty())
        }
        val problem = box.label("", 12f, Weight.SEMIBOLD, Hex.DANGER)
        val dialog = AlertDialog.Builder(this).setTitle(if (current == null) "Tambah obat" else "Ubah obat").setView(android.widget.ScrollView(this).apply { addView(box) })
            .setPositiveButton("Simpan obat", null)
            .setNegativeButton("Batal", null)
            .apply { if (current != null) setNeutralButton("Hapus") { _, _ -> items.removeAt(index); render() } }
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = fields.mapValues { it.value.text.toString().trim() }
            val missing = listOf("name" to "Nama obat", "quantity" to "Jumlah", "dosage" to "Aturan pakai").firstOrNull { value[it.first].isNullOrEmpty() }
            if (missing != null) {
                problem.text = "${missing.second} wajib diisi."
                return@setOnClickListener
            }
            val item = JSONObject()
            value.forEach { (k, v) -> item.put(k, v.ifEmpty { JSONObject.NULL }) }
            if (index < 0) items += item else items[index] = item
            dialog.dismiss()
            render()
        }
    }

    /** Buat permintaan penunjang: unit and test, sent to /pemeriksaan/{id}/penunjang. */
    private fun refer(row: JSONObject) {
        val units = board?.optJSONObject("supportUnits") ?: JSONObject()
        val keys = units.keys().asSequence().toList()
        var unit = 0
        var test = ""
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        box.label("${row.optString("number")} · ${row.optString("name")}", 13f, color = Hex.MUTED)
        box.label("Unit penunjang", 13f, Weight.SEMIBOLD)
        box.select("Unit penunjang", keys.map { units.optString(it) }, 0) { unit = it; (box.getChildAt(2) as LinearLayout).let { s -> (s.getChildAt(0) as TextView).text = units.optString(keys[it]) } }
        box.label("Jenis pemeriksaan", 13f, Weight.SEMIBOLD)
        box.field("Contoh: Darah lengkap") { test = it }
        AlertDialog.Builder(this).setTitle("Buat permintaan penunjang").setView(box)
            .setPositiveButton("Kirim permintaan") { _, _ ->
                examPick = row.optInt("id")
                send("pemeriksaan/${row.optInt("id")}/penunjang", JSONObject().put("unit", keys.getOrNull(unit)).put("test", test.trim()))
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    /** Konfirmasi pemeriksaan: allergy and a note for pharmacy, then the doctor's confirmation. */
    private fun confirmExam(row: JSONObject, pharmacy: Boolean) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        box.label(if (pharmacy) "${items.size} item obat dikirim ke farmasi setelah konfirmasi." else "Pasien selesai tanpa resep setelah konfirmasi.", 14f, color = Hex.MUTED)
        box.label("Alergi obat", 13f, Weight.SEMIBOLD)
        box.field("Kosongkan bila tidak dilaporkan", allergy) { allergy = it.take(120) }
        if (pharmacy) {
            box.label("Catatan untuk farmasi", 13f, Weight.SEMIBOLD)
            box.field("Contoh: berikan edukasi penggunaan obat sesuai resep.", pharmacyNote) { pharmacyNote = it.take(255) }
        }
        AlertDialog.Builder(this).setTitle("Konfirmasi pemeriksaan").setView(box)
            .setPositiveButton(if (pharmacy) "Konfirmasi & kirim resep" else "Konfirmasi pemeriksaan") { _, _ ->
                examPick = row.optInt("id")
                send("pemeriksaan/${row.optInt("id")}/konfirmasi", examPayload { put("examined", "1") }) { formFor = null }
            }
            .setNegativeButton("Periksa lagi", null)
            .show()
    }

    private fun LinearLayout.examConfirmed(row: JSONObject, rows: List<JSONObject>) {
        val order = row.optJSONObject("order")
        heading("Pemeriksaan selesai", "Konfirmasi dokter tercatat dan resep diteruskan ke petugas farmasi.") { sample() }
        switcher(rows, row)
        steps(1)
        patient(row.optString("number"), row.optString("name"), detailOf(row), "Sudah diperiksa · ${row.optString("confirmedAt")}", true)
        val done = card(8, 18, MINT, null, 16)
        done.label("Pemeriksaan berhasil dikonfirmasi", 22f, Weight.BOLD, Hex.PRIMARY)
        done.label("${row.optString("doctor")} · ${row.optString("confirmedStamp")}", 14f, Weight.MEDIUM)
        done.label("${row.optString("name")} " + if (order != null) "diarahkan ke farmasi." else "selesai tanpa resep." +
            if (row.optJSONObject("support") == null) " Tidak ada rujukan penunjang." else "", 14f, color = MINT_TEXT)
        if (order != null) {
            val sent = card(10, 18, Color.WHITE, null, 16)
            sent.label("Resep ${order.optString("pickup")} dikirim ke farmasi", 20f, Weight.BOLD)
            sent.label("${row.optInt("itemCount")} item obat · resep final dari ${row.optString("doctor")}", 13f, color = Hex.MUTED)
            row.list("items").forEach { item ->
                val c = sent.card(4, 12, Hex.PAGE, null, 10)
                c.label(item.optString("name"), 15f, Weight.SEMIBOLD)
                c.label(listOfNotNull(item.str("quantity"), item.str("dosage"), item.str("timing")?.lowercase()).joinToString(" · "), 13f, color = Hex.MUTED)
            }
            sent.note("Resep diterima · ${order.optString("statusLabel").lowercase()}")
        }
        val trail = panel()
        trail.label("Jejak pelayanan pasien", 20f, Weight.BOLD)
        row.list("trail").takeLast(4).forEachIndexed { i, t ->
            val r = trail.row(14, Gravity.TOP)
            r.label("%02d".format(i + 1), 14f, Weight.BOLD, Hex.PRIMARY).wrap()
            val s = r.stack(4).weight()
            s.label("${t.optString("time")} · ${t.optString("title")}", 14f, Weight.SEMIBOLD)
            s.label(t.optString("detail"), 13f, color = Hex.MUTED)
        }
        val next = trail.card(6, 14, MINT, null, 10)
        next.label(if (order != null) "Berikutnya: verifikasi resep" else "Kunjungan selesai", 14f, Weight.SEMIBOLD, Hex.PRIMARY)
        next.label(if (order != null) "Petugas farmasi memulai proses obat." else "Tidak ada resep yang perlu diproses.", 12f, color = MINT_TEXT)
        buttons("*Penunjang hanya jika dirujuk dokter · data contoh.", "Lihat antrean poli" to true,
            (order?.let { "Lihat resep ${it.optString("pickup")}" } ?: "Lihat riwayat aktivitas") to true,
            onSecondary = { open("Antrean poli") },
        ) {
            if (order != null) { orderPick = order.optInt("id"); open("Farmasi") } else open("Riwayat aktivitas")
        }
    }

    // Pelayanan penunjang (Figma 47:2 frame 33) ------------------------------------------------

    private var sUnit = ""
    private var sStatus = ""
    private var sQuery = ""
    private var supportPick: Int? = null
    private var panelFor: Int? = null
    private var sNewStatus = "waiting"
    private var sNote = ""
    private var sFile: Api.Upload? = null

    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(::attach) }

    /** Reads the picked file; the server checks type and size again. */
    private fun attach(uri: Uri) {
        val type = contentResolver.getType(uri) ?: "application/octet-stream"
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "hasil"
        if (type !in RESULT_TYPES) {
            error = "Hasil pemeriksaan harus berupa PDF, JPG, atau PNG."
            return render()
        }
        val bytes = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
        if (bytes == null || bytes.size > RESULT_MAX) {
            error = if (bytes == null) "File tidak dapat dibaca." else "Ukuran hasil pemeriksaan maksimal 5 MB."
            return render()
        }
        sFile = Api.Upload("result", name, type, bytes)
        error = null
        render()
    }

    private var pendingResult: File? = null
    private val saveResult = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val file = pendingResult ?: return@registerForActivityResult
        uri?.let { contentResolver.openOutputStream(it)?.use { out -> file.inputStream().use { input -> input.copyTo(out) } } }
        file.delete()
        pendingResult = null
    }

    /** GET /api/petugas/penunjang/{id}/hasil: only support staff and the visit's doctor may read it. */
    private fun downloadResult(id: Int, name: String) {
        val into = File(cacheDir, "hasil-$id")
        request({ Api.download("petugas/penunjang/$id/hasil", into) }) { result ->
            result.onSuccess { pendingResult = it; saveResult.launch(name) }
                .onFailure { e -> error = e.message; render() }
        }
    }

    private fun LinearLayout.support() {
        val rows = arr("support")
        val units = board?.optJSONObject("supportUnits") ?: JSONObject()
        val unitKeys = units.keys().asSequence().toList()
        val shown = rows.filter { r ->
            (sUnit.isEmpty() || r.optString("unit") == sUnit) && (sStatus.isEmpty() || r.optString("status") == sStatus) &&
                "${r.optString("ticket")} ${r.optString("name")} ${r.optString("number")}".contains(sQuery, ignoreCase = true)
        }
        val row = shown.firstOrNull { it.optInt("id") == supportPick } ?: shown.firstOrNull()
        heading("Pelayanan penunjang", "Kelola permintaan pemeriksaan dan kirim hasil kembali kepada dokter.") { sample() }
        steps(2)
        if (row != null) patient(row.optString("ticket"), row.optString("name"),
            "${row.optString("unitLabel")} · permintaan dari dokter · diterima ${row.optString("requestedAt")}",
            when (row.optString("status")) {
                "done" -> "Selesai · hasil terkirim ${row.optString("completedAt")}"
                "processing" -> "Diproses · menunggu hasil"
                else -> "Menunggu pemeriksaan"
            }, row.optString("status") == "done")

        val list = card(10, 18, Color.WHITE, null, 16)
        val head = list.row(8)
        head.label("Antrean penunjang", 20f, Weight.BOLD).weight()
        head.label("${rows.count { it.optString("status") != "done" }} pasien aktif", 13f, color = Hex.MUTED).wrap()
        list.select("Unit", listOf("Semua unit") + unitKeys.map { units.optString(it) }, unitKeys.indexOf(sUnit) + 1) { sUnit = if (it == 0) "" else unitKeys[it - 1]; render() }
        list.select("Status", listOf("Semua status") + SUPPORT_STATUS.map { it.second }, SUPPORT_STATUS.indexOfFirst { it.first == sStatus } + 1) {
            sStatus = if (it == 0) "" else SUPPORT_STATUS[it - 1].first
            render()
        }
        list.field("Cari pasien atau nomor", sQuery) { sQuery = it }.setOnEditorActionListener { _, _, _ -> render(); false }
        shown.forEach { r ->
            val picked = r === row
            val item = list.card(2, 10, if (picked) MINT else Hex.PAGE, null, 8)
            item.label("${r.optString("ticket")} · ${r.optString("name")}", 13f, if (picked) Weight.SEMIBOLD else Weight.REGULAR)
            item.label("${r.optString("unitLabel")} · ${r.optString("test")}", 12f, color = Hex.MUTED)
            item.label(r.optString("listLabel"), 12f, Weight.MEDIUM, Hex.PRIMARY)
            item.isSelected = picked
            item.setOnClickListener { supportPick = r.optInt("id"); render() }
        }
        if (shown.isEmpty()) list.label(if (rows.isEmpty()) "Belum ada permintaan penunjang hari ini." else "Tidak ada permintaan yang cocok.", 13f, color = Hex.MUTED)

        if (row == null) {
            val p = panel()
            p.label("Konfirmasi petugas", 20f, Weight.BOLD)
            p.label("Pilih permintaan untuk memperbarui statusnya.", 13f, color = Hex.MUTED)
            return
        }
        val request = card(8, 18, Color.WHITE, null, 16)
        request.label("Permintaan pemeriksaan", 20f, Weight.BOLD)
        request.label("${row.optString("test")} · ${row.optString("unitLabel")}", 16f, Weight.SEMIBOLD)
        request.label("Nomor permintaan ${row.optString("number")} · ${row.optString("name")} · ${row.optString("ticket")} · ${row.optString("doctor")}", 13f, color = Hex.MUTED)
        request.note("Penunjang selesai → dokter meninjau hasil → tindak lanjut pasien")
        supportPanel(row)
    }

    private fun LinearLayout.supportPanel(row: JSONObject) {
        val id = row.optInt("id")
        if (panelFor != id) {
            panelFor = id
            sNewStatus = row.optString("status")
            sNote = row.str("note").orEmpty()
            sFile = null
        }
        val locked = !row.optBoolean("canEdit")
        val p = panel()
        p.label("Konfirmasi petugas", 20f, Weight.BOLD)
        p.label("Status pemeriksaan penunjang", 13f, Weight.SEMIBOLD, Hex.MUTED)
        val segments = p.row(7)
        SUPPORT_STATUS.forEach { (value, text) ->
            val chosen = sNewStatus == value
            segments.label(text, 12f, Weight.SEMIBOLD, if (chosen) Color.WHITE else Hex.MUTED).also {
                it.weight()
                it.gravity = Gravity.CENTER
                it.minHeight = dp(44)
                it.background = it.shape(if (chosen) Hex.PRIMARY else Hex.PAGE, 9)
                it.isEnabled = !locked
                it.contentDescription = "$text${if (chosen) ", dipilih" else ""}"
                it.setOnClickListener { sNewStatus = value; render() }
            }
        }
        p.label("Hasil pemeriksaan", 13f, Weight.SEMIBOLD, Hex.MUTED)
        val file = row.optJSONObject("file")
        val box = p.card(6, 14, MINT, null, 10)
        box.label(sFile?.name ?: file?.optString("name") ?: "Lampirkan hasil pemeriksaan", 14f, Weight.SEMIBOLD, Hex.PRIMARY)
        box.label(when {
            sFile != null -> "Siap diunggah · simpan untuk melampirkan"
            file != null -> if (row.optString("status") == "done") "Terkirim ke dokter pengirim" else "Terlampir · menunggu konfirmasi petugas"
            else -> "PDF, JPG, atau PNG · maksimal 5 MB"
        }, 12f, color = MINT_TEXT)
        if (!locked) box.setOnClickListener { pickFile.launch("*/*") }
        box.contentDescription = "Pilih file hasil pemeriksaan"
        if (file != null && sFile == null) p.link("Unduh hasil terlampir") { downloadResult(id, file.optString("name")) }
        p.label("Catatan petugas", 13f, Weight.SEMIBOLD, Hex.MUTED)
        p.field("Contoh: sampel diterima, hasil siap ditinjau dokter.", sNote, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE) { sNote = it.take(1000) }
            .apply { minLines = 2; isEnabled = !locked; background = shape(Hex.PAGE, 10) }
        p.label("Tujuan berikutnya", 13f, Weight.SEMIBOLD, Hex.MUTED)
        p.label("Kembali ke dokter pengirim", 16f, Weight.SEMIBOLD, Hex.PRIMARY)
        p.label(listOfNotNull(row.str("by") ?: me?.str("name"), row.str("byUnit")?.let { "Petugas ${it.lowercase()}" }, row.str("updatedAt")?.let { "$it WIB" }).joinToString(" · "), 12f, Weight.MEDIUM, Hex.PRIMARY)
        val fields = { JSONObject().put("note", sNote) }
        val complete = { send("penunjang/$id/selesai", fields(), sFile) { panelFor = null } }
        buttons("*Penunjang bila dirujuk · hasil tidak langsung menjadi resep.", "Simpan status" to !locked, "Konfirmasi selesai & kirim hasil" to !locked,
            onSecondary = {
                supportPick = id
                if (sNewStatus == "done") complete() else send("penunjang/$id", fields().put("status", sNewStatus), sFile) { panelFor = null }
            },
        ) { supportPick = id; complete() }
        if (locked && room != "support") footnote("Hanya petugas laboratorium atau radiologi yang dapat mengubah status.")
    }

    // Alur pelayanan (Figma 47:2 frame 21, 47:484) -----------------------------------------------

    private var flowClinic = 0

    private fun LinearLayout.flow() {
        val clinics = board!!.list("clinics")
        heading("Alur pelayanan", "Pantau antrean, konfirmasi dokter, penunjang, dan obat hingga diserahkan.") { sample() }
        select("Filter poliklinik", listOf("Semua poliklinik") + clinics.map { it.optString("name") }, clinics.indexOfFirst { it.optInt("id") == flowClinic } + 1) {
            flowClinic = if (it == 0) 0 else clinics[it - 1].optInt("id")
            render()
        }
        box("Hari ini · ${Ops.shortDate(Ops.today())}")
        label("Pembaruan langsung · semua unit", 13f, color = Hex.MUTED)
        val flow = board!!.optJSONObject("flow") ?: JSONObject()
        val visits = flow.list("visits").filter { flowClinic == 0 || it.optInt("clinicId") == flowClinic }
        val late = visits.filter { it.optBoolean("late") }
        val completedBy = flow.optJSONObject("completed") ?: JSONObject()
        val completed = completedBy.keys().asSequence().filter { flowClinic == 0 || it == "$flowClinic" }.sumOf { completedBy.optInt(it) }
        stat("Dalam pelayanan", "${visits.size}", "Pasien di seluruh tahap")
        stat("Selesai hari ini", "$completed", "Sudah menyelesaikan layanan")
        stat("Perlu perhatian", "${late.size}", "Waktu tunggu di atas target", valueColor = if (late.isNotEmpty()) Hex.WARM else Hex.TEXT)
        FLOW.forEachIndexed { i, (stage, title, target, button) ->
            val rows = visits.filter { it.optString("stage") == stage }
            val col = stack(12, 12).apply {
                background = android.graphics.drawable.LayerDrawable(arrayOf(shape(FLOW_FILL, 16, FLOW_BORDER), shape(Hex.PRIMARY, 2))).apply {
                    setLayerGravity(1, Gravity.TOP or Gravity.FILL_HORIZONTAL)
                    setLayerHeight(1, dp(4))
                }
            }
            val top = col.row(10)
            top.label("%02d".format(i + 1), 13f, Weight.BOLD, Color.WHITE).also {
                it.layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
                it.gravity = Gravity.CENTER
                it.background = it.shape(Hex.PRIMARY, 9)
            }
            top.label("${rows.size} pasien", 13f, Weight.SEMIBOLD, Hex.PRIMARY).weight()
            top.link("→") { summary(i, title, rows, target) }.also { it.wrap(); it.contentDescription = "Ringkasan $title"; it.minWidth = dp(44); it.gravity = Gravity.CENTER }
            col.label(title, 17f, Weight.BOLD)
            rows.take(2).forEach { v ->
                val item = col.card(6, 12, Color.WHITE, if (v.optBoolean("late")) Color.parseColor("#E9CF9C") else FLOW_BORDER, 12)
                val head = item.row(8)
                head.label(v.optString("number"), 20f, Weight.BOLD).weight()
                head.label("↗", 18f, Weight.MEDIUM, Hex.PRIMARY).also { it.wrap(); it.setPadding(dp(8), 0, dp(8), 0); it.background = it.shape(FLOW_FILL, 8) }
                item.label(v.optString("name"), 14f, Weight.SEMIBOLD)
                item.label(v.optString("note"), 12f, Weight.MEDIUM, Hex.PRIMARY).also { it.background = it.shape(Color.parseColor("#E7F4EF"), 7); it.setPadding(dp(8), dp(6), dp(8), dp(6)) }
                item.setOnClickListener { go(target) }
            }
            if (rows.isEmpty()) col.label("Tidak ada pasien.", 13f, color = Hex.MUTED)
            if (target in sections) col.action(button, Btn.PRIMARY) { go(target) }
        }
        if (late.isNotEmpty()) {
            val f = card(8, 16, Hex.WARM_BG, null, 16)
            f.label("${late.size} pasien membutuhkan tindak lanjut", 16f, Weight.BOLD, Hex.WARM)
            f.label("Periksa waktu tunggu dan koordinasikan dengan unit terkait sebelum memindahkan pasien.", 13f, color = Hex.WARM)
            f.action("Lihat pasien", Btn.WHITE) { summary(1, FLOW[1][1], late, "Antrean poli") }
        }
        footnote(flash ?: "Status diperbarui oleh masing-masing unit. Setiap perpindahan tercatat di riwayat aktivitas.")
    }

    private fun go(target: String) = if (target in sections) open(target) else Unit

    /** Ringkasan tahap (47:2267–47:2325): the stage's patients and a way into the service. */
    private fun summary(index: Int, title: String, rows: List<JSONObject>, target: String) {
        val text = rows.joinToString("\n\n") { "${it.optString("number")} · ${it.optString("name")}\n${it.optString("note")}" }
            .ifEmpty { "Tidak ada pasien di tahap ini." }
        AlertDialog.Builder(this)
            .setTitle("TAHAP %02d / 04 · %s".format(index + 1, title))
            .setMessage("${rows.size} pasien · ${rows.size} pasien ditampilkan\n\n$text\n\nStatus diperbarui oleh masing-masing unit.")
            .setNegativeButton("Kembali ke alur", null)
            .apply { if (target in sections) setPositiveButton("Buka layanan →") { _, _ -> open(target) } }
            .show()
    }

    // Farmasi (Figma 43:2 frame 22, revised by 47:2) ---------------------------------------------

    private var rxStatus = ""
    private var rxQuery = ""
    private var rxPage = 1
    private var orderPick: Int? = null
    private var rxRows: () -> Unit = {}

    private fun LinearLayout.pharmacy() {
        heading("Farmasi", "Verifikasi resep, siapkan obat, dan kelola penyerahan kepada pasien.") { sample() }
        val statuses = RX_SHORT.keys.toList()
        select("Filter status resep", listOf("Semua status") + statuses.map { RX_SHORT[it]!! }, statuses.indexOf(rxStatus) + 1) {
            rxStatus = if (it == 0) "" else statuses[it - 1]
            rxPage = 1
            render()
        }
        field("Cari nomor resep atau pasien", rxQuery) { rxQuery = it; rxPage = 1; rxRows() }
        box("Hari ini · ${Ops.shortDate(Ops.today())}")
        val all = orders
        val open = all.filter { it.optString("status") !in setOf("completed", "returned") }
        val count = { s: String -> open.count { it.optString("status") == s } }
        val stats = board!!.optJSONObject("pharmacyStats") ?: JSONObject()
        stat("Antrean resep", "${open.size}", "${count("received")} belum diproses · ${count("preparing")} diproses · ${count("ready")} siap")
        stat("Sudah diserahkan", "${stats.optInt("done")}", "Resep selesai hari ini")
        stat("Rata-rata penyiapan", "${stats.int("average") ?: 0} mnt", "Target layanan ≤ 15 menit")

        val list = card()
        list.label("Daftar resep", 20f, Weight.BOLD)
        list.label("${open.size} resep aktif", 13f, color = Hex.MUTED)
        rxRows = list.live {
            val base = if (rxStatus == "completed" || rxStatus == "returned") all else open
            val rows = base.filter { o -> (rxStatus.isEmpty() || o.optString("status") == rxStatus) && "${o.optString("pickup")} ${o.optString("number")} ${o.optString("name")}".contains(rxQuery, ignoreCase = true) }
            val (shown, now, pages) = rows.page(rxPage, 6)
            val t = table()
            t.cells(listOf("Nomor", "Pasien", "Masuk", "Status", "Aksi"), W5, header = true)
            shown.forEach { o ->
                val r = t.cells(listOf(o.optString("pickup"), o.optString("name"), o.str("receivedAt") ?: "—", RX_SHORT[o.optString("status")] ?: o.optString("statusLabel"), ""), W5)
                r.removeViewAt(r.childCount - 1)
                r.link(RX_ACTION[o.optString("status")] ?: "Detail") { orderPick = o.optInt("id"); render() }.weight(W5.last())
            }
            if (rows.isEmpty()) label(if (open.isNotEmpty()) "Tidak ada resep yang cocok." else "Tidak ada resep yang sedang diproses.", 13f, color = Hex.MUTED)
            label(if (rows.isNotEmpty()) "Menampilkan ${(now - 1) * 6 + 1}–${minOf(now * 6, rows.size)} dari ${rows.size} resep" else "Menampilkan 0 resep", 12f, color = Hex.MUTED)
            pager(now, pages) { rxPage = it; rxRows() }
        }
        val guide = card(6, 16, Hex.WARM_BG, null, 16)
        guide.label("Sebelum menyerahkan obat", 16f, Weight.BOLD, Hex.WARM)
        guide.label("Cocokkan dua identitas pasien dan berikan penjelasan penggunaan sesuai resep.", 13f, color = Hex.WARM)
        guide.label("Penyerahan dicatat atas nama petugas.", 12f, color = Hex.WARM)
        footnote("Perubahan status resep tersimpan di riwayat aktivitas.")
    }

    private var checksFor: String? = null
    private val checks = mutableSetOf<String>()

    /** Detail resep (frames 34–37): one step per status with its required checklist. */
    private fun LinearLayout.order(o: JSONObject) {
        val status = o.optString("status")
        val key = "${o.optInt("id")}:$status"
        if (checksFor != key) {
            checksFor = key
            checks.clear()
            o.optJSONArray("checks")?.strings()?.let { checks += it }
        }
        val screen = ORDER_SCREEN[status] ?: ORDER_SCREEN.getValue("received")
        val act = o.optBoolean("canAct")
        val time = when (status) {
            "received" -> o.str("receivedAt")
            "preparing" -> o.str("verifiedAt")
            "ready" -> o.str("readyAt")
            "completed" -> o.str("completedAt")
            else -> o.str("returnedAt")
        }
        heading(screen[0], screen[1]) { sample() }
        steps(if (status == "completed") 4 else 3)
        val mint = status == "completed" || status == "ready"
        patient(o.optString("pickup"), o.optString("name"),
            listOfNotNull(o.str("record")?.let { "No. RM $it" }, "Antrean poli ${o.optString("number")}", o.str("clinic")).joinToString(" · "),
            "${screen[3]}${time?.let { " · $it" } ?: ""}", mint)

        val recipe = card(10, 18, Color.WHITE, null, 16)
        recipe.label("Resep dokter · ${o.optString("pickup")}", 20f, Weight.BOLD)
        recipe.label("${o.optString("doctor")} · dikirim ${o.optString("sentAt")}", 13f, color = Hex.MUTED)
        recipe.note("Sudah diperiksa · konfirmasi dokter ${o.str("confirmedAt") ?: "—"} WIB · ${o.optInt("itemCount")} item obat")
        if (act) {
            recipe.medicines(o.list("items"))
            recipe.label("Alergi: ${o.str("allergy") ?: "tidak dilaporkan"} · perlu dikonfirmasi ulang saat penyerahan.", 12f, color = Hex.MUTED)
            o.str("doctorNote")?.let { recipe.note("Catatan dokter: $it") }
        } else recipe.label("Rincian obat hanya ditampilkan untuk petugas farmasi.", 13f, color = Hex.MUTED)
        if (status == "returned") recipe.note("Dikembalikan ke dokter: ${o.str("returnReason") ?: "—"}", true)

        val p = panel()
        p.label(screen[2], 20f, Weight.BOLD)
        p.label(if (status == "received") o.optString("statusLabel") else screen[3], 16f, Weight.SEMIBOLD, if (mint) Hex.PRIMARY else WARM_TEXT).also {
            it.background = it.shape(if (mint) MINT else WARM_FILL, 10)
            it.setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        p.label(when (status) {
            "received" -> "Resep diterima ${o.str("receivedAt") ?: "—"} WIB. Penyiapan obat belum dimulai oleh petugas farmasi."
            "preparing" -> "Diverifikasi dan mulai disiapkan ${o.str("verifiedAt") ?: "—"} WIB oleh ${o.str("handledBy") ?: me?.str("name")}."
            "ready" -> "${o.optInt("itemCount")} item obat selesai disiapkan ${o.str("readyAt") ?: "—"} WIB. Obat belum diserahkan kepada pasien."
            "completed" -> "${o.optString("name")} · ${o.optInt("itemCount")} item obat\nDiterima ${o.str("completedAt") ?: "—"} WIB di ${o.optString("counter")}."
            else -> "Resep dikembalikan ${o.str("returnedAt") ?: "—"} WIB dan menunggu peninjauan dokter."
        }, 14f, color = Hex.MUTED)
        if (status == "completed") {
            listOf(o.str("receivedAt") to "Resep dokter diterima", o.str("verifiedAt") to "Obat mulai diproses", o.str("readyAt") to "Obat siap diambil", o.str("completedAt") to "Obat diserahkan ke pasien")
                .filter { it.first != null }.forEach { (at, text) ->
                    val r = p.row(14)
                    r.label(at, 14f, Weight.BOLD, Hex.PRIMARY).wrap()
                    r.label(text, 14f).weight()
                }
        } else if (o.list("checklist").isNotEmpty()) {
            p.label(when (status) {
                "received" -> "Verifikasi sebelum memproses"
                "preparing" -> "Pemeriksaan penyiapan obat"
                else -> "Periksa saat pasien datang"
            }, 14f, Weight.SEMIBOLD)
            o.list("checklist").forEach { item ->
                val k = item.optString("key")
                p.addView(CheckBox(this@StaffActivity).apply {
                    text = item.optString("label")
                    textSize = 13f
                    typeface = ResourcesCompat.getFont(context, R.font.inter_medium)
                    setTextColor(if (k in checks) Hex.PRIMARY else Hex.MUTED)
                    buttonTintList = android.content.res.ColorStateList.valueOf(Hex.PRIMARY)
                    isChecked = k in checks
                    isEnabled = act
                    minHeight = dp(44)
                    setOnCheckedChangeListener { _, on -> if (on) checks += k else checks -= k; render() }
                })
            }
        }
        val next = p.card(6, 14, if (status == "ready") WARM_FILL else MINT, null, 10)
        next.label(when (status) {
            "received" -> "Mulai proses setelah verifikasi"
            "preparing" -> "Pastikan semua obat sudah siap"
            "ready" -> "Konfirmasi setelah obat diterima"
            "completed" -> "Dikonfirmasi oleh ${o.str("handledBy") ?: "—"}"
            else -> "Menunggu dokter"
        }, 14f, Weight.SEMIBOLD, if (status == "ready") WARM_TEXT else Hex.PRIMARY)
        next.label(when (status) {
            "received" -> "Status berikutnya: Sedang diproses"
            "preparing" -> "Status berikutnya: Siap diambil"
            "ready" -> "Jangan selesaikan saat pasien belum datang."
            "completed" -> "Tercatat di riwayat aktivitas pelayanan."
            else -> "Dokter meninjau resep lalu mengirim ulang."
        }, 12f, color = if (status == "ready") WARM_TEXT else MINT_TEXT)
        if (status != "completed") p.label("Petugas: ${o.str("handledBy") ?: me?.str("name")}${if (status == "ready") " · ${o.optString("counter")}" else ""}", 12f, Weight.MEDIUM, Hex.PRIMARY)
        if (!act) p.label("Hanya petugas farmasi yang dapat memproses resep.", 12f, color = Hex.MUTED)

        val required = o.list("checklist").map { it.optString("key") }
        val ticked = required.all { it in checks }
        val payload = { JSONObject().put("checks", JSONArray(checks.toList())) }
        val id = o.optInt("id")
        val foot = "*Penunjang bila dirujuk · resep contoh, bukan instruksi klinis."
        when (status) {
            "received" -> buttons(foot, "Kembalikan ke dokter" to act, "Verifikasi & mulai proses" to (act && ticked), onSecondary = { giveBack(o) }) { send("farmasi/$id/lanjut", payload()) }
            "preparing" -> buttons(foot, "Simpan proses" to act, "Konfirmasi obat siap diambil" to (act && ticked), onSecondary = { send("farmasi/$id/cek", payload()) }) { send("farmasi/$id/lanjut", payload()) }
            "ready" -> buttons(foot, (if (o.str("calledAt") != null) "Panggil ulang ${o.optString("pickup")}" else "Panggil pasien ${o.optString("pickup")}") to act,
                "Konfirmasi obat diserahkan" to (act && ticked), onSecondary = { send("farmasi/$id/panggil") }) { send("farmasi/$id/lanjut", payload()) }
            else -> buttons(foot, "Lihat riwayat aktivitas" to true, "Kembali ke antrean farmasi" to true, onSecondary = { open("Riwayat aktivitas") }) { orderPick = null; render() }
        }
    }

    /** Kembalikan ke dokter: a reason is required (only before processing starts). */
    private fun giveBack(o: JSONObject) {
        var reason = ""
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        box.label("Resep ${o.optString("pickup")} kembali ke ${o.optString("doctor")} untuk ditinjau.", 13f, color = Hex.MUTED)
        box.field("Alasan, mis. dosis perlu dikonfirmasi", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE) { reason = it.take(255) }.minLines = 2
        AlertDialog.Builder(this).setTitle("Kembalikan ke dokter").setView(box)
            .setPositiveButton("Kembalikan resep") { _, _ -> send("farmasi/${o.optInt("id")}/kembalikan", JSONObject().put("reason", reason.trim())) }
            .setNegativeButton("Batal", null)
            .show()
    }

    // Riwayat aktivitas (Figma 43:2 frame 23) ------------------------------------------------------

    private var unit = ""
    private var who = ""
    private var logQuery = ""
    private var logPage = 1
    private var logRows: () -> Unit = {}

    private fun LinearLayout.activity() {
        val history = arr("history")
        val filtered = {
            history.filter { (unit.isEmpty() || it.optString("unit") == unit) && (who.isEmpty() || it.optString("staff") == who) && it.optString("patient").contains(logQuery, ignoreCase = true) }
        }
        heading("Riwayat aktivitas", "Telusuri perubahan status dan tindakan petugas di seluruh layanan.") {
            action("Ekspor riwayat", enabled = filtered().isNotEmpty()) { exportHistory(filtered()) }.wrap()
        }
        box("Hari ini · ${Ops.shortDate(Ops.today())}")
        val units = history.map { it.optString("unit") }.distinct()
        val people = history.mapNotNull { it.str("staff") }.distinct()
        select("Filter unit", listOf("Semua unit") + units, units.indexOf(unit) + 1) { unit = if (it == 0) "" else units[it - 1]; logPage = 1; render() }
        select("Filter petugas", listOf("Semua petugas") + people, people.indexOf(who) + 1) { who = if (it == 0) "" else people[it - 1]; logPage = 1; render() }
        field("Cari pasien atau nomor", logQuery) { logQuery = it; logPage = 1; logRows() }
        stat("Aktivitas hari ini", "${history.size}", "Seluruh tindakan tercatat")
        stat("Petugas aktif", "${people.size}", "Di ${units.size} unit pelayanan")
        stat("Pembaruan terakhir", history.firstOrNull()?.str("time") ?: "—", "Data contoh · diperbarui langsung")
        val log = card()
        log.label("Catatan aktivitas", 20f, Weight.BOLD)
        log.label("Terbaru lebih dulu", 13f, color = Hex.MUTED)
        logRows = log.live {
            val rows = filtered()
            val (shown, now, pages) = rows.page(logPage, 8)
            shown.forEach { a ->
                val item = stack(2).apply { setPadding(0, dp(6), 0, dp(6)) }
                val top = item.row(8)
                top.label(a.optString("time"), 14f, Weight.BOLD).wrap()
                top.label(a.optString("activity"), 14f, Weight.SEMIBOLD).weight()
                top.label(a.optString("result"), 12f, Weight.SEMIBOLD, Hex.PRIMARY).wrap()
                item.label("${a.optString("patient")} · ${a.str("staff") ?: "—"} · ${a.optString("unit")}", 12f, color = Hex.MUTED)
            }
            if (rows.isEmpty()) label(if (history.isNotEmpty()) "Tidak ada aktivitas yang cocok." else "Belum ada aktivitas hari ini.", 13f, color = Hex.MUTED)
            label(if (rows.isNotEmpty()) "Menampilkan ${(now - 1) * 8 + 1}–${minOf(now * 8, rows.size)} dari ${rows.size} aktivitas" else "Menampilkan 0 aktivitas", 12f, color = Hex.MUTED)
            pager(now, pages) { logPage = it; logRows() }
        }
        footnote("Jejak aktivitas tersimpan otomatis. Catatan tidak dapat diubah atau dihapus oleh petugas.")
    }

    private var pendingCsv: String? = null

    /** Same CSV as the web export (cells quoted, formulas neutralised), saved where the user picks. */
    private fun exportHistory(rows: List<JSONObject>) {
        val cell = { v: String -> "\"" + v.replace(Regex("^[=+\\-@]"), "'$0").replace("\"", "\"\"") + "\"" }
        val lines = listOf(listOf("Waktu", "Aktivitas", "Pasien / nomor", "Petugas", "Unit", "Hasil")) +
            rows.map { r -> listOf("time", "activity", "patient", "staff", "unit", "result").map { r.str(it).orEmpty() } }
        pendingCsv = "\uFEFF" + lines.joinToString("\r\n") { line -> line.joinToString(",") { cell(it) } } + "\r\n"
        saveCsv.launch("hytrack-riwayat-aktivitas.csv")
    }

    private val saveCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val csv = pendingCsv ?: return@registerForActivityResult
        uri?.let { contentResolver.openOutputStream(it)?.use { out -> out.write(csv.toByteArray()) } }
        pendingCsv = null
    }

    // Daftar booking (CRUD) ------------------------------------------------------------------------

    private var bTyped = ""
    private var opening: Int? = null
    private var cancelling = false
    private var reason = ""

    private fun LinearLayout.bookings() {
        val b = board!!
        val filters = b.optJSONObject("bookingFilters") ?: JSONObject()
        val clinics = b.list("clinics")
        val statuses = b.list("statuses")
        heading(BOOKINGS, "Buat, cari, perbarui, dan batalkan booking pasien. Semua perubahan tersimpan di server.") { sample() }
        label("Tanggal layanan", 12f, Weight.SEMIBOLD, Hex.MUTED)
        val date = filters.str("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        box(date?.let { Ops.dateLabel(it) } ?: "Semua tanggal").setOnClickListener {
            val d = date ?: Ops.today()
            DatePickerDialog(this@StaffActivity, { _, y, m, day -> params["tanggal"] = LocalDate.of(y, m + 1, day).toString(); load() }, d.year, d.monthValue - 1, d.dayOfMonth).show()
        }
        link("Semua tanggal") { params["tanggal"] = ""; load() }
        label("Poliklinik", 12f, Weight.SEMIBOLD, Hex.MUTED)
        select("Poliklinik", listOf("Semua poli") + clinics.map { it.optString("name") }, clinics.indexOfFirst { it.optInt("id") == filters.int("clinic") } + 1) {
            params["bpoli"] = if (it == 0) null else clinics[it - 1].optInt("id")
            load()
        }
        label("Status", 12f, Weight.SEMIBOLD, Hex.MUTED)
        select("Status", listOf("Semua status") + statuses.map { it.optString("label") }, statuses.indexOfFirst { it.optString("value") == filters.optString("status") } + 1) {
            params["status"] = if (it == 0) null else statuses[it - 1].optString("value")
            load()
        }
        val find = row(10)
        if (bTyped.isEmpty()) bTyped = filters.optString("search")
        find.field("Cari nomor antrean", bTyped) { bTyped = it }.weight().contentDescription = "Cari nomor antrean"
        find.action("Cari", Btn.SECONDARY) { params["cari"] = bTyped.trim(); load() }.wrap()

        val rows = b.list("bookings")
        val list = card()
        rows.forEach { q ->
            val item = list.stack(4).apply { setPadding(0, dp(8), 0, dp(8)) }
            val top = item.row(8)
            top.label(q.optString("number"), 16f, Weight.BOLD).wrap()
            top.label(q.optString("name"), 14f, Weight.SEMIBOLD).weight()
            top.pill(q.optString("status"), q.optString("statusLabel"))
            item.label("${q.optString("clinic")} · ${q.optString("doctor")}", 12f, color = Hex.MUTED)
            val bottom = item.row(8)
            bottom.label(q.optString("dateLabel").substringAfter(", "), 12f, color = Hex.MUTED).weight()
            bottom.link("Detail") { opening = if (opening == q.optInt("id")) null else q.optInt("id"); cancelling = false; reason = ""; error = null; render() }.wrap()
            if (opening == q.optInt("id")) item.detail(q)
        }
        if (rows.isEmpty()) list.label("Tidak ada booking yang cocok.", 13f, color = Hex.MUTED)
        list.label("Menampilkan ${rows.size} dari ${b.optInt("bookingTotal")} booking", 12f, color = Hex.MUTED)
        list.label("Urut tanggal, poli, nomor", 12f, color = Hex.MUTED)

        val recent = card(8)
        recent.label("Booking terbaru", 18f, Weight.BOLD)
        recent.label("Termasuk booking untuk hari lain.", 13f, color = Hex.MUTED)
        b.list("recentBookings").forEach { q ->
            val r = recent.stack(2)
            r.label("${q.optString("number")} · ${q.optString("name")}", 14f, Weight.SEMIBOLD)
            r.label("${q.optString("clinic")} · ${q.optString("dateLabel")} · ${q.optString("status")}${q.str("bookedAt")?.let { " · dibuat $it" } ?: ""}", 12f, color = Hex.MUTED)
        }
        walkIn()
    }

    private fun LinearLayout.pill(status: String, text: String) = label(text, 11f, Weight.BOLD, when (status) {
        "booked" -> Hex.INFO_TEXT
        "no_show", "cancelled" -> Hex.WARNING_TEXT
        "completed" -> Hex.MUTED
        else -> Hex.PRIMARY
    }).also {
        it.wrap()
        it.background = it.shape(when (status) {
            "booked" -> Hex.INFO
            "no_show", "cancelled" -> Hex.WARNING
            "completed" -> Hex.DONE
            else -> Hex.PALE
        }, 12)
        it.setPadding(it.dp(10), it.dp(3), it.dp(10), it.dp(3))
    }

    private fun LinearLayout.detail(q: JSONObject) {
        val id = q.optInt("id")
        val status = q.optString("status")
        val c = card(10, 16, Color.WHITE, Hex.PALE_BORDER)
        val head = c.row(8)
        val title = head.stack(2).weight()
        title.eyebrow("DETAIL BOOKING · ${q.optString("code")}")
        title.label(q.optString("number"), 34f, Weight.BOLD)
        head.link("Tutup") { opening = null; render() }.wrap()
        c.pill(status, q.optString("statusLabel"))
        fun pair(k: String, v: String) = c.stack(2).apply { label(k, 12f, color = Hex.MUTED); label(v, 14f, Weight.MEDIUM) }
        pair("Pasien", q.optString("name") + (q.str("phone")?.let { " · $it" } ?: ""))
        pair("Poli · Dokter", "${q.optString("clinic")} · ${q.optString("doctor")}")
        pair("Tanggal", "${q.optString("dateLabel")} · ${q.optString("time")}")
        pair("Dibuat", q.str("bookedAt")?.let { "$it · ${q.str("bookedBy") ?: "Pasien (aplikasi)"}" } ?: "Data lama")
        pair("Kehadiran", when {
            q.str("checkInAt") != null -> "Hadir ${q.optString("checkInAt")}${q.str("checkInBy")?.let { " · dikonfirmasi $it" } ?: ""}${if (q.str("checkInMethod") == "code") " · Nomor check-in" else ""}"
            status == "no_show" -> "Tidak hadir · ${q.str("noShowAt").orEmpty()}${q.str("noShowBy")?.let { " oleh $it" } ?: ""}"
            status == "cancelled" -> "—"
            else -> "Belum check-in · batas ${q.optString("deadline")}${if (q.optBoolean("checkInClosed")) " (terlewati)" else ""}"
        })
        if (q.optInt("calledCount") > 0) pair("Dipanggil", "${q.optInt("calledCount")}×")
        q.str("cancelledAt")?.let { pair("Dibatalkan", "$it · ${q.str("cancelledBy").orEmpty()}${q.str("cancelReason")?.let { r -> " · $r" } ?: ""}") }
        q.list("stages").forEachIndexed { i, s ->
            c.label("${i + 1}. ${s.optString("name")} · ${s.optString("status")}${s.str("at")?.let { " · $it" } ?: ""}${s.str("by")?.let { " · $it" } ?: ""}", 12f, color = Hex.MUTED)
        }
        val today = q.optBoolean("isToday")
        val actions = c.stack(10)
        if (status == "booked" && today) actions.action("Konfirmasi hadir", enabled = !busy) {
            actions.confirm("Konfirmasi ${q.optString("name")} (${q.optString("number")}) hadir? Cocokkan identitas pasien terlebih dahulu.") {
                send("check-in", JSONObject().put("code", q.optString("code")))
            }
        }
        if (status == "booked") actions.action("Tandai tidak hadir", Btn.SECONDARY, q.optBoolean("checkInClosed") && !busy) {
            actions.confirm("Tandai ${q.optString("number")} tidak hadir? Tidak dapat diulang.") { send("antrean/$id/tidak-hadir") }
        }
        if (status == "waiting" && today) actions.action("Panggil", enabled = !busy) { send("antrean/$id/panggil") }
        if (status == "serving") {
            actions.action("Panggil ulang", Btn.SECONDARY, !busy) { send("antrean/$id/panggil-ulang") }
            actions.action("Tidak hadir", Btn.SECONDARY, q.optInt("calledCount") >= 2 && !busy) {
                actions.confirm("Tandai ${q.optString("number")} tidak hadir setelah dipanggil?") { send("antrean/$id/tidak-hadir") }
            }
            // Figma 47:2: only the doctor confirms the examination.
            c.label("Pemeriksaan dan resep dikonfirmasi oleh dokter di Pemeriksaan dokter.", 12f, color = Hex.MUTED)
        }
        if (status == "laboratory") c.label("Hasil penunjang dikirim petugas penunjang lalu ditinjau dokter.", 12f, color = Hex.MUTED)
        if (status == "booked" && !q.optBoolean("checkInClosed")) {
            c.label("Status masih Belum check-in. Tidak hadir baru dapat dicatat setelah batas ${q.optString("deadline")}.", 12f, color = Hex.MUTED)
        }
        val cancellable = (status == "booked" || status == "waiting") && (today || !q.optBoolean("checkInClosed"))
        if (!cancellable) return
        if (!cancelling) {
            c.link("Batalkan booking…", Hex.DANGER) { cancelling = true; render() }
            return
        }
        c.label("Alasan pembatalan", 12f, Weight.SEMIBOLD, Hex.MUTED)
        lateinit var yes: TextView
        c.field("Mis. pasien meminta jadwal ulang", reason, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE) {
            reason = it.take(255)
            yes.isEnabled = reason.trim().length >= 3
            yes.alpha = if (yes.isEnabled) 1f else 0.55f
        }.minLines = 2
        c.label("Booking tidak dihapus: alasan, waktu, dan nama Anda dicatat. Kuota dokter dibuka kembali.", 12f, color = Hex.MUTED)
        val split = c.row(10)
        yes = split.action("Ya, batalkan booking", Btn.DANGER, reason.trim().length >= 3) {
            send("antrean/$id/batal", JSONObject().put("reason", reason.trim())) { cancelling = false }
        }.weight()
        split.action("Tidak jadi", Btn.SECONDARY) { cancelling = false; render() }.weight()
    }

    private var wPhone = ""
    private var wPatient: JSONObject? = null
    private var wLookup = ""
    private var wDate = Ops.today()
    private var wSchedule: JSONObject? = null
    private var wClinic = 0
    private var wDoctor = 0
    private var wError: String? = null

    /** Create: a registered patient who arrived at the desk, under the same quota and numbering. */
    private fun LinearLayout.walkIn() {
        val range = board!!.optJSONObject("bookingDates")
        val min = range?.str("min")?.let(LocalDate::parse) ?: Ops.today()
        val max = range?.str("max")?.let(LocalDate::parse) ?: Ops.today()
        val c = card()
        c.label("Buat booking · pasien datang langsung", 18f, Weight.BOLD)
        c.label("Nomor ponsel pasien terdaftar", 12f, Weight.SEMIBOLD, Hex.MUTED)
        val find = c.row(10)
        lateinit var go: TextView
        find.field("0812-3456-7890", wPhone, InputType.TYPE_CLASS_PHONE) {
            wPhone = it
            go.isEnabled = it.trim().length >= 8
            go.alpha = if (go.isEnabled) 1f else 0.55f
        }.weight()
        go = find.action("Cari pasien", Btn.SECONDARY, wPhone.trim().length >= 8) {
            request({ Api.call("GET", "petugas/pasien" + Api.query("telepon" to wPhone.trim())) }) { result ->
                result.onSuccess { wPatient = it.getJSONObject("patient"); wLookup = ""; schedule() }
                    .onFailure { e -> wPatient = null; wLookup = e.message.orEmpty() }
                wError = null
                render()
            }
        }.wrap()
        if (wLookup.isNotEmpty()) c.label(wLookup, 12f, color = Hex.MUTED)
        val patient = wPatient ?: return
        c.flag("${patient.optString("name")} · ${patient.optString("phone")}${patient.str("active")?.let { " · sudah punya kunjungan aktif $it" } ?: ""}",
            if (patient.str("active") != null) Tone.WARNING else Tone.GREEN)
        c.label("Tanggal kunjungan", 12f, Weight.SEMIBOLD, Hex.MUTED)
        c.box(Ops.dateLabel(wDate)).setOnClickListener {
            DatePickerDialog(this@StaffActivity, { _, y, m, d -> wDate = LocalDate.of(y, m + 1, d); schedule() }, wDate.year, wDate.monthValue - 1, wDate.dayOfMonth).apply {
                datePicker.minDate = min.toEpochDay() * 86_400_000
                datePicker.maxDate = max.toEpochDay() * 86_400_000
            }.show()
        }
        val sched = wSchedule ?: return c.label("Memuat jadwal dokter…", 13f, color = Hex.MUTED).let {}
        val clinics = sched.list("clinics")
        val clinic = clinics.firstOrNull { it.optInt("id") == wClinic } ?: clinics.firstOrNull() ?: return
        val doctors = clinic.list("doctors")
        val doctor = doctors.firstOrNull { it.optInt("id") == wDoctor } ?: doctors.firstOrNull()
        c.label("Poliklinik", 12f, Weight.SEMIBOLD, Hex.MUTED)
        c.select("Poliklinik", clinics.map { it.optString("name") }, clinics.indexOf(clinic)) { wClinic = clinics[it].optInt("id"); wDoctor = 0; render() }
        c.label("Dokter", 12f, Weight.SEMIBOLD, Hex.MUTED)
        c.select("Dokter", doctors.map { "${it.optString("name")} · ${it.optString("time")} · sisa ${it.optInt("quota")}" }, doctors.indexOf(doctor), doctors.isNotEmpty()) { wDoctor = doctors[it].optInt("id"); render() }
        val quota = doctor?.optInt("quota")
        c.label("Sisa kuota: ${quota?.let { "$it pasien" } ?: "—"}", 13f)
        if (!sched.optBoolean("open")) c.flag("Tanggal ini di luar masa booking.", Tone.WARNING)
        wError?.let { c.flag(it, Tone.WARNING) }
        c.action(if (quota != null && quota < 1) "Kuota penuh" else "Ambil antrean", enabled = doctor != null && (quota ?: 0) > 0 && sched.optBoolean("open") && !busy) {
            val body = JSONObject().put("phone", wPhone.trim()).put("polyclinic_id", clinic.optInt("id")).put("doctor_id", doctor!!.optInt("id")).put("date", wDate.toString())
            busy = true
            request({ Api.call("POST", "petugas/booking", body) }) { result ->
                busy = false
                result.onSuccess {
                    flash = it.str("message")
                    wPatient = null
                    wPhone = ""
                    wError = null
                    load()
                }.onFailure { e -> wError = e.message; render() }
            }
        }
    }

    private fun schedule() {
        wSchedule = null
        request({ Api.call("GET", "petugas/jadwal" + Api.query("tanggal" to wDate.toString())) }) { result ->
            result.onSuccess { wSchedule = it }.onFailure { e -> wError = e.message }
            render()
        }
    }

    // Data poliklinik (CRUD) -----------------------------------------------------------------------

    private var editing: JSONObject? = null
    private var formName = ""
    private var formRoom = ""

    private fun LinearLayout.clinicAdmin() {
        val clinics = board!!.list("clinicAdmin")
        heading(CLINICS, "Tambah, ubah, dan hapus data poliklinik yang dipilih pasien saat booking.") { sample() }
        val list = card()
        clinics.forEach { c ->
            val item = list.stack(4).apply { setPadding(0, dp(6), 0, dp(6)) }
            item.label(c.optString("name"), 16f, Weight.BOLD)
            item.label(c.str("room") ?: "Belum diatur", 13f, color = Hex.MUTED)
            item.label("Dokter ${c.optInt("doctors")} · Antrean ${c.optInt("queues")}", 12f, color = Hex.MUTED)
            val r = item.row(16)
            r.link("Ubah") { editing = c; formName = c.optString("name"); formRoom = c.str("room").orEmpty(); error = null; render() }.wrap()
            r.link("Hapus", Hex.DANGER, !busy) {
                r.confirm("Hapus poliklinik ${c.optString("name")}? Riwayat kunjungannya tetap tersimpan.") { send("poliklinik/${c.optInt("id")}", method = "DELETE") }
            }.wrap().contentDescription = "Hapus ${c.optString("name")}"
        }
        list.label("${clinics.size} poliklinik", 12f, color = Hex.MUTED)

        val form = card()
        val current = editing
        form.label(current?.let { "Ubah ${it.optString("name")}" } ?: "Tambah poliklinik", 18f, Weight.BOLD)
        form.label("Nama poliklinik", 12f, Weight.SEMIBOLD, Hex.MUTED)
        form.field("Contoh: Mata", formName) { formName = it }
        form.label("Lokasi ruangan (opsional)", 12f, Weight.SEMIBOLD, Hex.MUTED)
        form.field("Ruang B-201 · Gedung B, lantai 2", formRoom) { formRoom = it }
        val r = form.row(10)
        r.action(if (current != null) "Simpan perubahan" else "Tambah poliklinik", enabled = !busy) {
            val body = JSONObject().put("name", formName.trim()).put("room", formRoom.trim().ifEmpty { JSONObject.NULL })
            val done = { editing = null; formName = ""; formRoom = "" }
            if (current != null) send("poliklinik/${current.optInt("id")}", body, method = "PUT", done = done) else send("poliklinik", body, done = done)
        }.weight()
        if (current != null) r.action("Batal", Btn.SECONDARY) { editing = null; formName = ""; formRoom = ""; render() }.weight()
    }

    companion object {
        const val SCAN = "Scan QR kedatangan"
        const val EXAM = "Pemeriksaan dokter"
        const val SUPPORT = "Pelayanan penunjang"
        const val BOOKINGS = "Daftar booking"
        const val CLINICS = "Data poliklinik"
        private val W5 = listOf(1.1f, 1.8f, 1f, 1.3f, 1f)

        // Figma 47:2 tokens.
        private val MINT = Color.parseColor("#E0EEE5")
        private val MINT_TEXT = Color.parseColor("#53665F")
        private val WARM_FILL = Color.parseColor("#FFF1CF")
        private val WARM_TEXT = Color.parseColor("#925900")
        private val FLOW_FILL = Color.parseColor("#EDF5F1")
        private val FLOW_BORDER = Color.parseColor("#DCE6E1")

        private val STEPS = listOf("1  Kedatangan", "2  Poli & dokter", "3  Penunjang*", "4  Farmasi", "5  Selesai")
        private val EXAM_STATE = mapOf(
            "serving" to "Sedang diperiksa",
            "review" to "Hasil penunjang siap ditinjau",
            "returned" to "Resep dikembalikan farmasi",
            "support" to "Menunggu hasil penunjang",
            "confirmed" to "Sudah diperiksa",
        )
        private val SUPPORT_STATUS = listOf("waiting" to "Menunggu", "processing" to "Diproses", "done" to "Selesai")
        private val RX_SHORT = linkedMapOf(
            "received" to "Belum diproses",
            "preparing" to "Diproses",
            "ready" to "Siap diambil",
            "completed" to "Diserahkan",
            "returned" to "Dikembalikan",
        )
        private val RX_ACTION = mapOf("received" to "Mulai proses", "preparing" to "Detail", "ready" to "Panggil", "completed" to "Detail", "returned" to "Detail")
        /** Title, subtitle, panel title and status pill per prescription status (frames 34–37). */
        private val ORDER_SCREEN = mapOf(
            "received" to listOf("Detail resep", "Verifikasi resep dokter sebelum memulai penyiapan obat.", "Konfirmasi farmasi", "Resep diterima"),
            "preparing" to listOf("Penyiapan obat", "Konfirmasi obat sedang diproses dan periksa kelengkapannya.", "Konfirmasi farmasi", "Sedang diproses"),
            "ready" to listOf("Penyerahan obat", "Panggil pasien dan konfirmasi penyerahan setelah identitas sesuai.", "Konfirmasi penyerahan", "Siap diambil"),
            "completed" to listOf("Pelayanan selesai", "Obat sudah diserahkan dan seluruh tahapan kunjungan tercatat.", "Pelayanan telah selesai", "Sudah diserahkan"),
            "returned" to listOf("Detail resep", "Resep dikembalikan dan menunggu peninjauan dokter.", "Resep dikembalikan", "Dikembalikan ke dokter"),
        )
        /** Alur pelayanan columns: stage, title, the service it opens and its button (47:484). */
        private val FLOW = listOf(
            listOf("arrival", "Verifikasi kedatangan", SCAN, "Verifikasi kedatangan →"),
            listOf("clinic", "Poli & dokter", EXAM, "Pemeriksaan dokter →"),
            listOf("support", "Penunjang", SUPPORT, "Ringkasan penunjang →"),
            listOf("pharmacy", "Farmasi", "Farmasi", "Kelola pemrosesan obat →"),
        )
        private val RESULT_TYPES = setOf("application/pdf", "image/jpeg", "image/png")
        private const val RESULT_MAX = 5 * 1024 * 1024
    }
}

private operator fun <T> List<T>.component4() = this[3]

/** .scan-frame: where the QR is held, 180dp with 12dp corners, the rest of the picture dimmed. */
private class Viewfinder(context: Context) : View(context) {
    private val dim = Paint().apply { color = Color.argb(115, 23, 61, 59) }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE; strokeWidth = dp(2).toFloat() }
    private val hole = Path()

    override fun onDraw(canvas: Canvas) {
        val size = dp(180).toFloat()
        val box = RectF((width - size) / 2, dp(50).toFloat(), (width + size) / 2, dp(50) + size)
        val r = dp(12).toFloat()
        hole.reset()
        hole.fillType = Path.FillType.EVEN_ODD
        hole.addRect(0f, 0f, width.toFloat(), height.toFloat(), Path.Direction.CW)
        hole.addRoundRect(box, r, r, Path.Direction.CW)
        canvas.drawPath(hole, dim)
        canvas.drawRoundRect(box, r, r, edge)
    }
}
