package ru.notiprint.work

/** Retry policy shared by the persistent scheduler and its JVM tests. */
internal object RetryBackoff {
    private const val INITIAL_DELAY_SECONDS = 15L
    private const val MAX_DELAY_SECONDS = 8 * 60L
    private const val MAX_EXPONENT = 5

    fun delaySeconds(consecutiveFailures: Int): Long =
        (INITIAL_DELAY_SECONDS * (1L shl consecutiveFailures.coerceIn(0, MAX_EXPONENT)))
            .coerceAtMost(MAX_DELAY_SECONDS)

    fun nextFailureCount(consecutiveFailures: Int): Int =
        consecutiveFailures.coerceIn(0, MAX_EXPONENT) + 1
}
