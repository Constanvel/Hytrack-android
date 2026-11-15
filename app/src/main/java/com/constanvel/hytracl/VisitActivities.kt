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

/** Kunjungan (features/patient/visit.tsx): one screen whose layout follows the visit stage. */
class VisitActivity : ShellActivity() {
    override fun spec(): Spec {
        val v = Demo.visit ?: return Spec(getString(R.string.kunjungan), getString(R.string.rawat_jalan))
        return when (v.stage) {
            Stage.BOOKED -> Spec(getString(R.string.tiket_kunjungan), v.day.long.uppercase(), back = HomeActivity::class.java, guide = GuideStep.TIKET)
            Stage.WAITING, Stage.SERVING -> Spec(getString(R.string.antrean_saya), getString(R.string.rawat_jalan_hari_ini), queue = true, guide = GuideStep.ANTREAN)
            Stage.LABORATORY -> Spec(getString(R.string.layanan_berikutnya), getString(R.string.kunjungan_2, v.ticket), back = VisitActivity::class.java, guide = GuideStep.LAYANAN)
            Stage.PHARMACY -> Spec(getString(R.string.status_obat), getString(R.string.farmasi_rawat_jalan), back = VisitActivity::class.java, guide = GuideStep.LAYANAN)
            Stage.COMPLETED -> Spec(getString(R.string.kunjungan_selesai))
            Stage.NO_SHOW -> Spec(getString(R.string.kunjungan), getString(R.string.rawat_jalan))
        }
    }

