package ru.notiprint.work

import kotlinx.coroutines.CancellationException
import ru.notiprint.data.PrintJob
import ru.notiprint.printer.PrinterConnectionGate

internal interface PrintQueueStore {
    suspend fun recoverInterruptedPrints()
    suspend fun nextJobs(limit: Int): List<PrintJob>
    suspend fun markPrinting(id: Long)
    suspend fun markPrinted(id: Long)
    suspend fun markForRetry(id: Long, error: String)
}

internal fun interface PrinterSessionFactory {
    suspend fun connect(address: String): PrinterSession
}

internal interface PrinterSession : AutoCloseable {
    suspend fun print(job: PrintJob)
}

internal data class PrintBatchResult(val retryNeeded: Boolean = false, val hasMore: Boolean = false)

/** Processes one durable batch using the same gate as the manual diagnostic print. */
internal class PrintQueueProcessor(
    private val store: PrintQueueStore,
    private val printers: PrinterSessionFactory,
) {
    suspend fun processBatch(address: String): PrintBatchResult = PrinterConnectionGate.withLock {
        var currentJob: PrintJob? = null
        try {
            // Recovery, selection and failure recording all own the printer gate.
            // A waiting worker must never recover a row whose failure is still
            // being recorded by the previous worker.
            store.recoverInterruptedPrints()
            val jobs = store.nextJobs(BATCH_SIZE)
            if (jobs.isEmpty()) return@withLock PrintBatchResult()

            printers.connect(address).use { printer ->
                for (job in jobs) {
                    currentJob = job
                    store.markPrinting(job.id)
                    printer.print(job)
                    store.markPrinted(job.id)
                    currentJob = null
                }
            }
            // A bounded batch keeps one worker short. Explicitly report any
            // remainder, including events received while this batch printed.
            PrintBatchResult(hasMore = store.nextJobs(limit = 1).isNotEmpty())
        } catch (error: CancellationException) {
            // WorkManager cancellation leaves PRINTING recoverable on the next
            // run and must not be disguised as a Bluetooth failure.
            throw error
        } catch (error: Exception) {
            currentJob?.let { store.markForRetry(it.id, error.message ?: error.javaClass.simpleName) }
            PrintBatchResult(retryNeeded = true)
        }
    }

    private companion object {
        const val BATCH_SIZE = 50
    }
}
