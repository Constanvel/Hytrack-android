package com.constanvel.hytracl

import android.graphics.Color
import android.os.Bundle
import android.print.PrintAttributes
import android.print.PrintManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Management dashboard (web features/operations/management.tsx) on GET /api/manajemen: the same
 * ServiceInsights aggregates as the web, never patient identities. The CSV is /api/manajemen/ekspor.
 */
class ManagementActivity : DashboardActivity() {
    override val role = Local.Role.MANAGER
    override val sections = listOf("Ringkasan layanan", "Waktu tunggu", "Kinerja poliklinik", "Laporan")
    override fun crumb(section: String) = "Analitik / $section"

    private class Clinic(val id: Int, val name: String)
    private class Hour(val hour: String, val wait: Int)
    private class StageReport(val label: String, val minutes: Int?, val hourly: List<Hour>)
    private class ClinicReport(val clinic: String, val complete: Int, val wait: Int?)
    private class Report(
        val registered: Int, val completed: Int, val active: Int, val notArrived: Int,
        val averageWait: Int?, val withinTarget: Int?, val onTime: Int?, val updatedAt: String?,
        val clinics: List<ClinicReport>, val stages: List<StageReport>,
    ) {
        val hourly get() = stages[0].hourly
    }

    private var data: JSONObject? = null
    private var loadError: String? = null
    private var date = Ops.today()
    private var clinicId = 0
    /** Today plus the most recent service dates that have tickets (ManagementController::index). */
    private val dates get() = data?.list("dates")?.map { LocalDate.parse(it.getString("value")) } ?: listOf(Ops.today())
    private val clinics get() = data?.list("clinics")?.map { Clinic(it.optInt("id"), it.optString("name")) }.orEmpty()
    private val clinic get() = clinics.firstOrNull { it.id == clinicId }
    private var report = blank()
    private var target = 30
    private val empty get() = report.registered == 0
    private val todayWord get() = if (date == dates.first()) "hari ini" else "pada tanggal ini"

