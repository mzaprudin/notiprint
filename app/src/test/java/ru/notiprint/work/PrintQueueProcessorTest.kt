package ru.notiprint.work

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import ru.notiprint.data.NotificationKind
import ru.notiprint.data.PrintJob
import ru.notiprint.data.PrintStatus
import ru.notiprint.printer.PrinterConnectionGate

@OptIn(ExperimentalCoroutinesApi::class)
class PrintQueueProcessorTest {
    @Test
    fun `offline printer keeps jobs until a successful retry`() = runTest {
        val store = MemoryQueue(listOf(job(1), job(2)))
        val printer = RecordingPrinter().apply { connectionError = IOException("Printer is off") }
        val processor = PrintQueueProcessor(store, printer)

        assertTrue(processor.processBatch(ADDRESS).retryNeeded)
        assertEquals(listOf(PrintStatus.PENDING, PrintStatus.PENDING), store.rows.values.map { it.status })
        assertTrue(printer.printed.isEmpty())

        printer.connectionError = null
        assertEquals(PrintBatchResult(), processor.processBatch(ADDRESS))
        assertEquals(listOf(1L, 2L), printer.printed)
        assertTrue(store.rows.values.all { it.status == PrintStatus.PRINTED })
        assertEquals(1, printer.closedSessions)
    }

    @Test
    fun `interrupted row is recovered without repeating completed rows`() = runTest {
        val store = MemoryQueue(
            listOf(job(1, PrintStatus.PRINTED), job(2, PrintStatus.PRINTING), job(3)),
        )
        val printer = RecordingPrinter()

        assertEquals(PrintBatchResult(), PrintQueueProcessor(store, printer).processBatch(ADDRESS))

        assertEquals(listOf(2L, 3L), printer.printed)
        assertTrue(store.rows.values.all { it.status == PrintStatus.PRINTED })
        assertTrue(store.rows.values.all { it.attempts == 0 })
    }

    @Test
    fun `simultaneous processors cannot recover or print an active row`() = runTest {
        val store = MemoryQueue(listOf(job(1), job(2)))
        val printing = CompletableDeferred<Unit>()
        val releasePrint = CompletableDeferred<Unit>()
        val printer = RecordingPrinter().apply {
            onPrint = { item ->
                if (item.id == 1L) {
                    printing.complete(Unit)
                    releasePrint.await()
                }
            }
        }
        val processor = PrintQueueProcessor(store, printer)

        val first = async { processor.processBatch(ADDRESS) }
        printing.await()
        val second = async { processor.processBatch(ADDRESS) }
        runCurrent()

        assertEquals(1, store.recoveries)
        assertEquals(1, printer.connections)
        assertEquals(PrintStatus.PRINTING, store.rows.getValue(1).status)
        assertFalse(second.isCompleted)

        releasePrint.complete(Unit)
        first.await()
        second.await()
        assertEquals(listOf(1L, 2L), printer.printed)
        assertTrue(store.rows.values.all { it.status == PrintStatus.PRINTED })
    }

    @Test
    fun `failed row is recorded before another processor can recover it`() = runTest {
        val store = MemoryQueue(listOf(job(1)))
        val recordingRetry = CompletableDeferred<Unit>()
        val releaseRetry = CompletableDeferred<Unit>()
        store.beforeRetry = {
            recordingRetry.complete(Unit)
            releaseRetry.await()
        }
        var failFirstPrint = true
        val printer = RecordingPrinter().apply {
            onPrint = {
                if (failFirstPrint) {
                    failFirstPrint = false
                    throw IOException("Disconnected")
                }
            }
        }
        val processor = PrintQueueProcessor(store, printer)

        val first = async { processor.processBatch(ADDRESS) }
        recordingRetry.await()
        val second = async { processor.processBatch(ADDRESS) }
        runCurrent()
        releaseRetry.complete(Unit)

        assertTrue(first.await().retryNeeded)
        assertFalse(second.await().retryNeeded)
        assertEquals(PrintStatus.PRINTED, store.rows.getValue(1).status)
        assertEquals(1, store.rows.getValue(1).attempts)
        assertEquals(listOf(1L), printer.printed)
    }

    @Test
    fun `remaining rows explicitly request another batch until the queue is empty`() = runTest {
        val store = MemoryQueue((1L..120L).map { job(it) })
        val printer = RecordingPrinter()
        val processor = PrintQueueProcessor(store, printer)
        var batches = 0

        do {
            val result = processor.processBatch(ADDRESS)
            assertFalse(result.retryNeeded)
            batches++
            assertTrue("Queue must finish within three batches", batches <= 3)
        } while (result.hasMore)

        assertEquals((1L..120L).toList(), printer.printed)
        assertEquals(3, batches)
        assertEquals(3, printer.closedSessions)
        assertTrue(store.rows.values.all { it.status == PrintStatus.PRINTED })
    }

