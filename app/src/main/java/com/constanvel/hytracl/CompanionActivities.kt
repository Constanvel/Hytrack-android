package com.constanvel.hytracl

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.constanvel.hytracl.databinding.ScreenCompanionBinding
import com.constanvel.hytracl.databinding.ViewBookBarBinding
import com.constanvel.hytracl.databinding.ViewRowLinkBinding
import org.json.JSONObject

/*
 * Family Companion on the app's own data ([Local], after App\Services\CompanionInvitations): an
 * invitation per visit, accepted with the companion's own account, single-use, ending with the
 * visit, revocable by the patient at any time.
 */

/** Patient side (web CompanionAccess): invite, see who accepted, revoke. */
class CompanionAccessActivity : ShellActivity() {
    /** The new link is shown once, right after it is created. */
    private var newLink: String? = null

    private lateinit var bar: ViewBookBarBinding

    override fun spec() = if (Demo.activeVisit == null) Spec(getString(R.string.akses_pendamping), back = VisitActivity::class.java)
    else Spec(getString(R.string.akses_pendamping), getString(R.string.kelola_akses_kunjungan), back = VisitActivity::class.java, action = true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return
        // "Bagikan tautan aman" is pinned in place of the nav (Figma 09).
        bar = ViewBookBarBinding.inflate(layoutInflater, shell.footer, true)
        shell.footer.setBackgroundResource(R.color.bg) // also behind the system navigation bar
        bar.book.setOnClickListener {
            runCatching { newLink = Local.invite() }
                .onSuccess { Demo.flash = getString(R.string.undangan_pendamping_dibuat) }.onFailure { Demo.warn(it.message) }
            render()
        }
    }

