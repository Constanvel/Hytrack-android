package com.constanvel.hytracl

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.constanvel.hytracl.Demo.Stage
import com.constanvel.hytracl.databinding.ItemDetailsBinding
import com.constanvel.hytracl.databinding.ItemMessageBinding
import com.constanvel.hytracl.databinding.ItemSuggestionBinding
import com.constanvel.hytracl.databinding.ItemTopicBinding
import com.constanvel.hytracl.databinding.ScreenDirectionsBinding
import com.constanvel.hytracl.databinding.ScreenHelpBinding
import com.constanvel.hytracl.databinding.ScreenHelpCheckinBinding
import com.constanvel.hytracl.databinding.ScreenHelpQueueBinding
import com.constanvel.hytracl.databinding.ScreenHyaBinding
import com.constanvel.hytracl.databinding.ViewComposerBinding

class HyaActivity : ShellActivity() {
    private class Message(val user: Boolean, val text: String)

    private val messages = mutableListOf<Message>()
    private var pending = false
    /** Clock of the first question, for the day stamp above the chat. */
    private var startedAt = ""
    private lateinit var composer: ViewComposerBinding

    override fun spec() = Spec(getString(R.string.hya), getString(R.string.asisten_digital_hytrack), back = VisitActivity::class.java, hya = true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return
        composer = ViewComposerBinding.inflate(layoutInflater, shell.footer, true)
        shell.footer.setBackgroundResource(R.color.white) // also behind the system navigation bar
        composer.input.doAfterTextChanged { syncSend() }
        composer.input.setOnEditorActionListener { _, id, _ -> (id == EditorInfo.IME_ACTION_SEND).also { if (it) ask(composer.input.text.toString()) } }
        composer.send.setOnClickListener { ask(composer.input.text.toString()) }
        syncSend()
    }

    private fun syncSend() {
        val enabled = composer.input.text.isNotBlank() && !pending
        composer.send.isEnabled = enabled
        composer.send.alpha = if (enabled) 1f else 0.55f
    }

    /** Hya answers locally (HyaResponder port); the short pause mirrors the web request. */
    private fun ask(question: String) {
        val value = question.trim().take(500)
        if (value.isEmpty() || pending) return
        if (messages.isEmpty()) startedAt = Demo.now()
        messages += Message(true, value)
        composer.input.text.clear()
        pending = true
        syncSend()
        render()
        shell.root.postDelayed({
            messages += Message(false, Hya.reply(value, Demo.visit))
            pending = false
            syncSend()
            render()
        }, 450)
    }

    private fun summary(v: Demo.Visit) = when (v.stage) {
        Stage.BOOKED -> getString(R.string.belum_check_in_2)
        Stage.WAITING -> getString(R.string.pasien_lagi, v.ahead)
        Stage.SERVING -> getString(R.string.giliran_anda_2)
        Stage.LABORATORY -> getString(R.string.layanan_laboratorium)
        Stage.PHARMACY -> getString(R.string.penyiapan_obat)
        else -> ""
    }

