package com.constanvel.hytracl

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Html
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import com.constanvel.hytracl.databinding.ActivityLoginBinding
import com.constanvel.hytracl.databinding.ActivityRegisterBinding
import com.constanvel.hytracl.databinding.ActivityRegisterOtpBinding
import com.constanvel.hytracl.databinding.ViewAuthFieldBinding

/** Shared field setup for AuthInput / PasswordInput. */
private fun ViewAuthFieldBinding.setup(label: String, hint: String, phone: Boolean = false, password: Boolean = false, username: Boolean = false) {
    this.label.text = label
    input.hint = hint
    icon.isVisible = phone
    val face = input.typeface // password input types reset it to monospace
    input.inputType = when {
        password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        // Sign-in also takes a staff username (web login.tsx: a text field, autocomplete="username").
        username -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        phone -> InputType.TYPE_CLASS_PHONE
        else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
    }
    input.typeface = face
    eye.isVisible = password
    if (password) {
        // Swap only the masking, so the input type, typeface and cursor stay as they are.
        eye.setOnClickListener {
            val shown = input.transformationMethod !is PasswordTransformationMethod
            val start = input.selectionStart
            val end = input.selectionEnd
            input.transformationMethod = if (shown) PasswordTransformationMethod.getInstance() else null
            input.setSelection(start, end)
            eye.contentDescription = root.context.getString(if (shown) R.string.tampilkan_kata_sandi else R.string.sembunyikan_kata_sandi)
        }
    }
}

private fun ViewAuthFieldBinding.error(message: String?) {
    error.text = message
    error.isVisible = message != null
    box.setBackgroundResource(if (message != null) R.drawable.bg_auth_input_error else R.drawable.bg_auth_input)
}

private val ViewAuthFieldBinding.value get() = input.text.toString()

/** Edge-to-edge insets plus the 412px column of auth.css on wide screens. */
private fun AppCompatActivity.authFrame(scroll: View, main: LinearLayout, gutterDp: Int = 24) {
    lightEdgeToEdge()
    val gutter = main.dp(gutterDp)
    val bottom = main.paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(scroll) { _, insets ->
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
        val side = maxOf(gutter, (scroll.width - main.dp(412)) / 2 + gutter)
        main.updatePadding(left = side, right = side, top = main.paddingTop, bottom = bottom + bars.bottom)
        scroll.updatePadding(top = bars.top)
        insets
    }
    scroll.addOnLayoutChangeListener { v, l, _, r, _, ol, _, or, _ -> if (r - l != or - ol) ViewCompat.requestApplyInsets(v) }
    cssLeading(main)
}

