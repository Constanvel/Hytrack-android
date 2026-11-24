package com.constanvel.hytracl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The staff and management dashboards read the same seed as the web (DatabaseSeeder, ServiceInsights). */
class OpsTest {
    @Test
    fun seedReportAndCheckInFollowTheWeb() {
        // One test: Ops is a process-wide object, so the report is read before anything changes it.
        val report = Ops.report(Ops.today())
        assertEquals(51, report.registered)
        assertEquals(17, report.completed)
        assertEquals(14, report.active)
        assertEquals(17, report.notArrived)

        val rina = Ops.doctors.first { it.name.startsWith("dr. Rina") }
        assertEquals(listOf("A-024", "A-025", "A-026"), Ops.waiting(rina).map { it.number })

        val ticket = Ops.identify("ht000101")
        assertEquals("B-001", ticket.number)
        Ops.checkIn(ticket)
        assertEquals(Ops.Status.WAITING, ticket.status)
        assertThrows(Ops.Refused::class.java) { Ops.checkIn(ticket) } // never recorded twice
        assertThrows(Ops.Refused::class.java) { Ops.identify("QR-123") }

        // The ticket QR (CheckInTicket): signed, tied to its visit, so a changed digit is refused.
        val andi = Ops.queues.first { it.number == "A-001" && it.doctor.name.startsWith("dr. Andi") && it.date == Ops.today() }
        val qr = Ops.payload(andi)
        assertEquals(andi, Ops.identify(qr))
        assertEquals("qr", Ops.method(qr))
        assertThrows(Ops.Refused::class.java) { Ops.identify(qr.dropLast(1) + if (qr.last() == '0') '1' else '0') }
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

        // Siti's booking from the patient app is a ticket in the staff data; staff actions reach her.
        assertTrue(Local.login("0812-5550-0842", Demo.DEMO_PASSWORD))
        val budi = Demo.doctors.first { it.name.startsWith("dr. Budi") }
        assertTrue(Demo.book(budi, Demo.days[0]))
        val visit = Demo.activeVisit!!
        assertEquals("B-014", visit.ticket)
        assertEquals(visit.queue, Ops.identify(visit.checkInCode))
        assertEquals(Demo.Stage.BOOKED, visit.stage)
        Ops.checkIn(Ops.identify(visit.qrPayload), method = "qr")
        assertEquals(Demo.Stage.WAITING, visit.stage)
        assertEquals("qr", visit.queue.checkInMethod)
        assertEquals("Check-in berhasil. Anda masuk antrean dokter.", Demo.notifications.first().message)
        assertFalse(Demo.book(budi, Demo.days[0])) // one active visit per patient
        Local.signOut()
    }
}