    override fun content(into: LinearLayout) {
        val v = Demo.activeVisit
        val b = ScreenHyaBinding.inflate(layoutInflater, into)
        val chatting = messages.isNotEmpty()
        b.intro.isVisible = !chatting
        b.chat.isVisible = chatting
        if (!chatting) {
            b.visit.text = if (v != null) HtmlCompat.fromHtml(
                getString(R.string.hya_active_visit, v.ticket, summary(v), v.clinic.name), HtmlCompat.FROM_HTML_MODE_LEGACY,
            ) else getString(R.string.belum_ada_kunjungan_aktif_anda_tetap_bisa)
            listOf(getString(R.string.kapan_giliran_saya), getString(R.string.ke_ruangan_mana_saya_harus_pergi), getString(R.string.bagaimana_cara_check_in)).forEach { q ->
                ItemSuggestionBinding.inflate(layoutInflater, b.suggestions, true).apply {
                    question.text = q
                    root.setOnClickListener { ask(q) }
                }
            }
            return
        }
        // Conversation (Figma 43:2 frame 21): visit card, day stamp, turns, then where to wait.
        b.context.text = if (v != null) getString(R.string.poli_2, v.ticket, v.clinic.name) else getString(R.string.panduan_layanan)
        b.contextTime.isVisible = v != null
        b.contextTime.text = getString(R.string.data_pukul, Demo.now())
        b.day.text = getString(R.string.hari_ini_pukul, startedAt)
        val column = minOf(resources.displayMetrics.widthPixels, into.dp(540)) - into.dp(48)
        val bubbles = messages.map { it.user to it.text } + if (pending) listOf(false to getString(R.string.menyiapkan_jawaban)) else emptyList()
        bubbles.forEach { (user, text) ->
            ItemMessageBinding.inflate(layoutInflater, b.messages, false).apply {
                body.text = text
                from.isVisible = !user
                if (user) {
                    root.gravity = Gravity.END
                    body.setBackgroundResource(R.drawable.bg_msg_user)
                    body.setTextColor(ContextCompat.getColor(this@HyaActivity, R.color.white))
                    body.textSize = 16f
                    body.setPadding(into.dp(16), into.dp(16), into.dp(16), into.dp(16))
                    (body.layoutParams as ViewGroup.MarginLayoutParams).marginStart = 0
                    body.maxWidth = column - into.dp(98) // max-width: calc(100% - 98px)
                } else {
                    body.maxWidth = column - into.dp(40)
                }
                b.messages.addView(root)
            }
        }
        val room = v?.clinic?.room
        b.place.isVisible = room != null
        b.source.isVisible = room != null
        b.directions.root.isVisible = room == null
        if (room != null) {
            val (name, building) = Demo.splitRoom(room)
            b.placeRoom.text = name
            b.placeBuilding.text = building
            b.placeBuilding.isVisible = building.isNotEmpty()
            b.place.contentDescription = getString(R.string.tempat_menunggu_detail, name, building)
            b.place.setOnClickListener { go(DirectionsActivity::class.java) }
            b.source.text = getString(R.string.sumber_antrean_kunjungan, Demo.now())
        } else {
            b.directions.bind(getString(R.string.buka_petunjuk_ruangan)) { go(DirectionsActivity::class.java) }
        }
        b.late.isEnabled = !pending
        b.late.setOnClickListener { ask(getString(R.string.bagaimana_jika_saya_terlambat)) }
        b.restart.isVisible = true
        b.restart.setOnClickListener { messages.clear(); render() }
        // The medical notice joins the indented column under Hya's reply.
        b.warning.apply {
            (layoutParams as ViewGroup.MarginLayoutParams).marginStart = into.dp(40)
            textSize = 12f
            typeface = ResourcesCompat.getFont(this@HyaActivity, R.font.inter_regular)
            setTextColor(ContextCompat.getColor(this@HyaActivity, R.color.chat_warning))
        }
        // scrollIntoView({ block: 'nearest' }) on the row under the messages: only if it is hidden.
        shell.scroll.post {
            val rect = android.graphics.Rect().also { b.follow.getDrawingRect(it) }
            shell.scroll.offsetDescendantRectToMyCoords(b.follow, rect)
            val visibleBottom = shell.scroll.scrollY + shell.scroll.height - shell.footer.height
            if (rect.bottom > visibleBottom) shell.scroll.smoothScrollBy(0, rect.bottom - visibleBottom)
        }
    }
}

class HelpActivity : ShellActivity() {
    override val tab = Tab.ACCOUNT

    private class Topic(val title: String, val detail: String, val target: Class<out Activity>)

    private val topics by lazy {
        listOf(
        Topic(getString(R.string.antrean_jadwal_dokter), getString(R.string.nomor_perkiraan_tunggu_dan_perubahan), QueueGuideActivity::class.java),
        Topic(getString(R.string.check_in_dengan_qr), getString(R.string.cara_konfirmasi_kedatangan_di_rumah_sakit), CheckInGuideActivity::class.java),
        Topic(getString(R.string.arah_layanan_pengambilan_obat), getString(R.string.temukan_ruang_tujuan_dan_loket_farmasi), DirectionsActivity::class.java),
        Topic(getString(R.string.akses_pendamping), getString(R.string.bagikan_pantau_dan_cabut_akses_keluarga), CompanionAccessActivity::class.java),
        )
    }

