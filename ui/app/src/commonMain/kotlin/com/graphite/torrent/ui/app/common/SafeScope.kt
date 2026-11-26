package com.graphite.torrent.ui.app.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope

/** Handler of unexpected errors in coroutines started from composables; provided by GraphiteApp. */
val LocalErrorHandler = staticCompositionLocalOf { CoroutineExceptionHandler { _, e -> e.printStackTrace() } }

/**
 * Like [rememberCoroutineScope], but a failed action shows a message instead of crashing the app
 * (an uncaught coroutine exception ends the process on Android).
 */
@Composable
fun rememberAppScope(): CoroutineScope {
    val handler = LocalErrorHandler.current
    return rememberCoroutineScope { handler }
}