    override fun content(into: LinearLayout) {
        val v = Demo.activeVisit
        if (v == null) {
            into.notice(getString(R.string.tidak_ada_kunjungan_aktif_untuk_dibagikan), Tone.WARNING)
            into.add(R.layout.view_row_link).let { ViewRowLinkBinding.bind(it) }.bind(getString(R.string.buka_kunjungan)) { go(VisitActivity::class.java) }
            return
        }
        val list = Local.invitations()
        val shown = newLink
        ScreenCompanionBinding.inflate(layoutInflater, into).apply {
            eyebrow.text = getString(if (v.isToday) R.string.kunjungan_hari_ini else R.string.kunjungan_anda)
            who.text = "${Demo.patient} · ${v.ticket}"
            `when`.text = "${v.day.long} · ${Demo.HOSPITAL}"
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
            bar.label.text = getString(if (list.isEmpty()) R.string.bagikan_tautan_aman else R.string.bagikan_tautan_baru)
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
        if (live) card.addView(TextView(this, null, 0, R.style.Widget_HyTrack_TextButton).apply {
            this.text = getString(R.string.cabut_akses)
            setTextColor(ContextCompat.getColor(context, R.color.warning_text))
            setOnClickListener {
                Local.revoke(item.getInt("id"))
                Demo.flash = getString(R.string.akses_pendamping_dicabut)
                render()
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

    companion object {
        const val TOKEN = "token"
    }

    override fun spec() = Spec(getString(R.string.undangan_pendamping), getString(R.string.akses_pendamping_2), bare = true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Not signed in: sign in first, then come back to this invitation.
        if (!Demo.signedIn && !isFinishing) {
            Demo.pendingInvite = token
            startActivity(Intent(this, LoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            finish()
        }
    }

    override fun content(into: LinearLayout) {
        val item = Local.preview(token)
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
        if (!Local.canAccept(token)) return run { into.notice(getString(R.string.undangan_untuk_pendamping), Tone.INFO) }
        into.text(R.style.Widget_HyTrack_Button, getString(R.string.terima_undangan)).setOnClickListener {
            runCatching { Local.accept(token) }.onSuccess { id ->
                go(CompanionMonitorActivity::class.java) { putExtra(CompanionMonitorActivity.SHARE, id) }
                finish()
            }.onFailure { Demo.warn(it.message); render() }
        }
    }
}

/** Pantauan keluarga: every visit this account accompanies right now. */
class CompanionListActivity : ShellActivity() {
    override val tab = Tab.HOME

    override fun spec() = Spec(getString(R.string.pantauan_keluarga), getString(R.string.akses_pendamping_2), back = HomeActivity::class.java)

    override fun content(into: LinearLayout) {
        val list = Local.following()
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

/** Antrean pasien (Figma 31:136): the queue of one accompanied patient, view only, from real queue data. */
class CompanionMonitorActivity : ShellActivity() {
    companion object {
        const val SHARE = "share"
    }

    override val tab = Tab.HOME
    private var share = 0
    private var syncedAt = Demo.now()

    // Figma 31:136 has no bottom nav: the lockup and a "Pendamping" badge head the page.
    override fun spec() = Spec(getString(R.string.antrean_pasien), brand = true, bare = true, decor = Decor.WAVES)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return
        share = intent.getIntExtra(SHARE, 0)
        shell.lockup.setOnClickListener { go(HomeActivity::class.java) }
        shell.header.addView(LinearLayout(this).apply {
            inter(getString(R.string.pendamping), 12f, 18f, Weight.SEMIBOLD, R.color.brand).apply {
                gravity = Gravity.CENTER
                minWidth = dp(116)
                setPadding(dp(16), dp(6), dp(16), dp(6))
                background = rounded(R.color.auth_benefit, 15)
            }
        })
    }

    /** go() brings an open monitor to the front, so another patient arrives here rather than in onCreate. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        share = intent.getIntExtra(SHARE, 0)
        syncedAt = Demo.now()
        render()
    }

    override fun onResume() {
        super.onResume()
        syncedAt = Demo.now()
    }

    override fun content(into: LinearLayout) {
        into.dividerDrawable = ContextCompat.getDrawable(this, R.drawable.gap_16)
        // Another account's share, or one that is gone, reads as ended (the web answers 404).
        val json = runCatching { Local.followed(share) }.getOrNull()
        val visit = json?.optJSONObject("progress")
        into.column(7).apply {
            inter(getString(R.string.antrean_pasien), 26f, 36f, Weight.BOLD).isFocusable = true
            inter(if (visit == null) getString(R.string.akses_pendamping_berakhir, (json?.str("ended") ?: "Berakhir").lowercase())
                else getString(R.string.pantau_giliran_pasien), 14f, 20f, color = R.color.brand_muted)
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(3)
        }
        if (visit == null) {
            limit(into, getString(R.string.pantauan_berakhir), getString(R.string.kunjungan_selesai_atau_dicabut))
            into.button(getString(R.string.kembali_ke_pantauan_keluarga)) { go(CompanionListActivity::class.java) }
            return
        }

        // Pasien yang didampingi.
        into.column(0, R.color.white, 14, stroke = R.color.companion_line).apply {
            setPadding(dp(15), dp(10), dp(15), dp(9))
            inter(getString(R.string.pasien_yang_didampingi_upper), 10f, 15f, Weight.SEMIBOLD, R.color.brand_muted)
            inter(visit.str("patientName"), 18f, 25f, Weight.SEMIBOLD).top(5)
            inter(getString(R.string.keluarga_pasien_terhubung), 12f, 18f, color = R.color.brand_muted).top(4)
        }

        // Queue card: ticket, clinic, then called / ahead / estimate.
        val stage = visit.getString("stage")
        val inLine = stage == "booked" || stage == "waiting"
        into.column(0, R.color.brand, 18).apply {
            setPadding(dp(20), dp(16), dp(20), dp(18))
            val head = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
            addView(head)
            head.inter(getString(R.string.nomor_antrean_pasien), 13f, 19f, Weight.MEDIUM, R.color.companion_soft).layoutParams =
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            head.inter(stageLabel(stage), 11f, 16f, Weight.SEMIBOLD, R.color.brand).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                gravity = Gravity.CENTER
                minWidth = dp(102)
                setPadding(dp(12), dp(5), dp(12), dp(5))
                background = rounded(R.color.auth_benefit, 13)
            }
            inter(visit.str("ticket"), 60f, 74f, Weight.BOLD, R.color.white).apply { top(2); maxLines = 1 }
            inter(getString(R.string.poli_x, visit.getString("clinic")), 14f, 20f, Weight.MEDIUM, R.color.white).apply {
                top(3)
                setPadding(0, 0, 0, dp(15))
            }
            addView(View(context).apply { setBackgroundColor(ContextCompat.getColor(context, R.color.companion_rule)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            val metrics = LinearLayout(context)
            addView(metrics, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(17) })
            listOf(
                getString(R.string.dipanggil) to (visit.str("serving") ?: "—"),
                getString(R.string.di_depan_anda) to (if (inLine) getString(R.string.pasien, visit.optInt("ahead")) else "—"),
                getString(R.string.estimasi) to (visit.str("waitRange")?.takeIf { inLine }?.let { getString(R.string.kira_menit, it.substringBefore('–')) } ?: "—"),
            ).forEachIndexed { i, (label, value) ->
                metrics.column(6).apply {
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = dp(12) }
                    inter(label, 11f, 16f, color = R.color.companion_soft)
                    inter(value, 19f, 27f, Weight.SEMIBOLD, R.color.white).maxLines = 1
                }
            }
            inter(getString(R.string.perbarui_antrean), 13f, 20f, Weight.SEMIBOLD, R.color.brand).apply {
                top(21)
                gravity = Gravity.CENTER
                minHeight = dp(38)
                background = rounded(R.color.white, 10)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    syncedAt = Demo.now()
                    render()
                }
            }
        }

        // Informasi kunjungan: service day, doctor's session and where to go.
        into.column(0, R.color.white, 14, stroke = R.color.companion_line).apply {
            setPadding(dp(15), dp(11), dp(15), dp(18))
            inter(getString(R.string.informasi_kunjungan), 14f, 20f, Weight.SEMIBOLD)
            inter("${visit.optString("day")} · ${visit.optString("schedule")}", 13f, 19f, color = R.color.brand_muted).top(9)
            inter(visit.str("placeDetail") ?: visit.str("place"), 13f, 19f, Weight.MEDIUM).top(10)
        }

        limit(into, getString(R.string.akses_pendamping_hanya_lihat), getString(R.string.hanya_dapat_memantau))
        into.inter(getString(R.string.diperbarui_pukul, syncedAt), 11f, 16f, color = R.color.brand_muted).apply {
            gravity = Gravity.CENTER
            top(-5)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        into.inter(getString(R.string.berhenti_memantau), 12f, 17f, Weight.SEMIBOLD, R.color.warning_text).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(-12)
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                AlertDialog.Builder(this@CompanionMonitorActivity)
                    .setMessage(R.string.berhenti_memantau_konfirmasi)
                    .setNegativeButton(R.string.batal, null)
                    .setPositiveButton(R.string.berhenti_memantau) { _, _ ->
                        Local.stop(share)
                        Demo.flash = getString(R.string.anda_berhenti_memantau)
                        go(CompanionListActivity::class.java)
                    }.show()
            }
        }
    }

    /** .companion-limit: pale mint note with a green heading. */
    private fun limit(into: LinearLayout, title: String, text: String) = into.column(8, R.color.auth_benefit, 14).apply {
        setPadding(dp(16), dp(12), dp(16), dp(13))
        inter(title, 13f, 19f, Weight.SEMIBOLD, R.color.brand)
        inter(text, 12f, 18f, color = R.color.muted)
    }

    private fun TextView.top(margin: Int) {
        (layoutParams as LinearLayout.LayoutParams).topMargin = dp(margin)
    }

    private fun stageLabel(stage: String) = getString(when (stage) {
        "booked" -> R.string.stage_belum_check_in
        "serving" -> R.string.stage_diperiksa
        "laboratory" -> R.string.stage_laboratorium
        "pharmacy" -> R.string.stage_farmasi
        else -> R.string.stage_menunggu
    })
}
