package com.constanvel.hytracl

import android.app.AlertDialog
import android.app.Dialog
import android.app.DatePickerDialog
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

private val genders = mapOf("female" to "Perempuan", "male" to "Laki-laki")
private val indonesian: Locale = Locale.forLanguageTag("id-ID")

/** Pinned action bar of the Inter frames: white, hairline on top, one 50dp button. */
fun ShellActivity.footerButton(label: String, onClick: () -> Unit) {
    shell.footer.removeAllViews()
    shell.footer.setBackgroundColor(ContextCompat.getColor(this, R.color.white))
    val bar = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        updatePadding(left = dp(24), right = dp(24), top = dp(16), bottom = dp(16))
    }
    bar.button(label, onClick = onClick)
    shell.footer.addView(bar)
    shell.footer.addView(View(this).apply { setBackgroundColor(ContextCompat.getColor(context, R.color.border)) },
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, shell.root.dp(1)))
}

/** Data pasien (Figma 27:61): the account's own record, then the family it manages. */
class PatientDataActivity : ShellActivity() {
    override val tab = Tab.ACCOUNT

    override fun spec() = Spec(getString(R.string.data_pasien), getString(R.string.profil_dan_data_pasien),
        back = AccountActivity::class.java, action = true)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return
        interHeader()
        footerButton(getString(R.string.tambah_pasien)) { go(PatientFormActivity::class.java) }
    }

    override fun content(into: LinearLayout) {
        into.dividerDrawable = ContextCompat.getDrawable(this, R.drawable.gap_24)
        into.lead(getString(R.string.data_tepat_berobat_lebih_mudah), getString(R.string.kelola_identitas_dan_kontak_pasien))
        Local.records().forEach { patient -> card(into, patient) }
        into.tipCard(getString(R.string.mengurus_kunjungan_keluarga), getString(R.string.tambahkan_data_pasien_yang_anda_dampingi))
    }

    private fun card(into: LinearLayout, patient: JSONObject) = into.column(16, R.color.white, 20, 20, R.color.border).apply {
        column(4).apply {
            inter(getString(if (patient.optBoolean("self")) R.string.diri_sendiri else R.string.pasien_yang_didampingi), 11f, 17f, Weight.SEMIBOLD, R.color.brand)
            inter(patient.getString("name"), 22f, 28f, Weight.BOLD)
            patient.str("recordNumber")?.let { inter(getString(R.string.no_rm, it), 13f, 19f, color = R.color.brand_muted) }
        }
        rule()
        column(16).apply {
            detail(getString(R.string.tanggal_lahir_umur), patient.str("birthLabel")?.let { getString(R.string.tanggal_umur, it, patient.optInt("age")) })
            detail(getString(R.string.jenis_kelamin), genders[patient.str("gender")])
            detail(getString(R.string.nomor_ponsel), patient.str("phone"))
            detail(getString(R.string.alamat_domisili), patient.str("address")?.let { address ->
                listOf(address, listOfNotNull(patient.str("city"), patient.str("postalCode")).joinToString(" · ")).filter { it.isNotEmpty() }.joinToString("\n")
            })
        }
        button(getString(R.string.ubah_data_pasien), Look.OUTLINE) {
            go(PatientFormActivity::class.java) { putExtra(PatientFormActivity.PATIENT, patient.toString()) }
        }
        // Only family records can go; the account's own record stays.
        if (!patient.optBoolean("self")) inter(getString(R.string.hapus_data_pasien), 14f, 20f, Weight.SEMIBOLD, R.color.danger).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                setMargins(0, dp(-8), 0, dp(-10))
            }
            setPadding(dp(12))
            isClickable = true
            isFocusable = true
            setOnClickListener { confirmDelete(patient) }
        }
    }

    /** Hapus data pasien (Figma 31:273): confirm, then delete; the owner's record is refused, as in PatientRecords. */
    private fun confirmDelete(patient: JSONObject) {
        val dialog = Dialog(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24))
            background = rounded(R.color.white, 22)
        }
        box.addView(FrameLayout(this).apply {
            background = rounded(R.color.danger_soft, 18)
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_trash); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        }, LinearLayout.LayoutParams(box.dp(56), box.dp(56)))
        fun TextView.centered(top: Int) = apply {
            gravity = Gravity.CENTER
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(top)
        }
        box.inter(getString(R.string.hapus_data_pasien_tanya), 22f, 31f, Weight.BOLD).centered(16).isAccessibilityHeading = true
        box.inter(getString(R.string.hapus_data_pasien_detail), 14f, 20f, color = R.color.brand_muted).centered(12)
        box.column(5, R.color.soft_bg, 12, stroke = R.color.companion_line).apply {
            setPadding(dp(15), dp(9), dp(15), dp(9))
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(36)
            inter(patient.getString("name"), 15f, 21f, Weight.SEMIBOLD)
            inter(patient.str("recordNumber")?.let { getString(R.string.no_rm, it) } ?: getString(R.string.belum_ada_no_rm), 12f, 18f, color = R.color.brand_muted)
        }
        box.inter(getString(R.string.pastikan_data_pasien_benar), 12f, 18f, color = R.color.brand_muted).centered(18)
        val error = box.inter(null, 13f, 18f, color = R.color.error).centered(12).apply {
            isVisible = false
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE
        }
        val actions = LinearLayout(this)
        box.addView(actions, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = box.dp(32) })
        val cancel = actions.button(getString(R.string.batal), Look.OUTLINE, 52, 12) { dialog.cancel() }
        val remove = actions.button(getString(R.string.hapus_data), height = 52, radius = 12) {
            runCatching { Local.deleteRecord(patient.getInt("id")) }.onSuccess {
                dialog.dismiss()
                Demo.flash = getString(R.string.data_x_dihapus, patient.getString("name"))
                render()
            }.onFailure {
                error.text = it.message
                error.isVisible = true
            }
        }
        remove.background = remove.rounded(R.color.danger, 12)
        listOf(cancel, remove).forEachIndexed { i, b ->
            b.textSize = 15f
            b.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (i > 0) marginStart = box.dp(12) }
        }
        dialog.setContentView(box)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.48f)
            val width = minOf(box.dp(364), resources.displayMetrics.widthPixels - box.dp(48))
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.show()
        cancel.requestFocus()
    }

    private fun ViewGroup.detail(label: String, value: String?) = column(3).apply {
        inter(label, 12f, 18f, color = R.color.brand_muted)
        if (value != null) inter(value, 14f, 20f, Weight.MEDIUM)
        else inter(getString(R.string.belum_diisi), 14f, 20f, color = R.color.brand_muted)
    }
}