    override fun content(into: LinearLayout) {
        val v = Demo.visit
        when (v?.stage) {
            null, Stage.NO_SHOW -> message(into, getString(R.string.belum_ada_kunjungan_aktif), getString(R.string.pilih_poliklinik_dan_jadwal_dokter_untuk), getString(R.string.ambil_antrean_2))
            Stage.COMPLETED -> message(into, getString(R.string.terima_kasih), getString(R.string.semoga_lekas_sehat, Demo.patient), getString(R.string.mulai_kunjungan_baru)).apply {
                notice.isVisible = true
                notice.text = getString(R.string.kunjungan_selesai_akses_pendamping_telah)
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
        leaveBy.text = getString(R.string.berangkat_sebelum, v.leaveBy)
        leaveText.text = getString(R.string.check_in_paling_lambat_menit_sebelum, v.checkInBy, v.leadMinutes) +
            getString(R.string.perjalanan_menit_adalah_perkiraan_umum, v.travelMinutes)
        ahead.text = getString(R.string.pasien, v.ahead)
        wait.text = getString(R.string.wait_minutes, v.waitRange ?: "—")
        showQr.setOnClickListener { go(CheckInActivity::class.java) }
        cancel.setOnClickListener { visit { Demo.cancel() } }
    }

    private fun queue(into: LinearLayout, v: Demo.Visit) = ScreenVisitQueueBinding.inflate(layoutInflater, into).apply {
        val serving = v.stage == Stage.SERVING
        val (room, building) = Demo.splitRoom(v.clinic.room)
        synced.text = getString(R.string.diperbarui, Demo.now())
        eyebrow.text = if (serving) getString(R.string.giliran_anda) else getString(R.string.sudah_check_in)
        headline.text = if (serving) getString(R.string.silakan_masuk) else getString(R.string.menunggu_giliran)
        ticket.text = v.ticket
        ahead.text = if (serving) getString(R.string.nomor_anda_dipanggil_ke, room) else getString(R.string.pasien_lagi_sebelum_anda, v.ahead)
        wait.text = if (serving) getString(R.string.sekarang) else getString(R.string.wait_minutes, v.waitRange ?: "—")
        this.serving.text = if (serving) v.ticket else v.serving ?: "—"
        locationEyebrow.text = if (serving) getString(R.string.masuk_ke_sini) else getString(R.string.tunggu_di_sini)
        clinic.text = getString(R.string.poli, v.clinic.name)
        this.room.text = if (building.isNotEmpty()) "$room · $building" else room
        directions.setOnClickListener { go(DirectionsActivity::class.java) }
        share.bind(getString(R.string.bagikan_ke_pendamping)) { go(CompanionAccessActivity::class.java) }
        demo.bind(getString(R.string.demo_staff_control), getString(R.string.simulasikan_pemeriksaan_selesai), secondary = true) { visit { Demo.simulate("examined") } }
    }

    private fun lab(into: LinearLayout, v: Demo.Visit) = ScreenVisitLabBinding.inflate(layoutInflater, into).apply {
        val room = Demo.splitRoom(v.clinic.room).first
        steps.steps(
            getString(R.string.gunakan_lift_di_sebelah_kanan) to getString(R.string.dari_ikuti_tanda_lift, room),
            getString(R.string.turun_ke_lantai_1) to getString(R.string.keluar_lift_lalu_belok_kiri),
            getString(R.string.lapor_di_meja_laboratorium) to getString(R.string.tunjukkan_tiket, v.ticket),
        )
        directions.setOnClickListener { go(DirectionsActivity::class.java) }
        demo.bind(getString(R.string.demo_staff_control), getString(R.string.simulasikan_lanjut_ke_farmasi)) { visit { Demo.simulate("laboratory") } }
    }

    private fun pharmacy(into: LinearLayout, v: Demo.Visit) = ScreenVisitPharmacyBinding.inflate(layoutInflater, into).apply {
        val ready = v.rx == Rx.READY
        val label = v.rx?.label ?: getString(R.string.menunggu_resep)
        val pickup = v.pickupNumber
        status.text = getString(R.string.status_farmasi, label)
        eyebrow.text = if (ready) getString(R.string.obat_siap_diambil) else getString(R.string.obat_sedang_disiapkan)
        headline.text = if (ready) getString(R.string.silakan_ke_loket_f2) else label
        instruction.text = if (ready) getString(R.string.tunjukkan_nomor_pengambilan_anda) else getString(R.string.tunggu_sampai_nomor_anda_dipanggil)
        this.pickup.text = pickup
        steps.steps(
            getString(R.string.resep_diterima) to getString(R.string.resep_dari_dokter_diterima),
            getString(R.string.obat_disiapkan) to getString(R.string.sedang_dikerjakan_petugas_farmasi),
            getString(R.string.pemeriksaan_akhir) to getString(R.string.obat_diperiksa_apoteker),
            getString(R.string.siap_diambil) to if (ready) getString(R.string.nomor_dipanggil, pickup) else getString(R.string.tunggu_pemberitahuan_sebelum_ke_loket),
        )
        where.text = if (ready) getString(R.string.ambil_obat_di_farmasi) else getString(R.string.tunggu_di_area_farmasi)
        whereText.text = getString(R.string.gedung_a_lantai_1_loket_f2_nambil_obat, pickup)
        if (ready) demo.bind(getString(R.string.demo_staff_control), getString(R.string.simulasikan_pengambilan_selesai)) { visit { Demo.simulate("picked-up") } }
        else demo.bind(getString(R.string.demo_staff_control), getString(R.string.simulasikan_obat_siap), secondary = true) { visit { Demo.simulate("medicine-ready") } }
    }
}

class CheckInActivity : ShellActivity() {
    private var showManual = false

    override fun spec() = Spec(getString(R.string.check_in_kedatangan), Demo.HOSPITAL.uppercase(), back = VisitActivity::class.java, guide = GuideStep.CHECKIN)

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
            code.text = getString(R.string.nomor_check_in, v.checkInCode)
            status.text = when {
                !booked -> getString(R.string.sudah_check_in_2)
                v.isToday -> getString(R.string.belum_check_in_batas, v.checkInBy)
                else -> getString(R.string.check_in_dibuka_pada, v.day.long)
            }
            status.tone(if (booked) Tone.WARNING else Tone.GREEN)
            route.plainList(
                getString(R.string.naik_ke_lantai_2_melalui_lift_gedung_a_2),
                getString(R.string.tunjukkan_layar_ini_ke_petugas_check_in),
                getString(R.string.status_berubah_setelah_petugas),
            )
            manualCode.text = getString(R.string.nomor_check_in_sebutkan_kepada_petugas, v.checkInCode)
            manualCode.isVisible = showManual
            manual.setOnClickListener { showManual = !showManual; render() }
            demo.root.isVisible = booked
            demo.bind(getString(R.string.kontrol_demo_menggantikan_konfirmasi), getString(R.string.simulasikan_konfirmasi_petugas)) {
                visit { Demo.simulate("check-in") }
            }
            queue.isVisible = !booked
            queue.setOnClickListener { go(VisitActivity::class.java) }
        }
    }
}

