package com.ftpflix.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.ftpflix.data.repository.TmdbFeedRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WorkerEntryPoint {
    fun feedRepository(): TmdbFeedRepository
}

class LibraryScanWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = try {
        val entry = EntryPointAccessors.fromApplication(
            applicationContext,
            WorkerEntryPoint::class.java,
        )
        withContext(Dispatchers.Default) {
            entry.feedRepository().refresh()
        }
        Result.success()
    } catch (_: Exception) {
        Result.retry()
    }
}