    override fun spec() = Spec(getString(R.string.bantuan), getString(R.string.panduan_kunjungan_anda_2))

    override fun content(into: LinearLayout) {
        val b = ScreenHelpBinding.inflate(layoutInflater, into)
        b.support.setOnClickListener { go(HyaActivity::class.java) }
        b.search.query.hint = getString(R.string.cari_topik_bantuan)
        b.search.query.contentDescription = getString(R.string.cari_topik_bantuan)
        guideRestart(b.guideRestart)
        fun filter(query: String) {
            b.topics.removeAllViews()
            val found = topics.filter { "${it.title} ${it.detail}".contains(query.trim(), ignoreCase = true) }
            found.forEach { topic ->
                ItemTopicBinding.inflate(layoutInflater, b.topics, true).apply {
                    title.text = topic.title
                    detail.text = topic.detail
                    root.setOnClickListener { go(topic.target) }
                }
            }
            b.noTopic.isVisible = found.isEmpty()
        }
        b.search.query.doAfterTextChanged { filter(it.toString()) }
        filter("")
    }
}

class CheckInGuideActivity : ShellActivity() {
    override val tab = Tab.ACCOUNT

    override fun spec() = Spec(getString(R.string.panduan_check_in), getString(R.string.bantuan_kedatangan), back = HelpActivity::class.java)

    override fun content(into: LinearLayout) {
        val b = ScreenHelpCheckinBinding.inflate(layoutInflater, into)
        b.steps.steps(
            getString(R.string.buka_tiket_kunjungan) to getString(R.string.pilih_kunjungan_aktif_lalu_tekan),
            getString(R.string.tunjukkan_qr_ke_petugas) to getString(R.string.gunakan_qr_dari_tiket_anda_petugas_akan),
            getString(R.string.periksa_status_kehadiran) to getString(R.string.status_menjadi_sudah_check_in_setelah),
            numbered = true,
        )
        b.openVisit.setOnClickListener { go(VisitActivity::class.java) }
    }
}

class QueueGuideActivity : ShellActivity() {
    override val tab = Tab.ACCOUNT
    private val open = booleanArrayOf(true, false, false) // <details open> on the first one

    override fun spec() = Spec(getString(R.string.antrean_jadwal), getString(R.string.bantuan_rawat_jalan), back = HelpActivity::class.java)

    override fun content(into: LinearLayout) {
        val b = ScreenHelpQueueBinding.inflate(layoutInflater, into)
        listOf(
            b.faq1 to (getString(R.string.bagaimana_mengambil_antrean) to getString(R.string.pilih_poliklinik_dokter_dan_jadwal_tekan)),
            b.faq2 to (getString(R.string.apakah_waktu_tunggu_pasti) to getString(R.string.tidak_estimasi_berubah_mengikuti)),
            b.faq3 to (getString(R.string.bagaimana_jika_saya_terlambat) to getString(R.string.hubungi_petugas_di_meja_check_in)),
        ).forEachIndexed { i, (faq: ItemDetailsBinding, qa) ->
            faq.question.text = qa.first
            faq.answer.text = qa.second
            faq.answer.isVisible = open[i]
            faq.marker.text = if (open[i]) "▼" else "▶"
            faq.summary.setOnClickListener { open[i] = !open[i]; render() }
        }
        b.openVisit.setOnClickListener { go(VisitActivity::class.java) }
    }
}

class DirectionsActivity : ShellActivity() {
    override fun spec() = Spec(getString(R.string.petunjuk_ruangan), getString(R.string.gedung_a_rawat_jalan), back = VisitActivity::class.java)

    override fun content(into: LinearLayout) {
        val b = ScreenDirectionsBinding.inflate(layoutInflater, into)
        b.steps.steps(
            getString(R.string.laboratorium_a_108) to getString(R.string.dari_ruang_a_204_gunakan_lift_di_sebelah),
            getString(R.string.farmasi_loket_f2) to getString(R.string.gedung_a_lantai_1_ambil_obat_setelah),
        )
        b.help.setOnClickListener { go(HelpActivity::class.java) }
    }
}
