package com.constanvel.hytracl

import android.content.Intent
import android.os.Bundle
import android.text.Html
import android.text.InputType
import android.text.method.PasswordTransformationMethod
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import com.constanvel.hytracl.databinding.ActivityLoginBinding
import com.constanvel.hytracl.databinding.ActivityRegisterBinding
import com.constanvel.hytracl.databinding.ViewAuthFieldBinding

/** Shared field setup for AuthInput / PasswordInput. */
private fun ViewAuthFieldBinding.setup(label: String, hint: String, phone: Boolean = false, password: Boolean = false) {
    this.label.text = label
    input.hint = hint
    icon.isVisible = phone
    val face = input.typeface // password input types reset it to monospace
    input.inputType = when {
        password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
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
private fun AppCompatActivity.authFrame(scroll: View, main: LinearLayout) {
    enableEdgeToEdge()
    val gutter = main.dp(24)
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

private fun AppCompatActivity.enterApp() {
    Demo.signedIn = true
    startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    // An invitation link opened while signed out continues once signed in.
    Demo.pendingInvite?.takeIf { Api.online }?.let { token ->
        Demo.pendingInvite = null
        startActivity(Intent(this, CompanionInviteActivity::class.java).putExtra(CompanionInviteActivity.TOKEN, token))
    }
}

/**
 * "Masuk sebagai Pendamping" opens the companion view: the one visit this account follows,
 * else the list (web AuthController::signedIn). An invitation link waiting to open comes first.
 */
private fun AppCompatActivity.enterCompanion() {
    if (!Api.online || Demo.pendingInvite != null) return enterApp()
    request({ Api.call("GET", "pendampingan").getJSONArray("patients").objects() }) { result ->
        enterApp()
        val only = result.getOrNull()?.singleOrNull()
        startActivity(Intent(this, if (only != null) CompanionMonitorActivity::class.java else CompanionListActivity::class.java)
            .apply { only?.let { putExtra(CompanionMonitorActivity.SHARE, it.getInt("id")) } })
    }
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
        if (Demo.signedIn) return enterApp()
        val b = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(b.root)
        authFrame(b.scroll, b.main)
        b.identity.authTitle.text = getString(R.string.selamat_datang_kembali)
        b.identity.authIntro.text = getString(R.string.masuk_untuk_melanjutkan_kunjungan_ndan)
        b.phone.setup(getString(R.string.nomor_ponsel), getString(R.string.phone_hint), phone = true)
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
        b.submit.setOnClickListener {
            val phone = b.phone.value.trim()
            val password = b.password.value
            b.phone.error(if (phone.isEmpty()) getString(R.string.nomor_ponsel_wajib_diisi) else null)
            b.password.error(if (password.isEmpty()) getString(R.string.kata_sandi_wajib_diisi) else null)
            if (phone.isEmpty() || password.isEmpty()) return@setOnClickListener
            b.submit.isEnabled = false
            b.submit.text = getString(R.string.memeriksa)
            // The Laravel API first; without a connection the app falls back to the offline demo.
            request({ Api.login(phone, password) }) { result ->
                b.submit.isEnabled = true
                b.submit.text = getString(R.string.masuk)
                val failure = result.exceptionOrNull() as? Api.Failure
                when {
                    result.isSuccess && Api.role != "patient" -> {
                        Api.logout()
                        b.phone.error(getString(R.string.server_staff_only))
                    }
                    result.isSuccess -> result.getOrThrow().let { account ->
                        Demo.signedInAs(account.getString("name"), account.str("phone"))
                        if (role == "companion") enterCompanion() else enterApp()
                    }
                    failure?.status == 0 && Demo.isDemoAccount(phone, password) -> if (role == "companion") enterCompanion() else enterApp()
                    else -> {
                        b.phone.error(if (failure?.status == 0) Api.OFFLINE else getString(R.string.nomor_ponsel_atau_kata_sandi_salah))
                        b.password.input.text.clear()
                    }
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
                Demo.isDemoAccount(phone, Demo.DEMO_PASSWORD) -> getString(R.string.nomor_ponsel_sudah_terdaftar_silakan)
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
            Demo.register(name, phone)
            enterApp()
        }
    }
}
