package com.constanvel.hytracl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The patient side's local visit data. Staff check-in, the board and the management report now
 * run on the Laravel API and are tested there (tests/Feature in hytrack-laravel).
 */
class OpsTest {
    @Test
    fun patientTicketAndBookingFollowTheWeb() {
        // The ticket QR (CheckInTicket): "HT1.<id>.<yyyyMMdd>.<signature>", the format the server reads.
        val andi = Ops.queues.first { it.number == "A-001" && it.doctor.name.startsWith("dr. Andi") && it.date == Ops.today() }
        val qr = Ops.payload(andi)
        assertTrue(qr.matches(Regex("^HT1\\.${andi.id}\\.\\d{8}\\.[0-9a-f]{20}$")))
        // Drawn as ticketQr draws it (#173D3B on white, level M, 4-module margin), a scanner reads it back.
        val size = 232
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(qr, com.google.zxing.BarcodeFormat.QR_CODE, size, size, mapOf(
            com.google.zxing.EncodeHintType.ERROR_CORRECTION to com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M,
            com.google.zxing.EncodeHintType.MARGIN to 4,
        ))
        val pixels = IntArray(size * size) { if (matrix[it % size, it / size]) 0xFF173D3B.toInt() else -1 }
        val read = com.google.zxing.qrcode.QRCodeReader().decode(
            com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(com.google.zxing.RGBLuminanceSource(size, size, pixels))),
        )
        assertEquals(qr, read.text)

        // Siti books from the patient app; the demo check-in moves her visit on, and only once.
        assertTrue(Local.login("0812-5550-0842", Demo.DEMO_PASSWORD))
        val budi = Demo.doctors.first { it.name.startsWith("dr. Budi") }
        assertTrue(Demo.book(budi, Demo.days[0]))
        val visit = Demo.activeVisit!!
        assertEquals("B-014", visit.ticket)
        assertEquals(Demo.Stage.BOOKED, visit.stage)
        Ops.checkIn(visit.queue, method = "qr")
        assertEquals(Demo.Stage.WAITING, visit.stage)
        assertEquals("qr", visit.queue.checkInMethod)
        assertThrows(Ops.Refused::class.java) { Ops.checkIn(visit.queue) } // never recorded twice
        assertEquals("Check-in berhasil. Anda masuk antrean dokter.", Demo.notifications.first().message)
        assertFalse(Demo.book(budi, Demo.days[0])) // one active visit per patient
        Local.signOut()
    }
}
