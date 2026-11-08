package ru.notiprint.work

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import ru.notiprint.settings.AppPreferences
import ru.notiprint.settings.NightMode
import java.util.concurrent.TimeUnit

object PrintScheduler {
    private const val PRINT_NOW_WORK = "notiprint-print-now"
    private const val PRINT_RETRY_WORK = "notiprint-print-retry"
    private const val MORNING_WORK = "notiprint-morning-print"
    private const val RETRY_PREFERENCES = "notiprint_retry_state"
    private const val CONSECUTIVE_FAILURES = "consecutive_failures"

    fun enqueueNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<PrintWorker>()
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            PRINT_NOW_WORK,
            // If a notification arrives during a print, preserve one more worker after it.
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    fun scheduleMorning(context: Context) {
        val settings = AppPreferences(context).snapshot()
        val request = OneTimeWorkRequestBuilder<PrintWorker>()
            .setInitialDelay(NightMode.millisUntilEnd(settings), TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            MORNING_WORK,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Rechecks a pending queue after a transient Bluetooth/printer failure. This
     * uses a separate chain so an existing foreground print is never cancelled.
     * Retry spacing grows to eight minutes, then stays capped at that interval.
     */
    fun enqueueRetry(context: Context) {
        val delaySeconds = nextRetryDelaySeconds(context)
        val request = OneTimeWorkRequestBuilder<PrintWorker>()
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            PRINT_RETRY_WORK,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    fun resetRetryBackoff(context: Context) {
        context.getSharedPreferences(RETRY_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .remove(CONSECUTIVE_FAILURES)
            .apply()
    }

    private fun nextRetryDelaySeconds(context: Context): Long {
        val preferences = context.getSharedPreferences(RETRY_PREFERENCES, Context.MODE_PRIVATE)
        val failures = preferences.getInt(CONSECUTIVE_FAILURES, 0)
        val delay = RetryBackoff.delaySeconds(failures)
        preferences.edit()
            .putInt(CONSECUTIVE_FAILURES, RetryBackoff.nextFailureCount(failures))
            .apply()
        return delay
    }
}
