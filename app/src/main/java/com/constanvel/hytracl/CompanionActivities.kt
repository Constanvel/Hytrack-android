package com.constanvel.hytracl

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.constanvel.hytracl.databinding.ScreenCompanionBinding
import com.constanvel.hytracl.databinding.ViewRowLinkBinding
import org.json.JSONObject

/*
 * Family Companion over the Laravel API (App\Services\CompanionInvitations): an invitation per
 * visit, accepted with the companion's own account, single-use, ending with the visit or at
 * 23.59 on the service day, revocable by the patient at any time.
 */

private val liveStages = setOf("booked", "waiting", "serving", "laboratory", "pharmacy")

/** Patient side (web CompanionAccess): invite, see who accepted, revoke. */
class CompanionAccessActivity : ShellActivity() {
    private var visit: JSONObject? = null
    private var invitations: List<JSONObject>? = null
    private var problem: String? = null
    private var busy = false
    /** The new link is shown once, right after it is created (only its hash is stored). */
    private var newLink: String? = null

    private val active get() = visit?.str("stage") in liveStages

    override fun spec() = if (invitations != null && !active) Spec(getString(R.string.akses_pendamping), back = VisitActivity::class.java)
    else Spec(getString(R.string.akses_pendamping), getString(R.string.kelola_akses_kunjungan), back = VisitActivity::class.java)

    override fun onResume() {
        super.onResume()
        if (Api.online) load()
    }

    private fun load() = request({
        Api.call("GET", "akun").optJSONObject("visit") to Api.call("GET", "pendamping").getJSONArray("invitations").objects()
    }) { result ->
        result.onSuccess { (v, list) -> visit = v; invitations = list; problem = null }.onFailure { problem = it.message }
        render()
    }

    private fun act(work: () -> Unit, done: () -> Unit = {}) {
        busy = true
        render()
        request(work) { result ->
            busy = false
            result.onSuccess { done() }.onFailure { Demo.warn(it.message) }
            load()
        }
    }

    override fun content(into: LinearLayout) {
        if (!Api.online || problem != null) return into.serverNeeded(problem) { load() }
        val list = invitations ?: return run { into.text(R.style.Text_P_Muted, getString(R.string.memuat)) }
        val v = visit
        if (v == null || !active) {
            into.notice(getString(R.string.tidak_ada_kunjungan_aktif_untuk_dibagikan), Tone.WARNING)
            into.add(R.layout.view_row_link).let { ViewRowLinkBinding.bind(it) }.bind(getString(R.string.buka_kunjungan)) { go(VisitActivity::class.java) }
            return
        }
        val shown = newLink
        ScreenCompanionBinding.inflate(layoutInflater, into).apply {
            who.text = "${v.str("patientName")} · ${v.str("ticket")}"
            `when`.text = "${v.str("dateLabel")} · ${Demo.HOSPITAL}"
            linkCard.isVisible = shown != null
            this.link.text = shown
            copy.setOnClickListener {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(getString(R.string.tautan_pendamping), shown))
                copy.text = getString(R.string.tautan_disalin)
            }
            share.setOnClickListener {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.undangan_pendamping_hytrack))
                    .putExtra(Intent.EXTRA_TEXT, shown), getString(R.string.bagikan_tautan)))
            }
            invite.text = getString(if (list.isEmpty()) R.string.undang_pendamping else R.string.undang_pendamping_lain)
            invite.isEnabled = !busy
            invite.alpha = if (busy) 0.6f else 1f
            invite.setOnClickListener {
                act({ newLink = Api.call("POST", "pendamping").getString("link") }) { Demo.flash = getString(R.string.undangan_pendamping_dibuat) }
            }
            invitations.isVisible = list.isNotEmpty()
            list.forEach { item -> row(invitations, item) }
        }
    }

    /** "Ahmad F. · Aktif · sejak 09.12" and, while it is live, Cabut akses. */
    private fun row(parent: ViewGroup, item: JSONObject) {
        val status = item.getString("status")
        val live = status == "pending" || status == "accepted"
        val card = LinearLayout(this, null, 0, R.style.Widget_HyTrack_Card).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (!live) alpha = 0.7f
        }
        parent.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        card.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        text.text(R.style.Text_Strong, item.str("companion") ?: getString(R.string.belum_diterima))
        val since = if (status == "accepted") item.str("acceptedAt")?.let { getString(R.string.sejak, it) }
        else item.str("sentAt")?.let { getString(R.string.dikirim, it) }
        text.text(R.style.Text_Small_Muted, listOfNotNull(item.getString("label"), since).joinToString(" · "))
        if (live) card.addView(android.widget.TextView(this, null, 0, R.style.Widget_HyTrack_TextButton).apply {
            this.text = getString(R.string.cabut_akses)
            setTextColor(ContextCompat.getColor(context, R.color.warning_text))
            isEnabled = !busy
            setOnClickListener {
                act({ Api.call("DELETE", "pendamping/${item.getInt("id")}") }) { Demo.flash = getString(R.string.akses_pendamping_dicabut) }
            }
        })
    }
}