    override fun status() = report.updatedAt?.let { "Pembaruan terakhir $it WIB" } ?: "Belum ada pembaruan"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isFinishing) load()
    }

    private fun filter() = Api.query("tanggal" to date, "poli" to clinicId.takeIf { it != 0 })

    private fun load() {
        request({ Api.call("GET", "manajemen" + filter()) }) { result ->
            result.onSuccess {
                data = it
                loadError = null
                date = LocalDate.parse(it.getString("date"))
                clinicId = it.int("clinicId") ?: 0
                read(it.getJSONObject("report"))
            }.onFailure { e ->
                if ((e as? Api.Failure)?.status == 401) return@request signedOut()
                loadError = e.message ?: Api.OFFLINE
            }
            render()
        }
    }

    /** ServiceInsights::report as the Ops report the screens already draw. */
    private fun read(r: JSONObject) {
        target = r.optInt("target", 30)
        fun hours(list: List<JSONObject>) = list.map { Hour(it.getString("hour"), it.optInt("wait")) }
        report = Report(
            registered = r.optInt("registered"), completed = r.optInt("completed"), active = r.optInt("active"), notArrived = r.optInt("notArrived"),
            averageWait = r.int("averageWait"), withinTarget = r.int("withinTarget"), onTime = r.int("onTime"), updatedAt = r.str("updatedAt"),
            clinics = r.list("clinics").map { ClinicReport(it.optString("clinic"), it.optInt("complete"), it.int("wait")) },
            stages = r.list("stages").map { StageReport(it.optString("label"), it.int("minutes"), hours(it.list("hourly"))) },
        )
    }

    private fun blank() = Report(0, 0, 0, 0, null, null, null, null, emptyList(),
        listOf("Check-in → dokter", "Dokter → laboratorium", "Resep → obat siap").map { StageReport(it, null, emptyList()) })

    override fun content(into: LinearLayout, section: String) = if (data == null) into.waiting() else when (section) {
        "Waktu tunggu" -> into.waits()
        "Kinerja poliklinik" -> into.performance()
        "Laporan" -> into.reports()
        else -> into.summary()
    }

    private fun LinearLayout.waiting() {
        val c = card(10, 18)
        c.label(if (loadError == null) "Memuat analitik layanan…" else "Data belum dapat dimuat", 18f, Weight.BOLD)
        c.label(loadError ?: "Mengambil data agregat dari server HyTrack.", 13f, color = Hex.MUTED)
        if (loadError != null) c.action("Coba lagi") { loadError = null; render(); load() }
    }

    private fun minutes(value: Int?) = value?.let { "$it mnt" } ?: if (empty) "0 mnt" else "—"
    private fun percent(value: Int?) = value?.let { "$it%" } ?: if (empty) "0%" else "—"
    private fun nextHour(hour: String) = "%02d.00".format(Locale.ROOT, hour.take(2).toInt() + 1)
    private fun peakOf(hourly: List<Hour>) = hourly.maxByOrNull { it.wait }?.let { top -> hourly.first { it.wait == top.wait } }
    private fun gap(wait: Int) = when {
        wait > target -> "+${wait - target} mnt"
        wait < target -> "−${target - wait} mnt"
        else -> "0 mnt"
    }
    private fun names(list: List<String>) = if (list.size < 2) list.firstOrNull().orEmpty() else list.dropLast(1).joinToString(", ") + " dan " + list.last()

    private fun LinearLayout.pill() = sample(if (empty) "Belum ada data" else "Data contoh")

    private fun dateLabel(d: LocalDate) = Ops.shortDate(d)

    /** Tanggal laporan, Filter poliklinik, the section's own select, then export. */
    private fun LinearLayout.filters(flat: Boolean, extra: (LinearLayout.() -> Unit)? = null) {
        val list = dates
        select("Tanggal laporan", list.mapIndexed { i, d -> if (i == 0) "Hari ini · ${dateLabel(d)}" else dateLabel(d) }, list.indexOf(date)) {
            date = list[it]
            load()
        }
        val clinics = clinics
        select("Filter poliklinik", listOf("Semua poliklinik") + clinics.map { it.name }, clinics.indexOfFirst { it.id == clinicId } + 1) {
            clinicId = if (it == 0) 0 else clinics[it - 1].id
            load()
        }
        extra?.invoke(this)
        action(if (flat) "Ekspor data" else "↓  Ekspor laporan", if (flat) Btn.SECONDARY else Btn.PRIMARY, !empty) { exportCsv() }
    }

    // Ringkasan layanan (Figma 43:2 frames 12 / 27) ------------------------------------------------

    private fun LinearLayout.summary() {
        val r = report
        heading("Ringkasan layanan", "Dari kedatangan hingga obat: lihat di mana pasien paling lama menunggu.") { pill() }
        filters(flat = false)
        val over = r.averageWait?.let { it - target }
        stat("Pasien terdaftar", "${r.registered}", "${r.completed} selesai · ${r.active} aktif · ${r.notArrived} belum tiba", Hex.MINT, stacked = true)
        stat("Rata-rata tunggu", minutes(r.averageWait), when {
            over == null -> "Belum ada data waktu tunggu"
            over > 0 -> "$over menit di atas target ${target} menit"
            else -> "Sesuai target ${target} menit"
        }, valueColor = if (over != null && over > 0) Hex.WARM else Hex.TEXT, stacked = true)
        stat("Sesuai target tunggu", percent(r.withinTarget), if (r.withinTarget == null) "Belum dinilai · target ≥85%" else "Target layanan: minimal 85%", Hex.SLATE, stacked = true)
        stat("Check-in tepat waktu", percent(r.onTime), if (r.onTime == null) "Belum ada pasien check-in" else "Dari pasien yang sudah tiba", Color.parseColor("#8AA16C"), Hex.PRIMARY, stacked = true)
        chart(r.hourly, "Check-in → dokter")
        attention(r, listOf("Belum ada pasien", "${r.registered} pasien", "Belum ada aktivitas layanan pada periode yang dipilih.",
            "Ringkasan akan diperbarui setelah pendaftaran dan pelayanan tercatat."))

        val panel = card()
        panel.label("Kinerja poliklinik", 18f, Weight.BOLD)
        val w = listOf(1.6f, 0.9f, 1.4f, 1f)
        val t = panel.table(400)
        t.cells(listOf("Poliklinik", "Selesai", "Rata-rata tunggu", "Target"), w, header = true)
        r.clinics.forEach { row ->
            val cells = t.cells(listOf(row.clinic, "${row.complete}", row.wait?.let { "$it menit" } ?: "—", row.wait?.let { if (it > target) "Di atas" else "Sesuai" } ?: "—"), w)
            (cells.getChildAt(3) as android.widget.TextView).setTextColor(if ((row.wait ?: 0) > target) Hex.WARNING_TEXT else Hex.PRIMARY)
        }
        if (r.clinics.isEmpty()) panel.emptyState("Belum ada data poliklinik", "Data muncul setelah ada aktivitas pelayanan.")

        val stages = card()
        stages.label("Tahap pelayanan", 18f, Weight.BOLD)
        r.stages.forEach { s ->
            val line = stages.row(8)
            line.label(s.label, 14f, color = Hex.MUTED).weight()
            line.label(minutes(s.minutes), 18f, Weight.BOLD).wrap()
        }
        stages.label(if (empty) "Belum ada data pada setiap tahap pelayanan." else "Rata-rata tunggu antar tahap, bukan durasi tindakan medis.", 11f, color = Hex.MUTED)
        footnote("Definisi: waktu tunggu dokter dihitung sejak check-in hingga mulai dilayani. Tampilan manajemen tidak memuat identitas pasien.")
    }

    private fun LinearLayout.emptyState(title: String, text: String) = stack(4, 12).apply {
        label(title, 14f, Weight.BOLD)
        label(text, 12f, color = Hex.MUTED)
    }

    /** "Waktu tunggu per jam" (frames 12, 24, 27, 28): 07.00–15.00 plus any hour with data. */
    private fun LinearLayout.chart(hourly: List<Hour>, label: String) {
        val panel = card(10)
        val head = panel.row(12, Gravity.TOP)
        head.label("Waktu tunggu per jam", 16f, Weight.BOLD).weight()
        head.label("Menit · $label", 11f, color = Hex.MUTED).wrap()
        val hours = (HOURS + hourly.map { it.hour }).distinct().sorted()
        val wait = hourly.associate { it.hour to it.wait }
        val plot = FrameLayout(context)
        panel.addView(plot, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(196)))
        // Axis: 60, 30 and 0 minutes over 156dp (2.6dp a minute), and the 30-minute target line.
        listOf(60, 30, 0).forEach { v ->
            plot.addView(android.widget.TextView(context).apply {
                text = "$v"
                textSize = 9f
                setTextColor(Hex.MUTED)
            }, FrameLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(156 - v * 2.6f) })
        }
        plot.addView(View(context).apply { setBackgroundColor(Hex.WARNING_TEXT); alpha = 0.6f },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { marginStart = dp(26); topMargin = dp(156 - 30 * 2.6f + 6) })
        val bars = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        plot.addView(bars, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { marginStart = dp(26) })
        hours.forEach { hour ->
            val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
            bars.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            wait[hour]?.let { v ->
                col.label("$v", 11f, Weight.SEMIBOLD).apply { gravity = Gravity.CENTER }
                col.addView(View(context).apply { background = shape(if (v > target) Hex.ABOVE else Hex.PRIMARY, 4) },
                    LinearLayout.LayoutParams(dp(18), dp(minOf(v, 60) * 2.6f)).apply { topMargin = dp(4) })
            }
            col.label(hour, 8f, color = Hex.MUTED).apply { gravity = Gravity.CENTER; setPadding(0, dp(8), 0, 0) }
        }
        if (hourly.isEmpty()) panel.emptyState("Belum ada data waktu tunggu", "Grafik akan terisi setelah pasien mulai dilayani.")
        val legend = panel.row(10)
        legend.label("■ ≤${target} menit", 9f, color = Hex.PRIMARY).weight()
        legend.label("■ >${target} menit", 9f, color = Hex.ABOVE).weight()
        legend.label("— Target ${target} menit", 9f, color = Hex.WARNING_TEXT).weight()
        if (hourly.isNotEmpty()) {
            panel.label("Data grafik", 12f, Weight.SEMIBOLD, Hex.MUTED)
            panel.cells(listOf("Jam", "Menit"), listOf(1f, 1f), header = true)
            hourly.forEach { panel.cells(listOf(it.hour, "${it.wait}"), listOf(1f, 1f)) }
        }
    }

    private fun LinearLayout.attention(r: Report, idle: List<String>) {
        val slowest = r.clinics.firstOrNull { it.wait != null }
        val peak = peakOf(r.hourly)
        if (slowest?.wait == null) {
            val c = card(6, 20)
            c.eyebrow("MENUNGGU DATA LAYANAN")
            c.label(idle[0], 18f, Weight.BOLD)
            c.label(idle[1], 32f, Weight.BOLD)
            c.label(idle[2], 13f, color = Hex.MUTED)
            c.rule()
            c.label(idle[3], 12f, color = Hex.MUTED)
            return
        }
        val wait = slowest.wait
        val above = wait > target
        val hours = peak?.let { " pada jam ${it.hour}–${nextHour(it.hour)}" }.orEmpty()
        val c = card(6, 20, Hex.WARNING, Color.parseColor("#ECDBB7"))
        c.eyebrow(if (above) "PERLU PERHATIAN" else "TUNGGU TERLAMA", Hex.WARNING_TEXT)
        c.label(slowest.clinic, 18f, Weight.BOLD, Hex.WARNING_TEXT)
        c.label("$wait menit", 32f, Weight.BOLD, Hex.WARNING_TEXT)
        c.label("Rata-rata tunggu tertinggi $todayWord.\n" + if (above) "${wait - target} menit di atas target." else "Masih sesuai target ${target} menit.", 13f, color = Hex.WARNING_TEXT)
        c.rule()
        c.label(if (above) "Tinjau kapasitas layanan$hours sebelum menambah kuota." else "Pertahankan kapasitas layanan$hours.", 12f, color = Hex.WARNING_TEXT)
    }

    // Waktu tunggu (Figma 43:2 frames 24 / 28) ------------------------------------------------------

    private var stageIndex = 0

    private fun LinearLayout.waits() {
        val r = report
        val stage = r.stages[stageIndex]
        val peak = peakOf(stage.hourly)
        val over = stage.minutes?.let { it - target }
        val firstPeak = peakOf(r.stages[0].hourly)
        heading("Waktu tunggu", "Telusuri jam sibuk dan waktu tunggu pada setiap tahap pelayanan.") { pill() }
        filters(flat = true) {
            select("Tahap pelayanan", r.stages.map { it.label }, stageIndex) { stageIndex = it; render() }
        }
        stat("Rata-rata tunggu", minutes(stage.minutes), when {
            over == null -> "Belum ada data waktu tunggu"
            stageIndex > 0 -> stage.label
            over > 0 -> "$over menit di atas target ${target} menit"
            else -> "Sesuai target ${target} menit"
        }, valueColor = if (stageIndex == 0 && over != null && over > 0) Hex.WARM else Hex.TEXT, stacked = true)
        stat("Sesuai target tunggu", percent(r.withinTarget), if (r.withinTarget == null) "Belum dinilai · target ≥85%" else "Target layanan minimal 85%", stacked = true)
        stat("Jam tunggu tertinggi", peak?.hour ?: "—", peak?.let { "Rata-rata tunggu ${it.wait} menit" } ?: "Belum ada jam layanan tercatat", stacked = true)
        stat("Pasien selesai", "${r.completed}", "Dari ${r.registered} pasien terdaftar", stacked = true)
        chart(stage.hourly, stage.label)
        attention(r, listOf("Belum ada antrean", "0 menit", "Waktu tunggu belum dapat dihitung karena belum ada pelayanan.",
            "Analisis jam sibuk tersedia setelah aktivitas pelayanan tercatat."))
        r.stages.forEachIndexed { i, item ->
            val warm = i == 0 && item.minutes != null && item.minutes > target
            val (kind, note, idle) = STAGE_NOTES[i]
            val c = if (warm) card(4, 16, Hex.WARM_BG, null, 16) else if (i > 0) card(4, 16, Hex.PALE, null, 16) else card(4, 16)
            val tone = if (warm) Hex.WARM else Hex.TEXT
            c.label("0${i + 1} · ${item.label}", 14f, Weight.BOLD, tone)
            c.label(minutes(item.minutes), 28f, Weight.BOLD, tone)
            c.label(if (i == 0) "Target ≤${target} menit" else kind, 12f, Weight.SEMIBOLD, if (warm) Hex.WARM else Hex.MUTED)
            c.label(when {
                item.minutes == null -> idle
                i == 0 -> if (warm && firstPeak != null) "Prioritaskan peninjauan kapasitas pada jam ${firstPeak.hour}–${nextHour(firstPeak.hour)}." else "Waktu tunggu dokter masih sesuai target."
                else -> note
            }, 13f, color = if (warm) Hex.WARM else Hex.MUTED)
        }
        footnote("Waktu tunggu antar tahap, bukan durasi tindakan medis. Data agregat tidak memuat identitas pasien.")
    }

    // Kinerja poliklinik (Figma 43:2 frames 25 / 29) -------------------------------------------------

    private var sort = 0
    private var focus: String? = null

    private fun LinearLayout.performance() {
        val r = report
        heading("Kinerja poliklinik", "Bandingkan volume layanan dan pencapaian waktu tunggu setiap poliklinik.") { pill() }
        filters(flat = true) {
            select("Urutan poliklinik", SORTS, sort) { sort = it; render() }
        }
        val rows = when (sort) {
            2 -> r.clinics.sortedWith { a, b -> java.text.Collator.getInstance(Locale.forLanguageTag("id")).compare(a.clinic, b.clinic) }
            1 -> r.clinics.sortedByDescending { it.complete }
            else -> r.clinics.sortedByDescending { it.wait ?: -1 }
        }
        val above = r.clinics.filter { (it.wait ?: 0) > target }
        val share = { row: ClinicReport -> if (r.completed > 0) Math.round(row.complete * 100.0 / r.completed).toInt() else 0 }
        val focused = rows.firstOrNull { it.clinic == focus } ?: rows.firstOrNull()
        val slowest = r.clinics.firstOrNull { it.wait != null }
        val peak = peakOf(r.hourly)
        stat(if (empty) "Poliklinik dengan layanan" else "Poliklinik dipantau", "${r.clinics.size}",
            if (empty) "Belum ada aktivitas poliklinik" else if (clinicId != 0) "Poliklinik terpilih" else "Seluruh poliklinik rawat jalan", stacked = true)
        stat("Pasien selesai", "${r.completed}", "Total layanan selesai $todayWord", stacked = true)
        stat("Di atas target tunggu", "${above.size} poli",
            if (empty) "Belum dapat dievaluasi" else if (above.isNotEmpty()) names(above.map { it.clinic }) else "Seluruh poliklinik sesuai target",
            valueColor = if (above.isNotEmpty()) Hex.WARM else Hex.TEXT, stacked = true)
        stat("Rata-rata tunggu", minutes(r.averageWait), r.averageWait?.let { "Target maksimal ${target} menit" } ?: "Belum ada data · target ≤${target} menit", stacked = true)

        val panel = card()
        val head = panel.row(12, Gravity.TOP)
        head.label("Perbandingan poliklinik", 18f, Weight.BOLD).weight()
        head.label("Target waktu tunggu ≤${target} menit", 11f, color = Hex.MUTED).wrap()
        rows.forEach { row ->
            val late = (row.wait ?: 0) > target
            val tone = if (late) Hex.WARNING_TEXT else Hex.PRIMARY
            val item = panel.stack(6, 12).apply { if (row === focused) background = shape(Hex.PALE, 12) }
            val top = item.row(8)
            top.label(row.clinic, 15f, Weight.BOLD).weight()
            top.label("${row.complete} selesai", 13f, color = Hex.MUTED).wrap()
            val bar = item.row(8)
            val track = FrameLayout(context).apply { background = shape(Hex.RULE, 4) }
            bar.addView(track, LinearLayout.LayoutParams(0, dp(8), 1f))
            track.addView(View(context).apply { background = shape(Hex.PRIMARY, 4) },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            track.post { track.getChildAt(0).layoutParams.width = track.width * share(row) / 100; track.getChildAt(0).requestLayout() }
            bar.label("${share(row)}%", 11f, color = Hex.MUTED).wrap()
            val facts = item.row(8)
            facts.label(row.wait?.let { "$it mnt" } ?: "—", 13f).weight()
            facts.label(row.wait?.let(::gap) ?: "—", 13f, Weight.SEMIBOLD, tone).weight()
            facts.label(row.wait?.let { if (late) "Di atas" else "Sesuai" } ?: "—", 13f, Weight.SEMIBOLD, tone).weight()
            facts.link("Detail") { focus = row.clinic; render() }.wrap().contentDescription = "Detail ${row.clinic}"
        }
        if (rows.isEmpty()) panel.emptyState("Belum ada data poliklinik", "Belum ada pasien yang dilayani pada periode ini.\nData perbandingan akan muncul setelah pelayanan tercatat.")
        panel.label(if (r.completed > 0) "Porsi dihitung dari ${r.completed} layanan selesai · tidak termasuk pasien aktif atau belum tiba." else "Belum ada layanan selesai pada periode yang dipilih.", 11f, color = Hex.MUTED)

        val f = card(6, 16, Color.WHITE, Hex.CARD_BORDER, 16)
        f.eyebrow(focused?.let { "FOKUS · ${it.clinic.uppercase()}" } ?: "RINGKASAN LAYANAN")
        f.label("${focused?.complete ?: 0} pasien selesai", 22f, Weight.BOLD)
        f.label(focused?.let { "${share(it)}% dari seluruh layanan selesai $todayWord." } ?: "Belum ada kontribusi layanan untuk dihitung.", 13f, color = Hex.MUTED)
        val sw = slowest?.wait
        if (sw != null && sw > target) {
            val w = card(6, 16, Hex.WARM_BG, null, 16)
            w.label("Prioritas evaluasi kapasitas", 16f, Weight.BOLD, Hex.WARM)
            w.label("${slowest.clinic} memiliki rata-rata tunggu $sw menit. Tinjau kapasitas layanan${peak?.let { " pada jam ${it.hour}–${nextHour(it.hour)}" }.orEmpty()} sebelum menambah kuota.", 13f, color = Hex.WARM)
        } else {
            val ok = card(6, 16, Color.WHITE, Hex.CARD_BORDER, 16)
            ok.label(if (slowest != null) "Seluruh poliklinik sesuai target" else "Evaluasi menunggu data", 16f, Weight.BOLD)
            ok.label(if (slowest != null) "Rata-rata tunggu tertinggi ${slowest.clinic} ${slowest.wait} menit, masih dalam target ${target} menit."
                else "Perbandingan dan rekomendasi akan tersedia setelah ada aktivitas pelayanan. Belum ada poliklinik yang perlu diprioritaskan.", 13f, color = Hex.MUTED)
        }
        footnote("Perbandingan menggunakan periode yang sama. Data agregat tidak memuat identitas pasien.")
    }

    // Laporan (Figma 43:2 frames 26 / 30) -----------------------------------------------------------

    private class Download(val kind: Int, val date: LocalDate, val clinic: Clinic?, val format: String, val at: String)

    private var kind = 0
    private var format = "PDF"
    /** On this device across restarts, as the web keeps it in the browser (localStorage). */
    private val store by lazy { getSharedPreferences("laporan", MODE_PRIVATE) }
    private val downloads by lazy {
        store.getString("downloads", null).orEmpty().lines().mapNotNull { line ->
            val p = line.split('|')
            if (p.size != 5) return@mapNotNull null
            runCatching { Download(p[0].toInt(), LocalDate.parse(p[1]), p[2].toInt().takeIf { it != 0 }?.let { id -> Clinic(id, "") }, p[3], p[4]) }.getOrNull()
        }.toMutableList()
    }

    private fun LinearLayout.reports() {
        val r = report
        heading("Laporan", "Susun laporan agregat untuk evaluasi dan dokumentasi layanan.") { pill() }
        val form = card(10)
        form.label("Buat laporan", 18f, Weight.BOLD)
        form.label("Pilih cakupan data yang ingin diunduh.", 13f, color = Hex.MUTED)
        form.label("Jenis laporan", 12f, Weight.SEMIBOLD, Hex.MUTED)
        form.select("Jenis laporan", KINDS.map { it.first }, kind) { kind = it; render() }
        form.label("Periode", 12f, Weight.SEMIBOLD, Hex.MUTED)
        val list = dates
        form.select("Periode", list.map { "${dateLabel(it)} – ${dateLabel(it)}" }, list.indexOf(date)) { date = list[it]; load() }
        form.label("Poliklinik", 12f, Weight.SEMIBOLD, Hex.MUTED)
        val clinics = clinics
        form.select("Poliklinik", listOf("Semua poliklinik") + clinics.map { it.name }, clinics.indexOfFirst { it.id == clinicId } + 1) {
            clinicId = if (it == 0) 0 else clinics[it - 1].id
            load()
        }
        form.label("Format unduhan", 12f, Weight.SEMIBOLD, Hex.MUTED)
        val formats = form.row(10)
        listOf("PDF", "CSV").forEach { f ->
            formats.action(if (format == f) "✓ $f" else f, if (format == f) Btn.PRIMARY else Btn.WHITE) { format = f; render() }.weight()
                .also { it.isSelected = format == f }
        }
        val privacy = form.card(4, 12, Hex.PALE, null, 12)
        privacy.label("Hanya data agregat", 13f, Weight.BOLD)
        privacy.label("Laporan tidak memuat nama, nomor rekam medis, atau identitas pasien.", 12f, color = Hex.MUTED)
        form.action("Unduh laporan $format", enabled = !empty) {
            record()
            if (format == "CSV") exportCsv() else printPreview()
        }
        form.label(r.updatedAt?.let { "Data terakhir diperbarui pukul $it WIB." } ?: "Unduhan tersedia setelah ada data layanan.", 11f, color = Hex.MUTED)

        val preview = card(10)
        preview.label("Pratinjau · ${KINDS[kind].first}", 18f, Weight.BOLD)
        preview.label("${longDate(date)} · ${clinic?.name ?: "Semua poliklinik"}", 13f, color = Hex.MUTED)
        listOf("Terdaftar" to "${r.registered}", "Selesai" to "${r.completed}", "Rata-rata tunggu" to minutes(r.averageWait)).forEach { (k, v) ->
            val b = preview.card(2, 12, Hex.PALE, null, 12)
            b.label(k, 12f, color = Hex.MUTED)
            b.label(v, 22f, Weight.BOLD)
        }
        val w = listOf(1.6f, 1f, 1f, 1f)
        val t = preview.table(380)
        t.cells(listOf("Poliklinik", "Selesai", "Tunggu", "Target"), w, header = true)
        r.clinics.forEach { row ->
            t.cells(listOf(row.clinic, "${row.complete}", row.wait?.let { "$it mnt" } ?: "—", row.wait?.let { if (it > target) "Di atas" else "Sesuai" } ?: "—"), w)
        }
        if (r.clinics.isEmpty()) preview.emptyState("Belum ada data laporan", "Tidak ada aktivitas layanan pada periode ini.\nCoba pilih periode lain atau tunggu pelayanan tercatat.")
        preview.label(if (r.withinTarget == null && r.onTime == null) "Belum ada data · pencapaian target belum dapat dinilai."
            else "Sesuai target tunggu ${percent(r.withinTarget)} · Check-in tepat waktu ${percent(r.onTime)}", 11f, color = Hex.MUTED)

        val history = card(10)
        history.label("Riwayat unduhan", 18f, Weight.BOLD)
        val hw = listOf(2f, 0.8f, 1.3f, 0.9f)
        val t2 = history.table(440)
        t2.cells(listOf("Laporan / periode", "Format", "Dibuat", "Aksi"), hw, header = true)
        downloads.take(3).forEach { d ->
            val row = t2.cells(listOf("${KINDS[d.kind].second} · ${dateLabel(d.date)}", d.format, d.at, ""), hw)
            row.removeViewAt(row.childCount - 1)
            row.link("Unduh") {
                date = d.date
                clinicId = d.clinic?.id ?: 0
                load()
                if (d.format == "CSV") exportCsv()
            }.weight(hw.last())
        }
        if (downloads.isEmpty()) history.emptyState("Belum ada riwayat unduhan", "Laporan yang sudah dibuat akan tampil di sini.")
        history.label(if (downloads.isNotEmpty()) "Menampilkan ${minOf(downloads.size, 3)} unduhan terakhir · tersimpan di perangkat ini" else "0 unduhan · belum ada laporan yang dibuat", 11f, color = Hex.MUTED)
        footnote("Angka pada pratinjau mengikuti pilihan periode dan poliklinik. Laporan ini untuk evaluasi layanan, bukan catatan klinis.")
    }

    private fun longDate(d: LocalDate) = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.forLanguageTag("id")).format(d)

    private fun record() {
        val now = java.time.LocalDateTime.now()
        val at = "${DateTimeFormatter.ofPattern("d MMM", Locale.forLanguageTag("id")).format(now).replace(".", "")} · ${Ops.clock(java.time.Instant.now())}"
        downloads.add(0, Download(kind, date, clinic, format, at))
        if (downloads.size > 20) downloads.removeAt(downloads.size - 1)
        store.edit().putString("downloads", downloads.joinToString("\n") { "${it.kind}|${it.date}|${it.clinic?.id ?: 0}|${it.format}|${it.at}" }).apply()
        render()
    }

    private var pendingCsv: File? = null

    /** GET /api/manajemen/ekspor: the server's per-clinic CSV, aggregates only. */
    private fun exportCsv() {
        val name = "hytrack-laporan-$date.csv"
        request({ Api.download("manajemen/ekspor" + filter(), File(cacheDir, name)) }) { result ->
            result.onSuccess { pendingCsv = it; saveCsv.launch(name) }
                .onFailure { e -> error = e.message; render() }
        }
    }

    private val saveCsv = registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val file = pendingCsv ?: return@registerForActivityResult
        uri?.let { contentResolver.openOutputStream(it)?.use { out -> file.inputStream().use { input -> input.copyTo(out) } } }
        file.delete()
        pendingCsv = null
    }

    /** The print job renders from this WebView, so it must outlive [printPreview] (a local one is collected mid-job). */
    private var printing: WebView? = null

    /** "Unduh laporan PDF" prints the preview, as window.print() does on the web. */
    private fun printPreview() {
        val r = report
        val esc = { s: String -> s.replace("&", "&amp;").replace("<", "&lt;") }
        val rows = r.clinics.joinToString("") { row ->
            "<tr><td>${esc(row.clinic)}</td><td>${row.complete}</td><td>${row.wait?.let { "$it mnt" } ?: "—"}</td><td>${row.wait?.let { if (it > target) "Di atas" else "Sesuai" } ?: "—"}</td></tr>"
        }
        val html = """<html><body style="font-family:sans-serif;color:#173d3b">
            <h2>Pratinjau · ${KINDS[kind].first}</h2><p>${longDate(date)} · ${esc(clinic?.name ?: "Semua poliklinik")}</p>
            <p>Terdaftar <b>${r.registered}</b> · Selesai <b>${r.completed}</b> · Rata-rata tunggu <b>${minutes(r.averageWait)}</b></p>
            <table border="1" cellpadding="6" style="border-collapse:collapse"><tr><th>Poliklinik</th><th>Selesai</th><th>Tunggu</th><th>Target</th></tr>$rows</table>
            <p>Sesuai target tunggu ${percent(r.withinTarget)} · Check-in tepat waktu ${percent(r.onTime)}</p>
            <p><small>Laporan tidak memuat nama, nomor rekam medis, atau identitas pasien.</small></p></body></html>"""
        val web = WebView(this).also { printing = it }
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                (getSystemService(PRINT_SERVICE) as PrintManager)
                    .print("HyTrack laporan $date", view.createPrintDocumentAdapter("hytrack-laporan-$date"), PrintAttributes.Builder().build())
            }
        }
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
    }

    private companion object {
        /** Figma 43:2 frames 12 and 24 always show 07.00–15.00. */
        val HOURS = (7..15).map { "%02d.00".format(Locale.ROOT, it) }
        val SORTS = listOf("Urutkan: tunggu tertinggi", "Urutkan: selesai terbanyak", "Urutkan: nama poliklinik")
        val KINDS = listOf("Ringkasan layanan" to "Ringkasan", "Waktu tunggu" to "Waktu tunggu", "Kinerja poliklinik" to "Kinerja poli")
        val STAGE_NOTES = listOf(
            Triple("Target ≤30 menit", "Belum ada pasien yang mulai dilayani.", "Belum ada pasien yang mulai dilayani."),
            Triple("Waktu antar tahap", "Dihitung untuk pasien yang melanjutkan ke laboratorium.", "Belum ada pasien menuju laboratorium."),
            Triple("Waktu penyiapan farmasi", "Dihitung dari resep diterima hingga obat siap diambil.", "Belum ada resep yang diproses."),
        )
    }
}
