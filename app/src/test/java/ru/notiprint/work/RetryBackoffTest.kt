package ru.notiprint.work

import org.junit.Assert.assertEquals
import org.junit.Test

class RetryBackoffTest {
    @Test
    fun `retry delay doubles from fifteen seconds and caps at eight minutes`() {
        var failures = 0
        val delays = (1..8).map {
            RetryBackoff.delaySeconds(failures).also { failures = RetryBackoff.nextFailureCount(failures) }
        }

        assertEquals(listOf(15L, 30L, 60L, 120L, 240L, 480L, 480L, 480L), delays)
        assertEquals(6, failures)
    }

    @Test
    fun `extreme counts keep delay within policy and cannot overflow`() {
        assertEquals(15L, RetryBackoff.delaySeconds(Int.MIN_VALUE))
        assertEquals(480L, RetryBackoff.delaySeconds(Int.MAX_VALUE))
        assertEquals(1, RetryBackoff.nextFailureCount(Int.MIN_VALUE))
        assertEquals(6, RetryBackoff.nextFailureCount(Int.MAX_VALUE))
    }
}
