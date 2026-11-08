package ru.notiprint.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.notiprint.data.AppDatabase
import ru.notiprint.printer.BluetoothPermissions
import ru.notiprint.settings.AppPreferences
import ru.notiprint.settings.AppSettings
import ru.notiprint.settings.NightMode

internal data class PrintWorkerDependencies(
    val settings: () -> AppSettings,
    val hasBluetoothPermission: () -> Boolean,
    val processor: () -> PrintQueueProcessor,
    val scheduleMorning: () -> Unit,
    val enqueueRetry: () -> Unit,
    val enqueueNow: () -> Unit,
    val resetRetryBackoff: () -> Unit,
)

class PrintWorker internal constructor(
    appContext: Context,
    workerParams: WorkerParameters,
    private val dependencies: PrintWorkerDependencies,
) : CoroutineWorker(appContext, workerParams) {
    constructor(appContext: Context, workerParams: WorkerParameters) : this(
        appContext,
        workerParams,
        PrintWorkerDependencies(
            settings = { AppPreferences(appContext).snapshot() },
            hasBluetoothPermission = { BluetoothPermissions.hasConnectPermission(appContext) },
            processor = {
                PrintQueueProcessor(
                    RoomPrintQueueStore(AppDatabase.get(appContext).printJobDao()),
                    BluetoothPrinterSessionFactory(appContext),
                )
            },
            scheduleMorning = { PrintScheduler.scheduleMorning(appContext) },
            enqueueRetry = { PrintScheduler.enqueueRetry(appContext) },
            enqueueNow = { PrintScheduler.enqueueNow(appContext) },
            resetRetryBackoff = { PrintScheduler.resetRetryBackoff(appContext) },
        ),
    )

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val settings = dependencies.settings()
        if (NightMode.isActive(settings)) {
            dependencies.scheduleMorning()
            return@withContext Result.success()
        }
        val address = settings.printerAddress
        if (address.isNullOrBlank() || !dependencies.hasBluetoothPermission()) {
            return@withContext Result.success()
        }

        val result = dependencies.processor().processBatch(address)
        if (result.retryNeeded) {
            dependencies.enqueueRetry()
        } else {
            dependencies.resetRetryBackoff()
            if (result.hasMore) dependencies.enqueueNow()
        }
        Result.success()
    }
}