/** Opens where this session belongs: the companion view when signed in as Pendamping, else the home. */
fun AppCompatActivity.enterSignedIn() = when {
    // Staff and managers open their dashboard and ignore the role cards (AuthController::signedIn).
    Local.role != null -> startActivity(
        Intent(this, if (Local.role == Local.Role.STAFF) StaffActivity::class.java else ManagementActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
    )
    Demo.companion -> enterCompanion()
    else -> enterApp()
}

private fun AppCompatActivity.enterApp() {
    startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    // An invitation link opened while signed out continues once signed in.
    Demo.pendingInvite?.let { token ->
        Demo.pendingInvite = null
        startActivity(Intent(this, CompanionInviteActivity::class.java).putExtra(CompanionInviteActivity.TOKEN, token))
    }
}

/**
 * "Masuk sebagai Pendamping" opens the companion view: the one visit this account follows,
 * else the list (web AuthController::signedIn). An invitation link waiting to open comes first.
 */
private fun AppCompatActivity.enterCompanion() {
    if (Demo.pendingInvite != null) return enterApp()
    val only = Local.following().singleOrNull()
    enterApp()
    startActivity(Intent(this, if (only != null) CompanionMonitorActivity::class.java else CompanionListActivity::class.java)
        .apply { only?.let { putExtra(CompanionMonitorActivity.SHARE, it.getInt("id")) } })
}

/** Role card of Figma 31:2 (auth.css .auth-role): icon, radio, name and a two-line detail. */
private fun LinearLayout.roleCard(icon: Int, label: String, detail: String, onPick: () -> Unit): FrameLayout {
    val card = FrameLayout(context).apply {
        minimumHeight = dp(112)
        setPadding(dp(11), dp(9), dp(11), dp(7))
        isClickable = true
        isFocusable = true
        contentDescription = "$label. ${detail.replace('\n', ' ')}"
        setOnClickListener { onPick() }
    }
    addView(card, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
    val stack = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    card.addView(stack)
    stack.addView(ImageView(context).apply { setImageResource(icon); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
        LinearLayout.LayoutParams(dp(28), dp(28)))
    stack.inter(label, 15f, 21f, Weight.SEMIBOLD).apply { tag = "label"; (layoutParams as LinearLayout.LayoutParams).topMargin = dp(6) }
    stack.inter(detail, 12f, 18f, color = R.color.brand_muted).apply { tag = "detail"; (layoutParams as LinearLayout.LayoutParams).topMargin = dp(3) }
    stack.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    card.addView(View(context).apply { tag = "radio" },
        FrameLayout.LayoutParams(dp(16), dp(16), Gravity.TOP or Gravity.END).apply { topMargin = dp(6); marginEnd = dp(2) })
    return card
}

private fun FrameLayout.pick(chosen: Boolean) {
    val brand = ContextCompat.getColor(context, R.color.brand)
    background = GradientDrawable().apply {
        cornerRadius = dp(12).toFloat()
        setColor(ContextCompat.getColor(context, if (chosen) R.color.auth_benefit else R.color.white))
        setStroke(if (chosen) dp(1.5) else dp(1), if (chosen) brand else 0xFFDCE8E1.toInt())
    }
    findViewWithTag<View>("radio").background = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        // Checked: a white dot inside a 4dp green ring.
        setColor(ContextCompat.getColor(context, R.color.white))
        setStroke(if (chosen) dp(4.5) else dp(1), if (chosen) brand else ContextCompat.getColor(context, R.color.brand_muted))
    }
    findViewWithTag<android.widget.TextView>("label").setTextColor(if (chosen) brand else ContextCompat.getColor(context, R.color.auth_text))
    findViewWithTag<android.widget.TextView>("detail").setTextColor(ContextCompat.getColor(context, if (chosen) R.color.auth_benefit_text else R.color.brand_muted))
    ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
        override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
            super.onInitializeAccessibilityNodeInfo(host, info)
            info.className = RadioButton::class.java.name
            info.isCheckable = true
            info.isChecked = chosen
        }
    })
}

class LoginActivity : AppCompatActivity() {
    /** Masuk sebagai: "patient" or "companion"; both sign in a patient account. */
    private var role = "patient"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Demo.inSession) return enterSignedIn()
        val b = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(b.root)
        authFrame(b.scroll, b.main)
        b.identity.authTitle.text = getString(R.string.selamat_datang_kembali)
        b.identity.authIntro.text = getString(R.string.masuk_untuk_melanjutkan_kunjungan_ndan)
        b.phone.setup(getString(R.string.nomor_ponsel), getString(R.string.phone_hint), phone = true, username = true)
        b.password.setup(getString(R.string.kata_sandi), getString(R.string.masukkan_kata_sandi), password = true)
        b.password.input.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        role = savedInstanceState?.getString("role") ?: role
        val cards = mutableMapOf<String, FrameLayout>()
        fun choose(value: String) {
            role = value
            cards.forEach { (key, card) -> card.pick(key == role) }
        }
        cards["patient"] = b.roles.roleCard(R.drawable.ic_role_patient, getString(R.string.role_pasien), getString(R.string.berobat_untuk_diri_sendiri)) { choose("patient") }
        cards["companion"] = b.roles.roleCard(R.drawable.ic_role_companion, getString(R.string.pendamping), getString(R.string.membantu_kunjungan_keluarga)) { choose("companion") }
        choose(role)
        b.forgot.setOnClickListener { b.forgotNotice.isVisible = true }
        b.contact.setOnClickListener { b.contactNotice.isVisible = true }
        b.switchLink.setOnClickListener { startActivity(Intent(this, RegisterActivity::class.java)) }
        b.internalLink.setOnClickListener { startActivity(Intent(this, InternalLoginActivity::class.java)) }
        b.submit.setOnClickListener {
            val phone = b.phone.value.trim()
            val password = b.password.value
            b.phone.error(if (phone.isEmpty()) getString(R.string.nomor_ponsel_wajib_diisi) else null)
            b.password.error(if (password.isEmpty()) getString(R.string.kata_sandi_wajib_diisi) else null)
            if (phone.isEmpty() || password.isEmpty()) return@setOnClickListener
            if (Local.login(phone, password)) {
                Demo.companion = role == "companion"
                enterSignedIn()
            } else {
                // A username, not a phone number: staff and managers use the internal sign-in.
                b.phone.error(getString(if (phone.any(Char::isLetter)) R.string.akun_internal_di_sini else R.string.nomor_ponsel_atau_kata_sandi_salah))
                b.password.input.text.clear()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("role", role)
    }
}

