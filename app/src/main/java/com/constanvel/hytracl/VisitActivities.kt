package com.constanvel.hytracl

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.constanvel.hytracl.Demo.Rx
import com.constanvel.hytracl.Demo.Stage
import com.constanvel.hytracl.databinding.ScreenCheckinBinding
import com.constanvel.hytracl.databinding.ScreenCompanionBinding
import com.constanvel.hytracl.databinding.ScreenCompanionViewBinding
import com.constanvel.hytracl.databinding.ScreenMessageBinding
import com.constanvel.hytracl.databinding.ScreenVisitLabBinding
import com.constanvel.hytracl.databinding.ScreenVisitPharmacyBinding
import com.constanvel.hytracl.databinding.ScreenVisitQueueBinding
import com.constanvel.hytracl.databinding.ScreenVisitTicketBinding

private const val STAFF = "Kontrol demo · menggantikan tindakan petugas"

/** Kunjungan (features/patient/visit.tsx): one screen whose layout follows the visit stage. */
class VisitActivity : ShellActivity() {
    override fun spec(): Spec {
        val v = Demo.visit ?: return Spec("Kunjungan", "RAWAT JALAN")
        return when (v.stage) {
            Stage.BOOKED -> Spec("Tiket kunjungan", v.day.long.uppercase(), back = HomeActivity::class.java, guide = GuideStep.TIKET)
            Stage.WAITING, Stage.SERVING -> Spec("Antrean saya", "RAWAT JALAN · HARI INI", queue = true, guide = GuideStep.ANTREAN)
            Stage.LABORATORY -> Spec("Layanan berikutnya", "KUNJUNGAN ${v.ticket}", back = VisitActivity::class.java, guide = GuideStep.LAYANAN)
            Stage.PHARMACY -> Spec("Status obat", "FARMASI RAWAT JALAN", back = VisitActivity::class.java, guide = GuideStep.LAYANAN)
            Stage.COMPLETED -> Spec("Kunjungan selesai")
            Stage.NO_SHOW -> Spec("Kunjungan", "RAWAT JALAN")
        }
    }

    override fun content(into: LinearLayout) {
        val v = Demo.visit
        when (v?.stage) {
            null, Stage.NO_SHOW -> message(into, "Belum ada kunjungan aktif", "Pilih poliklinik dan jadwal dokter untuk memulai.", "Ambil antrean")
            Stage.COMPLETED -> message(into, "Terima kasih.", "Semoga lekas sehat, ${Demo.patient}.", "Mulai kunjungan baru").apply {
                notice.isVisible = true
                notice.text = "Kunjungan selesai. Akses pendamping telah berakhir."
                text.setTextColor(ContextCompat.getColor(this@VisitActivity, R.color.text))
                hyaHelp.root.isVisible = false
            }
            Stage.BOOKED -> ticket(into, v)
            Stage.WAITING, Stage.SERVING -> queue(into, v)
            Stage.LABORATORY -> lab(into, v)
            Stage.PHARMACY -> pharmacy(into, v)
        }
    }

    private fun message(into: LinearLayout, title: String, text: String, action: String) =
        ScreenMessageBinding.inflate(layoutInflater, into).apply {
            this.title.text = title
            this.text.text = text
            this.action.text = action
            this.action.setOnClickListener { go(ClinicsActivity::class.java) }
        }

    private fun ticket(into: LinearLayout, v: Demo.Visit) = ScreenVisitTicketBinding.inflate(layoutInflater, into).apply {
        ticket.text = v.ticket
        patient.text = Demo.patient
        clinic.text = v.clinic.name
        schedule.text = "${v.doctor.name}\n${v.day.long} · ${v.time}"
        leaveBy.text = "Berangkat sebelum ${v.leaveBy}"
        leaveText.text = "Check-in paling lambat ${v.checkInBy}, ${v.leadMinutes} menit sebelum praktik dimulai. " +
            "Perjalanan ±${v.travelMinutes} menit adalah perkiraan umum; sesuaikan dengan perjalanan Anda."
        ahead.text = "${v.ahead} pasien"
        wait.text = "${v.waitRange ?: "—"} mnt"
        showQr.setOnClickListener { go(CheckInActivity::class.java) }
        cancel.setOnClickListener { visit { Demo.cancel() } }
    }

