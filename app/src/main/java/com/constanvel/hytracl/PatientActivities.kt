package com.constanvel.hytracl

import android.app.DatePickerDialog
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.constanvel.hytracl.databinding.ItemClinicBinding
import com.constanvel.hytracl.databinding.ItemDateBinding
import com.constanvel.hytracl.databinding.ItemDoctorBinding
import com.constanvel.hytracl.databinding.ItemDoctorOptionBinding
import com.constanvel.hytracl.databinding.ItemNotificationBinding
import com.constanvel.hytracl.databinding.ScreenClinicsBinding
import com.constanvel.hytracl.databinding.ScreenDoctorsBinding
import com.constanvel.hytracl.databinding.ScreenHomeBinding
import com.constanvel.hytracl.databinding.ScreenMessageBinding
import com.constanvel.hytracl.databinding.ScreenNotificationsBinding
import com.constanvel.hytracl.databinding.ViewBookBarBinding
import com.constanvel.hytracl.databinding.ViewDemoControlsBinding
import java.util.Locale

/** Notice tone switch for an existing notice view. */
fun TextView.tone(tone: Tone) {
    setBackgroundResource(when (tone) {
        Tone.GREEN -> R.drawable.bg_notice
        Tone.WARNING -> R.drawable.bg_notice_warning
        Tone.INFO -> R.drawable.bg_notice_info
    })
    setTextColor(ContextCompat.getColor(context, when (tone) {
        Tone.GREEN -> R.color.primary
        Tone.WARNING -> R.color.warning_text
        Tone.INFO -> R.color.info_text
    }))
}

/** GuideRestart: shows the guide again from the first step. */
fun ShellActivity.guideRestart(view: TextView) {
    view.text = if (Guide.off(this)) getString(R.string.tampilkan_panduan_penggunaan) else getString(R.string.ulangi_panduan_dari_awal)
    view.setOnClickListener {
        Guide.restart(this)
        render()
    }
}

class HomeActivity : ShellActivity() {
    override val tab = Tab.HOME

    override fun spec() = Spec(
        getString(R.string.app_name),
        brand = true,
        decor = Decor.CORNER,
        guide = if (Demo.activeVisit == null) GuideStep.PASIEN else null,
    )

    override fun content(into: LinearLayout) {
        val b = ScreenHomeBinding.inflate(layoutInflater, into)
        val visit = Demo.activeVisit
        b.heroTitle.text = if (visit != null) getString(R.string.kunjungan_anda_n, visit.ticket) else getString(R.string.belum_ada_nkunjungan_aktif)
        b.heroText.text = visit?.let { "${it.clinic.name} · ${it.doctor.name}" }
            ?: getString(R.string.ambil_antrean_untuk_poliklinik_dan_dokter)
        b.heroAction.text = if (visit != null) getString(R.string.lihat_kunjungan) else getString(R.string.ambil_antrean)
        b.heroAction.setOnClickListener {
            go(if (visit != null) VisitActivity::class.java else ClinicsActivity::class.java)
        }
        if (visit == null) {
            b.heroAction.tag = "guide"
            b.profile.tag = "guide"
        }
        b.patientName.text = Demo.patient
        b.patientPhone.text = getString(R.string.telp, Demo.phone)
        b.accountLink.setOnClickListener { go(AccountActivity::class.java) }
        b.beforeList.plainList(
            getString(R.string.siapkan_kartu_identitas_dan_rujukan),
            getString(R.string.pilih_poliklinik_dan_jadwal_dokter),
            getString(R.string.check_in_saat_tiba_di_rumah_sakit),
        )
        guideRestart(b.guideRestart)
        b.helpLink.setOnClickListener { go(HelpActivity::class.java) }
    }
}

/** Akun (Figma 27:248): the account card, then Data pasien first among the actions. */
class AccountActivity : ShellActivity() {
    override val tab = Tab.ACCOUNT

