package com.ftpflix.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Cheap HEAD probes for cached stream URLs (liveness + size). */
@Singleton
class HttpProbe @Inject constructor(okHttp: OkHttpClient) {

    private val client = okHttp.newBuilder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    suspend fun headOk(url: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).head().build()).execute()
                .use { it.isSuccessful }
        }.getOrDefault(defaultValue = false)
    }

    /** Content-Length of a direct media URL, or null when unavailable. */
    suspend fun contentLength(url: String): Long? = withContext(Dispatchers.IO) {
        runCatching {
            client.newCall(Request.Builder().url(url).head().build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                resp.header("content-length")?.toLongOrNull()?.takeIf { it > 0 }
            }
        }.getOrNull()
    }
}
