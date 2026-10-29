package com.ftpflix.core.config

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private val Context.sourceHealthDataStore by preferencesDataStore(name = "source_health")

// One-time reachability check; failed pings disable the source.
@Singleton
class SourceHealthStore @Inject constructor(
    @ApplicationContext private val context: Context,
    okHttp: OkHttpClient
) {
    private val client = okHttp.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        // Bounds the whole call (DNS included) so a black-holed host can't hang a launch.
        .callTimeout(PING_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()

    private val checkedOnce = booleanPreferencesKey("checked_once")
    private val keyCircle = booleanPreferencesKey("circleftp_enabled")
    private val keyRoar = booleanPreferencesKey("roarzone_enabled")

    // Defaults to true so a resolve racing the first ping still tries both sources.
    val circleFtpEnabled: Flow<Boolean> =
        context.sourceHealthDataStore.data.map { it[keyCircle] ?: true }

    val roarZoneEnabled: Flow<Boolean> =
        context.sourceHealthDataStore.data.map { it[keyRoar] ?: true }

    suspend fun ensureChecked() {
        if (context.sourceHealthDataStore.data.first()[checkedOnce] == true) return
        val (circleOk, roarOk) = coroutineScope {
            val circle = async { ping(CIRCLE_PING_URL) }
            val roar = async { ping(ROARZONE_PING_URL) }
            circle.await() to roar.await()
        }
        context.sourceHealthDataStore.edit {
            it[checkedOnce] = true
            it[keyCircle] = circleOk
            it[keyRoar] = roarOk
        }
    }

    private suspend fun ping(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    private companion object {
        /** Cheap authenticated-free GET per source: the CircleFTP posts list and Emby's public system info. */
        const val CIRCLE_PING_URL = "http://new.circleftp.net:5000/api/posts?searchTerm=a&order=desc"
        const val ROARZONE_PING_URL = "https://play.roarzone.net/emby/System/Info/Public"
        const val PING_TIMEOUT_MS = 8_000L
    }
}
