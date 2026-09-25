package dev.duardo.neotransfer

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ServiceDateFieldTest {
    @Test fun `contract date round trips without timezone adjustment`() {
        val date = LocalDate.of(2024, 2, 29)
        assertEquals("29/02/2024", formatServiceDate(date))
        assertEquals(date, parseServiceDate("29/02/2024"))
        assertEquals(LocalDate.of(2026, 9, 24), parseServiceDate("24/09/2026"))
    }

    @Test fun `invalid or differently formatted dates are not coerced`() {
        assertNull(parseServiceDate("29/02/2026"))
        assertNull(parseServiceDate("31/04/2026"))
        assertNull(parseServiceDate("24/9/2026"))
        assertNull(parseServiceDate("2026-09-24"))
        assertNull(parseServiceDate(" 24/09/2026"))
    }

    @Test fun `optional picker bounds are inclusive and leave other services unrestricted`() {
        val first = LocalDate.of(2026, 9, 24)
        val last = first.plusMonths(6)
        assertEquals(true, isServiceDateSelectable(first, first, last))
        assertEquals(true, isServiceDateSelectable(last, first, last))
        assertEquals(false, isServiceDateSelectable(first.minusDays(1), first, last))
        assertEquals(false, isServiceDateSelectable(last.plusDays(1), first, last))
        assertEquals(true, isServiceDateSelectable(first.minusYears(1), null, null))
    }
}
