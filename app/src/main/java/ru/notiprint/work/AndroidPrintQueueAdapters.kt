package ru.notiprint.work

import android.content.Context
import android.util.Log
import ru.notiprint.data.PrintJob
import ru.notiprint.data.PrintJobDao
import ru.notiprint.printer.BluetoothPrinterClient
import ru.notiprint.printer.NotificationBitmapRenderer

internal class RoomPrintQueueStore(private val dao: PrintJobDao) : PrintQueueStore {
    override suspend fun recoverInterruptedPrints() = dao.recoverInterruptedPrints()
    override suspend fun nextJobs(limit: Int): List<PrintJob> = dao.nextJobs(limit)
    override suspend fun markPrinting(id: Long) = dao.markPrinting(id)
    override suspend fun markPrinted(id: Long) = dao.markPrinted(id)
    override suspend fun markForRetry(id: Long, error: String) = dao.markForRetry(id, error)
}

internal class BluetoothPrinterSessionFactory(private val context: Context) : PrinterSessionFactory {
    override suspend fun connect(address: String): PrinterSession {
        val client = BluetoothPrinterClient(context)
        try {
            client.connect(address)
        } catch (error: Throwable) {
            client.close()
            throw error
        }
        return object : PrinterSession {
            override suspend fun print(job: PrintJob) {
                Log.i(TAG, "Printing job id=${job.id}")
                val bitmap = NotificationBitmapRenderer.render(job)
                try {
                    client.print(bitmap)
                } finally {
                    bitmap.recycle()
                }
                Log.i(TAG, "Sent job id=${job.id}")
            }

            override fun close() = client.close()
        }
    }

    private companion object {
        const val TAG = "NotiPrintWorker"
    }
}
