package com.constanvel.hytracl

import android.content.Intent
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.constanvel.hytracl.databinding.ItemClinicBinding
import com.constanvel.hytracl.databinding.ItemDateBinding
import com.constanvel.hytracl.databinding.ItemDoctorBinding
import com.constanvel.hytracl.databinding.ItemNotificationBinding
import com.constanvel.hytracl.databinding.ScreenAccountBinding
import com.constanvel.hytracl.databinding.ScreenClinicsBinding
import com.constanvel.hytracl.databinding.ScreenDoctorsBinding
import com.constanvel.hytracl.databinding.ScreenHomeBinding
import com.constanvel.hytracl.databinding.ScreenMessageBinding
import com.constanvel.hytracl.databinding.ScreenNotificationsBinding

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
    view.text = if (Guide.off(this)) "Tampilkan panduan penggunaan" else "Ulangi panduan dari awal"
    view.setOnClickListener {
        Guide.restart(this)
        render()
    }
}

class HomeActivity : ShellActivity() {
    override val tab = Tab.HOME

    override fun spec() = Spec(
        "HyTrack",
        brand = true,
        decor = Decor.CORNER,
        guide = if (Demo.activeVisit == null) GuideStep.PASIEN else null,
    )

    override fun content(into: LinearLayout) {
        val b = ScreenHomeBinding.inflate(layoutInflater, into)
        val visit = Demo.activeVisit
        b.heroTitle.text = if (visit != null) "Kunjungan Anda\n${visit.ticket}" else "Belum ada\nkunjungan aktif"
        b.heroText.text = visit?.let { "${it.clinic.name} · ${it.doctor.name}" }
            ?: "Ambil antrean untuk poliklinik dan dokter pilihan Anda."
        b.heroAction.text = if (visit != null) "Lihat kunjungan" else "+ Ambil antrean"
        b.heroAction.setOnClickListener {
            go(if (visit != null) VisitActivity::class.java else ClinicsActivity::class.java)
        }
        if (visit == null) {
            b.heroAction.tag = "guide"
            b.profile.tag = "guide"
        }
        b.patientName.text = Demo.patient
        b.patientPhone.text = "Telp ${Demo.phone}"
        b.accountLink.setOnClickListener { go(AccountActivity::class.java) }
        b.beforeList.plainList(
            "Siapkan kartu identitas dan rujukan.",
            "Pilih poliklinik dan jadwal dokter.",
            "Check-in saat tiba di rumah sakit.",
        )
        guideRestart(b.guideRestart)
        b.helpLink.setOnClickListener { go(HelpActivity::class.java) }
    }
}

class AccountActivity : ShellActivity() {
    override val tab = Tab.ACCOUNT

    override fun spec() = Spec("Akun", "Profil dan bantuan")

    override fun content(into: LinearLayout) {
        val b = ScreenAccountBinding.inflate(layoutInflater, into)
        b.name.text = Demo.patient
        b.phone.text = "Telp ${Demo.phone}"
        b.help.setOnClickListener { go(HelpActivity::class.java) }
        b.askHya.setOnClickListener { go(HyaActivity::class.java) }
        b.demo.root.isVisible = Demo.activeVisit != null
        b.demo.bind("Kontrol demo", "Reset data kunjungan demo") {
            visit { Demo.cancel() }
        }
        b.demo.demoAction.setBackgroundResource(R.drawable.bg_btn_danger)
        b.demo.demoAction.setTextColor(ContextCompat.getColor(this, R.color.warning_text))
        b.signOut.setOnClickListener {
            Demo.signOut()
            startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        }
    }
}