/** Tambah / ubah data pasien (Figma 27:110 and 27:179). [Local] validates like Laravel; its messages show per field. */
class PatientFormActivity : ShellActivity() {
    companion object {
        const val PATIENT = "patient"
    }

    override val tab = Tab.ACCOUNT
    private val patient by lazy { intent.getStringExtra(PATIENT)?.let(::JSONObject) }
    /** Typed values survive re-renders (render rebuilds the views). */
    private val values = mutableMapOf<String, String>()
    private var errors: Map<String, String> = emptyMap()

    override fun spec() = Spec(getString(if (patient != null) R.string.ubah_data_pasien else R.string.tambah_data_pasien),
        getString(R.string.profil_dan_data_pasien), back = PatientDataActivity::class.java, bare = true)

    override fun onCreate(savedInstanceState: Bundle?) {
        patient?.let { p ->
            mapOf("name" to "name", "birth_date" to "birthDate", "gender" to "gender", "phone" to "phone",
                "address" to "address", "city" to "city", "postal_code" to "postalCode")
                .forEach { (field, key) -> p.str(key)?.let { values[field] = it } }
        }
        super.onCreate(savedInstanceState)
        if (isFinishing) return
        interHeader()
    }

    override fun content(into: LinearLayout) {
        into.dividerDrawable = ContextCompat.getDrawable(this, R.drawable.gap_24)
        val editing = patient != null
        into.lead(getString(if (editing) R.string.perbarui_data_pasien else R.string.lengkapi_data_pasien),
            getString(if (editing) R.string.pastikan_informasi_terbaru else R.string.tambahkan_data_diri_atau_pasien), gap = 6)
            .inter(getString(R.string.wajib_diisi), 12f, 18f, Weight.MEDIUM, R.color.brand)
        errors["form"]?.let { into.notice(it, Tone.WARNING) }

        section(into, R.string.identitas_pasien).apply {
            field(this, "name", R.string.nama_lengkap_wajib) {
                input(getString(R.string.nama_sesuai_identitas), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)
                    .limit(100)
            }
            field(this, "birth_date", R.string.tanggal_lahir_wajib, hint = birthHint()) {
                picker(values["birth_date"]?.let(::dateLabel) ?: getString(R.string.pilih_tanggal_lahir), values["birth_date"] != null) { pickBirthDate() }
            }
            field(this, "gender", R.string.jenis_kelamin_wajib) {
                picker(genders[values["gender"]] ?: getString(R.string.pilih_jenis_kelamin), values["gender"] != null) { pickGender() }
            }
        }
        section(into, R.string.kontak_yang_bisa_dihubungi).apply {
            field(this, "phone", R.string.nomor_ponsel_wajib, hint = getString(R.string.gunakan_nomor_aktif)) {
                input(getString(R.string.contoh_nomor_ponsel), InputType.TYPE_CLASS_PHONE).limit(20)
            }
        }
        section(into, R.string.alamat_domisili).apply {
            field(this, "address", R.string.alamat_lengkap_wajib) {
                input(getString(R.string.alamat_placeholder), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES, lines = 3)
                    .limit(255)
            }
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            addView(row)
            listOf("city" to R.string.kota_kabupaten_wajib, "postal_code" to R.string.kode_pos_opsional).forEachIndexed { index, (name, label) ->
                val cell = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { if (index == 1) marginStart = dp(12) })
                field(cell, name, label) {
                    if (name == "city") input(getString(R.string.contoh_bandung), InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS).limit(100)
                    else input(getString(R.string.lima_digit), InputType.TYPE_CLASS_NUMBER).limit(5)
                }
            }
        }
        into.tipCard(getString(R.string.pastikan_data_sudah_benar), getString(R.string.data_dipakai_untuk_identifikasi), padding = 14, textSize = 12f)
        into.column(12).apply {
            (layoutParams as LinearLayout.LayoutParams).topMargin = dp(16)
            button(getString(if (patient != null) R.string.simpan_perubahan else R.string.simpan_data_pasien)) { save() }
            button(getString(R.string.batal), Look.GHOST) { go(PatientDataActivity::class.java) }
        }
    }

    private fun section(into: LinearLayout, title: Int) = into.column(16).apply {
        inter(getString(title), 18f, 24f, Weight.BOLD)
    }

    /** Label, control, then the hint or Laravel's error for [name]. */
    private fun field(parent: ViewGroup, name: String, label: Int, hint: String? = null, control: ViewGroup.() -> TextView) {
        parent.column(8).apply {
            inter(getString(label), 14f, 20f, Weight.SEMIBOLD)
            val view = control()
            val error = errors[name]
            view.background = view.fieldBackground(error != null)
            view.contentDescription = getString(label)
            if (view is EditText) {
                view.setText(values[name].orEmpty())
                view.doAfterTextChanged { values[name] = it.toString() }
            }
            (error ?: hint)?.let { inter(it, 12f, 18f, if (error != null) Weight.MEDIUM else Weight.REGULAR, if (error != null) R.color.error else R.color.brand_muted) }
        }
    }

    private fun EditText.limit(max: Int) = apply { filters = arrayOf(InputFilter.LengthFilter(max)) }

    /** A select-like control: looks like an input, opens a picker. */
    private fun ViewGroup.picker(text: String, chosen: Boolean, open: () -> Unit) = inter(text, 14f, 20f, color = if (chosen) R.color.auth_text else R.color.brand_muted).apply {
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(50)
        updatePadding(left = dp(14), right = dp(40))
        setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_chevron_down, 0)
        compoundDrawablePadding = -dp(26)
        isClickable = true
        isFocusable = true
        setOnClickListener { open() }
    }

    private fun birthHint(): String {
        val date = values["birth_date"] ?: return getString(R.string.umur_dihitung_otomatis)
        val (y, m, d) = date.split("-").map { it.toInt() }
        val now = Calendar.getInstance()
        var years = now.get(Calendar.YEAR) - y
        if (now.get(Calendar.MONTH) + 1 < m || (now.get(Calendar.MONTH) + 1 == m && now.get(Calendar.DAY_OF_MONTH) < d)) years--
        return getString(R.string.umur_dari_tanggal_lahir, years)
    }

    private fun dateLabel(date: String): String {
        val (y, m, d) = date.split("-").map { it.toInt() }
        val month = java.text.DateFormatSymbols(indonesian).months[m - 1]
        return "$d $month $y"
    }

    private fun pickBirthDate() {
        val current = values["birth_date"]?.split("-")?.map { it.toInt() } ?: listOf(1990, 1, 1)
        // Indonesian month and day names whatever the device language, like the booking calendar.
        val previous = Locale.getDefault()
        Locale.setDefault(indonesian)
        val themed = ContextThemeWrapper(this, R.style.Theme_Hytracl).apply {
            applyOverrideConfiguration(Configuration().apply { setLocale(indonesian) })
        }
        DatePickerDialog(themed, { _, year, month, dom ->
            values["birth_date"] = String.format(Locale.ROOT, "%04d-%02d-%02d", year, month + 1, dom)
            errors = errors - "birth_date"
            render()
        }, current[0], current[1] - 1, current[2]).apply {
            datePicker.minDate = Calendar.getInstance().apply { set(1900, 0, 1) }.timeInMillis
            datePicker.maxDate = System.currentTimeMillis()
            setOnDismissListener { Locale.setDefault(previous) }
        }.show()
    }

    private fun pickGender() {
        val keys = genders.keys.toList()
        AlertDialog.Builder(this)
            .setTitle(R.string.jenis_kelamin)
            .setSingleChoiceItems(genders.values.toTypedArray(), keys.indexOf(values["gender"])) { dialog, which ->
                values["gender"] = keys[which]
                errors = errors - "gender"
                dialog.dismiss()
                render()
            }
            .show()
    }

    private fun save() {
        val id = patient?.optInt("id")
        runCatching { Local.saveRecord(id, values) }.onSuccess { saved ->
            Demo.flash = if (id != null) getString(R.string.perubahan_data_pasien_tersimpan)
            else getString(R.string.data_tersimpan, saved.getString("name"))
            go(PatientDataActivity::class.java)
        }.onFailure { failure ->
            errors = (failure as? Local.Refused)?.fields?.takeIf { it.isNotEmpty() } ?: mapOf("form" to failure.message.orEmpty())
            render()
            shell.scroll.scrollTo(0, 0)
        }
    }
}
