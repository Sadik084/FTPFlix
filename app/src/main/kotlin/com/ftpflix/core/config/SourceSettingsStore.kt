package com.ftpflix.core.config

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ftpflix.sources.resolve.StreamSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.sourceSettingsDataStore by preferencesDataStore(name = "source_settings")

// User source preferences: enabled set and AUTO order (independent of health checks).
@Singleton
class SourceSettingsStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val keyOrder = stringPreferencesKey("source_order")
    private val keyEnabledCircle = booleanPreferencesKey("enabled_circle")
    private val keyEnabledRoar = booleanPreferencesKey("enabled_roar")
    private val keyEnabledHdhub = booleanPreferencesKey("enabled_hdhub")

    val sourceOrder: Flow<List<StreamSource>> = context.sourceSettingsDataStore.data.map { prefs ->
        prefs[keyOrder]
            ?.split(',')
            ?.mapNotNull { runCatching { StreamSource.valueOf(it.trim()) }.getOrNull() }
            ?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_ORDER
    }

    val enabledSources: Flow<Set<StreamSource>> = context.sourceSettingsDataStore.data.map { prefs ->
        buildSet {
            if (prefs[keyEnabledCircle] != false) add(StreamSource.CIRCLE_FTP)
            if (prefs[keyEnabledRoar] != false) add(StreamSource.ROAR_ZONE)
            if (prefs[keyEnabledHdhub] != false) add(StreamSource.HD_HUB)
        }
    }

    suspend fun setPrimary(source: StreamSource) {
        val order = sourceOrder.first()
        if (order.firstOrNull() == source) return
        context.sourceSettingsDataStore.edit { prefs ->
            prefs[keyOrder] = (listOf(source) + order.filter { it != source })
                .map { it.name }
                .joinToString(",")
        }
    }

    suspend fun setEnabled(source: StreamSource, enabled: Boolean) {
        val key = when (source) {
            StreamSource.CIRCLE_FTP -> keyEnabledCircle
            StreamSource.ROAR_ZONE -> keyEnabledRoar
            StreamSource.HD_HUB -> keyEnabledHdhub
            StreamSource.AUTO -> return
        }
        context.sourceSettingsDataStore.edit { prefs -> prefs[key] = enabled }
    }

    companion object {
        val DEFAULT_ORDER = listOf(
            StreamSource.CIRCLE_FTP,
            StreamSource.ROAR_ZONE,
            StreamSource.HD_HUB
        )
    }
}
