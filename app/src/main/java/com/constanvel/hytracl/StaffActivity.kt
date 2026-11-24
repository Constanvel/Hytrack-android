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
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.journeyapps.barcodescanner.camera.CameraSettings
import java.time.LocalDate

/** Staff dashboard (web features/operations/staff.tsx and its sections), on Ops' demo data. */
class StaffActivity : DashboardActivity() {
    override val role = Local.Role.STAFF
    override val sections = listOf(SCAN, "Antrean poli", "Alur pelayanan", "Farmasi", "Riwayat aktivitas", BOOKINGS, CLINICS)

    /** Breadcrumb under "Pelayanan" (Figma 43:2 frames 11, 18, 21–23). */
    override fun crumb(section: String) = "Pelayanan / " + when (section) {
        SCAN -> "Check-in kedatangan"
        "Antrean poli" -> "Rawat jalan"
        else -> section
    }

    override fun content(into: LinearLayout, section: String) = when (section) {
        SCAN -> into.scanner()
        "Alur pelayanan" -> into.flow()
        "Farmasi" -> into.pharmacy()
        "Riwayat aktivitas" -> into.activity()
        BOOKINGS -> into.bookings()
        CLINICS -> into.clinicAdmin()
        else -> into.queue()
    }

    // Antrean poli (Figma 43:2 frame 11) -----------------------------------------------------------

    private var clinicId = 1
    private var doctorId = 0
    private var query = ""
    private var page = 1
    private var detail: String? = null

    private fun LinearLayout.queue() {
        heading("Antrean poli", "Kelola panggilan dan bantu pasien melanjutkan pelayanan.") { sample() }
        val clinic = Ops.clinics.firstOrNull { it.id == clinicId } ?: Ops.clinics.first()
        val doctors = Ops.doctors.filter { it.clinic === clinic }
        val doctor = doctors.firstOrNull { it.id == doctorId } ?: doctors.firstOrNull()
        select("Poliklinik", Ops.clinics.map { it.name }, Ops.clinics.indexOf(clinic)) {
            clinicId = Ops.clinics[it].id
            doctorId = 0
            render()
        }
        select("Dokter", doctors.map { it.name }, doctors.indexOf(doctor), doctors.isNotEmpty()) {
            doctorId = doctors[it].id
            render()
        }
        label(doctor?.let { "${clinic.room ?: "Ruang belum diatur"} · ${it.time}" } ?: "—", 14f, Weight.MEDIUM, Hex.MUTED)
        if (doctor == null) {
            card().apply {
                label("Belum ada dokter di poliklinik ini", 18f, Weight.BOLD)
                label("Pilih poliklinik lain untuk mengelola antrean.", 14f, color = Hex.MUTED)
            }
            return
        }
        val line = Ops.queues.filter { it.doctor === doctor && it.date == Ops.today() }
        val waiting = Ops.waiting(doctor)
        val current = Ops.serving(doctor)
        stat("Menunggu", "${waiting.size}", "Pasien sudah check-in", Hex.MINT)
        stat("Selesai", "${line.count { it.status in setOf(Ops.Status.LABORATORY, Ops.Status.PHARMACY, Ops.Status.COMPLETED) }}",
            "Dari ${line.count { it.status != Ops.Status.CANCELLED }} pasien terdaftar")
        stat("Belum check-in", "${line.count { it.status == Ops.Status.BOOKED }}", "Periksa kedatangan pasien", Hex.SLATE)

        val table = card()
        table.label("Daftar antrean", 20f, Weight.BOLD)
        table.label("${waiting.size} menunggu ·", 13f, color = Hex.MUTED)
        table.field("Cari pasien", query) { query = it; page = 1; redrawQueue() }
        queueRows = table.live {
            val filtered = waiting.filter { "${it.patient} ${it.number}".contains(query, ignoreCase = true) }
            val (rows, current0, pages) = filtered.page(page, 6)
            val t = table()
            t.cells(listOf("Nomor", "Pasien", "Check-in", "Status", "Aksi"), W5, header = true)
            rows.forEach { q ->
                val next = q === waiting.first()
                val r = t.cells(listOf(q.number, q.patient, Ops.clock(q.checkIn) ?: "", if (next) "Berikutnya" else "Menunggu", ""), W5, highlight = next)
                r.removeViewAt(r.childCount - 1)
                r.link(if (next) "Panggil" else "Detail", enabled = !next || current == null) {
                    if (next) act { "${Ops.callNext(doctor).number} dipanggil." }
                    else { detail = if (detail == q.number) null else q.number; redrawQueue() }
                }.weight(W5.last())
            }
            if (filtered.isEmpty()) label(if (waiting.isNotEmpty()) "Tidak ada pasien yang cocok." else "Belum ada pasien menunggu.", 13f, color = Hex.MUTED)
            detail?.let { flag("Nomor $it · Dashboard petugas hanya memuat data antrean, tanpa diagnosis atau rekam medis.") }
            label(if (filtered.isNotEmpty()) "Menampilkan ${(current0 - 1) * 6 + 1}–${minOf(current0 * 6, filtered.size)} dari ${filtered.size} pasien" else "Menampilkan 0 pasien", 12f, color = Hex.MUTED)
            pager(current0, pages) { page = it; redrawQueue() }
        }

        val hero = stack(6, 18).apply {
            background = gradient(Color.parseColor("#086B62"), Color.parseColor("#188777"), 22)
        }
        hero.eyebrow("SEDANG DILAYANI", Color.WHITE)
        hero.label(current?.number ?: "—", 48f, Weight.BOLD, Color.WHITE)
        hero.label(current?.patient ?: "Belum ada pasien", 19f, Weight.SEMIBOLD, Color.WHITE)
        hero.label(current?.let { q ->
            val started = q.stage(Ops.StageKind.CONSULTATION).started
            "Mulai ${Ops.clock(started)}${Ops.minutesSince(started)?.let { " · Berjalan $it menit" } ?: ""}"
        } ?: "Panggil pasien berikutnya", 13f, color = Color.parseColor("#D9EAE2"))
        hero.action("Selesai & lanjutkan layanan", Btn.WHITE, current != null) {
            act { Ops.examined(current!!); "${current.number} selesai diperiksa, lanjut ke laboratorium." }
        }

        val next = card()
        next.label("Berikutnya ${waiting.firstOrNull()?.number ?: "—"}", 19f, Weight.BOLD)
        next.label("Pastikan ruang siap sebelum memanggil.", 13f, color = Hex.MUTED)
        next.action("Panggil pasien berikutnya", enabled = current == null && waiting.isNotEmpty()) { act { "${Ops.callNext(doctor).number} dipanggil." } }
        val split = next.row(10)
        split.action("Panggil ulang", Btn.SECONDARY, current != null) { act { Ops.recall(current!!); "${current.number} dipanggil ulang." } }.weight()
        split.action("Tidak hadir", Btn.SECONDARY, current != null && current.calledCount >= 2) {
            act { Ops.noShow(current!!); "${current.number} ditandai tidak hadir." }
        }.weight()
        flag("Perubahan status tercatat. Tandai tidak hadir hanya setelah panggilan ulang.", Tone.WARNING)

        val history = Ops.history()
        footnote(flash?.let { "✓ $it" } ?: history.firstOrNull()?.let { "✓ ${Ops.sentence(it)}" } ?: "Belum ada aktivitas hari ini.")
        link("Lihat riwayat aktivitas →") { open("Riwayat aktivitas") }
    }

