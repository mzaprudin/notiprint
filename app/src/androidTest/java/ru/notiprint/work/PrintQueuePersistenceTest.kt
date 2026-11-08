package ru.notiprint.work

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import ru.notiprint.data.AppDatabase
import ru.notiprint.data.NotificationKind
import ru.notiprint.data.PrintJob
import ru.notiprint.data.PrintStatus

/** Real SQLite files verify that a new processor can resume a previously persisted queue. */
@RunWith(AndroidJUnit4::class)
class PrintQueuePersistenceTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var databaseName: String

    @Before
    fun createDatabase() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "print-queue-${UUID.randomUUID()}.db"
        database = openDatabase()
    }

    @After
    fun deleteDatabase() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun acceptedEventSurvivesReopenAndDuplicateIsIgnored() = runBlocking {
        val event = event("same-source-event")
        val id = database.printJobDao().insert(event)
        assertTrue(id > 0)
        assertEquals(-1L, database.printJobDao().insert(event.copy(message = "duplicate update")))

        reopenDatabase()

        val persisted = database.printJobDao().observeRecent(10).first().single()
        assertEquals(id, persisted.id)
        assertEquals(event.message, persisted.message)
        assertEquals(PrintStatus.PENDING, persisted.status)
        val printed = mutableListOf<Long>()
        val outcome = processor(printed).processBatch(PRINTER_ADDRESS)

        assertFalse(outcome.retryNeeded)
        assertFalse(outcome.hasMore)
        assertEquals(listOf(id), printed)
        assertEquals(PrintStatus.PRINTED, database.printJobDao().observeRecent(10).first().single().status)
        assertEquals(0, database.printJobDao().observeWaitingCount().first())
    }

    @Test
    fun interruptedPrintingIsRecoveredAfterDatabaseReopen() = runBlocking {
        val id = database.printJobDao().insert(event("interrupted"))
        database.printJobDao().markPrinting(id)

        reopenDatabase()

        assertEquals(PrintStatus.PRINTING, database.printJobDao().observeRecent(10).first().single().status)
        val printed = mutableListOf<Long>()
        assertFalse(processor(printed).processBatch(PRINTER_ADDRESS).retryNeeded)
        assertFalse(processor(printed).processBatch(PRINTER_ADDRESS).retryNeeded)

        assertEquals(listOf(id), printed)
        assertEquals(PrintStatus.PRINTED, database.printJobDao().observeRecent(10).first().single().status)
    }

    @Test
    fun failedTransferStaysRetryableAcrossReopenThenCompletes() = runBlocking {
        val id = database.printJobDao().insert(event("failed-transfer"))
        val failure = PrintQueueProcessor(
            RoomPrintQueueStore(database.printJobDao()),
            PrinterSessionFactory {
                object : PrinterSession {
                    override suspend fun print(job: PrintJob) {
                        throw IOException("Printer went offline")
                    }

                    override fun close() = Unit
                }
            },
        ).processBatch(PRINTER_ADDRESS)
        assertTrue(failure.retryNeeded)

        reopenDatabase()

        val retry = database.printJobDao().observeRecent(10).first().single()
        assertEquals(PrintStatus.RETRY, retry.status)
        assertEquals(1, retry.attempts)
        assertEquals("Printer went offline", retry.lastError)
        val printed = mutableListOf<Long>()
        assertFalse(processor(printed).processBatch(PRINTER_ADDRESS).retryNeeded)

        val completed = database.printJobDao().observeRecent(10).first().single()
        assertEquals(listOf(id), printed)
        assertEquals(PrintStatus.PRINTED, completed.status)
        assertEquals(1, completed.attempts)
        assertEquals(null, completed.lastError)
        assertEquals(0, database.printJobDao().observeWaitingCount().first())
    }

    private fun processor(printed: MutableList<Long>) = PrintQueueProcessor(
        RoomPrintQueueStore(database.printJobDao()),
        PrinterSessionFactory {
            object : PrinterSession {
                override suspend fun print(job: PrintJob) {
                    printed += job.id
                }

                override fun close() = Unit
            }
        },
    )

    private fun openDatabase(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, databaseName).build()

    private fun reopenDatabase() {
        database.close()
        database = openDatabase()
    }

    private fun event(key: String) = PrintJob(
        notificationKey = key,
        kind = NotificationKind.SMS,
        title = "Sender",
        message = "Durable message",
        packageName = "test.sms",
        postedAt = 1_000L,
        receivedAt = 1_000L,
    )

    private companion object {
        const val PRINTER_ADDRESS = "00:11:22:33:44:55"
    }
}