/**
 * Opened from an invitation link (http(s)://<host>/pantauan/<token>). Shows who invites and
 * what the companion will see; accepting needs the companion's own account.
 */
class CompanionInviteActivity : ShellActivity() {
    override val requiresSignIn = false
    private val token by lazy { intent.data?.lastPathSegment ?: intent.getStringExtra(TOKEN).orEmpty() }
    private var invite: JSONObject? = null
    private var canAccept = false
    private var problem: String? = null
    private var loaded = false
    private var busy = false

    companion object {
        const val TOKEN = "token"
    }

    override fun spec() = Spec(getString(R.string.undangan_pendamping), getString(R.string.akses_pendamping_2), bare = true)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Not signed in against the server: sign in first, then come back to this invitation.
        if (!Api.online) {
            Demo.pendingInvite = intent.data?.lastPathSegment ?: intent.getStringExtra(TOKEN)
            Demo.signOut()
        }
        super.onCreate(savedInstanceState)
        if (!Api.online && !isFinishing) {
            startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        if (Api.online && !loaded) request({ Api.call("GET", "pantauan/$token") }) { result ->
            loaded = true
            result.onSuccess { invite = it.getJSONObject("invite"); canAccept = it.optBoolean("canAccept") }
                .onFailure { problem = if ((it as? Api.Failure)?.status == 404) null else it.message }
            render()
        }
    }

    override fun content(into: LinearLayout) {
        if (problem != null) return into.serverNeeded(problem)
        if (!loaded) return run { into.text(R.style.Text_P_Muted, getString(R.string.memuat)) }
        val item = invite
        if (item == null) {
            into.notice(getString(R.string.undangan_tidak_berlaku), Tone.WARNING)
            into.text(R.style.Text_P_Muted, getString(R.string.minta_undangan_baru))
            return
        }
        into.text(R.style.Text_H2, getString(R.string.mengundang_anda, item.getString("patientName")))
        LinearLayout(this, null, 0, R.style.Widget_HyTrack_Card).apply {
            into.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            text(R.style.Text_Eyebrow, getString(R.string.kunjungan_keluarga))
            text(R.style.Text_H3, getString(R.string.poli, item.getString("clinic")))
            text(R.style.Text_P_Muted, item.getString("date"))
            text(R.style.Text, getString(R.string.yang_dilihat_pendamping))
            text(R.style.Text_Small_Muted, getString(R.string.hanya_pantauan))
        }
        if (!canAccept) return run { into.notice(getString(R.string.undangan_untuk_pendamping), Tone.INFO) }
        into.text(R.style.Widget_HyTrack_Button, getString(R.string.terima_undangan)).apply {
            isEnabled = !busy
            setOnClickListener {
                busy = true
                render()
                request({ Api.call("POST", "pantauan/$token").getInt("id") }) { result ->
                    busy = false
                    result.onSuccess { id ->
                        go(CompanionMonitorActivity::class.java) { putExtra(CompanionMonitorActivity.SHARE, id) }
                        finish()
                    }.onFailure { Demo.warn(it.message); loaded = false; onResume() }
                }
            }
        }
    }
}

/** Pantauan keluarga: every visit this account accompanies right now. */
class CompanionListActivity : ShellActivity() {
    override val tab = Tab.HOME
    private var patients: List<JSONObject>? = null
    private var problem: String? = null

    override fun spec() = Spec(getString(R.string.pantauan_keluarga), getString(R.string.akses_pendamping_2), back = HomeActivity::class.java)

    override fun onResume() {
        super.onResume()
        if (Api.online) request({ Api.call("GET", "pendampingan").getJSONArray("patients").objects() }) { result ->
            result.onSuccess { patients = it; problem = null }.onFailure { problem = it.message }
            render()
        }
    }

