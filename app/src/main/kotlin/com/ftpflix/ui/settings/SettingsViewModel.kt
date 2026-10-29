package com.ftpflix.ui.settings

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ftpflix.core.config.SourceSettingsStore
import com.ftpflix.data.local.ResolvedStreamDao
import com.ftpflix.data.repository.HistoryRepository
import com.ftpflix.data.repository.MetadataRepository
import com.ftpflix.data.repository.MyListRepository
import com.ftpflix.data.repository.TmdbFeedRepository
import com.ftpflix.sources.resolve.StreamSource
import com.ftpflix.update.AppUpdateManager
import com.ftpflix.update.AppUpdateState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUi(
    val busy: Boolean = false,
    val message: String? = null,
    /** Count badge for the "View Watch History" row. */
    val historyCount: Int = 0,
    /** Stream source order (primary first) from Settings. */
    val sourceOrder: List<StreamSource> = SourceSettingsStore.DEFAULT_ORDER,
    /** User-enabled stream sources. */
    val enabledSources: Set<StreamSource> = SourceSettingsStore.DEFAULT_ORDER.toSet(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val metadata: MetadataRepository,
    private val history: HistoryRepository,
    private val myList: MyListRepository,
    private val feedRepo: TmdbFeedRepository,
    private val resolvedStreamDao: ResolvedStreamDao,
    private val sourceSettings: SourceSettingsStore,
    private val updater: AppUpdateManager,
) : ViewModel() {

    /** Live state of the OTA update flow (check/download/install). */
    val update: StateFlow<AppUpdateState> = updater.state

    private val _busy = MutableStateFlow(value = false)
    private val _message = MutableStateFlow<String?>(null)

    val ui: StateFlow<SettingsUi> = combine(
        history.recent(),
        _busy,
        _message,
        sourceSettings.sourceOrder,
        sourceSettings.enabledSources,
    ) { recent, busy, message, order, enabled ->
        SettingsUi(
            busy = busy,
            message = message,
            historyCount = recent.size,
            sourceOrder = order,
            enabledSources = enabled,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUi())

    fun setPrimary(source: StreamSource) {
        viewModelScope.launch { sourceSettings.setPrimary(source) }
    }

    fun toggleSource(source: StreamSource, enabled: Boolean) {
        viewModelScope.launch { sourceSettings.setEnabled(source, enabled) }
    }

    fun refreshLibrary() {
        runAction("Library refreshed") { feedRepo.refresh() }
    }

    fun clearMediaCache() {
        runAction("Stream cache cleared") {
            resolvedStreamDao.clear()
        }
    }

    fun clearMetadataCache() {
        runAction("Metadata & poster cache cleared") { metadata.clearCache() }
    }

    fun clearHistory() {
        runAction("Watch history & My List cleared") {
            history.clear()
            myList.clear()
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    fun checkForUpdate(auto: Boolean = false) {
        updater.check(auto)
    }

    fun downloadUpdate() {
        updater.download()
    }

    fun cancelUpdateDownload() {
        updater.cancelDownload()
    }

    fun installUpdate() {
        updater.install()
    }

    fun onInstallPermissionResult() {
        updater.onInstallPermissionResult()
    }

    /** Returning from the installer without the install finishing allows a retry. */
    fun onInstallerClosed() {
        updater.onInstallerClosed()
    }

    /** Intent that opens this app's "install unknown apps" settings page. */
    fun permissionIntent(): Intent = updater.permissionIntent()

    private fun runAction(successMessage: String, block: suspend () -> Unit) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            _message.value = null
            try {
                block()
                _busy.value = false
                _message.value = successMessage
            } catch (e: Exception) {
                _busy.value = false
                _message.value = "Failed: ${e.message}"
            }
        }
    }
}
