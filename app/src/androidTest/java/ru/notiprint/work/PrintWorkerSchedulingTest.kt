package ru.notiprint.work

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.IOException
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.notiprint.data.AppDatabase
import ru.notiprint.data.NotificationKind
import ru.notiprint.data.PrintJob
import ru.notiprint.data.PrintStatus
import ru.notiprint.settings.AppSettings

/** Exercises the real worker, Room DAO and unique WorkManager chains without Bluetooth hardware. */
@RunWith(AndroidJUnit4::class)
class PrintWorkerSchedulingTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var workManager: WorkManager
    private val printerAvailable = AtomicBoolean(true)
    private val connectionAttempts = AtomicInteger()
    private val printed = Collections.synchronizedList(mutableListOf<Long>())

    @Before
    fun createWorkerEnvironment() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        PrintScheduler.resetRetryBackoff(context)
        val dependencies = PrintWorkerDependencies(
            settings = { daytimeSettings() },
            hasBluetoothPermission = { true },
            processor = {
                PrintQueueProcessor(
                    RoomPrintQueueStore(database.printJobDao()),
                    PrinterSessionFactory {
                        connectionAttempts.incrementAndGet()
                        if (!printerAvailable.get()) throw IOException("Printer is switched off")
                        object : PrinterSession {
                            override suspend fun print(job: PrintJob) {
                                printed += job.id
                            }

                            override fun close() = Unit
                        }
                    },
                )
            },
            scheduleMorning = { PrintScheduler.scheduleMorning(context) },
            enqueueRetry = { PrintScheduler.enqueueRetry(context) },
            enqueueNow = { PrintScheduler.enqueueNow(context) },
            resetRetryBackoff = { PrintScheduler.resetRetryBackoff(context) },
        )
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker? = if (workerClassName == PrintWorker::class.java.name) {
                PrintWorker(appContext, workerParameters, dependencies)
            } else {
                null
            }
        }
        val configuration = Configuration.Builder()
            .setWorkerFactory(factory)
            .setExecutor(SynchronousExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration)
        workManager = WorkManager.getInstance(context)
    }

    @After
    fun closeWorkerEnvironment() {
        workManager.cancelAllWork().result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        WorkManagerTestInitHelper.closeWorkDatabase()
        PrintScheduler.resetRetryBackoff(context)
        database.close()
    }

    @Test
    fun oneTriggerDrains120EventsThroughScheduledContinuation() = runBlocking {
        val ids = (1..120).map { index -> database.printJobDao().insert(event(index)) }

        PrintScheduler.enqueueNow(context)

        val completed = awaitWork(PRINT_NOW) { jobs ->
            jobs.size == 3 && jobs.all { it.state == WorkInfo.State.SUCCEEDED }
        }
        assertEquals(3, completed.size)
        assertEquals(ids, printed.toList())
        assertEquals(3, connectionAttempts.get())
        assertEquals(0, database.printJobDao().observeWaitingCount().first())
        assertTrue(database.printJobDao().observeRecent(120).first().all { it.status == PrintStatus.PRINTED })
    }

    @Test
    fun offlinePrinterSchedulesDelayedRetryAndSuccessResetsDelay() = runBlocking {
        val firstId = database.printJobDao().insert(event(1))
        printerAvailable.set(false)

        PrintScheduler.enqueueNow(context)
        awaitWork(PRINT_NOW) { jobs -> jobs.isNotEmpty() && jobs.all { it.state.isFinished } }
        val retry = awaitWork(PRINT_RETRY) { jobs -> jobs.any { it.state == WorkInfo.State.ENQUEUED } }
            .single { it.state == WorkInfo.State.ENQUEUED }

        assertEquals(15_000L, retry.initialDelayMillis)
        assertEquals(PrintStatus.PENDING, database.printJobDao().observeRecent(10).first().single().status)
        assertTrue(printed.isEmpty())

        printerAvailable.set(true)
        val driver = requireNotNull(WorkManagerTestInitHelper.getTestDriver(context))
        driver.setInitialDelayMet(retry.id)
        awaitWork(PRINT_RETRY) { jobs -> jobs.isNotEmpty() && jobs.all { it.state == WorkInfo.State.SUCCEEDED } }

        assertEquals(listOf(firstId), printed.toList())
        assertEquals(0, database.printJobDao().observeWaitingCount().first())

        // A later outage starts at the first interval after a successful retry.
        database.printJobDao().insert(event(2))
        printerAvailable.set(false)
        PrintScheduler.enqueueNow(context)
        val newRetry = awaitWork(PRINT_RETRY) { jobs ->
            jobs.any { it.id != retry.id && it.state == WorkInfo.State.ENQUEUED }
        }.single { it.id != retry.id && it.state == WorkInfo.State.ENQUEUED }
        assertEquals(15_000L, newRetry.initialDelayMillis)

        printerAvailable.set(true)
        driver.setInitialDelayMet(newRetry.id)
        awaitWork(PRINT_RETRY) { jobs -> jobs.isNotEmpty() && jobs.all { it.state == WorkInfo.State.SUCCEEDED } }
        assertEquals(2, printed.size)
        assertEquals(0, database.printJobDao().observeWaitingCount().first())
    }

    private suspend fun awaitWork(
        uniqueName: String,
        predicate: (List<WorkInfo>) -> Boolean,
    ): List<WorkInfo> = withTimeout(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS)) {
        workManager.getWorkInfosForUniqueWorkFlow(uniqueName).first(predicate)
    }

    private fun event(index: Int) = PrintJob(
        notificationKey = "sms:$index",
        kind = NotificationKind.SMS,
        title = "Sender $index",
        message = "Event $index",
        packageName = "test.sms",
        postedAt = index.toLong(),
        receivedAt = index.toLong(),
    )

    private fun daytimeSettings() = AppSettings(
        printerAddress = "00:11:22:33:44:55",
        printerName = "Test printer",
        smsEnabled = true,
        missedCallsEnabled = true,
        calendarEnabled = true,
        nightModeEnabled = false,
        nightStartMinutes = 22 * 60,
        nightEndMinutes = 8 * 60,
    )

    private companion object {
        const val PRINT_NOW = "notiprint-print-now"
        const val PRINT_RETRY = "notiprint-print-retry"
        const val TIMEOUT_SECONDS = 30L
    }
}
