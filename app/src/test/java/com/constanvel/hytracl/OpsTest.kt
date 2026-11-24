package com.constanvel.hytracl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
    }
}