    override fun spec() = Spec(getString(R.string.akun), getString(R.string.profil_dan_bantuan))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return
        interHeader(28f, 41f)
        shell.subtitle.typeface = ResourcesCompat.getFont(this, R.font.inter_regular)
        shell.subtitle.textSize = 14f
    }

    override fun content(into: LinearLayout) {
        into.dividerDrawable = ContextCompat.getDrawable(this, R.drawable.gap_24)
        (into.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = into.dp(12)
        into.column(12, R.color.white, 22).apply {
            setPadding(dp(22), dp(20), dp(22), dp(20))
            inter(getString(R.string.akun_pasien), 12f, 17f, Weight.SEMIBOLD, R.color.brand_muted)
            inter(Demo.patient, 26f, 38f, Weight.BOLD)
            inter(getString(R.string.telp, Demo.phone), 16f, 23f, color = R.color.brand_muted)
            inter(getString(R.string.kelola_data_anda), 15f, 22f)
        }
        into.column(16).apply {
            button(getString(R.string.data_pasien), height = 56, radius = 12) { go(PatientDataActivity::class.java) }
            button(getString(R.string.bantuan_panduan_kunjungan), Look.PALE, 56, 12) { go(HelpActivity::class.java) }
            button(getString(R.string.ask_hya), Look.PALE, 56, 12) { go(HyaActivity::class.java) }
            if (Demo.activeVisit != null) {
                ViewDemoControlsBinding.inflate(layoutInflater, this, true).apply {
                    bind(getString(R.string.kontrol_demo), getString(R.string.reset_data_kunjungan_demo)) { visit { Demo.cancel() } }
                    demoAction.setBackgroundResource(R.drawable.bg_btn_danger)
                    demoAction.setTextColor(ContextCompat.getColor(this@AccountActivity, R.color.warning_text))
                }
            }
            button(getString(R.string.keluar), Look.PALE, 56, 12) {
                Demo.signOut()
                startActivity(Intent(this@AccountActivity, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            }
        }
    }
}

class NotificationsActivity : ShellActivity() {
    override fun spec() = Spec(getString(R.string.notifications), back = HomeActivity::class.java)

    override fun content(into: LinearLayout) {
        val b = ScreenNotificationsBinding.inflate(layoutInflater, into)
        val items = Demo.notifications
        b.empty.isVisible = items.isEmpty()
        listOf(b.list, b.note, b.open).forEach { it.isVisible = items.isNotEmpty() }
        b.emptyAction.setOnClickListener { go(VisitActivity::class.java) }
        b.open.setOnClickListener { go(VisitActivity::class.java) }
        items.forEach { item ->
            ItemNotificationBinding.inflate(layoutInflater, b.list, true).apply {
                if (item.unread) root.setBackgroundResource(R.drawable.bg_card_unread)
                time.text = item.time + if (item.unread) getString(R.string.baru) else ""
                message.text = item.message
                action.text = item.action
                action.isVisible = item.action != null
            }
        }
    }

    /** Seen once, then no longer new (the bell dot clears). */
    override fun onPause() {
        super.onPause()
        Demo.notifications.forEach { it.unread = false }
    }
}

class ClinicsActivity : ShellActivity() {
    override fun spec() = Spec(
        getString(R.string.pilih_poliklinik),
        getString(R.string.kunjungan_baru_1_2),
        back = HomeActivity::class.java,
        decor = Decor.WAVES,
        guide = GuideStep.POLI,
    )

    override fun content(into: LinearLayout) {
        val b = ScreenClinicsBinding.inflate(layoutInflater, into)
        b.search.query.hint = getString(R.string.cari_nama_poliklinik)
        b.search.query.contentDescription = getString(R.string.cari_nama_poliklinik)
        b.hospital.text = getString(R.string.rawat_jalan_2, Demo.HOSPITAL)
        fun filter(query: String) {
            b.list.removeAllViews()
            val found = Demo.clinics.filter { it.name.contains(query.trim(), ignoreCase = true) }
            found.forEach { clinic ->
                val doctors = Demo.doctorsOf(clinic).size
                ItemClinicBinding.inflate(layoutInflater, b.list, true).apply {
                    name.text = clinic.name
                    detail.text = if (doctors > 0) getString(R.string.dokter_praktik_menit, doctors, clinic.wait) else getString(R.string.jadwal_belum_tersedia)
                    root.setOnClickListener { go(DoctorsActivity::class.java) { putExtra(DoctorsActivity.CLINIC, clinic.id) } }
                }
            }
            b.empty.isVisible = found.isEmpty()
        }
        b.search.query.doAfterTextChanged { filter(it.toString()) }
        b.clear.setOnClickListener { b.search.query.text.clear() }
        filter(b.search.query.text.toString())
    }
}

class DoctorsActivity : ShellActivity() {
    companion object {
        const val CLINIC = "poli"
    }

    private var clinicId = 0
    private var dayIndex = 0
    private var doctorId = 0
    private lateinit var bar: ViewBookBarBinding

    private val clinic get() = Demo.clinic(intent.getIntExtra(CLINIC, 1))
    private val booking get() = Demo.activeVisit == null && Demo.doctorsOf(clinic).isNotEmpty()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return
        // Figma 22:2 sets the header in Inter (21sp bold / 10sp medium) and pins the button bar.
        shell.title.typeface = ResourcesCompat.getFont(this, R.font.inter_bold)
        shell.title.textSize = 21f
        shell.title.lineHeight = shell.root.dp(29.4)
        shell.subtitle.typeface = ResourcesCompat.getFont(this, R.font.inter_medium)
        shell.subtitle.textSize = 10f
        bar = ViewBookBarBinding.inflate(layoutInflater, shell.footer, true)
        shell.footer.setBackgroundResource(R.color.bg) // also behind the system navigation bar
    }

    override fun spec(): Spec {
        if (Demo.activeVisit != null) return Spec(getString(R.string.dokter_jadwal), back = ClinicsActivity::class.java)
        return Spec(
            getString(R.string.dokter_jadwal),
            getString(R.string.kunjungan_baru_2_2),
            back = ClinicsActivity::class.java,
            decor = Decor.WAVES,
            guide = if (booking) GuideStep.DOKTER else null,
            action = booking,
        )
    }

    /** Kalender lengkap: the platform date picker, limited to the booking window. */
    private fun pickDate() {
        val day = Demo.days[dayIndex]
        // Indonesian month and day names, like the rest of the app, whatever the device language.
        // The picker's header formats with the default locale, so it is switched while the dialog is open.
        val id = Locale.forLanguageTag("id-ID")
        val previous = Locale.getDefault()
        Locale.setDefault(id)
        val indonesian = ContextThemeWrapper(this, R.style.Theme_Hytracl).apply {
            applyOverrideConfiguration(Configuration().apply { setLocale(id) })
        }
        DatePickerDialog(indonesian, { _, year, month, dom ->
            val index = Demo.days.indexOfFirst { it.year == year && it.month == month && it.dom == dom }
            if (index >= 0) { dayIndex = index; render() }
        }, day.year, day.month, day.dom).apply {
            datePicker.minDate = Demo.days.first().millis
            datePicker.maxDate = Demo.days.last().millis
            setOnDismissListener { Locale.setDefault(previous) }
        }.show()
    }

    private fun TextView.enable(enabled: Boolean, action: () -> Unit) {
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.4f
        setOnClickListener { action() }
    }

    override fun content(into: LinearLayout) {
        if (Demo.activeVisit != null) {
            val m = ScreenMessageBinding.inflate(layoutInflater, into)
            m.card.isVisible = false
            m.hyaHelp.root.isVisible = false
            m.notice.isVisible = true
            m.notice.text = getString(R.string.anda_sudah_memiliki_satu_kunjungan_aktif)
            m.notice.tone(Tone.WARNING)
            m.plainAction.isVisible = true
            m.plainAction.text = getString(R.string.buka_kunjungan_aktif)
            m.plainAction.setOnClickListener { go(VisitActivity::class.java) }
            return
        }
        val clinic = clinic
        val doctors = Demo.doctorsOf(clinic)
        if (clinic.id != clinicId) {
            clinicId = clinic.id
            dayIndex = 0
            doctorId = doctors.firstOrNull()?.id ?: 0
        }
        val day = Demo.days[dayIndex]
        val b = ScreenDoctorsBinding.inflate(layoutInflater, into, true)
        b.clinic.text = clinic.name + when {
            doctors.isEmpty() -> getString(R.string.jadwal_belum_tersedia_2)
            clinic.room != null -> " · ${clinic.room}"
            else -> ""
        }
        val none = doctors.isEmpty()
        b.empty.isVisible = none
        listOf(b.dateSection, b.doctors, b.notYet).forEach { it.isVisible = !none }
        b.otherClinic.setOnClickListener { go(ClinicsActivity::class.java) }
        if (none) return

        // Month navigation: the strip shows the selected day's month; the arrows jump to the
        // first open day of the previous / next month inside the booking window.
        val months = Demo.days.map { it.monthKey }.distinct()
        val month = months.indexOf(day.monthKey)
        fun jump(to: Int) { dayIndex = Demo.days.indexOfFirst { it.monthKey == months[to] }; render() }
        b.month.text = day.monthTitle
        b.prevMonth.enable(month > 0) { jump(month - 1) }
        b.nextMonth.enable(month < months.lastIndex) { jump(month + 1) }
        b.calendar.setOnClickListener { pickDate() }

        // Figma's one-line nav row (arrows, month, button) only holds while its two flexible
        // parts fit their longest word each; with larger text or a narrow screen the button
        // takes its own line instead of the month being cut mid-word.
        val column = minOf(resources.displayMetrics.widthPixels, into.dp(540)) - into.dp(48)
        val longestMonth = Demo.days.flatMap { it.monthTitle.split(' ') }.distinct().maxOf { b.month.paint.measureText(it) }
        val calendarMin = b.calendar.paint.measureText(b.calendar.text.toString().substringBefore(' ')) + b.calendar.paddingStart + b.calendar.paddingEnd
        val chrome = b.prevMonth.layoutParams.width + b.nextMonth.layoutParams.width + into.dp(24)
        if (2 * maxOf(longestMonth, calendarMin) + chrome > column) {
            b.navRow.removeView(b.calendar)
            b.dateSection.addView(b.calendar, 1, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        // Three dates fill the column (Figma 22:46); the rest of the month scrolls sideways.
        // A chip never goes narrower than its text, so a larger font widens every chip equally
        // instead of wrapping one label, which would make that chip (and the row) taller.
        var chipWidth = (column - into.dp(24)) / 3
        var selectedChip: View? = null
        val chips = Demo.days.withIndex().filter { it.value.monthKey == day.monthKey }.map { (index, item) ->
            ItemDateBinding.inflate(layoutInflater, b.dates, false).apply {
                this.day.text = item.day
                label.text = item.label
                root.contentDescription = item.long
                val selected = index == dayIndex
                root.isSelected = selected
                if (selected) {
                    val white = ContextCompat.getColor(this@DoctorsActivity, R.color.white)
                    this.day.setTextColor(white)
                    label.setTextColor(white)
                    selectedChip = root
                }
                root.setOnClickListener { dayIndex = index; render() }
                val text = maxOf(this.day.paint.measureText(item.day), label.paint.measureText(item.label))
                chipWidth = maxOf(chipWidth, kotlin.math.ceil(text).toInt() + root.paddingStart + root.paddingEnd)
            }
        }
        chips.forEach { b.dates.addView(it.root, LinearLayout.LayoutParams(chipWidth, ViewGroup.LayoutParams.MATCH_PARENT)) }
        b.dateScroll.post { selectedChip?.let { b.dateScroll.scrollTo(it.left, 0) } }

        doctors.forEach { doctor ->
            if (doctor.id == doctorId) ItemDoctorBinding.inflate(layoutInflater, b.doctors, true).apply {
                root.isSelected = true
                name.text = doctor.name
                schedule.text = "${clinic.name}\n${day.medium} · ${doctor.time}"
                wait.text = getString(R.string.menit, doctor.wait(day))
                quota.text = getString(R.string.pasien, doctor.quota(day))
                status.text = doctor.delay?.let { getString(R.string.dokter_terlambat, it) } ?: getString(R.string.jadwal_dipilih, doctor.time)
                if (doctor.delay != null) status.tone(Tone.WARNING)
            } else ItemDoctorOptionBinding.inflate(layoutInflater, b.doctors, true).apply {
                name.text = doctor.name
                detail.text = getString(R.string.jam_kuota_tersisa, doctor.time, doctor.quota(day))
                root.setOnClickListener { doctorId = doctor.id; render() }
            }
        }

        val doctor = doctors.first { it.id == doctorId }
        val full = doctor.quota(day) < 1
        bar.label.text = if (full) getString(R.string.kuota_penuh) else getString(R.string.ambil_antrean_2)
        bar.book.isEnabled = !full
        bar.book.alpha = if (full) 0.55f else 1f
        bar.book.setOnClickListener {
            Demo.book(doctor, day)
            go(VisitActivity::class.java)
        }
    }
}