    @Test
    fun `new event during printing is retained for a following batch`() = runTest {
        val store = MemoryQueue(listOf(job(1)))
        val printer = RecordingPrinter().apply {
            onPrint = { item -> if (item.id == 1L) store.rows[2] = job(2) }
        }
        val processor = PrintQueueProcessor(store, printer)

        assertTrue(processor.processBatch(ADDRESS).hasMore)
        assertEquals(PrintBatchResult(), processor.processBatch(ADDRESS))

        assertEquals(listOf(1L, 2L), printer.printed)
    }

    @Test
    fun `cancellation is propagated and interrupted row remains recoverable`() = runTest {
        val store = MemoryQueue(listOf(job(1)))
        val printer = RecordingPrinter().apply { onPrint = { throw CancellationException("Stopped") } }

        try {
            PrintQueueProcessor(store, printer).processBatch(ADDRESS)
            fail("Cancellation must not be converted into a printer retry")
        } catch (expected: CancellationException) {
            assertEquals("Stopped", expected.message)
        }

        assertEquals(PrintStatus.PRINTING, store.rows.getValue(1).status)
        assertEquals(0, store.rows.getValue(1).attempts)
        assertEquals(1, printer.closedSessions)
    }

    @Test
    fun `manual diagnostic print and queue share one gate`() = runTest {
        val store = MemoryQueue(listOf(job(1)))
        val printer = RecordingPrinter()
        val diagnosticStarted = CompletableDeferred<Unit>()
        val releaseDiagnostic = CompletableDeferred<Unit>()
        val diagnostic = launch {
            PrinterConnectionGate.withLock {
                diagnosticStarted.complete(Unit)
                releaseDiagnostic.await()
            }
        }
        diagnosticStarted.await()
        val worker = async { PrintQueueProcessor(store, printer).processBatch(ADDRESS) }
        runCurrent()

        assertEquals(0, printer.connections)
        assertEquals(0, store.recoveries)

        releaseDiagnostic.complete(Unit)
        diagnostic.join()
        worker.await()
        assertEquals(listOf(1L), printer.printed)
    }

    @Test
    fun `empty queue does not open a printer connection`() = runTest {
        val store = MemoryQueue(emptyList())
        val printer = RecordingPrinter()

        assertEquals(PrintBatchResult(), PrintQueueProcessor(store, printer).processBatch(ADDRESS))

        assertEquals(0, printer.connections)
    }

    private class MemoryQueue(jobs: List<PrintJob>) : PrintQueueStore {
        val rows = jobs.associateByTo(linkedMapOf()) { it.id }
        var recoveries = 0
        var beforeRetry: suspend () -> Unit = {}

        override suspend fun recoverInterruptedPrints() {
            recoveries++
            rows.replaceAll { _, item ->
                if (item.status == PrintStatus.PRINTING) item.copy(status = PrintStatus.PENDING) else item
            }
        }

        override suspend fun nextJobs(limit: Int): List<PrintJob> = rows.values
            .filter { it.status == PrintStatus.PENDING || it.status == PrintStatus.RETRY }
            .sortedBy { it.receivedAt }
            .take(limit)

        override suspend fun markPrinting(id: Long) {
            rows[id] = rows.getValue(id).copy(status = PrintStatus.PRINTING, lastError = null)
        }

        override suspend fun markPrinted(id: Long) {
            rows[id] = rows.getValue(id).copy(status = PrintStatus.PRINTED, lastError = null)
        }

        override suspend fun markForRetry(id: Long, error: String) {
            beforeRetry()
            rows[id] = rows.getValue(id).let {
                it.copy(status = PrintStatus.RETRY, attempts = it.attempts + 1, lastError = error)
            }
        }
    }

    private class RecordingPrinter : PrinterSessionFactory {
        val printed = mutableListOf<Long>()
        var connections = 0
        var closedSessions = 0
        var connectionError: IOException? = null
        var onPrint: suspend (PrintJob) -> Unit = {}

        override suspend fun connect(address: String): PrinterSession {
            assertEquals(ADDRESS, address)
            connections++
            connectionError?.let { throw it }
            return object : PrinterSession {
                override suspend fun print(job: PrintJob) {
                    onPrint(job)
                    printed += job.id
                }

                override fun close() {
                    closedSessions++
                }
            }
        }
    }

    private companion object {
        const val ADDRESS = "00:11:22:33:44:55"

        fun job(id: Long, status: PrintStatus = PrintStatus.PENDING) = PrintJob(
            id = id,
            notificationKey = "notification:$id",
            kind = NotificationKind.SMS,
            title = "Sender $id",
            message = "Message $id",
            packageName = "test",
            postedAt = id,
            receivedAt = id,
            status = status,
        )
    }
}
