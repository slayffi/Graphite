package com.graphite.torrent.ui.app.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.intl.Locale
import com.graphite.torrent.core.model.ErrorKind
import com.graphite.torrent.core.model.FilePriority
import com.graphite.torrent.core.model.TorrentError
import com.graphite.torrent.core.model.TorrentItem
import com.graphite.torrent.core.model.TorrentState
import com.graphite.torrent.ui.app.resources.Res
import com.graphite.torrent.ui.app.resources.*
import com.graphite.torrent.ui.designsystem.components.TorrentCardState
import com.graphite.torrent.ui.designsystem.components.VisualStatus
import com.graphite.torrent.ui.designsystem.format.Formatters
import com.graphite.torrent.ui.designsystem.theme.GraphiteTheme
import com.graphite.torrent.ui.designsystem.theme.UnitLabels
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

fun TorrentState.toVisual(): VisualStatus = when (this) {
    TorrentState.CHECKING -> VisualStatus.CHECKING
    TorrentState.METADATA -> VisualStatus.METADATA
    TorrentState.DOWNLOADING -> VisualStatus.DOWNLOADING
    TorrentState.QUEUED -> VisualStatus.QUEUED
    TorrentState.PAUSED -> VisualStatus.PAUSED
    TorrentState.COMPLETED -> VisualStatus.COMPLETED
    TorrentState.MOVING -> VisualStatus.MOVING
    TorrentState.ERROR -> VisualStatus.ERROR
}

fun TorrentState.labelRes(): StringResource = when (this) {
    TorrentState.CHECKING -> Res.string.status_checking
    TorrentState.METADATA -> Res.string.status_metadata
    TorrentState.DOWNLOADING -> Res.string.status_downloading
    TorrentState.QUEUED -> Res.string.status_queued
    TorrentState.PAUSED -> Res.string.status_paused
    TorrentState.COMPLETED -> Res.string.status_completed
    TorrentState.MOVING -> Res.string.status_moving
    TorrentState.ERROR -> Res.string.status_error
}

fun ErrorKind.titleRes(): StringResource = when (this) {
    ErrorKind.NO_SPACE -> Res.string.error_no_space
    ErrorKind.NO_NETWORK -> Res.string.error_no_network
    ErrorKind.INVALID_TORRENT -> Res.string.error_invalid_torrent
    ErrorKind.FOLDER_UNAVAILABLE -> Res.string.error_folder_unavailable
    ErrorKind.PERMISSION_DENIED -> Res.string.error_permission_denied
    ErrorKind.FILE_IO -> Res.string.error_file_io
    ErrorKind.METADATA_TIMEOUT -> Res.string.error_metadata_timeout
    ErrorKind.PORT_IN_USE -> Res.string.error_port_in_use
    ErrorKind.DUPLICATE -> Res.string.error_duplicate
    ErrorKind.TRACKER -> Res.string.error_tracker
    ErrorKind.REMOTE_UNREACHABLE -> Res.string.error_remote_unreachable
    ErrorKind.REMOTE_AUTH -> Res.string.error_remote_auth
    ErrorKind.UNKNOWN -> Res.string.error_unknown
}

fun ErrorKind.adviceRes(): StringResource = when (this) {
    ErrorKind.NO_SPACE -> Res.string.advice_no_space
    ErrorKind.NO_NETWORK -> Res.string.advice_no_network
    ErrorKind.INVALID_TORRENT -> Res.string.advice_invalid_torrent
    ErrorKind.FOLDER_UNAVAILABLE -> Res.string.advice_folder_unavailable
    ErrorKind.PERMISSION_DENIED -> Res.string.advice_permission_denied
    ErrorKind.FILE_IO -> Res.string.advice_file_io
    ErrorKind.METADATA_TIMEOUT -> Res.string.advice_metadata_timeout
    ErrorKind.PORT_IN_USE -> Res.string.advice_port_in_use
    ErrorKind.DUPLICATE -> Res.string.advice_duplicate
    ErrorKind.TRACKER -> Res.string.advice_tracker
    ErrorKind.REMOTE_UNREACHABLE -> Res.string.advice_remote_unreachable
    ErrorKind.REMOTE_AUTH -> Res.string.advice_remote_auth
    ErrorKind.UNKNOWN -> Res.string.advice_unknown
}

/** "Title. Advice." in one sentence, for cards and snackbars. */
@Composable
fun errorMessage(error: TorrentError): String =
    stringResource(error.kind.titleRes()) + ". " + stringResource(error.kind.adviceRes())

fun FilePriority.labelRes(): StringResource = when (this) {
    FilePriority.SKIP -> Res.string.priority_skip
    FilePriority.LOW -> Res.string.priority_low
    FilePriority.NORMAL -> Res.string.priority_normal
    FilePriority.HIGH -> Res.string.priority_high
}

/** Unit labels for the current language. */
fun currentUnitLabels(): UnitLabels = if (Locale.current.language == "ru") UnitLabels.Russian else UnitLabels.English

@Composable
@ReadOnlyComposable
fun units(): UnitLabels = GraphiteTheme.units

@Composable
fun bytesText(bytes: Long): String = Formatters.bytes(bytes, units())

@Composable
fun speedText(bytesPerSecond: Long): String = Formatters.speed(bytesPerSecond, units())

@Composable
fun TorrentItem.toCardState(): TorrentCardState {
    val u = units()
    val showSpeed = state == TorrentState.DOWNLOADING || state == TorrentState.METADATA
    val size = when {
        state == TorrentState.COMPLETED -> Formatters.bytes(wantedBytes, u)
        wantedBytes > 0 -> Formatters.bytesOf(doneBytes, wantedBytes, u).let { (done, total) -> stringResource(Res.string.size_of, done, total) }
        else -> ""
    }
    return TorrentCardState(
        id = id,
        name = name,
        status = state.toVisual(),
        statusLabel = stringResource(state.labelRes()),
        progress = progress,
        percentText = Formatters.percent(progress, u),
        speedText = if (showSpeed) Formatters.speed(downloadRate, u) else "",
        sizeText = size,
        etaText = if (state == TorrentState.DOWNLOADING && etaSeconds >= 0) Formatters.duration(etaSeconds, u) else "",
        errorText = error?.let { errorMessage(it) },
        tags = listOfNotNull(category) + tags,
    )
}
