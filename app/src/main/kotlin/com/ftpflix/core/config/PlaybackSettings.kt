package com.ftpflix.core.config

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.playbackDataStore by preferencesDataStore(name = "playback_settings")

/** How the video fills the screen; drives the player surface size (see PlayerScreen.fitModifier). */
enum class FitMode { Fit, Crop, Stretch, Original }

@Singleton
class PlaybackSettings @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val KEY_SPEED = floatPreferencesKey("playback_speed")
    private val KEY_FIT = stringPreferencesKey("fit_mode")

    val playbackSpeed: Flow<Float> = context.playbackDataStore.data.map {
        it[KEY_SPEED] ?: 1f
    }

    suspend fun setPlaybackSpeed(speed: Float) {
        context.playbackDataStore.edit { it[KEY_SPEED] = speed }
    }

    // Persisted fit mode; restored on player open.
    val fitMode: Flow<FitMode> = context.playbackDataStore.data.map { prefs ->
        prefs[KEY_FIT]?.let { name ->
            runCatching { FitMode.valueOf(name) }.getOrNull()
        } ?: FitMode.Fit
    }

    suspend fun setFitMode(mode: FitMode) {
        context.playbackDataStore.edit { it[KEY_FIT] = mode.name }
    }
}
