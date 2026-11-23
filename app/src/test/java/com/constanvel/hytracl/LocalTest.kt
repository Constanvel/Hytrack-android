package com.constanvel.hytracl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The web's rules that [Local] now enforces on the device (PatientRecords, sign-in, sign-up). */
class LocalTest {
    private fun refused(input: Map<String, String>, id: Int? = null): Map<String, String> =
        try {
            Local.saveRecord(id, input)
            emptyMap()
        } catch (e: Local.Refused) {
            e.fields
        }

    private val valid = mapOf(
        "name" to "Rani Fauzi", "birth_date" to "1990-01-01", "gender" to "female", "phone" to "0812 9999 0000",
        "address" to "Jl. Mawar 5", "city" to "Bandung", "postal_code" to "40132",
    )

    @Test
    fun signInAndSignUp() {
        assertFalse(Local.login("0812-5550-0842", "salah"))
        assertTrue(Local.login("+62 812-5550-0842", "hytrack-demo"))
        assertTrue(Local.phoneTaken("6281255500843"))
        assertFalse(Local.phoneTaken("6281377770001"))
    }

    @Test
    fun recordRules() {
        Local.login("081255500842", "hytrack-demo")
        val empty = refused(emptyMap())
        assertEquals("Nama lengkap wajib diisi.", empty["name"])
        assertEquals("Tanggal lahir wajib diisi.", empty["birth_date"])
        assertEquals("Pilih jenis kelamin.", empty["gender"])
        assertEquals("Nomor ponsel wajib diisi.", empty["phone"])
        assertEquals("Alamat lengkap wajib diisi.", empty["address"])
        assertEquals("Kota / kabupaten wajib diisi.", empty["city"])
        assertEquals("Kode pos terdiri dari 5 angka.", refused(valid + ("postal_code" to "123"))["postal_code"])
        assertEquals("Masukkan tanggal lahir yang valid.", refused(valid + ("birth_date" to "1990-02-31"))["birth_date"])
        assertEquals("Tanggal lahir tidak boleh di masa depan.", refused(valid + ("birth_date" to "2999-01-01"))["birth_date"])
        assertEquals("Gunakan nomor aktif dengan awalan 08 atau +62.", refused(valid + ("phone" to "12345"))["phone"])
        // The owner's own record, typed again, is a duplicate.
        assertEquals("Data pasien ini sudah tersimpan. Ubah data yang ada bila perlu.",
            refused(valid + ("name" to "siti aminah") + ("birth_date" to "1988-05-12"))["name"])
        // The owner's own phone may not be another account's.
        assertEquals("Nomor ponsel sudah dipakai akun lain.", refused(valid + ("phone" to "081255500843"), id = Local.current!!.self.id)["phone"])
        val deleteOwn = runCatching { Local.deleteRecord(Local.current!!.self.id) }.exceptionOrNull()
        assertEquals("Data pasien pemilik akun tidak dapat dihapus.", deleteOwn?.message)
    }
}