    private fun queue(into: LinearLayout, v: Demo.Visit) = ScreenVisitQueueBinding.inflate(layoutInflater, into).apply {
        val serving = v.stage == Stage.SERVING
        val (room, building) = Demo.splitRoom(v.clinic.room)
        synced.text = "Diperbarui ${Demo.now()}"
        eyebrow.text = if (serving) "GILIRAN ANDA" else "SUDAH CHECK-IN"
        headline.text = if (serving) "Silakan masuk" else "Menunggu giliran"
        ticket.text = v.ticket
        ahead.text = if (serving) "Nomor Anda dipanggil ke $room" else "${v.ahead} pasien lagi sebelum Anda"
        wait.text = if (serving) "Sekarang" else "${v.waitRange ?: "—"} mnt"
        this.serving.text = if (serving) v.ticket else v.serving ?: "—"
        locationEyebrow.text = if (serving) "MASUK KE SINI" else "TUNGGU DI SINI"
        clinic.text = "Poli ${v.clinic.name}"
        this.room.text = if (building.isNotEmpty()) "$room · $building" else room
        directions.setOnClickListener { go(DirectionsActivity::class.java) }
        share.bind("Bagikan ke pendamping") { go(CompanionAccessActivity::class.java) }
        demo.bind(STAFF, "Simulasikan pemeriksaan selesai", secondary = true) { visit { Demo.simulate("examined") } }
    }

    private fun lab(into: LinearLayout, v: Demo.Visit) = ScreenVisitLabBinding.inflate(layoutInflater, into).apply {
        val room = Demo.splitRoom(v.clinic.room).first
        steps.steps(
            "Gunakan lift di sebelah kanan" to "Dari $room, ikuti tanda lift.",
            "Turun ke lantai 1" to "Keluar lift, lalu belok kiri.",
            "Lapor di meja laboratorium" to "Tunjukkan tiket ${v.ticket}.",
        )
        directions.setOnClickListener { go(DirectionsActivity::class.java) }
        demo.bind(STAFF, "Simulasikan lanjut ke farmasi") { visit { Demo.simulate("laboratory") } }
    }

    private fun pharmacy(into: LinearLayout, v: Demo.Visit) = ScreenVisitPharmacyBinding.inflate(layoutInflater, into).apply {
        val ready = v.rx == Rx.READY
        val label = v.rx?.label ?: "Menunggu resep"
        val pickup = v.pickupNumber
        status.text = "● Status farmasi · $label"
        eyebrow.text = if (ready) "OBAT SIAP DIAMBIL" else "OBAT SEDANG DISIAPKAN"
        headline.text = if (ready) "Silakan ke loket F2" else label
        instruction.text = if (ready) "Tunjukkan nomor pengambilan Anda." else "Tunggu sampai nomor Anda dipanggil."
        this.pickup.text = pickup
        steps.steps(
            "Resep diterima" to "Resep dari dokter diterima.",
            "Obat disiapkan" to "Sedang dikerjakan petugas farmasi.",
            "Pemeriksaan akhir" to "Obat diperiksa apoteker.",
            "Siap diambil" to if (ready) "Nomor $pickup dipanggil." else "Tunggu pemberitahuan sebelum ke loket.",
        )
        where.text = if (ready) "Ambil obat di Farmasi" else "Tunggu di area Farmasi"
        whereText.text = "Gedung A, lantai 1 · Loket F2\nAmbil obat setelah nomor $pickup dipanggil."
        if (ready) demo.bind(STAFF, "Simulasikan pengambilan selesai") { visit { Demo.simulate("picked-up") } }
        else demo.bind(STAFF, "Simulasikan obat siap", secondary = true) { visit { Demo.simulate("medicine-ready") } }
    }
}

class CheckInActivity : ShellActivity() {
    private var showManual = false

    override fun spec() = Spec("Check-in kedatangan", Demo.HOSPITAL.uppercase(), back = VisitActivity::class.java, guide = GuideStep.CHECKIN)

    override fun content(into: LinearLayout) {
        val v = Demo.activeVisit
        if (v == null) { // the web renders the visit screen here
            go(VisitActivity::class.java)
            finish()
            return
        }
        val booked = v.stage == Stage.BOOKED
        ScreenCheckinBinding.inflate(layoutInflater, into).apply {
            who.text = "${v.ticket} · ${Demo.patient}"
            code.text = "${v.checkInCode} · Nomor check-in"
            status.text = when {
                !booked -> "Sudah check-in"
                v.isToday -> "Belum check-in · Batas ${v.checkInBy}"
                else -> "Check-in dibuka pada ${v.day.long}"
            }
            status.tone(if (booked) Tone.WARNING else Tone.GREEN)
            route.plainList(
                "Naik ke lantai 2 melalui lift Gedung A.",
                "Tunjukkan layar ini ke petugas check-in.",
                "Status berubah setelah petugas mengonfirmasi.",
            )
            manualCode.text = "Nomor check-in: ${v.checkInCode}. Sebutkan kepada petugas."
            manualCode.isVisible = showManual
            manual.setOnClickListener { showManual = !showManual; render() }
            demo.root.isVisible = booked
            demo.bind("Kontrol demo · menggantikan konfirmasi petugas", "Simulasikan konfirmasi petugas") {
                visit { Demo.simulate("check-in") }
            }
            queue.isVisible = !booked
            queue.setOnClickListener { go(VisitActivity::class.java) }
        }
    }
}

