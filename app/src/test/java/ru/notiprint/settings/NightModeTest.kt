package ru.notiprint.settings

import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightModeTest {
    @Test
    fun `overnight interval includes start and excludes morning end`() {
        val settings = settings(start = 22 * 60, end = 8 * 60)

        assertFalse(NightMode.isActive(settings, at("2026-11-08T21:59:59Z")))
        assertTrue(NightMode.isActive(settings, at("2026-11-08T22:00:00Z")))
        assertTrue(NightMode.isActive(settings, at("2026-11-09T00:00:00Z")))
        assertTrue(NightMode.isActive(settings, at("2026-11-09T07:59:59Z")))
        assertFalse(NightMode.isActive(settings, at("2026-11-09T08:00:00Z")))
    }

    @Test
    fun `daytime interval uses the same inclusive and exclusive boundaries`() {
        val settings = settings(start = 9 * 60, end = 17 * 60)

        assertFalse(NightMode.isActive(settings, at("2026-11-08T08:59:59Z")))
        assertTrue(NightMode.isActive(settings, at("2026-11-08T09:00:00Z")))
        assertTrue(NightMode.isActive(settings, at("2026-11-08T16:59:59Z")))
        assertFalse(NightMode.isActive(settings, at("2026-11-08T17:00:00Z")))
    }

    @Test
    fun `disabled and zero-length intervals never defer printing`() {
        val now = at("2026-11-08T23:00:00Z")
        assertFalse(NightMode.isActive(settings(22 * 60, 8 * 60).copy(nightModeEnabled = false), now))
        assertFalse(NightMode.isActive(settings(22 * 60, 22 * 60), now))
    }

    @Test
    fun `morning delay crosses midnight and is never zero`() {
        val settings = settings(start = 22 * 60, end = 8 * 60)

        assertEquals(10 * 60 * 60 * 1_000L, NightMode.millisUntilEnd(settings, at("2026-11-08T22:00:00Z")))
        assertEquals(60 * 60 * 1_000L, NightMode.millisUntilEnd(settings, at("2026-11-09T07:00:00Z")))
        assertEquals(1_000L, NightMode.millisUntilEnd(settings, at("2026-11-09T07:59:59.500Z")))
    }

    @Test
    fun `morning scheduling follows local clock across daylight-saving transition`() {
        val settings = settings(start = 22 * 60, end = 8 * 60)
        val now = at("2026-10-24T22:00:00+02:00[Europe/Berlin]")

        assertEquals(11 * 60 * 60 * 1_000L, NightMode.millisUntilEnd(settings, now))
    }

    private fun at(value: String) = ZonedDateTime.parse(value)

    private fun settings(start: Int, end: Int) = AppSettings(
        printerAddress = null,
        printerName = null,
        smsEnabled = true,
        missedCallsEnabled = true,
        calendarEnabled = true,
        nightModeEnabled = true,
        nightStartMinutes = start,
        nightEndMinutes = end,
    )
}
