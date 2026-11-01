package com.ftpflix.sources.roarzone

import android.net.Uri
import com.ftpflix.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class EmbyAuthRequest(
    @SerialName("Username") val username: String,
    @SerialName("Pw") val pw: String,
)

@Serializable
data class EmbyAuthResponse(
    @SerialName("AccessToken") val accessToken: String? = null,
)

@Serializable
data class EmbyItemsResponse(
    @SerialName("Items") val items: List<EmbyItem> = emptyList()
)

@Serializable
data class EmbyItem(
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("ProductionYear") val year: Int? = null,
    @SerialName("IndexNumber") val indexNumber: Int? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Int? = null,
    @SerialName("MediaSources") val mediaSources: List<EmbyMediaSource> = emptyList()
) {
    /** Quality/source tags live in the media source names/paths, not the item name. */
    val qualityText: String
        get() = mediaSources.joinToString(" ") { (name, path) -> "${name.orEmpty()} ${path.orEmpty()}" }
}

@Serializable
data class EmbyMediaSource(
    @SerialName("Name") val name: String? = null,
    @SerialName("Path") val path: String? = null,
    @SerialName("Size") val size: Long? = null
)

// Emby server API with a ~30 min cached guest token.
@Singleton
class RoarZoneApi @Inject constructor(
    okHttp: OkHttpClient,
    private val json: Json
) {
    private val client = okHttp.newBuilder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val tokenMutex = Mutex()
    private var cachedToken: String? = null
    private var tokenExpiry = 0L

    // Stable device id per process.
    private val deviceId = "ftpflix-${UUID.randomUUID()}"

    suspend fun currentToken(force: Boolean = false): String? = tokenMutex.withLock {
        if (!force) {
            cachedToken?.takeIf { System.currentTimeMillis() < tokenExpiry }?.let { return it }
        }
        val body = json.encodeToString(EmbyAuthRequest(USERNAME, ""))
        val token = withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(
                    Request.Builder()
                        .url("$EMBY_BASE/emby/Users/AuthenticateByName")
                        .header("X-Emby-Authorization", authHeader())
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build()
                ).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching null
                    val payload = resp.body.string()
                    json.decodeFromString<EmbyAuthResponse>(payload).accessToken
                }
            }.getOrNull()
        }
        if (token != null) {
            cachedToken = token
            tokenExpiry = System.currentTimeMillis() + TOKEN_TTL_MS
        }
        token
    }

    suspend fun searchMovies(imdbId: String?, title: String): List<EmbyItem> {
        val query = if (!imdbId.isNullOrBlank()) {
            "/emby/Items?AnyProviderIdEquals=imdb.$imdbId" +
                "&IncludeItemTypes=Movie&Recursive=true&Fields=MediaSources,ProviderIds,Path"
        } else {
            "/emby/Items?SearchTerm=${Uri.encode(title)}" +
                "&IncludeItemTypes=Movie&Recursive=true&Fields=MediaSources,Path"
        }
        return decodeItems(query)
    }

    suspend fun searchSeries(imdbId: String?, title: String): List<EmbyItem> {
        val query = if (!imdbId.isNullOrBlank()) {
            "/emby/Items?AnyProviderIdEquals=imdb.$imdbId" +
                "&IncludeItemTypes=Series&Recursive=true&Fields=ProviderIds"
        } else {
            "/emby/Items?SearchTerm=${Uri.encode(title)}" +
                "&IncludeItemTypes=Series&Recursive=true&Fields=ProviderIds"
        }
        return decodeItems(query)
    }

    // Episodes for a season; MediaSources requested for quality/size.
    suspend fun episodes(seriesId: String, season: Int): List<EmbyItem> =
        decodeItems("/emby/Shows/$seriesId/Episodes?Season=$season&Fields=MediaSources,Path")

    fun streamUrl(itemId: String, token: String): String =
        "$EMBY_BASE/emby/Videos/$itemId/stream?Static=true&api_key=$token"

    // GET with token; re-auth once on failure (revoked token) and retry.
    private suspend fun decodeItems(path: String): List<EmbyItem> {
        val token = currentToken() ?: return emptyList()
        val body = fetch(path, token) ?: run {
            val fresh = currentToken(force = true) ?: return emptyList()
            fetch(path, fresh) ?: return emptyList()
        }
        return runCatching {
            json.decodeFromString<EmbyItemsResponse>(body).items
        }.getOrDefault(emptyList())
    }

    private suspend fun fetch(path: String, token: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val separator = if (path.contains('?')) '&' else '?'
            client.newCall(
                Request.Builder().url("$EMBY_BASE$path${separator}api_key=$token").build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body.string()
            }
        }.getOrNull()
    }

    private fun authHeader(): String =
        "MediaBrowser Client=\"FTPFlix\", Device=\"FTPFlix\", " +
            "DeviceId=\"$deviceId\", Version=\"1.0.0\""

    companion object {
        /** Overridable via ROARZONE_BASE_URL in local.properties. */
        const val EMBY_BASE: String = BuildConfig.ROARZONE_BASE_URL
        const val HOST_LABEL = "roarzone"
        const val USERNAME = "RoarZone_Guest"

        // Refresh before the 30-min token lifetime so URLs stay valid.
        private const val TOKEN_TTL_MS = 25 * 60_000L
    }
}