class CompanionAccessActivity : ShellActivity() {
    override fun spec() = if (Demo.activeVisit == null) Spec("Akses pendamping", back = VisitActivity::class.java)
    else Spec("Akses pendamping", "KELOLA AKSES KUNJUNGAN", back = VisitActivity::class.java)

    override fun content(into: LinearLayout) {
        val v = Demo.activeVisit
        if (v == null) {
            ScreenMessageBinding.inflate(layoutInflater, into).apply {
                card.isVisible = false
                hyaHelp.root.isVisible = false
                notice.isVisible = true
                notice.text = "Tidak ada kunjungan aktif untuk dibagikan."
                notice.tone(Tone.WARNING)
                plainAction.isVisible = true
                plainAction.text = "Buka kunjungan"
                plainAction.setOnClickListener { go(VisitActivity::class.java) }
            }
            return
        }
        val link = Demo.companionLink.takeIf { Demo.revealLink }
        Demo.revealLink = false // shown once, like the web flash
        ScreenCompanionBinding.inflate(layoutInflater, into).apply {
            who.text = "${Demo.patient} · ${v.ticket}"
            `when`.text = "${v.day.long} · ${Demo.HOSPITAL}"
            linkCard.isVisible = link != null
            this.link.text = link
            copy.setOnClickListener {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Tautan pendamping", link))
                copy.text = "Tautan disalin"
            }
            openView.setOnClickListener { go(CompanionViewActivity::class.java) { putExtra(CompanionViewActivity.TOKEN, link) } }
            activate.isVisible = !v.companionActive
            activeNotice.isVisible = v.companionActive && link == null
            renew.isVisible = v.companionActive && link == null
            revoke.isVisible = v.companionActive
            activate.setOnClickListener { visit { Demo.shareCompanion() } }
            renew.setOnClickListener { visit { Demo.shareCompanion() } }
            revoke.setOnClickListener { visit { Demo.revokeCompanion() } }
        }
    }
}

/** Public page behind the companion link: read-only and limited. */
class CompanionViewActivity : ShellActivity() {
    companion object {
        const val TOKEN = "token"
    }

    override val requiresSignIn = false

    override fun spec() = Spec("Pantauan keluarga", "AKSES PENDAMPING", bare = true)

    override fun content(into: LinearLayout) {
        val v = Demo.activeVisit?.takeIf { it.companionActive && Demo.companionLink == intent.getStringExtra(TOKEN) }
        if (v == null) {
            ScreenMessageBinding.inflate(layoutInflater, into).apply {
                card.isVisible = false
                hyaHelp.root.isVisible = false
                notice.isVisible = true
                notice.text = "Akses pendamping tidak aktif atau telah berakhir."
                notice.tone(Tone.WARNING)
            }
            into.text(R.style.Text_P_Muted, "Minta tautan baru kepada pasien bila masih diperlukan.")
            return
        }
        val (eyebrow, headline) = when (v.stage) {
            Stage.BOOKED -> "BELUM CHECK-IN" to "Menunggu kedatangan"
            Stage.WAITING -> "MENUNGGU GILIRAN" to "${v.ahead} pasien lagi" + (v.waitRange?.let { " · $it menit" } ?: "")
            Stage.SERVING -> "GILIRAN DIPANGGIL" to "Sedang menuju ruang pemeriksaan"
            Stage.LABORATORY -> "LABORATORIUM" to "Menuju atau sedang di laboratorium"
            else -> "FARMASI" to "Obat: ${v.rx?.label ?: "menunggu resep"}"
        }
        val (room, building) = when (v.stage) {
            Stage.PHARMACY -> "Loket F2" to "Gedung A, lantai 1"
            Stage.LABORATORY -> "Ruang A-108" to "Gedung A, lantai 1"
            else -> Demo.splitRoom(v.clinic.room)
        }
        ScreenCompanionViewBinding.inflate(layoutInflater, into).apply {
            access.text = "Akses terbatas · Hanya pantauan · Diperbarui ${Demo.now()}"
            patient.text = Demo.patientShort
            stage.text = eyebrow
            ticket.text = v.ticket
            this.headline.text = headline
            clinic.text = "Poli ${v.clinic.name} · Estimasi dapat berubah."
            this.room.text = room
            this.building.text = listOf(building, Demo.HOSPITAL).filter { it.isNotEmpty() }.joinToString(" · ")
            stop.setOnClickListener { visit { Demo.revokeCompanion(byCompanion = true) } }
        }
    }
}