/**
 * Login · Petugas dan Manajemen (Figma 47:2345, web InternalLogin): the chosen role must match the
 * account, checked by POST /api/masuk with "as". There is no self-service reset; accounts are
 * issued and reset by the hospital's administrator.
 */
class InternalLoginActivity : AppCompatActivity() {
    private var role = "staff"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Local.role != null) return enterSignedIn()
        val b = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(b.root)
        authFrame(b.scroll, b.main)
        b.identity.authTitle.text = getString(R.string.masuk_ke_hytrack)
        b.identity.authIntro.text = getString(R.string.internal_intro)
        b.phone.setup(getString(R.string.id_pengguna_atau_email), getString(R.string.masukkan_id_pengguna), username = true)
        b.password.setup(getString(R.string.kata_sandi), getString(R.string.masukkan_kata_sandi), password = true)
        b.password.input.imeOptions = EditorInfo.IME_ACTION_DONE
        role = savedInstanceState?.getString("role") ?: role
        val cards = mutableMapOf<String, FrameLayout>()
        fun choose(value: String) {
            role = value
            cards.forEach { (key, card) -> card.pick(key == role) }
        }
        // Same artwork as public/assets/role-staff.svg and role-manager.svg.
        cards["staff"] = b.roles.roleCard(R.drawable.ic_role_patient, getString(R.string.role_petugas), getString(R.string.role_petugas_detail)) { choose("staff") }
        cards["manager"] = b.roles.roleCard(R.drawable.ic_role_companion, getString(R.string.role_manajemen), getString(R.string.role_manajemen_detail)) { choose("manager") }
        choose(role)
        b.forgotNotice.text = getString(R.string.internal_recovery)
        b.forgot.setOnClickListener { b.forgotNotice.isVisible = true; b.contactNotice.isVisible = false }
        b.switchPrompt.text = getString(R.string.belum_memiliki_akses)
        b.switchLink.text = getString(R.string.hubungi_administrator)
        b.contactNotice.text = getString(R.string.internal_access)
        b.switchLink.setOnClickListener { b.contactNotice.isVisible = true; b.forgotNotice.isVisible = false }
        b.benefit.isVisible = false
        b.contact.isVisible = false
        b.internalLink.isVisible = false
        b.submit.setOnClickListener {
            val username = b.phone.value.trim()
            val password = b.password.value
            b.phone.error(if (username.isEmpty()) getString(R.string.id_pengguna_wajib_diisi) else null)
            b.password.error(if (password.isEmpty()) getString(R.string.kata_sandi_wajib_diisi) else null)
            if (username.isEmpty() || password.isEmpty()) return@setOnClickListener
            b.submit.isEnabled = false
            b.submit.text = getString(R.string.memeriksa)
            request({ Api.login(username, password, role) }) { result ->
                b.submit.isEnabled = true
                b.submit.text = getString(R.string.masuk)
                result.onSuccess { enterSignedIn() }.onFailure { e ->
                    val failure = e as? Api.Failure
                    // The role mismatch comes back on "as"; wrong credentials on "username".
                    b.phone.error(failure?.message ?: Api.OFFLINE)
                    b.password.input.text.clear()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("role", role)
    }
}

class RegisterActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(b.root)
        authFrame(b.scroll, b.main)
        b.identity.authTitle.text = getString(R.string.mulai_berobat_lebih_tenang)
        b.identity.authIntro.text = getString(R.string.buat_akun_untuk_mengatur_kunjungan_anda)
        b.name.setup(getString(R.string.nama_lengkap), getString(R.string.sesuai_identitas_anda))
        b.phone.setup(getString(R.string.nomor_ponsel), getString(R.string.phone_hint), phone = true)
        b.password.setup(getString(R.string.kata_sandi), getString(R.string.minimal_8_karakter), password = true)
        b.confirm.setup(getString(R.string.konfirmasi_kata_sandi), getString(R.string.ulangi_kata_sandi), password = true)
        b.terms.text = Html.fromHtml(getString(R.string.terms_html), Html.FROM_HTML_MODE_LEGACY)
        b.contact.setOnClickListener { b.contactNotice.isVisible = true }
        b.switchLink.setOnClickListener { finish() }
        b.submit.setOnClickListener {
            val name = b.name.value.trim()
            val phone = Demo.normalizePhone(b.phone.value)
            val password = b.password.value
            b.name.error(when {
                name.isEmpty() -> getString(R.string.nama_lengkap_wajib_diisi)
                name.length > 100 -> getString(R.string.nama_lengkap_maksimal_100_karakter)
                else -> null
            })
            b.phone.error(when {
                b.phone.value.isBlank() -> getString(R.string.nomor_ponsel_wajib_diisi)
                !Regex("^628\\d{7,11}$").matches(phone) -> getString(R.string.phone_invalid)
                Local.phoneTaken(phone) -> getString(R.string.nomor_ponsel_sudah_terdaftar_silakan)
                else -> null
            })
            b.password.error(when {
                password.isEmpty() -> getString(R.string.kata_sandi_wajib_diisi)
                password.length < 8 -> getString(R.string.kata_sandi_minimal_8_karakter)
                password != b.confirm.value -> getString(R.string.konfirmasi_kata_sandi_tidak_sama)
                else -> null
            })
            val termsError = if (!b.terms.isChecked) getString(R.string.setujui_syarat_ketentuan_dan_kebijakan) else null
            b.termsError.text = termsError
            b.termsError.isVisible = termsError != null
            val valid = listOf(b.name, b.phone, b.password).none { it.error.isVisible } && termsError == null
            if (!valid) {
                b.password.input.text.clear()
                b.confirm.input.text.clear()
                return@setOnClickListener
            }
            // The code is shown on screen: there is no SMS gateway, as in the web demo.
            startActivity(RegisterOtpActivity.intent(this, name, phone, password))
        }
    }
}

