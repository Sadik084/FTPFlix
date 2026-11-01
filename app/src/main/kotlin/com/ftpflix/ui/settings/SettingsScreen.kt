package com.ftpflix.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.ftpflix.core.util.formatBytes
import com.ftpflix.sources.resolve.StreamSource
import com.ftpflix.ui.theme.Spacing
import com.ftpflix.update.AppUpdateStatus

// Material 3 settings: grouped cards with icon tiles, badges, destructive row, dialogs.
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun SettingsScreen(
    onOpenHistory: () -> Unit = {},
    vm: SettingsViewModel = hiltViewModel(),
) {
    val (busy, message, historyCount, sourceOrder, enabledSources) =
        vm.ui.collectAsStateWithLifecycle().value
    val update by vm.update.collectAsStateWithLifecycle()

    val installPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { vm.onInstallPermissionResult() }

    // One quiet update check per session when Settings opens.
    LaunchedEffect(Unit) { vm.checkForUpdate(auto = true) }

    // Returning from the installer without completing the install allows a retry.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.onInstallerClosed() }

    var confirmClearStreams by remember { mutableStateOf(value = false) }
    var confirmClearMeta by remember { mutableStateOf(value = false) }
    var confirmResetAll by remember { mutableStateOf(value = false) }
    var showDeviceCheck by remember { mutableStateOf(value = false) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding(),
        ) {
            Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
                Text(
                    "Settings",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Preferences & Cache Management",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionLabel("Watch History & Activity")
            GroupCard {
                SettingsRow(
                    icon = Icons.Rounded.History,
                    title = "View Watch History",
                    subtitle = "See recently watched movies & TV shows",
                    badge = if (historyCount > 0) "$historyCount Items" else null,
                    onClick = onOpenHistory,
                )
            }

            Spacer(Modifier.height(Spacing.xl))

            SectionLabel("Library Management")
            GroupCard {
                SettingsRow(
                    icon = Icons.Rounded.Refresh,
                    title = "Refresh Library",
                    subtitle = "Sync watchlists & metadata",
                    trailing = {
                        if (busy) {
                            CircularProgressIndicator(
                                Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            TrailingBadge("Sync")
                        }
                    },
                    onClick = vm::refreshLibrary,
                )
            }

            Spacer(Modifier.height(Spacing.xl))

            SectionLabel("Storage & Cache")
            GroupCard {
                SettingsRow(
                    icon = Icons.Rounded.DeleteOutline,
                    title = "Clear Stream Cache",
                    subtitle = "Removes saved stream links",
                ) { confirmClearStreams = true }
                GroupDivider()
                SettingsRow(
                    icon = Icons.Rounded.Image,
                    title = "Clear Posters & Metadata Cache",
                    subtitle = "Thumbnails re-download when needed",
                ) { confirmClearMeta = true }
                GroupDivider()
                // Destructive row, colored red.
                SettingsRow(
                    icon = Icons.Rounded.WarningAmber,
                    iconTint = MaterialTheme.colorScheme.error,
                    title = "Clear Watch History & My List",
                    subtitle = "Reset personal data",
                    titleColor = MaterialTheme.colorScheme.error,
                    subtitleColor = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                    iconContainerTint = MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
                ) { confirmResetAll = true }
            }

            Spacer(Modifier.height(Spacing.xl))

            SectionLabel("Stream Sources")
            GroupCard {
                val sources = listOf(
                    StreamSource.CIRCLE_FTP,
                    StreamSource.ROAR_ZONE,
                    StreamSource.HD_HUB,
                    StreamSource.HYPER,
                )
                sources.forEachIndexed { index, source ->
                    val enabled = source in enabledSources
                    val isPrimary = sourceOrder.firstOrNull() == source
                    // Cascade position among enabled sources; primary is 1st.
                    val fallbackRank = sourceOrder.filter { it in enabledSources }.indexOf(source) + 1
                    SettingsRow(
                        icon = when (source) {
                            StreamSource.CIRCLE_FTP -> Icons.Rounded.Language
                            StreamSource.ROAR_ZONE -> Icons.Rounded.Movie
                            StreamSource.HD_HUB -> Icons.Rounded.Link
                            StreamSource.HYPER -> Icons.Rounded.Bolt
                            StreamSource.AUTO -> Icons.Rounded.PlayArrow
                        },
                        title = source.label,
                        subtitle = when {
                            !enabled -> "Disabled"
                            isPrimary -> "Primary source"
                            else -> "Fallback $fallbackRank"
                        },
                        badge = if (isPrimary && enabled) "Primary" else null,
                        trailing = {
                            Switch(
                                checked = enabled,
                                onCheckedChange = { vm.toggleSource(source, it) }
                            )
                        }
                    )
                    if (index != sources.lastIndex) GroupDivider()
                }
                GroupDivider()
                Column(
                    Modifier.padding(horizontal = Spacing.md, vertical = 10.dp)
                ) {
                    Text(
                        "Primary Source",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        sourceOrder.filter { it in enabledSources }.forEach { s ->
                            PrimarySourceChip(
                                label = s.label,
                                selected = sourceOrder.firstOrNull() == s,
                            ) { vm.setPrimary(s) }
                        }
                    }
                }
            }

            Spacer(Modifier.height(Spacing.xl))

            SectionLabel("Device")
            GroupCard {
                SettingsRow(
                    icon = Icons.Rounded.Smartphone,
                    title = "Device check",
                    subtitle = "See which codecs this phone supports",
                    onClick = { showDeviceCheck = true },
                )
            }

            Spacer(Modifier.height(Spacing.xl))

            SectionLabel("App Update")
            GroupCard {
                SettingsRow(
                    icon = Icons.Rounded.SystemUpdateAlt,
                    title = "FTPFlix v${update.currentVersion}",
                    subtitle = updateStatusLine(update),
                    trailing = {
                        if (update.status == AppUpdateStatus.CHECKING) {
                            CircularProgressIndicator(
                                Modifier.size(18.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            TrailingBadge("Check")
                        }
                    },
                ) { vm.checkForUpdate() }

                when (update.status) {
                    AppUpdateStatus.AVAILABLE -> {
                        GroupDivider()
                        UpdateDetails(update)
                        GroupDivider()
                        UpdateActionRow {
                            FilledTonalButton(onClick = vm::downloadUpdate) {
                                Text("Download update")
                            }
                        }
                    }

                    AppUpdateStatus.DOWNLOADING -> {
                        GroupDivider()
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.md, vertical = 10.dp)
                        ) {
                            LinearProgressIndicator(
                                progress = { update.downloadProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(50))
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "${formatBytes(update.downloadedBytes)} of " +
                                    formatBytes(update.downloadSizeBytes),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(onClick = vm::cancelUpdateDownload) { Text("Cancel") }
                        }
                    }

                    AppUpdateStatus.READY_TO_INSTALL,
                    AppUpdateStatus.NEEDS_INSTALL_PERMISSION,
                    AppUpdateStatus.INSTALLING,
                -> {
                        GroupDivider()
                        UpdateDetails(update)
                        GroupDivider()
                        UpdateActionRow {
                            when (update.status) {
                                AppUpdateStatus.NEEDS_INSTALL_PERMISSION -> {
                                    Text(
                                        "Allow installing unknown apps to continue.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    TextButton(
                                        onClick = {
                                            runCatching {
                                                installPermissionLauncher.launch(vm.permissionIntent())
                                            }.onFailure { vm.onInstallPermissionResult() }
                                        },
                                    ) { Text("Open permission settings") }
                                }

                                AppUpdateStatus.INSTALLING -> Text(
                                    "Opening the installer…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                else -> {
                                    FilledTonalButton(onClick = vm::installUpdate) {
                                        Text("Install update")
                                    }
                                    TextButton(onClick = vm::cancelUpdateDownload) {
                                        Text("Discard")
                                    }
                                }
                            }
                        }
                    }

                    AppUpdateStatus.ERROR -> {
                        GroupDivider()
                        Column(Modifier.padding(horizontal = Spacing.md, vertical = 10.dp)) {
                            Text(
                                update.message ?: "Update check failed.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            TextButton(onClick = { vm.checkForUpdate() }) { Text("Retry") }
                        }
                    }

                    else -> {}
                }
            }

            Spacer(Modifier.height(Spacing.huge))
        }

        message?.let { msg ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(Spacing.lg),
                action = { TextButton(onClick = vm::consumeMessage) { Text("OK") } }
            ) { Text(msg) }
        }
    }

    ConfirmDialog(
        title = "Clear Stream Cache?",
        body = "Saved stream links will be removed and re-resolved next time you play.",
        confirmLabel = "Clear Cache",
        visible = confirmClearStreams,
        onConfirm = { vm.clearMediaCache(); confirmClearStreams = false },
        onDismiss = { confirmClearStreams = false }
    )
    ConfirmDialog(
        title = "Clear Posters & Metadata Cache?",
        body = "Thumbnails and details will be re-downloaded when needed.",
        confirmLabel = "Clear",
        visible = confirmClearMeta,
        onConfirm = { vm.clearMetadataCache(); confirmClearMeta = false },
        onDismiss = { confirmClearMeta = false }
    )
    ConfirmDialog(
        title = "Reset Watch History & My List?",
        body = "This action cannot be undone. All saved items and progress will be deleted.",
        confirmLabel = "Reset All",
        destructive = true,
        visible = confirmResetAll,
        onConfirm = { vm.clearHistory(); confirmResetAll = false },
        onDismiss = { confirmResetAll = false }
    )

    if (showDeviceCheck) {
        DeviceCheckDialog(onDismiss = { showDeviceCheck = false })
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xs)
    )
}

@Composable
private fun GroupCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        content()
    }
}