    private var queueRows: () -> Unit = {}
    private fun redrawQueue() = queueRows()

    // Scan QR kedatangan (Figma 27:360 / 43:2 frame 18) ----------------------------------------------

    private var typed = ""
    private var ticket: Ops.Queue? = null
    private var checkedIn: String? = null

    /** QrScanner's phases (scanner.tsx); the camera only reads the code, Ops decides. */
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

    /** Step 1: identify the ticket without changing anything. */
    private fun verify(code: String) {
        scanned = code
        checkedIn = null
        error = null
        try {
            ticket = Ops.identify(code)
            phase = Phase.FOUND
        } catch (e: Ops.Refused) {
            ticket = null
            problem = e.message.orEmpty()
            phase = Phase.INVALID
        }
        render()
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
            result.label(t.patient, 22f, Weight.BOLD)
            val chip = result.stack(2, 14).apply { background = shape(Hex.PALE, 14) }
            chip.eyebrow("NOMOR ANTREAN", Hex.PRIMARY)
            chip.label(t.number, 28f, Weight.BOLD, Hex.PRIMARY)
            chip.label(t.code, 12f, Weight.SEMIBOLD, Hex.MUTED)
            fun pair(k: String, v: String) = result.stack(2).apply { label(k, 12f, color = Hex.MUTED); label(v, 14f, Weight.SEMIBOLD) }
            pair("Poliklinik", t.clinic.name)
            pair("Dokter dan jadwal", "${t.doctor.name}\n${t.doctor.time}${t.clinic.room?.let { " · ${it.substringBefore(" · ")}" } ?: ""}")
            pair("Tanggal kunjungan", Ops.dateLabel(t.date).substringAfter(", "))
            val again = { typed = ""; error = null; rescan() }
            when {
                checkedIn != null -> {
                    result.flag("Sudah check-in · tercatat sekali")
                    result.action("Pindai pasien berikutnya") { again() }
                }
                t.status == Ops.Status.NO_SHOW -> {
                    result.flag("Tidak hadir · tiket tidak dapat dipakai lagi", Tone.WARNING)
                    result.label("Arahkan pasien ke petugas pendaftaran.", 13f, color = Hex.MUTED)
                    result.action("Pindai pasien berikutnya") { again() }
                }
                t.status != Ops.Status.BOOKED -> {
                    result.flag("Sudah check-in pukul ${Ops.clock(t.checkIn)}${t.checkInBy?.let { " · dikonfirmasi $it" } ?: ""}")
                    result.label("Kehadiran sudah tercatat. Tidak ada yang diubah.", 13f, color = Hex.MUTED)
                    result.action("Pindai pasien berikutnya") { again() }
                }
                else -> {
                    result.flag("Belum check-in · batas ${Ops.clock(t.deadline)}", Tone.INFO)
                    result.label("Cocokkan nama dan tujuan poli dengan pasien sebelum konfirmasi.", 13f, color = Hex.MUTED)
                    result.action("Konfirmasi check-in") { // step 2: record the arrival
                        try {
                            val q = Ops.checkIn(t, method = Ops.method(scanned))
                            checkedIn = "${q.number} berhasil check-in pukul ${Ops.clock(q.checkIn)}."
                            phase = Phase.DONE
                        } catch (e: Ops.Refused) {
                            ticket = null
                            problem = e.message.orEmpty()
                            phase = Phase.INVALID
                        }
                        render()
                    }
                    result.link("Batalkan pemindaian") { again() }.gravity = Gravity.CENTER
                }
            }
            checkedIn?.let { result.label("✓ $it", 13f, Weight.SEMIBOLD, Hex.PRIMARY) }
        }