/**
 * Sign-up steps 2 and 3 (verify.tsx; Figma 32:286 Kode OTP, 32:330 Verifikasi berhasil): six code
 * boxes, a resend countdown, then the verified number. The demo code is shown on screen; like
 * SignupVerification it lasts 5 minutes, allows 5 tries and can be resent after 45 seconds.
 */
class RegisterOtpActivity : AppCompatActivity() {
    private lateinit var b: ActivityRegisterOtpBinding
    private lateinit var boxes: List<EditText>
    private var sentAt = 0L
    private var attempts = 0
    private var left = 0
    private var filling = false
    private val ticker = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            showResend()
            if (left-- > 0) ticker.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityRegisterOtpBinding.inflate(layoutInflater)
        setContentView(b.root)
        authFrame(b.scroll, b.main, gutterDp = 30) // .verify-main: 30px sides
        b.count.text = getString(R.string.langkah_dari_3, 2)
        b.chip.text = getString(R.string.verifikasi_nomor)
        b.title.text = getString(R.string.masukkan_kode_otp)
        b.lead.text = getString(R.string.kode_dikirim_ke)
        b.back.text = getString(R.string.kembali_panah)
        b.back.setOnClickListener { finish() }
        b.changePhone.setOnClickListener { finish() }
        b.help.setOnClickListener { b.helpNotice.isVisible = true }
        b.submit.setOnClickListener { verify() }
        b.continueHome.setOnClickListener { enterApp() }
        boxes = List(6) { index ->
            EditText(this).apply {
                setBackgroundResource(R.drawable.bg_otp_box)
                gravity = Gravity.CENTER
                inputType = InputType.TYPE_CLASS_NUMBER
                typeface = ResourcesCompat.getFont(context, R.font.inter_semibold)
                textSize = 24f
                setTextColor(ContextCompat.getColor(context, R.color.text))
                setPadding(0, 0, 0, 0)
                setSelectAllOnFocus(true) // typing over a filled box replaces its digit
                contentDescription = getString(R.string.digit_dari_6, index + 1)
                if (index == 0) setAutofillHints("smsOTPCode") else importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
                imeOptions = if (index == 5) EditorInfo.IME_ACTION_DONE else EditorInfo.IME_ACTION_NEXT
                doAfterTextChanged { put(index, it?.toString().orEmpty()) }
                setOnKeyListener { _, keyCode, event ->
                    val back = keyCode == KeyEvent.KEYCODE_DEL && event.action == KeyEvent.ACTION_DOWN && text.isEmpty() && index > 0
                    if (back) boxes[index - 1].apply { requestFocus(); text.clear() }
                    back
                }
                setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_DONE) verify(); false }
                b.boxes.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            }
        }
        b.phone.text = maskPhone(phone)
        b.demoCode.isVisible = true
        b.demoCode.text = getString(R.string.mode_demo_kode_otp, DEMO_CODE)
        send()
        boxes[0].requestFocus()
        updateSubmit()
    }

    override fun onDestroy() {
        ticker.removeCallbacks(tick)
        super.onDestroy()
    }

    private val phone get() = intent.getStringExtra(PHONE).orEmpty()

    /** A new code: 5 minutes and 5 tries, and the next one after 45 seconds. */
    private fun send() {
        sentAt = SystemClock.elapsedRealtime()
        attempts = 0
        left = 45
        ticker.removeCallbacks(tick)
        tick.run()
    }

    private fun showResend() {
        if (left > 0) {
            b.resend.text = HtmlCompat.fromHtml(getString(R.string.kirim_ulang_dalam, "%02d:%02d".format(left / 60, left % 60)), HtmlCompat.FROM_HTML_MODE_LEGACY)
            b.resend.setOnClickListener(null)
            b.resend.isClickable = false
            b.resend.setTextAppearance(R.style.Text_Auth)
            b.resend.setTextColor(ContextCompat.getColor(this, R.color.muted))
            b.resend.textSize = 12f
        } else {
            b.resend.text = getString(R.string.kirim_ulang_kode)
            b.resend.setTextAppearance(R.style.Text_Auth_Link)
            b.resend.textSize = 13f
            b.resend.setOnClickListener { resend() }
        }
    }

    private fun resend() {
        send()
        error(null)
        status(getString(R.string.kode_baru_dikirim))
    }

    /** Writes typed, pasted or autofilled digits from box [index] on and moves focus past them. */
    private fun put(index: Int, value: String) {
        if (filling) return
        filling = true
        var digits = value.filter { it.isDigit() }
        if (digits.length == 2 && boxes[index].tag == digits[0]) digits = digits.drop(1) // typed after the old digit
        digits.take(6 - index).forEachIndexed { i, d -> boxes[index + i].apply { setText(d.toString()); tag = d } }
        if (digits.isEmpty()) boxes[index].apply { text.clear(); tag = null }
        filling = false
        if (digits.isNotEmpty()) boxes[minOf(index + digits.length, 5)].requestFocus()
        error(null)
        updateSubmit()
    }

    private val code get() = boxes.joinToString("") { it.text.toString() }

    private fun updateSubmit() {
        val ready = code.length == 6
        b.submit.isEnabled = ready
        b.submit.alpha = if (ready) 1f else 0.5f
    }

    private fun status(message: String) {
        b.status.text = message
        b.status.isVisible = true
    }

    private fun error(message: String?) {
        b.error.text = message
        b.error.isVisible = message != null
        boxes.forEach { it.isActivated = message != null }
    }

    private fun verify() {
        if (code.length != 6) return
        val problem = when {
            attempts >= 5 -> R.string.terlalu_banyak_percobaan
            SystemClock.elapsedRealtime() - sentAt > 5 * 60_000 -> R.string.kode_kedaluwarsa
            code != DEMO_CODE -> R.string.kode_otp_salah.also { attempts++ }
            else -> null
        }
        if (problem != null) return error(getString(problem)).also { boxes[0].requestFocus() }
        Local.register(intent.getStringExtra(NAME).orEmpty(), phone, intent.getStringExtra(PASSWORD).orEmpty())
        Demo.companion = false
        verified(maskPhone(phone))
    }

    /** Step 3/3: the account exists and is signed in; back goes on to the app, not to the form. */
    private fun verified(phone: String) {
        ticker.removeCallbacks(tick)
        currentFocus?.let { getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(it.windowToken, 0) }
        b.back.text = getString(R.string.akun_terverifikasi)
        b.back.setOnClickListener(null)
        b.back.isClickable = false
        b.count.text = getString(R.string.langkah_dari_3, 3)
        b.progress3.setBackgroundResource(R.drawable.bg_progress_done)
        b.chip.text = getString(R.string.verifikasi_selesai)
        b.title.text = getString(R.string.nomor_anda_terverifikasi)
        b.lead.text = getString(R.string.akun_siap_digunakan)
        b.otpStep.isVisible = false
        b.doneStep.isVisible = true
        b.verifiedPhone.text = phone
        onBackPressedDispatcher.addCallback(this) { enterApp() }
        b.title.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
    }

    companion object {
        private const val NAME = "name"
        private const val PHONE = "phone"
        private const val PASSWORD = "password"
        private const val DEMO_CODE = "123456"

        /** "+62 813 •••• 3333", as SignupVerification::mask. */
        fun maskPhone(normalized: String) = "+62 ${normalized.drop(2).take(3)} •••• ${normalized.takeLast(4)}"

        fun intent(context: Context, name: String, phone: String, password: String) =
            Intent(context, RegisterOtpActivity::class.java).putExtra(NAME, name).putExtra(PHONE, phone).putExtra(PASSWORD, password)
    }
}