    override fun content(into: LinearLayout) {
        if (!Api.online || problem != null) return into.serverNeeded(problem)
        val list = patients ?: return run { into.text(R.style.Text_P_Muted, getString(R.string.memuat)) }
        into.text(R.style.Text_H2, getString(R.string.kunjungan_yang_anda_dampingi))
        if (list.isEmpty()) {
            LinearLayout(this, null, 0, R.style.Widget_HyTrack_Card_Empty).apply {
                into.addView(this, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                text(R.style.Text_H3, getString(R.string.belum_ada_pantauan_aktif))
                text(R.style.Text_P_Muted, getString(R.string.buka_tautan_undangan))
            }
            return
        }
        val card = LinearLayout(this, null, 0, R.style.Widget_HyTrack_Card)
        into.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        list.forEach { item ->
            ViewRowLinkBinding.bind(card.add(R.layout.view_row_link)).bind(item.getString("name"), item.getString("detail")) {
                go(CompanionMonitorActivity::class.java) { putExtra(CompanionMonitorActivity.SHARE, item.getInt("id")) }
            }
        }
    }
}

/** Pantauan pendamping (Figma 27:303): live progress of one accompanied visit, from real journey data. */
class CompanionMonitorActivity : ShellActivity() {
    companion object {
        const val SHARE = "share"
        private const val POLL_MS = 15_000L // like the web's useLive pollSeconds
    }

    override val tab = Tab.HOME
    private var share = 0
    private var data: JSONObject? = null
    private var problem: String? = null
    private var syncedAt: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() {
            load()
            handler.postDelayed(this, POLL_MS)
        }
    }

    override fun spec() = Spec(getString(R.string.pantauan_pendamping), getString(R.string.akses_pendamping_2),
        back = CompanionListActivity::class.java, action = data?.optJSONObject("progress")?.str("phone") != null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return
        share = intent.getIntExtra(SHARE, 0)
        interHeader()
    }

