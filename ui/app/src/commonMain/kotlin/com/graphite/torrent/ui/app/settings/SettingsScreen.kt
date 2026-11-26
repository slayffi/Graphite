package com.graphite.torrent.ui.app.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.graphite.torrent.core.model.PortStatus
import com.graphite.torrent.core.settings.AppSettings
import com.graphite.torrent.core.settings.CompletionSystemAction
import com.graphite.torrent.core.settings.DiskIoMode
import com.graphite.torrent.core.settings.DiskWriteMode
import com.graphite.torrent.core.settings.EncryptionMode
import com.graphite.torrent.core.settings.ProxyType
import com.graphite.torrent.core.settings.SpeedProfile
import com.graphite.torrent.core.settings.WatchFolderAction
import com.graphite.torrent.ui.app.common.adviceRes
import com.graphite.torrent.ui.app.common.titleRes
import com.graphite.torrent.ui.app.details.formatDate
import com.graphite.torrent.ui.app.navigation.Destination
import com.graphite.torrent.ui.app.resources.Res
import com.graphite.torrent.ui.app.resources.*
import com.graphite.torrent.ui.designsystem.components.ButtonKind
import com.graphite.torrent.ui.designsystem.components.GraphiteButton
import com.graphite.torrent.ui.designsystem.components.GraphiteCard
import com.graphite.torrent.ui.designsystem.components.GraphiteMenu
import com.graphite.torrent.ui.designsystem.components.GraphiteTextField
import com.graphite.torrent.ui.designsystem.components.MenuAction
import com.graphite.torrent.ui.designsystem.components.SearchField
import com.graphite.torrent.ui.designsystem.components.SectionHeader
import com.graphite.torrent.ui.designsystem.components.SegmentedControl
import com.graphite.torrent.ui.designsystem.components.SettingRow
import com.graphite.torrent.ui.designsystem.components.SwitchSettingRow
import com.graphite.torrent.ui.designsystem.icons.GraphiteIcons
import com.graphite.torrent.ui.designsystem.platform.PlatformKind
import com.graphite.torrent.ui.designsystem.theme.GraphiteTheme
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** One searchable entry. [content] renders the row inside its group card. */
private class Entry(val group: StringResource, val title: String, val description: String, val content: @Composable () -> Unit)

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onNavigate: (Destination) -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    val interfaces by viewModel.interfaces.collectAsStateWithLifecycle()
    val allFiles by viewModel.allFilesAccess.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val spacing = GraphiteTheme.spacing
    val android = viewModel.platformKind == PlatformKind.ANDROID
    val desktop = viewModel.platformKind == PlatformKind.DESKTOP

    LaunchedEffect(Unit) {
        viewModel.refreshInterfaces()
        viewModel.refreshAllFilesAccess()
        viewModel.messages.collect { m ->
            onShowMessage(
                when (m) {
                    is SettingsMessage.TrackersUpdated -> getString(Res.string.trackers_updated, m.count)
                    is SettingsMessage.Failed -> getString(m.error.kind.titleRes()) + ". " + getString(m.error.kind.adviceRes())
                    is SettingsMessage.Exported -> getString(if (m.ok) Res.string.backup_exported else Res.string.backup_export_failed)
                    is SettingsMessage.Imported -> getString(Res.string.backup_imported, m.count)
                },
            )
        }
    }

    fun upd(t: (AppSettings) -> AppSettings) = viewModel.update(t)
    val entries = ArrayList<Entry>()
    fun add(group: StringResource, title: String, description: String, content: @Composable () -> Unit) {
        entries += Entry(group, title, description, content)
    }

    // ---------------------------------------------------------------- Speed
    val profileTitle = stringResource(Res.string.set_profile)
    val profileDesc = stringResource(
        when (s.profile) {
            SpeedProfile.TURBO -> Res.string.set_profile_turbo_desc
            SpeedProfile.BALANCED -> Res.string.set_profile_balanced_desc
            SpeedProfile.ECONOMY -> Res.string.set_profile_economy_desc
        },
    )
    add(Res.string.group_speed, profileTitle, profileDesc) {
        Column(Modifier.padding(spacing.sm)) {
            Text(profileTitle, style = GraphiteTheme.typography.body)
            Text(profileDesc, style = GraphiteTheme.typography.caption, color = GraphiteTheme.colors.textSecondary)
            Spacer(Modifier.height(spacing.xs))
            SegmentedControl(
                SpeedProfile.entries, s.profile,
                label = { p ->
                    stringResource(
                        when (p) {
                            SpeedProfile.TURBO -> Res.string.profile_turbo
                            SpeedProfile.BALANCED -> Res.string.profile_balanced
                            SpeedProfile.ECONOMY -> Res.string.profile_economy
                        },
                    )
                },
                onSelect = { p -> upd { it.copy(profile = p) } },
                icon = { p ->
                    when (p) {
                        SpeedProfile.TURBO -> GraphiteIcons.Zap
                        SpeedProfile.BALANCED -> GraphiteIcons.Scale
                        SpeedProfile.ECONOMY -> GraphiteIcons.Leaf
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    numberEntry(entries, Res.string.group_speed, stringResource(Res.string.set_download_limit), stringResource(Res.string.set_download_limit_desc), s.downloadLimitKbps, "KB/s") { v -> upd { it.copy(downloadLimitKbps = v) } }
    numberEntry(entries, Res.string.group_speed, stringResource(Res.string.set_active_downloads), stringResource(Res.string.set_auto_hint), s.maxActiveDownloads, "") { v -> upd { it.copy(maxActiveDownloads = v) } }
    numberEntry(entries, Res.string.group_speed, stringResource(Res.string.set_connections), stringResource(Res.string.set_auto_hint), s.connectionsLimit, "") { v -> upd { it.copy(connectionsLimit = v) } }

    // ---------------------------------------------------------------- Upload
    switchEntry(entries, Res.string.group_upload, stringResource(Res.string.set_zero_upload), stringResource(Res.string.set_zero_upload_desc), s.zeroUpload, GraphiteIcons.ShieldCheck) { v -> upd { it.copy(zeroUpload = v) } }
    if (!s.zeroUpload) {
        numberEntry(entries, Res.string.group_upload, stringResource(Res.string.set_upload_limit), stringResource(Res.string.set_download_limit_desc), s.uploadLimitKbps, "KB/s") { v -> upd { it.copy(uploadLimitKbps = v) } }
    }

    // ---------------------------------------------------------------- Downloads
    val folderTitle = stringResource(Res.string.set_default_folder)
    val defaultFolder by viewModel.defaultFolder.collectAsStateWithLifecycle()
    val folderValue = viewModel.displayPath(s.androidTreeUri).ifBlank { defaultFolder }.ifBlank { stringResource(Res.string.set_folder_default_value) }
    add(Res.string.group_downloads, folderTitle, folderValue) {
        SettingRow(folderTitle, folderValue, icon = GraphiteIcons.Folder, onClick = viewModel::pickDefaultFolder) {
            Text(stringResource(Res.string.change), style = GraphiteTheme.typography.label)
        }
    }
    if (android && viewModel.supportsAllFilesAccess) {
        val t = stringResource(Res.string.set_all_files)
        val d = stringResource(if (allFiles) Res.string.set_all_files_granted else Res.string.set_all_files_desc)
        add(Res.string.group_downloads, t, d) {
            SettingRow(t, d, icon = GraphiteIcons.HardDrive, onClick = if (allFiles) null else viewModel::requestAllFilesAccess) {
                if (!allFiles) Text(stringResource(Res.string.allow), style = GraphiteTheme.typography.label)
            }
        }
    }
    val moveTitle = stringResource(Res.string.set_move_completed)
    val moveValue = viewModel.displayPath(s.moveCompletedTo).ifBlank { stringResource(Res.string.set_move_completed_off) }
    add(Res.string.group_downloads, moveTitle, moveValue) {
        SettingRow(moveTitle, moveValue, icon = GraphiteIcons.FolderOutput, onClick = viewModel::pickMoveCompletedFolder) {
            if (s.moveCompletedTo.isNotBlank()) {
                GraphiteButton(stringResource(Res.string.turn_off), { upd { it.copy(moveCompletedTo = "") } }, kind = ButtonKind.TERTIARY)
            }
        }
    }
    switchEntry(entries, Res.string.group_downloads, stringResource(Res.string.set_preallocate), stringResource(Res.string.set_preallocate_desc), s.preallocate, GraphiteIcons.HardDrive) { v -> upd { it.copy(preallocate = v) } }
    switchEntry(entries, Res.string.group_downloads, stringResource(Res.string.sequential_download), stringResource(Res.string.set_sequential_default_desc), s.sequentialByDefault, GraphiteIcons.ListFilter) { v -> upd { it.copy(sequentialByDefault = v) } }
    switchEntry(entries, Res.string.group_downloads, stringResource(Res.string.start_paused), stringResource(Res.string.set_start_paused_desc), s.startPaused, GraphiteIcons.Pause) { v -> upd { it.copy(startPaused = v) } }

    // ---------------------------------------------------------------- Network
    val portTitle = stringResource(Res.string.set_port)
    val portStatus = stringResource(
        when (session.portStatus) {
            PortStatus.CHECKING -> Res.string.port_checking
            PortStatus.OPEN -> Res.string.port_open
            PortStatus.MAPPED -> Res.string.port_mapped
            PortStatus.FIREWALLED -> Res.string.port_firewalled
            PortStatus.FAILED -> Res.string.port_failed
        },
    )
    numberEntry(entries, Res.string.group_network, portTitle, portStatus, s.listenPort, "") { v -> if (v in 1024..65535) upd { it.copy(listenPort = v) } }
    switchEntry(entries, Res.string.group_network, stringResource(Res.string.set_auto_port), stringResource(Res.string.set_auto_port_desc), s.autoPort, GraphiteIcons.Router) { v -> upd { it.copy(autoPort = v) } }
    switchEntry(entries, Res.string.group_network, "DHT", stringResource(Res.string.set_dht_desc), s.enableDht, GraphiteIcons.Network) { v -> upd { it.copy(enableDht = v) } }
    switchEntry(entries, Res.string.group_network, "PEX", stringResource(Res.string.set_pex_desc), s.enablePex, GraphiteIcons.Users) { v -> upd { it.copy(enablePex = v) } }
    switchEntry(entries, Res.string.group_network, "LSD", stringResource(Res.string.set_lsd_desc), s.enableLsd, GraphiteIcons.Router) { v -> upd { it.copy(enableLsd = v) } }
    switchEntry(entries, Res.string.group_network, "UPnP", stringResource(Res.string.set_upnp_desc), s.enableUpnp, GraphiteIcons.Router) { v -> upd { it.copy(enableUpnp = v) } }
    switchEntry(entries, Res.string.group_network, "NAT-PMP", stringResource(Res.string.set_upnp_desc), s.enableNatPmp, GraphiteIcons.Router) { v -> upd { it.copy(enableNatPmp = v) } }
    switchEntry(entries, Res.string.group_network, "µTP", stringResource(Res.string.set_utp_desc), s.enableUtp, GraphiteIcons.Activity) { v -> upd { it.copy(enableUtp = v) } }
    switchEntry(entries, Res.string.group_network, "TCP", stringResource(Res.string.set_tcp_desc), s.enableTcp, GraphiteIcons.Activity) { v -> upd { it.copy(enableTcp = v) } }
    switchEntry(entries, Res.string.group_network, "IPv6", stringResource(Res.string.set_ipv6_desc), s.enableIpv6, GraphiteIcons.Globe) { v -> upd { it.copy(enableIpv6 = v) } }

    // ---------------------------------------------------------------- Trackers
    switchEntry(entries, Res.string.group_trackers, stringResource(Res.string.set_add_trackers), stringResource(Res.string.set_add_trackers_desc), s.addPublicTrackers, GraphiteIcons.RadioTower) { v -> upd { it.copy(addPublicTrackers = v) } }
    switchEntry(entries, Res.string.group_trackers, stringResource(Res.string.set_trackers_auto), stringResource(Res.string.set_trackers_auto_desc), s.trackersAutoUpdate, GraphiteIcons.RefreshCw) { v -> upd { it.copy(trackersAutoUpdate = v) } }
    val updateTitle = stringResource(Res.string.set_trackers_update_now)
    val updatedDesc = pluralStringResource(Res.plurals.trackers_in_list, s.trackerList.size, s.trackerList.size) +
        if (s.trackersUpdatedAt > 0) " · " + formatDate(s.trackersUpdatedAt) else ""
    add(Res.string.group_trackers, updateTitle, updatedDesc) {
        SettingRow(updateTitle, updatedDesc, icon = GraphiteIcons.Download, onClick = if (busy) null else viewModel::updateTrackersNow)
    }
    val sourcesTitle = stringResource(Res.string.set_tracker_sources)
    add(Res.string.group_trackers, sourcesTitle, stringResource(Res.string.set_tracker_sources_desc)) {
        CommitTextArea(sourcesTitle, s.trackerSources.joinToString("\n"), viewModel::setTrackerSourcesText)
    }
    val listTitle = stringResource(Res.string.set_tracker_list)
    add(Res.string.group_trackers, listTitle, stringResource(Res.string.set_tracker_list_desc)) {
        CommitTextArea(listTitle, s.trackerList.joinToString("\n"), viewModel::setTrackerListText, minLines = 6)
    }

    // ---------------------------------------------------------------- Privacy
    val encTitle = stringResource(Res.string.set_encryption)
    add(Res.string.group_privacy, encTitle, stringResource(Res.string.set_encryption_desc)) {
        Column(Modifier.padding(spacing.sm)) {
            Text(encTitle, style = GraphiteTheme.typography.body)
            Text(stringResource(Res.string.set_encryption_desc), style = GraphiteTheme.typography.caption, color = GraphiteTheme.colors.textSecondary)
            Spacer(Modifier.height(spacing.xs))
            SegmentedControl(EncryptionMode.entries, s.encryption, label = { m ->
                stringResource(
                    when (m) {
                        EncryptionMode.PREFER -> Res.string.enc_prefer
                        EncryptionMode.REQUIRE -> Res.string.enc_require
                        EncryptionMode.DISABLE -> Res.string.enc_off
                    },
                )
            }, onSelect = { m -> upd { it.copy(encryption = m) } }, modifier = Modifier.fillMaxWidth())
        }
    }
    val proxyTitle = stringResource(Res.string.set_proxy)
    add(Res.string.group_privacy, proxyTitle, stringResource(Res.string.set_proxy_desc)) { ProxyEditor(s, viewModel) }
    val bindTitle = stringResource(Res.string.set_bind_interface)
    val bindValue = s.bindInterface.ifBlank { stringResource(Res.string.set_bind_any) }
    add(Res.string.group_privacy, bindTitle, stringResource(Res.string.set_bind_interface_desc)) {
        var menu by remember { mutableStateOf(false) }
        Column {
            SettingRow(bindTitle, bindValue + " — " + stringResource(Res.string.set_bind_interface_desc), icon = GraphiteIcons.Shield, onClick = {
                viewModel.refreshInterfaces()
                menu = true
            })
            GraphiteMenu(menu, { menu = false }, listOf(MenuAction(stringResource(Res.string.set_bind_any)) { upd { it.copy(bindInterface = "") } }) +
                interfaces.map { ni ->
                    MenuAction(
                        "${ni.displayName} (${ni.name}) ${ni.addresses.firstOrNull().orEmpty()}",
                        if (ni.isLikelyVpn) GraphiteIcons.Lock else GraphiteIcons.Network,
                    ) { upd { it.copy(bindInterface = ni.name) } }
                })
        }
    }
    switchEntry(entries, Res.string.group_privacy, stringResource(Res.string.set_kill_switch), stringResource(Res.string.set_kill_switch_desc), s.killSwitch, GraphiteIcons.Power, enabled = s.bindInterface.isNotBlank()) { v -> upd { it.copy(killSwitch = v) } }
    val ipTitle = stringResource(Res.string.set_ip_filter)
    val ipDesc = if (s.ipFilterPath.isBlank()) stringResource(Res.string.set_ip_filter_desc) else viewModel.displayPath(s.ipFilterPath)
    add(Res.string.group_privacy, ipTitle, ipDesc) {
        Column {
            SwitchSettingRow(ipTitle, ipDesc, s.ipFilterEnabled && s.ipFilterPath.isNotBlank(), { v ->
                if (v && s.ipFilterPath.isBlank()) viewModel.pickIpFilter() else upd { it.copy(ipFilterEnabled = v) }
            }, icon = GraphiteIcons.Funnel)
            SettingRow(stringResource(Res.string.set_ip_filter_choose), null, onClick = viewModel::pickIpFilter)
        }
    }
    switchEntry(entries, Res.string.group_privacy, stringResource(Res.string.set_anonymous), stringResource(Res.string.set_anonymous_desc), s.anonymousMode, GraphiteIcons.EyeOff) { v -> upd { it.copy(anonymousMode = v) } }

    // ---------------------------------------------------------------- Disk
    numberEntry(entries, Res.string.group_disk, stringResource(Res.string.set_disk_queue), stringResource(Res.string.set_disk_queue_desc), s.diskQueueMb, "MB") { v -> upd { it.copy(diskQueueMb = v) } }
    numberEntry(entries, Res.string.group_disk, stringResource(Res.string.set_aio_threads), stringResource(Res.string.set_aio_threads_desc), s.aioThreads, "") { v -> upd { it.copy(aioThreads = v) } }
    val ioTitle = stringResource(Res.string.set_disk_io)
    add(Res.string.group_disk, ioTitle, stringResource(Res.string.set_disk_io_desc)) {
        Column(Modifier.padding(spacing.sm)) {
            Text(ioTitle, style = GraphiteTheme.typography.body)
            Text(stringResource(Res.string.set_disk_io_desc), style = GraphiteTheme.typography.caption, color = GraphiteTheme.colors.textSecondary)
            Spacer(Modifier.height(spacing.xs))
            SegmentedControl(DiskIoMode.entries, s.diskIoMode, label = { if (it == DiskIoMode.MMAP) "mmap" else "POSIX" }, onSelect = { m -> upd { it.copy(diskIoMode = m) } }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(spacing.xs))
            SegmentedControl(DiskWriteMode.entries, s.diskWriteMode, label = { m ->
                when (m) {
                    DiskWriteMode.AUTO -> stringResource(Res.string.write_auto)
                    DiskWriteMode.MMAP -> "mmap"
                    DiskWriteMode.PWRITE -> "pwrite"
                }
            }, onSelect = { m -> upd { it.copy(diskWriteMode = m) } }, modifier = Modifier.fillMaxWidth())
        }
    }

    // ---------------------------------------------------------------- Completion
    switchEntry(entries, Res.string.group_completion, stringResource(Res.string.set_notify), stringResource(Res.string.set_notify_desc), s.notifyOnComplete, GraphiteIcons.Bell) { v ->
        if (v) viewModel.requestNotifications()
        upd { it.copy(notifyOnComplete = v) }
    }
    switchEntry(entries, Res.string.group_completion, stringResource(Res.string.set_sound), stringResource(Res.string.set_sound_desc), s.soundOnComplete, GraphiteIcons.Volume2) { v -> upd { it.copy(soundOnComplete = v) } }
    if (android) {
        switchEntry(entries, Res.string.group_completion, stringResource(Res.string.set_vibrate), stringResource(Res.string.set_vibrate_desc), s.vibrateOnComplete, GraphiteIcons.Smartphone) { v -> upd { it.copy(vibrateOnComplete = v) } }
    }
    if (desktop) {
        val actionTitle = stringResource(Res.string.set_completion_action)
        add(Res.string.group_completion, actionTitle, stringResource(Res.string.set_completion_action_desc)) {
            Column(Modifier.padding(spacing.sm)) {
                Text(actionTitle, style = GraphiteTheme.typography.body)
                Text(stringResource(Res.string.set_completion_action_desc), style = GraphiteTheme.typography.caption, color = GraphiteTheme.colors.textSecondary)
                Spacer(Modifier.height(spacing.xs))
                SegmentedControl(CompletionSystemAction.entries, s.completionAction, label = { a ->
                    stringResource(
                        when (a) {
                            CompletionSystemAction.NONE -> Res.string.action_none
                            CompletionSystemAction.SLEEP -> Res.string.action_sleep
                            CompletionSystemAction.SHUTDOWN -> Res.string.action_shutdown
                            CompletionSystemAction.QUIT_APP -> Res.string.action_quit
                        },
                    )
                }, onSelect = { a -> upd { it.copy(completionAction = a) } }, modifier = Modifier.fillMaxWidth())
            }
        }
    }

    // ---------------------------------------------------------------- Android power rules
    if (android) {
        switchEntry(entries, Res.string.group_power, stringResource(Res.string.set_wifi_only), stringResource(Res.string.set_wifi_only_desc), s.wifiOnly, GraphiteIcons.Wifi) { v -> upd { it.copy(wifiOnly = v) } }
        switchEntry(entries, Res.string.group_power, stringResource(Res.string.set_charging_only), stringResource(Res.string.set_charging_only_desc), s.chargingOnly, GraphiteIcons.Plug) { v -> upd { it.copy(chargingOnly = v) } }
        switchEntry(entries, Res.string.group_power, stringResource(Res.string.set_low_battery), stringResource(Res.string.set_low_battery_desc, s.lowBatteryThreshold), s.pauseOnLowBattery, GraphiteIcons.BatteryLow) { v -> upd { it.copy(pauseOnLowBattery = v) } }
        numberEntry(entries, Res.string.group_power, stringResource(Res.string.set_low_battery_threshold), "", s.lowBatteryThreshold, "%") { v -> upd { it.copy(lowBatteryThreshold = v.coerceIn(5, 50)) } }
        val batteryTitle = stringResource(Res.string.set_battery_optimization)
        add(Res.string.group_power, batteryTitle, stringResource(Res.string.set_battery_optimization_desc)) {
            SettingRow(batteryTitle, stringResource(Res.string.set_battery_optimization_desc), icon = GraphiteIcons.Battery, onClick = viewModel::openAppSettings)
        }
    }

    // ---------------------------------------------------------------- Desktop
    if (desktop) {
        switchEntry(entries, Res.string.group_windows, stringResource(Res.string.set_tray), stringResource(Res.string.set_tray_desc), s.minimizeToTray, GraphiteIcons.Minimize) { v -> upd { it.copy(minimizeToTray = v) } }
        switchEntry(entries, Res.string.group_windows, stringResource(Res.string.set_autostart), stringResource(Res.string.set_autostart_desc), s.autostart, GraphiteIcons.Power) { v -> upd { it.copy(autostart = v) } }
        switchEntry(entries, Res.string.group_windows, stringResource(Res.string.set_start_minimized), stringResource(Res.string.set_start_minimized_desc), s.startMinimized, GraphiteIcons.Minimize) { v -> upd { it.copy(startMinimized = v) } }
        switchEntry(entries, Res.string.group_windows, stringResource(Res.string.set_hotkeys), stringResource(Res.string.set_hotkeys_desc), s.globalHotkeys, GraphiteIcons.Keyboard) { v -> upd { it.copy(globalHotkeys = v) } }
        switchEntry(entries, Res.string.group_windows, stringResource(Res.string.set_assoc_torrent), stringResource(Res.string.set_assoc_torrent_desc), s.associateTorrentFiles, GraphiteIcons.File) { v -> upd { it.copy(associateTorrentFiles = v) } }
        switchEntry(entries, Res.string.group_windows, stringResource(Res.string.set_assoc_magnet), stringResource(Res.string.set_assoc_magnet_desc), s.associateMagnetLinks, GraphiteIcons.Magnet) { v -> upd { it.copy(associateMagnetLinks = v) } }
        if (viewModel.features.desktop != null) {
            val miniTitle = stringResource(Res.string.set_mini_window)
            add(Res.string.group_windows, miniTitle, stringResource(Res.string.set_mini_window_desc)) {
                SettingRow(miniTitle, stringResource(Res.string.set_mini_window_desc), icon = GraphiteIcons.PictureInPicture2, onClick = viewModel::openMiniWindow)
            }
        }
    }

    // ---------------------------------------------------------------- Automation
    switchEntry(entries, Res.string.group_automation, stringResource(Res.string.set_watch_folder), viewModel.displayPath(s.watchFolderPath).ifBlank { stringResource(Res.string.set_watch_folder_desc) }, s.watchFolderEnabled && s.watchFolderPath.isNotBlank(), GraphiteIcons.FolderInput) { v ->
        if (v && s.watchFolderPath.isBlank()) viewModel.pickWatchFolder() else upd { it.copy(watchFolderEnabled = v) }
    }
    val watchChoose = stringResource(Res.string.set_watch_folder_choose)
    add(Res.string.group_automation, watchChoose, "") {
        SettingRow(watchChoose, stringResource(if (s.watchFolderAction == WatchFolderAction.RENAME) Res.string.watch_rename else Res.string.watch_delete), onClick = viewModel::pickWatchFolder) {
            GraphiteButton(stringResource(Res.string.watch_toggle_action), {
                upd { it.copy(watchFolderAction = if (it.watchFolderAction == WatchFolderAction.RENAME) WatchFolderAction.DELETE else WatchFolderAction.RENAME) }
            }, kind = ButtonKind.TERTIARY)
        }
    }
    if (viewModel.features.scheduler != null) {
        val t = stringResource(Res.string.scheduler_title)
        add(Res.string.group_automation, t, stringResource(Res.string.scheduler_desc)) {
            SettingRow(t, stringResource(Res.string.scheduler_desc), icon = GraphiteIcons.CalendarClock, onClick = { onNavigate(Destination.Scheduler) })
        }
    }
    if (viewModel.features.rss != null) {
        val t = stringResource(Res.string.rss_title)
        add(Res.string.group_automation, t, stringResource(Res.string.rss_desc)) {
            SettingRow(t, stringResource(Res.string.rss_desc), icon = GraphiteIcons.Rss, onClick = { onNavigate(Destination.Rss) })
        }
    }
    if (viewModel.features.remote != null) {
        val t = stringResource(Res.string.remote_title)
        add(Res.string.group_automation, t, stringResource(if (desktop) Res.string.remote_desc_desktop else Res.string.remote_desc_android)) {
            SettingRow(t, stringResource(if (desktop) Res.string.remote_desc_desktop else Res.string.remote_desc_android), icon = GraphiteIcons.Smartphone, onClick = { onNavigate(Destination.Remote) })
        }
    }

    // ---------------------------------------------------------------- Appearance
    switchEntry(entries, Res.string.group_appearance, stringResource(Res.string.set_amoled), stringResource(Res.string.set_amoled_desc), s.amoledBlack, GraphiteIcons.Moon) { v -> upd { it.copy(amoledBlack = v) } }
    switchEntry(entries, Res.string.group_appearance, stringResource(Res.string.set_reduce_motion), stringResource(Res.string.set_reduce_motion_desc), s.reduceMotion, GraphiteIcons.Activity) { v -> upd { it.copy(reduceMotion = v) } }

    // ---------------------------------------------------------------- Data
    if (viewModel.features.backup != null) {
        val exportTitle = stringResource(Res.string.backup_export)
        add(Res.string.group_data, exportTitle, stringResource(Res.string.backup_export_desc)) {
            SettingRow(exportTitle, stringResource(Res.string.backup_export_desc), icon = GraphiteIcons.Upload, onClick = viewModel::exportBackup)
        }
        val importTitle = stringResource(Res.string.backup_import)
        add(Res.string.group_data, importTitle, stringResource(Res.string.backup_import_desc)) {
            SettingRow(importTitle, stringResource(Res.string.backup_import_desc), icon = GraphiteIcons.Download, onClick = viewModel::importBackup)
        }
    }
    val logTitle = stringResource(Res.string.event_log)
    add(Res.string.group_data, logTitle, stringResource(Res.string.event_log_desc)) {
        SettingRow(logTitle, stringResource(Res.string.event_log_desc), icon = GraphiteIcons.History, onClick = { onNavigate(Destination.EventLog) })
    }
    val aboutTitle = stringResource(Res.string.about)
    val aboutDesc = "Graphite ${viewModel.appVersion} · libtorrent ${session.libtorrentVersion}"
    add(Res.string.group_data, aboutTitle, aboutDesc) {
        SettingRow(aboutTitle, aboutDesc + "\n" + stringResource(Res.string.about_privacy), icon = GraphiteIcons.Info)
    }

    // ---------------------------------------------------------------- Render
    val q = query.trim().lowercase()
    val visible = if (q.isEmpty()) entries else entries.filter { it.title.lowercase().contains(q) || it.description.lowercase().contains(q) }
    val grouped = visible.groupBy { it.group }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(spacing.md), verticalArrangement = Arrangement.spacedBy(spacing.md)) {
        item {
            Text(stringResource(Res.string.tab_settings), style = GraphiteTheme.typography.screenTitle, modifier = Modifier.semantics { heading() })
        }
        item {
            SearchField(query, viewModel::setQuery, stringResource(Res.string.search_settings), stringResource(Res.string.clear), Modifier.widthIn(max = 640.dp))
        }
        if (visible.isEmpty()) {
            item { Text(stringResource(Res.string.nothing_found), style = GraphiteTheme.typography.body, color = GraphiteTheme.colors.textSecondary) }
        }
        grouped.forEach { (group, groupEntries) ->
            item(key = group.key) {
                Column(Modifier.widthIn(max = 840.dp)) {
                    SectionHeader(stringResource(group), Modifier.padding(horizontal = spacing.xs))
                    GraphiteCard(contentPadding = spacing.xxs) {
                        groupEntries.forEach { it.content() }
                    }
                }
            }
        }
    }
}

private fun switchEntry(
    entries: MutableList<Entry>,
    group: StringResource,
    title: String,
    description: String,
    checked: Boolean,
    icon: ImageVector,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    entries += Entry(group, title, description) {
        SwitchSettingRow(title, description, checked, onChange, icon = icon, enabled = enabled)
    }
}

private fun numberEntry(
    entries: MutableList<Entry>,
    group: StringResource,
    title: String,
    description: String,
    value: Int,
    suffix: String,
    onCommit: (Int) -> Unit,
) {
    entries += Entry(group, title, description) {
        SettingRow(title, description.ifBlank { null }) {
            NumberField(value, suffix, onCommit)
        }
    }
}

@Composable
private fun NumberField(value: Int, suffix: String, onCommit: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        GraphiteTextField(
            text,
            { t -> text = t.filter { it.isDigit() }.take(7) },
            modifier = Modifier.width(112.dp).onFocusChanged { if (!it.isFocused) text.toIntOrNull()?.let { v -> if (v != value) onCommit(v) } },
            keyboardType = KeyboardType.Number,
            onImeAction = { text.toIntOrNull()?.let(onCommit) },
        )
        if (suffix.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(suffix, style = GraphiteTheme.typography.caption, color = GraphiteTheme.colors.textSecondary)
        }
    }
}

@Composable
private fun CommitTextArea(title: String, value: String, onCommit: (String) -> Unit, minLines: Int = 3) {
    var text by remember(value) { mutableStateOf(value) }
    Column(Modifier.padding(GraphiteTheme.spacing.sm)) {
        GraphiteTextField(
            text,
            { text = it },
            label = title,
            singleLine = false,
            minLines = minLines,
            modifier = Modifier.onFocusChanged { if (!it.isFocused && text != value) onCommit(text) },
        )
        if (text != value) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                GraphiteButton(stringResource(Res.string.save), { onCommit(text) }, kind = ButtonKind.TERTIARY)
            }
        }
    }
}