        val manual = card(10, 18)
        manual.label("QR sulit terbaca?", 18f, Weight.BOLD)
        manual.label("Masukkan nomor check-in pada tiket pasien.", 13f, color = Hex.MUTED)
        lateinit var search: android.widget.TextView
        manual.field("Contoh: HT-000027", typed, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) {
            typed = it
            search.isEnabled = it.isNotBlank()
            search.alpha = if (it.isNotBlank()) 1f else 0.55f
        }.contentDescription = "Nomor check-in"
        search = manual.action("Cari tiket", Btn.SECONDARY, typed.isNotBlank()) { verify(typed.trim()) }
        footnote("Check-in hanya dicatat setelah konfirmasi. Tiket yang sudah check-in tidak akan dicatat dua kali.")
    }

    // Alur pelayanan (Figma 43:2 frame 21) ---------------------------------------------------------

    private var flowClinic = 0
    private val opened = mutableSetOf<String>()

    private fun LinearLayout.flow() {
        heading("Alur pelayanan", "Pantau perpindahan pasien dari kedatangan hingga layanan selesai.") { sample() }
        select("Filter poliklinik", listOf("Semua poliklinik") + Ops.clinics.map { it.name }, Ops.clinics.indexOfFirst { it.id == flowClinic } + 1) {
            flowClinic = if (it == 0) 0 else Ops.clinics[it - 1].id
            render()
        }
        box("Hari ini · ${Ops.shortDate(Ops.today())}")
        label("Pembaruan langsung · semua unit", 13f, color = Hex.MUTED)
        val visits = Ops.flow().filter { flowClinic == 0 || it.clinic.id == flowClinic }
        val late = visits.filter(Ops::late)
        val completed = Ops.queues.count { it.date == Ops.today() && it.status == Ops.Status.COMPLETED && (flowClinic == 0 || it.clinic.id == flowClinic) }
        stat("Dalam pelayanan", "${visits.size}", "Pasien di seluruh tahap")
        stat("Selesai hari ini", "$completed", "Sudah menyelesaikan layanan")
        stat("Perlu perhatian", "${late.size}", "Waktu tunggu di atas target", valueColor = if (late.isNotEmpty()) Hex.WARM else Hex.TEXT)
        val columns = listOf("arrival" to "Verifikasi kedatangan", "clinic" to "Antrean poli", "support" to "Penunjang", "pharmacy" to "Farmasi")
        val stageOf = { q: Ops.Queue ->
            when (q.status) {
                Ops.Status.BOOKED -> "arrival"
                Ops.Status.WAITING, Ops.Status.SERVING -> "clinic"
                Ops.Status.LABORATORY -> "support"
                else -> "pharmacy"
            }
        }
        columns.forEach { (stage, title) ->
            val rows = visits.filter { stageOf(it) == stage }
            val expanded = stage in opened
            val col = card(10, 16, Hex.PALE, null, 16)
            col.label(title, 16f, Weight.BOLD)
            col.label("${rows.size} pasien", 13f, color = Hex.MUTED)
            (if (expanded) rows else rows.take(2)).forEach { q ->
                val item = col.card(4, 14, Color.WHITE, if (Ops.late(q)) Color.parseColor("#E9CF9C") else null, 16)
                item.label(q.number, 20f, Weight.BOLD)
                item.label(q.patient, 14f, Weight.MEDIUM)
                item.label(Ops.note(q), 12f, color = Hex.MUTED)
                if (stage == "support") item.link("Selesai, lanjut ke farmasi") { act { Ops.laboratoryDone(q); "${q.number} lanjut ke farmasi." } }
            }
            if (rows.isEmpty()) col.label("Tidak ada pasien.", 13f, color = Hex.MUTED)
            if (rows.size > 2) col.link(if (expanded) "Tampilkan lebih sedikit ↑" else "Lihat semua pasien →") {
                if (!opened.remove(stage)) opened += stage
                render()
            }
        }
        if (late.isNotEmpty()) {
            val f = card(8, 16, Hex.WARM_BG, null, 16)
            f.label("${late.size} pasien membutuhkan tindak lanjut", 16f, Weight.BOLD, Hex.WARM)
            f.label("Periksa waktu tunggu dan koordinasikan dengan unit terkait sebelum memindahkan pasien.", 13f, color = Hex.WARM)
            f.action("Lihat pasien", Btn.WHITE) { opened += "clinic"; render() }
        }
        footnote(flash ?: "Status diperbarui oleh masing-masing unit. Setiap perpindahan tercatat di riwayat aktivitas.")
    }

    // Farmasi (Figma 43:2 frame 22) ----------------------------------------------------------------

    private var rxStatus: Ops.Rx? = null
    private var rxQuery = ""
    private var rxPage = 1
    private var picked: Int? = null

    private fun LinearLayout.pharmacy() {
        heading("Farmasi", "Verifikasi resep, siapkan obat, dan kelola penyerahan kepada pasien.") { sample() }
        val statuses = Ops.Rx.entries.take(4)
        select("Filter status resep", listOf("Semua status") + statuses.map { it.short }, rxStatus?.let { statuses.indexOf(it) + 1 } ?: 0) {
            rxStatus = if (it == 0) null else statuses[it - 1]
            rxPage = 1
            render()
        }
        field("Cari nomor resep atau pasien", rxQuery) { rxQuery = it; rxPage = 1; rxRows() }
        box("Hari ini · ${Ops.shortDate(Ops.today())}")
        val all = Ops.todayOrders()
        val open = all.filter { it.queue.status == Ops.Status.PHARMACY }
        val count = { s: Set<Ops.Rx> -> open.count { it.status in s } }
        stat("Antrean resep", "${open.size}", "${count(setOf(Ops.Rx.RECEIVED))} verifikasi · ${count(setOf(Ops.Rx.PREPARING, Ops.Rx.CHECKING))} disiapkan · ${count(setOf(Ops.Rx.READY))} siap")
        stat("Sudah diserahkan", "${all.count { it.status == Ops.Rx.COMPLETED }}", "Resep selesai hari ini")
        stat("Rata-rata penyiapan", "${Ops.pharmacyAverage() ?: 0} mnt", "Target layanan ≤ 15 menit")

        val list = card()
        list.label("Daftar resep", 20f, Weight.BOLD)
        list.label("${open.size} resep aktif", 13f, color = Hex.MUTED)
        val side = LinearLayout(this@StaffActivity)
        rxRows = list.live {
            val rows = open.filter { o -> (rxStatus == null || o.status == rxStatus) && "${o.pickup} ${o.queue.number} ${o.queue.patient}".contains(rxQuery, ignoreCase = true) }
            val order = rows.firstOrNull { it.id == picked } ?: rows.firstOrNull()
            val (shown, current, pages) = rows.page(rxPage, 6)
            val t = table()
            t.cells(listOf("Nomor", "Pasien", "Masuk", "Status", "Aksi"), W5, header = true)
            shown.forEach { o ->
                val r = t.cells(listOf(o.pickup, o.queue.patient, Ops.clock(o.received) ?: "—", o.status.short, ""), W5, highlight = o === order)
                r.removeViewAt(r.childCount - 1)
                r.link(when (o.status) { Ops.Rx.RECEIVED -> "Verifikasi"; Ops.Rx.READY -> "Panggil"; else -> "Detail" }) { picked = o.id; rxRows() }.weight(W5.last())
            }
            if (rows.isEmpty()) label(if (open.isNotEmpty()) "Tidak ada resep yang cocok." else "Tidak ada resep yang sedang diproses.", 13f, color = Hex.MUTED)
            label(if (rows.isNotEmpty()) "Menampilkan ${(current - 1) * 6 + 1}–${minOf(current * 6, rows.size)} dari ${rows.size} resep aktif" else "Menampilkan 0 resep", 12f, color = Hex.MUTED)
            pager(current, pages) { rxPage = it; rxRows() }
            side.removeAllViews()
            side.prescription(order)
        }
        side.orientation = LinearLayout.VERTICAL
        addView(side)
        val guide = card(6, 16, Hex.WARM_BG, null, 16)
        guide.label("Sebelum menyerahkan obat", 16f, Weight.BOLD, Hex.WARM)
        guide.label("Cocokkan dua identitas pasien dan berikan penjelasan penggunaan sesuai resep.", 13f, color = Hex.WARM)
        guide.label("Penyerahan dicatat atas nama petugas.", 12f, color = Hex.WARM)
        footnote(flash ?: "Perubahan status resep tersimpan di riwayat aktivitas.")
    }

    private var rxRows: () -> Unit = {}

    private fun LinearLayout.prescription(order: Ops.Order?) {
        val c = card(8, 16)
        if (order == null) {
            c.eyebrow("RESEP DIPILIH")
            c.label("Belum ada resep aktif", 19f, Weight.BOLD)
            c.label("Resep baru muncul setelah pasien selesai di laboratorium.", 13f, color = Hex.MUTED)
            return
        }
        c.eyebrow("RESEP DIPILIH · ${order.status.short.uppercase()}")
        c.label(order.pickup, 34f, Weight.BOLD)
        c.label(order.queue.patient, 19f, Weight.SEMIBOLD)
        c.label(order.queue.clinic.name, 13f, color = Hex.MUTED)
        c.label("Masuk ${Ops.clock(order.received) ?: "—"}${order.by?.let { " · Ditangani $it" } ?: ""}", 13f, color = Hex.MUTED)
        val check = c.card(12, 14, Hex.PALE, null, 10)
        check.label(if (order.status == Ops.Rx.RECEIVED) "Resep menunggu verifikasi" else "Resep sudah diverifikasi", 14f, Weight.BOLD)
        check.label("Pastikan obat, label, dan identitas sesuai.", 12f, color = Hex.MUTED)
        val next = order.status.next
        c.action("Tandai ${next?.label?.lowercase().orEmpty()}", enabled = next != null) {
            act { val o = Ops.advance(order); "${o.pickup}: ${o.status.label}." }
        }
    }

    // Riwayat aktivitas (Figma 43:2 frame 23) ------------------------------------------------------

    private var unit = ""
    private var who = ""
    private var logQuery = ""
    private var logPage = 1
    private var logRows: () -> Unit = {}

    private fun LinearLayout.activity() {
        val history = Ops.history()
        val filtered = { history.filter { (unit.isEmpty() || it.unit == unit) && (who.isEmpty() || it.staff == who) && it.patient.contains(logQuery, ignoreCase = true) } }
        heading("Riwayat aktivitas", "Telusuri perubahan status dan tindakan petugas di seluruh layanan.") {
            action("Ekspor riwayat", enabled = filtered().isNotEmpty()) { exportHistory(filtered()) }.wrap()
        }
        box("Hari ini · ${Ops.shortDate(Ops.today())}")
        val units = history.map { it.unit }.distinct()
        val people = history.mapNotNull { it.staff }.distinct()
        select("Filter unit", listOf("Semua unit") + units, units.indexOf(unit) + 1) { unit = if (it == 0) "" else units[it - 1]; logPage = 1; render() }
        select("Filter petugas", listOf("Semua petugas") + people, people.indexOf(who) + 1) { who = if (it == 0) "" else people[it - 1]; logPage = 1; render() }
        field("Cari pasien atau nomor", logQuery) { logQuery = it; logPage = 1; logRows() }
        stat("Aktivitas hari ini", "${history.size}", "Seluruh tindakan tercatat")
        stat("Petugas aktif", "${people.size}", "Di ${units.size} unit pelayanan")
        stat("Pembaruan terakhir", history.firstOrNull()?.time ?: "—", "Data contoh · diperbarui langsung")
        val log = card()
        log.label("Catatan aktivitas", 20f, Weight.BOLD)
        log.label("Terbaru lebih dulu", 13f, color = Hex.MUTED)
        logRows = log.live {
            val rows = filtered()
            val (shown, current, pages) = rows.page(logPage, 8)
            shown.forEach { a ->
                val item = stack(2).apply { setPadding(0, dp(6), 0, dp(6)) }
                val top = item.row(8)
                top.label(a.time, 14f, Weight.BOLD).wrap()
                top.label(a.activity, 14f, Weight.SEMIBOLD).weight()
                top.label(a.result, 12f, Weight.SEMIBOLD, Hex.PRIMARY).wrap()
                item.label("${a.patient} · ${a.staff ?: "—"} · ${a.unit}", 12f, color = Hex.MUTED)
            }
            if (rows.isEmpty()) label(if (history.isNotEmpty()) "Tidak ada aktivitas yang cocok." else "Belum ada aktivitas hari ini.", 13f, color = Hex.MUTED)
            label(if (rows.isNotEmpty()) "Menampilkan ${(current - 1) * 8 + 1}–${minOf(current * 8, rows.size)} dari ${rows.size} aktivitas" else "Menampilkan 0 aktivitas", 12f, color = Hex.MUTED)
            pager(current, pages) { logPage = it; logRows() }
        }
        footnote("Jejak aktivitas tersimpan otomatis. Catatan tidak dapat diubah atau dihapus oleh petugas.")
    }

    private var pendingCsv: String? = null

    /** Same CSV as the web export (cells quoted, formulas neutralised), saved where the user picks. */
    private fun exportHistory(rows: List<Ops.Activity>) {
        val cell = { v: String -> "\"" + v.replace(Regex("^[=+\\-@]"), "'$0").replace("\"", "\"\"") + "\"" }
        val lines = listOf(listOf("Waktu", "Aktivitas", "Pasien / nomor", "Petugas", "Unit", "Hasil")) +
            rows.map { listOf(it.time.orEmpty(), it.activity, it.patient, it.staff ?: "", it.unit, it.result) }
        pendingCsv = "\uFEFF" + lines.joinToString("\r\n") { line -> line.joinToString(",") { cell(it) } } + "\r\n"
        saveCsv.launch("hytrack-riwayat-aktivitas.csv")
    }

    private val saveCsv = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val csv = pendingCsv ?: return@registerForActivityResult
        uri?.let { contentResolver.openOutputStream(it)?.use { out -> out.write(csv.toByteArray()) } }
        pendingCsv = null
    }

    // Daftar booking (CRUD) ------------------------------------------------------------------------

    private var bDate: LocalDate? = Ops.today()
    private var bClinic = 0
    private var bStatus: Ops.Status? = null
    private var bSearch = ""
    private var bTyped = ""
    private var opening: Int? = null
    private var cancelling = false
    private var reason = ""

    private fun LinearLayout.bookings() {
        heading(BOOKINGS, "Buat, cari, perbarui, dan batalkan booking pasien. Semua perubahan tersimpan di server.") { sample() }
        flash?.let { flag(it) }
        label("Tanggal layanan", 12f, Weight.SEMIBOLD, Hex.MUTED)
        val dates = Ops.bookingDates()
        box(bDate?.let { Ops.dateLabel(it) } ?: "Semua tanggal").setOnClickListener {
            val d = bDate ?: Ops.today()
            DatePickerDialog(this@StaffActivity, { _, y, m, day -> bDate = LocalDate.of(y, m + 1, day); render() }, d.year, d.monthValue - 1, d.dayOfMonth).show()
        }
        link("Semua tanggal") { bDate = null; render() }
        label("Poliklinik", 12f, Weight.SEMIBOLD, Hex.MUTED)
        select("Poliklinik", listOf("Semua poli") + Ops.clinics.map { it.name }, Ops.clinics.indexOfFirst { it.id == bClinic } + 1) {
            bClinic = if (it == 0) 0 else Ops.clinics[it - 1].id
            render()
        }
        label("Status", 12f, Weight.SEMIBOLD, Hex.MUTED)
        select("Status", listOf("Semua status") + Ops.Status.entries.map { it.label }, bStatus?.let { it.ordinal + 1 } ?: 0) {
            bStatus = if (it == 0) null else Ops.Status.entries[it - 1]
            render()
        }
        val find = row(10)
        find.field("Cari nomor antrean", bTyped) { bTyped = it }.weight().contentDescription = "Cari nomor antrean"
        find.action("Cari", Btn.SECONDARY) { bSearch = bTyped.trim(); render() }.wrap()

        val code = Regex("^HT-?(\\d{1,9})$", RegexOption.IGNORE_CASE).find(bSearch)?.groupValues?.get(1)?.toIntOrNull()
        val rows = Ops.queues.filter { q ->
            (bDate == null || q.date == bDate) && (bClinic == 0 || q.clinic.id == bClinic) && (bStatus == null || q.status == bStatus) &&
                (bSearch.isEmpty() || q.number.contains(bSearch, true) || q.patient.contains(bSearch, true) || q.id == code)
        }.sortedWith(compareBy({ it.date }, { it.clinic.id }, { it.doctor.id }, { it.id }))
        val list = card()
        rows.take(200).forEach { q ->
            val item = list.stack(4).apply { setPadding(0, dp(8), 0, dp(8)) }
            val top = item.row(8)
            top.label(q.number, 16f, Weight.BOLD).wrap()
            top.label(q.patient, 14f, Weight.SEMIBOLD).weight()
            top.pill(q.status)
            item.label("${q.clinic.name} · ${q.doctor.name}", 12f, color = Hex.MUTED)
            val bottom = item.row(8)
            bottom.label(Ops.dateLabel(q.date).substringAfter(", "), 12f, color = Hex.MUTED).weight()
            bottom.link("Detail") { opening = if (opening == q.id) null else q.id; cancelling = false; reason = ""; error = null; render() }.wrap()
            if (opening == q.id) item.detail(q)
        }
        if (rows.isEmpty()) list.label("Tidak ada booking yang cocok.", 13f, color = Hex.MUTED)
        list.label("Menampilkan ${minOf(rows.size, 200)} dari ${rows.size} booking", 12f, color = Hex.MUTED)
        list.label("Urut tanggal, poli, nomor", 12f, color = Hex.MUTED)

        val recent = card(8)
        recent.label("Booking terbaru", 18f, Weight.BOLD)
        recent.label("Termasuk booking untuk hari lain.", 13f, color = Hex.MUTED)
        Ops.queues.sortedByDescending { it.id }.take(5).forEach { q ->
            val r = recent.stack(2)
            r.label("${q.number} · ${q.patient}", 14f, Weight.SEMIBOLD)
            r.label("${q.clinic.name} · ${Ops.dateLabel(q.date)} · ${q.status.label}${Ops.stamp(q.bookedAt)?.let { " · dibuat $it" } ?: ""}", 12f, color = Hex.MUTED)
        }
        walkIn(dates)
    }

    private fun LinearLayout.pill(status: Ops.Status) = label(status.label, 11f, Weight.BOLD, when (status) {
        Ops.Status.BOOKED -> Hex.INFO_TEXT
        Ops.Status.NO_SHOW, Ops.Status.CANCELLED -> Hex.WARNING_TEXT
        Ops.Status.COMPLETED -> Hex.MUTED
        else -> Hex.PRIMARY
    }).also {
        it.wrap()
        it.background = it.shape(when (status) {
            Ops.Status.BOOKED -> Hex.INFO
            Ops.Status.NO_SHOW, Ops.Status.CANCELLED -> Hex.WARNING
            Ops.Status.COMPLETED -> Hex.DONE
            else -> Hex.PALE
        }, 12)
        it.setPadding(it.dp(10), it.dp(3), it.dp(10), it.dp(3))
    }

    private fun LinearLayout.detail(q: Ops.Queue) {
        val c = card(10, 16, Color.WHITE, Hex.PALE_BORDER)
        val head = c.row(8)
        val title = head.stack(2).weight()
        title.eyebrow("DETAIL BOOKING · ${q.code}")
        title.label(q.number, 34f, Weight.BOLD)
        head.link("Tutup") { opening = null; render() }.wrap()
        c.pill(q.status)
        fun pair(k: String, v: String) = c.stack(2).apply { label(k, 12f, color = Hex.MUTED); label(v, 14f, Weight.MEDIUM) }
        pair("Pasien", q.patient + (q.phone?.let { " · •••• ${it.takeLast(4)}" } ?: ""))
        pair("Poli · Dokter", "${q.clinic.name} · ${q.doctor.name}")
        pair("Tanggal", "${Ops.dateLabel(q.date)} · ${q.doctor.time}")
        pair("Dibuat", Ops.stamp(q.bookedAt)?.let { "$it · ${q.bookedBy ?: "Pasien (aplikasi)"}" } ?: "Data lama")
        pair("Kehadiran", when {
            q.checkIn != null -> "Hadir ${Ops.stamp(q.checkIn)}${q.checkInBy?.let { " · dikonfirmasi $it" } ?: ""}${if (q.checkInMethod == "code") " · Nomor check-in" else ""}"
            q.status == Ops.Status.NO_SHOW -> "Tidak hadir · ${Ops.stamp(q.noShowAt).orEmpty()}${q.noShowBy?.let { " oleh $it" } ?: ""}"
            q.status == Ops.Status.CANCELLED -> "—"
            else -> "Belum check-in · batas ${Ops.clock(q.deadline)}${if (q.checkInClosed) " (terlewati)" else ""}"
        })
        if (q.calledCount > 0) pair("Dipanggil", "${q.calledCount}×")
        q.cancelledAt?.let { pair("Dibatalkan", "${Ops.stamp(it)} · ${q.cancelledBy}${q.cancelReason?.let { r -> " · $r" } ?: ""}") }
        q.stages.forEachIndexed { i, s ->
            c.label("${i + 1}. ${s.kind.label} · ${s.status.label}${Ops.stamp(s.completed ?: s.started)?.let { " · $it" } ?: ""}${s.by?.let { " · $it" } ?: ""}", 12f, color = Hex.MUTED)
        }
        val isToday = q.date == Ops.today()
        val actions = c.stack(10)
        if (q.status == Ops.Status.BOOKED && isToday) actions.action("Konfirmasi hadir") {
            actions.confirm("Konfirmasi ${q.patient} (${q.number}) hadir? Cocokkan identitas pasien terlebih dahulu.") {
                act { "${Ops.checkIn(q).number} berhasil check-in pukul ${Ops.clock(q.checkIn)}." }
            }
        }
        if (q.status == Ops.Status.BOOKED) actions.action("Tandai tidak hadir", Btn.SECONDARY, q.checkInClosed) {
            actions.confirm("Tandai ${q.number} tidak hadir? Tidak dapat diulang.") { act { Ops.noShow(q); "${q.number} ditandai tidak hadir." } }
        }
        if (q.status == Ops.Status.WAITING && isToday) actions.action("Panggil") { act { "${Ops.call(q).number} dipanggil." } }
        if (q.status == Ops.Status.SERVING) {
            actions.action("Panggil ulang", Btn.SECONDARY) { act { Ops.recall(q); "${q.number} dipanggil ulang." } }
            actions.action("Selesai periksa, lanjut lab") { act { Ops.examined(q); "${q.number} selesai diperiksa, lanjut ke laboratorium." } }
            actions.action("Tidak hadir", Btn.SECONDARY, q.calledCount >= 2) {
                actions.confirm("Tandai ${q.number} tidak hadir setelah dipanggil?") { act { Ops.noShow(q); "${q.number} ditandai tidak hadir." } }
            }
        }
        if (q.status == Ops.Status.LABORATORY) actions.action("Selesai lab, lanjut ke farmasi") { act { Ops.laboratoryDone(q); "${q.number} lanjut ke farmasi." } }
        if (q.status == Ops.Status.BOOKED && !q.checkInClosed) {
            c.label("Status masih Belum check-in. Tidak hadir baru dapat dicatat setelah batas ${Ops.clock(q.deadline)}.", 12f, color = Hex.MUTED)
        }
        val cancellable = (q.status == Ops.Status.BOOKED || q.status == Ops.Status.WAITING) && (isToday || !q.checkInClosed)
        if (!cancellable) return
        if (!cancelling) {
            c.link("Batalkan booking…", Hex.DANGER) { cancelling = true; render() }
            return
        }
        c.label("Alasan pembatalan", 12f, Weight.SEMIBOLD, Hex.MUTED)
        lateinit var yes: android.widget.TextView
        c.field("Mis. pasien meminta jadwal ulang", reason, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE) {
            reason = it.take(255)
            yes.isEnabled = reason.trim().length >= 3
            yes.alpha = if (yes.isEnabled) 1f else 0.55f
        }.minLines = 2
        c.label("Booking tidak dihapus: alasan, waktu, dan nama Anda dicatat. Kuota dokter dibuka kembali.", 12f, color = Hex.MUTED)
        val split = c.row(10)
        yes = split.action("Ya, batalkan booking", Btn.DANGER, reason.trim().length >= 3) {
            act { Ops.cancel(q, reason); cancelling = false; "Booking ${q.number} dibatalkan. Kuota dokter dibuka kembali." }
        }.weight()
        split.action("Tidak jadi", Btn.SECONDARY) { cancelling = false; render() }.weight()
    }

    private var wPhone = ""
    private var wPatient: Local.Patient? = null
    private var wLookup = ""
    private var wDate = Ops.today()
    private var wClinic = 0
    private var wDoctor = 0
    private var wError: String? = null

    /** Create: a registered patient who arrived at the desk, under the same quota and numbering. */
    private fun LinearLayout.walkIn(dates: List<LocalDate>) {
        val c = card()
        c.label("Buat booking · pasien datang langsung", 18f, Weight.BOLD)
        c.label("Nomor ponsel pasien terdaftar", 12f, Weight.SEMIBOLD, Hex.MUTED)
        val find = c.row(10)
        lateinit var go: android.widget.TextView
        find.field("0812-3456-7890", wPhone, InputType.TYPE_CLASS_PHONE) {
            wPhone = it
            go.isEnabled = it.trim().length >= 8
            go.alpha = if (go.isEnabled) 1f else 0.55f
        }.weight()
        go = find.action("Cari pasien", Btn.SECONDARY, wPhone.trim().length >= 8) {
            wPatient = Local.patientByPhone(wPhone)
            wLookup = if (wPatient == null) "Nomor ponsel belum terdaftar sebagai akun pasien." else ""
            wError = null
            render()
        }.wrap()
        if (wLookup.isNotEmpty()) c.label(wLookup, 12f, color = Hex.MUTED)
        val patient = wPatient ?: return
        c.flag("${patient.name} · ${patient.masked}${patient.active?.let { " · sudah punya kunjungan aktif $it" } ?: ""}", if (patient.active != null) Tone.WARNING else Tone.GREEN)
        c.label("Tanggal kunjungan", 12f, Weight.SEMIBOLD, Hex.MUTED)
        c.box(Ops.dateLabel(wDate)).setOnClickListener {
            DatePickerDialog(this@StaffActivity, { _, y, m, d -> wDate = LocalDate.of(y, m + 1, d); render() }, wDate.year, wDate.monthValue - 1, wDate.dayOfMonth).apply {
                datePicker.minDate = dates.first().toEpochDay() * 86_400_000
                datePicker.maxDate = dates.last().toEpochDay() * 86_400_000
            }.show()
        }
        val clinic = Ops.clinics.firstOrNull { it.id == wClinic } ?: Ops.clinics.first()
        val doctors = Ops.doctors.filter { it.clinic === clinic }
        val doctor = doctors.firstOrNull { it.id == wDoctor } ?: doctors.firstOrNull()
        c.label("Poliklinik", 12f, Weight.SEMIBOLD, Hex.MUTED)
        c.select("Poliklinik", Ops.clinics.map { it.name }, Ops.clinics.indexOf(clinic)) { wClinic = Ops.clinics[it].id; wDoctor = 0; render() }
        c.label("Dokter", 12f, Weight.SEMIBOLD, Hex.MUTED)
        c.select("Dokter", doctors.map { "${it.name} · ${it.time} · sisa ${Ops.quota(it, wDate)}" }, doctors.indexOf(doctor), doctors.isNotEmpty()) { wDoctor = doctors[it].id; render() }
        val quota = doctor?.let { Ops.quota(it, wDate) }
        c.label("Sisa kuota: ${quota?.let { "$it pasien" } ?: "—"}", 13f)
        if (wDate !in dates) c.flag("Tanggal ini di luar masa booking.", Tone.WARNING)
        wError?.let { c.flag(it, Tone.WARNING) }
        c.action(if (quota != null && quota < 1) "Kuota penuh" else "Ambil antrean", enabled = doctor != null && (quota ?: 0) > 0 && wDate in dates) {
            try {
                val q = Ops.book(patient, doctor!!, wDate)
                flash = "Booking ${q.number} dibuat untuk ${patient.name} · ${Ops.dateLabel(q.date)}."
                wPatient = null
                wPhone = ""
                wError = null
            } catch (e: Ops.Refused) {
                wError = e.message
            }
            render()
        }
    }

    // Data poliklinik (CRUD) -----------------------------------------------------------------------

    private var editing: Ops.Clinic? = null
    private var formName = ""
    private var formRoom = ""
    private var formError: String? = null

    private fun LinearLayout.clinicAdmin() {
        heading(CLINICS, "Tambah, ubah, dan hapus data poliklinik yang dipilih pasien saat booking.") { sample() }
        flash?.let { flag(it) }
        val list = card()
        Ops.clinics.sortedBy { it.name }.forEach { c ->
            val item = list.stack(4).apply { setPadding(0, dp(6), 0, dp(6)) }
            item.label(c.name, 16f, Weight.BOLD)
            item.label(c.room ?: "Belum diatur", 13f, color = Hex.MUTED)
            item.label("Dokter ${Ops.doctorsOf(c)} · Antrean ${Ops.queuesOf(c)}", 12f, color = Hex.MUTED)
            val r = item.row(16)
            r.link("Ubah") { editing = c; formName = c.name; formRoom = c.room.orEmpty(); formError = null; render() }.wrap()
            val used = Ops.doctorsOf(c) > 0 || Ops.queuesOf(c) > 0
            r.link("Hapus", Hex.DANGER, !used) {
                r.confirm("Hapus poliklinik ${c.name}? Tindakan ini tidak dapat dibatalkan.") { act { Ops.deleteClinic(c); "Poliklinik ${c.name} dihapus." } }
            }.wrap().contentDescription = if (used) "Masih dipakai dokter atau antrean" else "Hapus ${c.name}"
        }
        list.label("${Ops.clinics.size} poliklinik", 12f, color = Hex.MUTED)
        list.label("Poliklinik yang masih dipakai dokter atau antrean tidak dapat dihapus.", 12f, color = Hex.MUTED)

        val form = card()
        val current = editing
        form.label(current?.let { "Ubah ${it.name}" } ?: "Tambah poliklinik", 18f, Weight.BOLD)
        form.label("Nama poliklinik", 12f, Weight.SEMIBOLD, Hex.MUTED)
        form.field("Contoh: Mata", formName) { formName = it }
        form.label("Lokasi ruangan (opsional)", 12f, Weight.SEMIBOLD, Hex.MUTED)
        form.field("Ruang B-201 · Gedung B, lantai 2", formRoom) { formRoom = it }
        formError?.let { form.flag(it, Tone.WARNING) }
        val r = form.row(10)
        r.action(if (current != null) "Simpan perubahan" else "Tambah poliklinik") {
            try {
                val saved = Ops.saveClinic(current, formName, formRoom)
                flash = if (current != null) "Poliklinik ${saved.name} diperbarui." else "Poliklinik ${saved.name} ditambahkan."
                editing = null
                formName = ""
                formRoom = ""
                formError = null
            } catch (e: Ops.Refused) {
                formError = e.message
            }
            render()
        }.weight()
        if (current != null) r.action("Batal", Btn.SECONDARY) { editing = null; formName = ""; formRoom = ""; formError = null; render() }.weight()
    }

    companion object {
        const val SCAN = "Scan QR kedatangan"
        const val BOOKINGS = "Daftar booking"
        const val CLINICS = "Data poliklinik"
        private val W5 = listOf(1.1f, 1.8f, 1f, 1.3f, 1f)
    }
}

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