class NotificationsActivity : ShellActivity() {
    override fun spec() = Spec("Notifikasi", back = HomeActivity::class.java)

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
                time.text = item.time + if (item.unread) " · BARU" else ""
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
        "Pilih poliklinik",
        "KUNJUNGAN BARU · 1 / 2",
        back = HomeActivity::class.java,
        decor = Decor.WAVES,
        guide = GuideStep.POLI,
    )

    override fun content(into: LinearLayout) {
        val b = ScreenClinicsBinding.inflate(layoutInflater, into)
        b.search.query.hint = "Cari nama poliklinik"
        b.search.query.contentDescription = "Cari nama poliklinik"
        b.hospital.text = "${Demo.HOSPITAL} · Rawat jalan"
        fun filter(query: String) {
            b.list.removeAllViews()
            val found = Demo.clinics.filter { it.name.contains(query.trim(), ignoreCase = true) }
            found.forEach { clinic ->
                val doctors = Demo.doctorsOf(clinic).size
                ItemClinicBinding.inflate(layoutInflater, b.list, true).apply {
                    name.text = clinic.name
                    detail.text = if (doctors > 0) "$doctors dokter praktik · ${clinic.wait} menit" else "Jadwal belum tersedia"
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

    private val clinic get() = Demo.clinic(intent.getIntExtra(CLINIC, 1))

    override fun spec(): Spec {
        if (Demo.activeVisit != null) return Spec("Dokter & jadwal", back = ClinicsActivity::class.java)
        return Spec(
            "Dokter & jadwal",
            "KUNJUNGAN BARU · 2 / 2",
            back = ClinicsActivity::class.java,
            decor = Decor.WAVES,
            guide = if (Demo.doctorsOf(clinic).isNotEmpty()) GuideStep.DOKTER else null,
        )
    }

    override fun content(into: LinearLayout) {
        if (Demo.activeVisit != null) {
            val m = ScreenMessageBinding.inflate(layoutInflater, into)
            m.card.isVisible = false
            m.hyaHelp.root.isVisible = false
            m.notice.isVisible = true
            m.notice.text = "Anda sudah memiliki satu kunjungan aktif."
            m.notice.tone(Tone.WARNING)
            m.plainAction.isVisible = true
            m.plainAction.text = "Buka kunjungan aktif"
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
        val b = ScreenDoctorsBinding.inflate(layoutInflater, into)
        b.clinic.text = clinic.name + when {
            doctors.isEmpty() -> " · Jadwal belum tersedia"
            clinic.room != null -> " · ${clinic.room}"
            else -> ""
        }
        val none = doctors.isEmpty()
        b.empty.isVisible = none
        listOf(b.dates, b.doctors, b.notYet, b.book).forEach { it.isVisible = !none }
        b.otherClinic.setOnClickListener { go(ClinicsActivity::class.java) }
        if (none) return

        Demo.days.forEachIndexed { index, item ->
            ItemDateBinding.inflate(layoutInflater, b.dates, true).apply {
                this.day.text = item.day
                label.text = item.label
                val selected = index == dayIndex
                root.isSelected = selected
                val color = ContextCompat.getColor(this@DoctorsActivity, if (selected) R.color.white else R.color.text)
                this.day.setTextColor(color)
                label.setTextColor(color)
                root.setOnClickListener { dayIndex = index; render() }
            }
        }
        doctors.forEach { doctor ->
            val selected = doctor.id == doctorId
            ItemDoctorBinding.inflate(layoutInflater, b.doctors, true).apply {
                root.isSelected = selected
                name.text = doctor.name
                check.isVisible = selected
                schedule.text = (if (selected) "${clinic.name}\n" else "") + "${day.weekday}, ${day.label} · ${doctor.time}"
                listOf(rule, metrics, status).forEach { it.isVisible = selected }
                wait.text = "${doctor.wait(day)} menit"
                quota.text = "${doctor.quota(day)} pasien"
                status.text = doctor.delay?.let { "Dokter terlambat ±$it" } ?: "Jadwal dipilih · ${doctor.time}"
                status.tone(if (doctor.delay != null) Tone.WARNING else Tone.GREEN)
                root.setOnClickListener { doctorId = doctor.id; render() }
            }
        }
        val doctor = doctors.first { it.id == doctorId }
        val full = doctor.quota(day) < 1
        b.bookLabel.text = if (full) "Kuota penuh" else "Ambil antrean"
        b.book.isEnabled = !full
        b.book.alpha = if (full) 0.55f else 1f
        b.book.setOnClickListener {
            Demo.book(doctor, day)
            go(VisitActivity::class.java)
        }
    }
}