@Composable
private fun ProxyEditor(s: AppSettings, viewModel: SettingsViewModel) {
    val spacing = GraphiteTheme.spacing
    val p = s.proxy
    var host by remember(p.host) { mutableStateOf(p.host) }
    var port by remember(p.port) { mutableStateOf(p.port.toString()) }
    var user by remember(p.username) { mutableStateOf(p.username) }
    var pass by remember(p.password) { mutableStateOf(p.password) }
    Column(Modifier.padding(spacing.sm), verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Text(stringResource(Res.string.set_proxy), style = GraphiteTheme.typography.body)
        Text(stringResource(Res.string.set_proxy_desc), style = GraphiteTheme.typography.caption, color = GraphiteTheme.colors.textSecondary)
        SegmentedControl(ProxyType.entries, p.type, label = { t ->
            when (t) {
                ProxyType.NONE -> stringResource(Res.string.proxy_none)
                ProxyType.SOCKS5 -> "SOCKS5"
                ProxyType.HTTP -> "HTTP"
            }
        }, onSelect = { t -> viewModel.update { it.copy(proxy = it.proxy.copy(type = t)) } }, modifier = Modifier.fillMaxWidth())
        if (p.type != ProxyType.NONE) {
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                GraphiteTextField(host, { host = it }, Modifier.weight(2f), label = stringResource(Res.string.proxy_host))
                GraphiteTextField(port, { port = it.filter(Char::isDigit).take(5) }, Modifier.weight(1f), label = stringResource(Res.string.proxy_port), keyboardType = KeyboardType.Number)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                GraphiteTextField(user, { user = it }, Modifier.weight(1f), label = stringResource(Res.string.proxy_user))
                GraphiteTextField(pass, { pass = it }, Modifier.weight(1f), label = stringResource(Res.string.proxy_password), password = true)
            }
            SwitchSettingRow(stringResource(Res.string.proxy_peers), null, p.proxyPeerConnections, { v -> viewModel.update { it.copy(proxy = it.proxy.copy(proxyPeerConnections = v)) } })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                GraphiteButton(stringResource(Res.string.save), {
                    viewModel.update {
                        it.copy(proxy = it.proxy.copy(host = host.trim(), port = port.toIntOrNull() ?: it.proxy.port, username = user, password = pass))
                    }
                })
            }
        }
    }
}
