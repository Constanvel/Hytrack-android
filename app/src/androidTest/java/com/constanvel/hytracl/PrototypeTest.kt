package com.constanvel.hytracl

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Mode prototype through the same [Api] calls the screens make; on the device for a real org.json. */
@RunWith(AndroidJUnit4::class)
class PrototypeTest {
    @After
    fun leave() = Prototype.exit()

    private fun refused(status: Int, block: () -> Unit): String {
        try {
            block()
        } catch (e: Api.Failure) {
            assertEquals(e.message, status, e.status)
            return e.message.orEmpty()
        }
        throw AssertionError("expected a refusal with $status")
    }

    private fun board(query: String = "") = Api.call("GET", "petugas$query")
    private fun post(path: String, body: JSONObject = JSONObject(), upload: Api.Upload? = null) =
        Api.call("POST", "petugas/$path", body, upload).getString("message")

    private fun JSONObject.row(list: String, key: String, value: Any) = list(list).first { it.get(key) == value }

    @Test
    fun normalAccountsAndPrototypeNeverMix() {
        Local.register("Warga Asli", "6281377770001", "rahasia-asli")
        val normalTickets = Ops.queues.size

        Prototype.enter()
        assertNull(Local.current)
        assertFalse(Local.phoneTaken("6281377770001"))
        // Nothing works without picking a role, and no real credentials are accepted.
        refused(401) { board() }
        refused(403) { Api.login("dina", "apa-saja", "staff") }
        refused(403) { Api.call("GET", "akun") }

        Prototype.signIn(Prototype.As.MANAGER)
        assertNull(Api.token)
        refused(403) { board() }
        Prototype.signIn(Prototype.As.STAFF)
        assertEquals("desk", board().getJSONObject("me").getString("room"))
        refused(403) { Api.call("GET", "manajemen") }
        Prototype.signIn(Prototype.As.PATIENT)
        assertNull(Api.account)
        assertTrue(Demo.book(Demo.doctors[0], Demo.days[0]))

        Prototype.exit()
        assertNull(Api.account)
        assertEquals("Warga Asli", Local.current!!.self.name)
        assertEquals(normalTickets, Ops.queues.size)
        assertFalse(Demo.companion)
        Local.signOut()

        // The next prototype session starts from the seed again.
        Prototype.enter()
        Prototype.signIn(Prototype.As.PATIENT)
        assertNull(Demo.activeVisit)
    }

    @Test
    fun oneVisitGoesThroughEveryRoom() {
        Prototype.enter()
        Prototype.signIn(Prototype.As.MANAGER)
        val completed = Api.call("GET", "manajemen").getJSONObject("report").getInt("completed")

        Prototype.signIn(Prototype.As.PATIENT)
        assertTrue(Demo.book(Demo.doctors[0], Demo.days[0]))
        val visit = Demo.activeVisit!!
        val id = visit.id

        // Meja check-in: scan, confirm, and the queue refuses a second patient with the doctor.
        Prototype.room("desk")
        val ticket = Api.call("POST", "petugas/scan", JSONObject().put("code", visit.qrPayload)).getJSONObject("ticket")
        assertEquals("booked", ticket.getString("state"))
        assertEquals("Siti Aminah", ticket.getString("name"))
        assertTrue(post("check-in", JSONObject().put("code", visit.checkInCode)).contains("berhasil check-in"))
        assertEquals(Demo.Stage.WAITING, visit.stage)
        refused(422) { post("check-in", JSONObject().put("code", visit.checkInCode)) }
        assertEquals("Selesaikan pasien aktif sebelum memanggil berikutnya.", refused(422) { post("dokter/1/panggil") })
        refused(403) { post("pemeriksaan/$id", JSONObject()) }

        // Ruang dokter: finish the patient inside, then take Siti.
        Prototype.room("doctor")
        val inside = board().getJSONObject("current").getInt("id")
        val medicine = JSONObject().put("name", "Amlodipin").put("quantity", "30 tablet").put("dosage", "1 × 1 tablet")
        assertEquals("Tandai pasien sudah diperiksa sebelum mengonfirmasi.", refused(422) { post("pemeriksaan/$inside/konfirmasi") })
        assertTrue(post("pemeriksaan/$inside/konfirmasi", JSONObject().put("examined", "1").put("notes", "Stabil.").put("items", JSONArray())).contains("tanpa resep"))
        post("antrean/$id/panggil")
        post("pemeriksaan/$id/penunjang", JSONObject().put("unit", "laboratorium").put("test", "Darah lengkap"))
        assertEquals(Demo.Stage.LABORATORY, visit.stage)
        assertEquals("support", board().row("examinations", "id", id).getString("state"))
        assertEquals("Tunggu hasil penunjang sebelum mengonfirmasi pemeriksaan.",
            refused(422) { post("pemeriksaan/$id/konfirmasi", JSONObject().put("examined", "1").put("notes", "x")) })

        // Penunjang: the result file goes back to the doctor.
        Prototype.room("support")
        val request = board().row("support", "queueId", id).getInt("id")
        assertEquals("Lampirkan hasil pemeriksaan sebelum mengirim ke dokter.", refused(422) { post("penunjang/$request/selesai") })
        val pdf = Api.Upload("result", "hasil.pdf", "application/pdf", byteArrayOf(37, 80, 68, 70))
        post("penunjang/$request", JSONObject().put("status", "processing"), pdf)
        post("penunjang/$request/selesai", JSONObject().put("note", "Siap ditinjau."))

        Prototype.room("doctor")
        val exam = board().row("examinations", "id", id)
        assertEquals("review", exam.getString("state"))
        val into = File.createTempFile("hasil", ".pdf")
        assertArrayEquals(pdf.bytes, Api.download("petugas/penunjang/$request/hasil", into).readBytes())
        post("pemeriksaan/$id/konfirmasi", JSONObject().put("examined", "1").put("notes", "Lanjut terapi.").put("items", JSONArray().put(medicine)))
        assertEquals(Demo.Stage.PHARMACY, visit.stage)

        // Farmasi: send back once, the doctor resends, then every step with its checklist.
        Prototype.room("pharmacy")
        val order = visit.queue.order!!.id
        post("farmasi/$order/kembalikan", JSONObject().put("reason", "Dosis perlu dikonfirmasi"))
        assertEquals("returned", board().row("pharmacy", "id", order).getString("status"))
        Prototype.room("doctor")
        assertEquals("returned", board().row("examinations", "id", id).getString("state"))
        post("pemeriksaan/$id/konfirmasi", JSONObject().put("examined", "1").put("notes", "Dosis sesuai.").put("items", JSONArray().put(medicine)))
        Prototype.room("pharmacy")
        listOf("received", "preparing", "ready").forEach { status ->
            val row = board().row("pharmacy", "id", order)
            assertEquals(status, row.getString("status"))
            assertEquals("Centang semua pemeriksaan sebelum melanjutkan.", refused(422) { post("farmasi/$order/lanjut") })
            val keys = JSONArray(row.list("checklist").map { it.getString("key") })
            if (status == "ready") post("farmasi/$order/panggil") else post("farmasi/$order/cek", JSONObject().put("checks", keys))
            post("farmasi/$order/lanjut", JSONObject().put("checks", keys))
        }
        assertEquals(Demo.Stage.COMPLETED, visit.stage)
        assertFalse(visit.active)
        assertTrue(board().list("history").any { it.getString("activity") == "Obat diserahkan" })

        // Manajemen sees both finished visits, as numbers only, and exports them.
        Prototype.signIn(Prototype.As.MANAGER)
        val data = Api.call("GET", "manajemen" + Api.query("poli" to 1))
        assertTrue(data.getJSONObject("report").getInt("completed") > 0)
        assertEquals(completed + 2, Api.call("GET", "manajemen").getJSONObject("report").getInt("completed"))
        assertFalse(data.toString().contains("Siti"))
        assertEquals(2, data.getJSONArray("dates").length())
        val csv = Api.download("manajemen/ekspor" + Api.query("tanggal" to Ops.today()), File.createTempFile("laporan", ".csv")).readText()
        assertTrue(csv.contains("Penyakit Dalam"))
    }