@Composable
private fun GroupDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 64.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    )
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    iconTint: Color = MaterialTheme.colorScheme.primary,
    iconContainerTint: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    badge: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
            )
            .padding(horizontal = Spacing.md, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(iconContainerTint),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = iconTint, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                color = titleColor
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = subtitleColor
            )
        }
        badge?.let { TrailingBadge(it) }
        trailing?.invoke()
    }
}

@Composable
private fun PrimarySourceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceContainerHighest
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

@Composable
private fun TrailingBadge(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 10.dp, vertical = 4.dp)
    )
}

@Composable
private fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    visible: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    destructive: Boolean = false
) {
    if (!visible) return
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                if (destructive) Icons.Rounded.WarningAmber else Icons.Rounded.Favorite,
                null,
                tint = if (destructive) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary
            )
        },
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    confirmLabel,
                    color = if (destructive) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

private fun updateStatusLine(update: com.ftpflix.update.AppUpdateState): String =
    when (update.status) {
        AppUpdateStatus.IDLE -> "Check GitHub Releases for a newer version"
        AppUpdateStatus.CHECKING -> "Checking for updates…"
        AppUpdateStatus.UP_TO_DATE -> "You're on the latest version"
        AppUpdateStatus.AVAILABLE -> "v${update.latestVersion} is available to install"
        AppUpdateStatus.DOWNLOADING -> "Downloading v${update.latestVersion}…"
        AppUpdateStatus.READY_TO_INSTALL -> "v${update.latestVersion} is ready to install"
        AppUpdateStatus.NEEDS_INSTALL_PERMISSION -> "Permission needed to install v${update.latestVersion}"
        AppUpdateStatus.INSTALLING -> "Opening the installer…"
        AppUpdateStatus.ERROR -> update.message ?: "Update check failed"
    }

/** Available version, size and release notes for a pending update. */
@Composable
private fun UpdateDetails(update: com.ftpflix.update.AppUpdateState) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = 10.dp)
    ) {
        Text(
            "v${update.latestVersion.orEmpty()} • " +
                (if (update.downloadSizeBytes > 0) formatBytes(update.downloadSizeBytes) else "size unknown"),
            style = MaterialTheme.typography.bodyMedium
        )
        update.releaseNotes?.let { notes ->
            Spacer(Modifier.height(6.dp))
            var expanded by remember { mutableStateOf(value = false) }
            Text(
                notes,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.clickable { expanded = !expanded }
            )
        }
    }
}

@Composable
private fun UpdateActionRow(content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.md, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        content()
    }
}