class CompanionAccessActivity : ShellActivity() {
    override fun spec() = if (Demo.activeVisit == null) Spec(getString(R.string.akses_pendamping), back = VisitActivity::class.java)
    else Spec(getString(R.string.akses_pendamping), getString(R.string.kelola_akses_kunjungan), back = VisitActivity::class.java)

    override fun content(into: LinearLayout) {
        val v = Demo.activeVisit
        if (v == null) {
            ScreenMessageBinding.inflate(layoutInflater, into).apply {
                card.isVisible = false
                hyaHelp.root.isVisible = false
                notice.isVisible = true
                notice.text = getString(R.string.tidak_ada_kunjungan_aktif_untuk_dibagikan)
                notice.tone(Tone.WARNING)
                plainAction.isVisible = true
                plainAction.text = getString(R.string.buka_kunjungan)
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
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(getString(R.string.tautan_pendamping), link))
                copy.text = getString(R.string.tautan_disalin)
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

    override fun spec() = Spec(getString(R.string.pantauan_keluarga), getString(R.string.akses_pendamping_2), bare = true)

    override fun content(into: LinearLayout) {
        val v = Demo.activeVisit?.takeIf { it.companionActive && Demo.companionLink == intent.getStringExtra(TOKEN) }
        if (v == null) {
            ScreenMessageBinding.inflate(layoutInflater, into).apply {
                card.isVisible = false
                hyaHelp.root.isVisible = false
                notice.isVisible = true
                notice.text = getString(R.string.akses_pendamping_tidak_aktif_atau_telah)
                notice.tone(Tone.WARNING)
            }
            into.text(R.style.Text_P_Muted, getString(R.string.minta_tautan_baru_kepada_pasien_bila))
            return
        }
        val (eyebrow, headline) = when (v.stage) {
            Stage.BOOKED -> getString(R.string.belum_check_in) to getString(R.string.menunggu_kedatangan)
            Stage.WAITING -> getString(R.string.menunggu_giliran_2) to getString(R.string.pasien_lagi, v.ahead) + (v.waitRange?.let { getString(R.string.menit_2, it) } ?: "")
            Stage.SERVING -> getString(R.string.giliran_dipanggil) to getString(R.string.sedang_menuju_ruang_pemeriksaan)
            Stage.LABORATORY -> getString(R.string.laboratorium) to getString(R.string.menuju_atau_sedang_di_laboratorium)
            else -> getString(R.string.farmasi) to getString(R.string.medicine_status, v.rx?.label ?: getString(R.string.menunggu_resep_lower))
        }
        val (room, building) = when (v.stage) {
            Stage.PHARMACY -> getString(R.string.loket_f2) to getString(R.string.gedung_a_lantai_1)
            Stage.LABORATORY -> getString(R.string.ruang_a_108) to getString(R.string.gedung_a_lantai_1)
            else -> Demo.splitRoom(v.clinic.room)
        }
        ScreenCompanionViewBinding.inflate(layoutInflater, into).apply {
            access.text = getString(R.string.akses_terbatas_hanya_pantauan_diperbarui, Demo.now())
            patient.text = Demo.patientShort
            stage.text = eyebrow
            ticket.text = v.ticket
            this.headline.text = headline
            clinic.text = getString(R.string.poli_estimasi_dapat_berubah, v.clinic.name)
            this.room.text = room
            this.building.text = listOf(building, Demo.HOSPITAL).filter { it.isNotEmpty() }.joinToString(" · ")
            stop.setOnClickListener { visit { Demo.revokeCompanion(byCompanion = true) } }
        }
    }
}