    @Test
    fun deskBookingsClinicsAndNoShow() {
        Prototype.enter()
        Prototype.signIn(Prototype.As.STAFF)
        assertEquals("Ahmad Fauzi", Api.call("GET", "petugas/pasien" + Api.query("telepon" to "081255500843")).getJSONObject("patient").getString("name"))
        refused(404) { Api.call("GET", "petugas/pasien" + Api.query("telepon" to "081200000000")) }
        val tomorrow = Ops.today().plusDays(1)
        assertTrue(Api.call("GET", "petugas/jadwal" + Api.query("tanggal" to tomorrow)).getBoolean("open"))
        val booking = JSONObject().put("phone", "081255500843").put("polyclinic_id", 2).put("doctor_id", 3).put("date", tomorrow.toString())
        assertTrue(post("booking", booking).startsWith("Booking "))
        assertEquals("Pasien ini sudah memiliki satu kunjungan aktif.", refused(422) { post("booking", booking) })

        val row = board(Api.query("tanggal" to tomorrow, "cari" to "Ahmad")).list("bookings").single()
        assertEquals("Tuliskan alasan pembatalan.", refused(422) { post("antrean/${row.getInt("id")}/batal") })
        post("antrean/${row.getInt("id")}/batal", JSONObject().put("reason", "Jadwal ulang"))
        assertEquals("cancelled", board(Api.query("tanggal" to tomorrow)).list("bookings").single().getString("status"))
        // "Semua tanggal" lists every service date.
        assertTrue(board(Api.query("tanggal" to "")).getInt("bookingTotal") > board().getInt("bookingTotal"))

        val serving = board(Api.query("poli" to 2)).getJSONObject("current").getInt("id")
        refused(422) { post("antrean/$serving/tidak-hadir") }
        post("antrean/$serving/panggil-ulang")
        post("antrean/$serving/tidak-hadir")
        assertTrue(post("dokter/3/panggil").endsWith("dipanggil."))

        post("poliklinik", JSONObject().put("name", "Mata").put("room", JSONObject.NULL))
        val added = board().row("clinicAdmin", "name", "Mata")
        assertTrue(added.isNull("room"))
        Api.call("PUT", "petugas/poliklinik/${added.getInt("id")}", JSONObject().put("name", "Mata Anak").put("room", "Ruang B-201"))
        Api.call("DELETE", "petugas/poliklinik/${added.getInt("id")}")
        assertFalse(board().list("clinicAdmin").any { it.getString("name") == "Mata Anak" })
    }

    @Test
    fun companionFollowsSitiOnce() {
        Prototype.enter()
        Prototype.signIn(Prototype.As.COMPANION)
        assertTrue(Demo.companion)
        assertEquals("Ahmad Fauzi", Local.current!!.self.name)
        assertNotNull(Local.following().single())
        Prototype.signIn(Prototype.As.COMPANION)
        assertEquals(1, Local.following().size)
        Prototype.signIn(Prototype.As.PATIENT)
        assertEquals(1, Local.invitations().size)
        assertEquals(Demo.Stage.WAITING, Demo.activeVisit!!.stage)
    }
}
