package com.ftpflix

import android.app.Application
import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import com.ftpflix.core.config.AgeRatingStore
import com.ftpflix.core.config.SourceHealthStore
import com.ftpflix.work.LibraryScanWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okio.Path.Companion.toPath
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class FtpFlixApp : Application(), SingletonImageLoader.Factory {

    @Inject lateinit var sourceHealth: SourceHealthStore

    @Inject lateinit var ageRatingStore: AgeRatingStore

    /** Outlives onCreate so the one-time source ping finishes even if startup moves on. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // First launch pings sources and persists verdict; later launches just read flags.
        appScope.launch { sourceHealth.ensureChecked() }
        appScope.launch { ageRatingStore.warmup() }
        scheduleLibraryRefresh()
    }

    // Poster cache capped at 50 MB; Coil LRU-evicts beyond it.
    override fun newImageLoader(context: Context): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    // Coil 3's DiskCache takes an okio.Path (KMP API).
                    .directory(context.cacheDir.resolve("poster_cache").absolutePath.toPath())
                    .maxSizeBytes(POSTER_CACHE_BYTES)
                    .build()
            }
            .build()

    private fun scheduleLibraryRefresh() {
        val request = PeriodicWorkRequestBuilder<LibraryScanWorker>(6, TimeUnit.HOURS)
            .setConstraints(
                androidx.work.Constraints.Builder()
                    .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                    .build(),
            )
            .setInitialDelay(1, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "library_refresh",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private companion object {
        /** 50 MB on-disk poster cache; LRU-evicted beyond this. */
        const val POSTER_CACHE_BYTES = 50L * 1024 * 1024
    }
}