    /** go() brings an open monitor to the front, so another patient arrives here rather than in onCreate. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        share = intent.getIntExtra(SHARE, 0)
        data = null
        problem = null
        render()
    }

    override fun onResume() {
        super.onResume()
        if (Api.online) handler.post(poll)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poll)
    }

    private fun load() = share.let { id ->
        request({ Api.call("GET", "pendampingan/$id") }) { result ->
            if (id != share) return@request // a reply for the patient shown before
            result.onSuccess { data = it; problem = null; syncedAt = Demo.now() }.onFailure { problem = it.message }
            render()
        }
    }

    override fun content(into: LinearLayout) {
        into.dividerDrawable = ContextCompat.getDrawable(this, R.drawable.gap_12)
        val json = data
        if (!Api.online || (json == null && problem != null)) return into.serverNeeded(problem) { load() }
        if (json == null) return run { into.text(R.style.Text_P_Muted, getString(R.string.memuat)) }
        val visit = json.optJSONObject("progress")
        if (visit == null) {
            shell.footer.isVisible = false
            into.notice(getString(R.string.akses_pendamping_berakhir, json.optString("ended").lowercase()), Tone.WARNING)
            into.text(R.style.Text_P_Muted, getString(R.string.kunjungan_selesai_atau_dicabut))
            into.text(R.style.Widget_HyTrack_Button, getString(R.string.kembali_ke_pantauan_keluarga)).setOnClickListener { go(CompanionListActivity::class.java) }
            return
        }
        visit.str("phone")?.let { phone ->
            footerButton(getString(R.string.hubungi_pasien)) { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:+$phone"))) }
        }

        // Patient and live indicator.
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        into.addView(row)
        val who = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        row.addView(who, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        who.inter(getString(R.string.kunjungan_keluarga), 10f, 14f, Weight.SEMIBOLD)
        who.inter(visit.str("patientName"), 22f, 31f, Weight.BOLD)
        row.inter(if (problem == null) getString(R.string.live_at, syncedAt) else getString(R.string.terputus), 11f, 15f, Weight.SEMIBOLD,
            if (problem == null) R.color.brand else R.color.warning_text).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
        }

        // Hero: ticket, status, wait.
        into.column(4, R.color.brand, 20, 18).apply {
            inter(visit.str("checkedInAt")?.let { getString(R.string.sudah_check_in_at, it) } ?: getString(R.string.belum_check_in_upper), 11f, 15f, Weight.SEMIBOLD, R.color.hero_eyebrow)
            val ticket = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            addView(ticket)
            ticket.inter(visit.str("ticket"), 48f, 67f, Weight.BOLD, R.color.white).layoutParams =
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(12) }
            ticket.inter(status(visit.getString("stage")), 14f, 20f, Weight.SEMIBOLD, R.color.white)
            inter(headline(visit), 16f, 22f, Weight.SEMIBOLD, R.color.white)
            visit.str("serving")?.let { inter(getString(R.string.sedang_dilayani_at, it), 12f, 17f, color = R.color.hero_text) }
            visit.optJSONObject("pharmacy")?.takeIf { visit.getString("stage") == "pharmacy" }?.let {
                inter(getString(R.string.nomor_ambil_obat, it.getString("pickupNumber")), 12f, 17f, color = R.color.hero_text)
            }
        }

        // Where the patient is now.
        into.column(2, R.color.white, 20, 16).apply {
            inter(getString(R.string.lokasi_saat_ini), 10f, 14f, Weight.SEMIBOLD)
            inter(visit.str("place"), 18f, 25f, Weight.BOLD)
            visit.str("placeDetail")?.let { inter(it, 13f, 18f, color = R.color.brand_muted) }
        }

        // Journey so far (patient_journeys) and the next stage.
        into.column(10, R.color.white, 20, 16).apply {
            inter(getString(R.string.alur_kunjungan), 16f, 22f, Weight.BOLD)
            val steps = visit.getJSONArray("timeline").objects()
            steps.forEach { step ->
                val line = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
                addView(line)
                val current = step.optBoolean("current")
                line.inter(if (current) "●" else "✓", 16f, 22f, Weight.BOLD, R.color.brand).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(15), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(10) }
                    gravity = Gravity.CENTER
                    importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }
                line.inter(step.getString("label"), 13f, 18f, if (current) Weight.SEMIBOLD else Weight.MEDIUM).layoutParams =
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                line.inter(if (current) getString(R.string.saat_ini) else step.str("time"), 11f, 15f, color = R.color.brand_muted).layoutParams =
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            inter(getString(R.string.berikutnya, visit.getString("next")), 12f, 17f, color = R.color.brand_muted)
        }

        into.column(3).apply {
            inter(getString(R.string.hanya_pantauan), 12f, 17f, Weight.MEDIUM, R.color.brand_muted)
            inter(getString(R.string.akses_berakhir_saat, visit.getString("endsAt")), 12f, 17f, color = R.color.brand_muted)
        }
        into.tipCard(getString(R.string.tetap_dekat_dengan_pasien), getString(R.string.ikuti_panggilan_petugas), padding = 14, titleSize = 13f, textSize = 12f)
        into.inter(getString(R.string.berhenti_memantau), 12f, 17f, Weight.SEMIBOLD, R.color.brand).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            minHeight = dp(32)
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setOnClickListener {
                AlertDialog.Builder(this@CompanionMonitorActivity)
                    .setMessage(R.string.berhenti_memantau_konfirmasi)
                    .setNegativeButton(R.string.batal, null)
                    .setPositiveButton(R.string.berhenti_memantau) { _, _ ->
                        request({ Api.call("DELETE", "pendampingan/$share") }) { result ->
                            result.onSuccess { Demo.flash = getString(R.string.anda_berhenti_memantau) }.onFailure { Demo.warn(it.message) }
                            go(CompanionListActivity::class.java)
                        }
                    }.show()
            }
        }
    }

    private fun status(stage: String) = getString(when (stage) {
        "booked" -> R.string.status_menunggu_kedatangan
        "waiting" -> R.string.status_menunggu_giliran
        "serving" -> R.string.status_sedang_diperiksa
        "laboratory" -> R.string.status_di_laboratorium
        else -> R.string.status_di_farmasi
    })

    private fun headline(visit: JSONObject): String = when (visit.getString("stage")) {
        "waiting" -> getString(R.string.pasien_lagi, visit.optInt("ahead").toString()) + (visit.str("waitRange")?.let { getString(R.string.sekitar_menit, it) } ?: "")
        "serving" -> getString(R.string.sedang_di_ruang_pemeriksaan)
        "laboratory" -> getString(R.string.menjalani_pemeriksaan_laboratorium)
        "pharmacy" -> getString(R.string.obat_status, visit.optJSONObject("pharmacy")?.str("label") ?: getString(R.string.menunggu_resep_lower))
        else -> getString(R.string.belum_tiba_di_meja_check_in)
    }
}
