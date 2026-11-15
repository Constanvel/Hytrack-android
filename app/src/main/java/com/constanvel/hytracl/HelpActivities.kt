package com.constanvel.hytracl

import android.app.Activity
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
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
    private lateinit var composer: ViewComposerBinding

    override fun spec() = Spec("Hya", "Asisten digital HyTrack", back = VisitActivity::class.java, hya = true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return
        composer = ViewComposerBinding.inflate(layoutInflater, shell.footer, true)
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
        Stage.BOOKED -> "Belum check-in"
        Stage.WAITING -> "${v.ahead} pasien lagi"
        Stage.SERVING -> "Giliran Anda"
        Stage.LABORATORY -> "Layanan laboratorium"
        Stage.PHARMACY -> "Penyiapan obat"
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
                "KUNJUNGAN AKTIF · ${v.ticket}<br><b>${summary(v)} · Poli ${v.clinic.name}</b>", HtmlCompat.FROM_HTML_MODE_LEGACY,
            ) else "Belum ada kunjungan aktif. Anda tetap bisa bertanya tentang panduan layanan."
            listOf("Kapan giliran saya?", "Ke ruangan mana saya harus pergi?", "Bagaimana cara check-in?").forEach { q ->
                ItemSuggestionBinding.inflate(layoutInflater, b.suggestions, true).apply {
                    question.text = q
                    root.setOnClickListener { ask(q) }
                }
            }
            return
        }
        b.context.text = if (v != null) "${v.ticket} · Poli ${v.clinic.name}" else "Panduan layanan"
        // .message { max-width: 94% } of the content column.
        val maxBubble = ((resources.displayMetrics.widthPixels - into.dp(48)) * 0.94f).toInt() - into.dp(32)
        val bubbles = messages.map { it.user to it.text } + if (pending) listOf(false to "Menyiapkan jawaban…") else emptyList()
        bubbles.forEach { (user, text) ->
            ItemMessageBinding.inflate(layoutInflater, b.messages, false).apply {
                body.text = text
                body.maxWidth = maxBubble
                author.isVisible = !user
                if (user) {
                    root.setBackgroundResource(R.drawable.bg_msg_user)
                    body.setTextColor(ContextCompat.getColor(this@HyaActivity, R.color.white))
                }
                b.messages.addView(root, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = if (user) android.view.Gravity.END else android.view.Gravity.START
                })
            }
        }
        b.directions.bind("Buka petunjuk ruangan") { go(DirectionsActivity::class.java) }
        b.late.question.text = "Bagaimana jika saya terlambat?"
        b.late.arrow.isVisible = false
        b.late.root.isEnabled = !pending
        b.late.root.setOnClickListener { ask("Bagaimana jika saya terlambat?") }
        b.restart.setOnClickListener { messages.clear(); render() }
        // scrollIntoView({ block: 'nearest' }) on the row under the messages: only if it is hidden.
        shell.scroll.post {
            val rect = android.graphics.Rect().also { b.directions.root.getDrawingRect(it) }
            shell.scroll.offsetDescendantRectToMyCoords(b.directions.root, rect)
            val visibleBottom = shell.scroll.scrollY + shell.scroll.height - shell.footer.height
            if (rect.bottom > visibleBottom) shell.scroll.smoothScrollBy(0, rect.bottom - visibleBottom)
        }
    }
}

class HelpActivity : ShellActivity() {
    override val tab = Tab.ACCOUNT

    private class Topic(val title: String, val detail: String, val target: Class<out Activity>)

    private val topics = listOf(
        Topic("Antrean & jadwal dokter", "Nomor, perkiraan tunggu, dan perubahan jadwal", QueueGuideActivity::class.java),
        Topic("Check-in dengan QR", "Cara konfirmasi kedatangan di rumah sakit", CheckInGuideActivity::class.java),
        Topic("Arah layanan & pengambilan obat", "Temukan ruang tujuan dan loket farmasi", DirectionsActivity::class.java),
        Topic("Akses pendamping", "Bagikan, pantau, dan cabut akses keluarga", CompanionAccessActivity::class.java),
    )

    override fun spec() = Spec("Bantuan", "Panduan kunjungan Anda")

    override fun content(into: LinearLayout) {
        val b = ScreenHelpBinding.inflate(layoutInflater, into)
        b.support.setOnClickListener { go(HyaActivity::class.java) }
        b.search.query.hint = "Cari topik bantuan"
        b.search.query.contentDescription = "Cari topik bantuan"
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

    override fun spec() = Spec("Panduan check-in", "BANTUAN · KEDATANGAN", back = HelpActivity::class.java)

    override fun content(into: LinearLayout) {
        val b = ScreenHelpCheckinBinding.inflate(layoutInflater, into)
        b.steps.steps(
            "Buka tiket kunjungan" to "Pilih kunjungan aktif lalu tekan “Tampilkan QR check-in”.",
            "Tunjukkan QR ke petugas" to "Gunakan QR dari tiket Anda. Petugas akan memindainya.",
            "Periksa status kehadiran" to "Status menjadi “Sudah check-in” setelah konfirmasi diterima.",
        )
        b.openVisit.setOnClickListener { go(VisitActivity::class.java) }
    }
}

class QueueGuideActivity : ShellActivity() {
    override val tab = Tab.ACCOUNT
    private val open = booleanArrayOf(true, false, false) // <details open> on the first one

    override fun spec() = Spec("Antrean & jadwal", "BANTUAN · RAWAT JALAN", back = HelpActivity::class.java)

    override fun content(into: LinearLayout) {
        val b = ScreenHelpQueueBinding.inflate(layoutInflater, into)
        listOf(
            b.faq1 to ("Bagaimana mengambil antrean?" to "Pilih poliklinik, dokter, dan jadwal. Tekan Ambil antrean untuk menerbitkan tiket. Maksimal satu kunjungan aktif."),
            b.faq2 to ("Apakah waktu tunggu pasti?" to "Tidak. Estimasi berubah mengikuti pelayanan. Dengarkan juga panggilan petugas rumah sakit."),
            b.faq3 to ("Bagaimana jika saya terlambat?" to "Hubungi petugas di meja check-in. Perubahan jadwal dan kebijakan keterlambatan harus dikonfirmasi kepada rumah sakit."),
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
    override fun spec() = Spec("Petunjuk ruangan", "GEDUNG A · RAWAT JALAN", back = VisitActivity::class.java)

    override fun content(into: LinearLayout) {
        val b = ScreenDirectionsBinding.inflate(layoutInflater, into)
        b.steps.steps(
            "Laboratorium · A-108" to "Dari ruang A-204, gunakan lift di sebelah kanan. Turun ke lantai 1, keluar lift lalu belok kiri.",
            "Farmasi · Loket F2" to "Gedung A, lantai 1. Ambil obat setelah nomor pengambilan dipanggil.",
        )
        b.help.setOnClickListener { go(HelpActivity::class.java) }
    }
}
