package com.singularity.todo.feature.alarms

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import co.touchlab.kermit.Logger
import com.singularity.todo.feature.reminders.AlarmHandler
import kotlinx.coroutines.CancellationException
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ReminderCatchUpWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params),
    KoinComponent {

    private val handler: AlarmHandler by inject()
    private val log = Logger.withTag("ReminderCatchUpWorker")

    @Suppress("TooGenericExceptionCaught")
    override suspend fun doWork(): androidx.work.ListenableWorker.Result = try {
        handler.catchUp()
        androidx.work.ListenableWorker.Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        log.e(e) { "catch-up failed" }
        androidx.work.ListenableWorker.Result.retry()
    }
}
