package com.graphite.torrent.ui.app

import com.graphite.torrent.ui.app.common.LocalErrorHandler
import com.graphite.torrent.core.model.GraphiteException
import com.graphite.torrent.core.model.EventType
import com.graphite.torrent.core.engine.ErrorMapper
import androidx.compose.animation.AnimatedContent
import com.graphite.torrent.ui.app.common.rememberAppScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.graphite.torrent.core.controller.ControllerNotice
import com.graphite.torrent.ui.app.add.AddSheet
import com.graphite.torrent.ui.app.add.AddViewModel
import com.graphite.torrent.ui.app.common.adviceRes
import com.graphite.torrent.ui.app.common.currentUnitLabels
import com.graphite.torrent.ui.app.common.titleRes
import com.graphite.torrent.ui.app.details.DetailsScreen
import com.graphite.torrent.ui.app.details.DetailsViewModel
import com.graphite.torrent.ui.app.downloads.DownloadsScreen
import com.graphite.torrent.ui.app.downloads.DownloadsViewModel
import com.graphite.torrent.ui.app.automation.RssScreen
import com.graphite.torrent.ui.app.automation.SchedulerScreen
import com.graphite.torrent.ui.app.log.EventLogScreen
import com.graphite.torrent.ui.app.player.PlayerScreen
import com.graphite.torrent.ui.app.remote.RemoteScreen
import com.graphite.torrent.ui.app.navigation.Destination
import com.graphite.torrent.ui.app.navigation.Navigator
import com.graphite.torrent.ui.app.navigation.RootTab
import com.graphite.torrent.ui.app.resources.Res
import com.graphite.torrent.ui.app.resources.*
import com.graphite.torrent.ui.app.settings.SettingsScreen
import com.graphite.torrent.ui.app.settings.SettingsViewModel
import com.graphite.torrent.ui.app.stats.StatsScreen
import com.graphite.torrent.ui.app.stats.StatsViewModel
import com.graphite.torrent.ui.designsystem.components.AnimatedCrystalLogo
import com.graphite.torrent.ui.designsystem.components.CrystalOutline
import com.graphite.torrent.ui.designsystem.components.GraphiteBottomBar
import com.graphite.torrent.ui.designsystem.components.GraphiteSidebar
import com.graphite.torrent.ui.designsystem.components.GraphiteSnackbarHost
import com.graphite.torrent.ui.designsystem.components.NavItem
import com.graphite.torrent.ui.designsystem.icons.GraphiteIcons
import com.graphite.torrent.ui.designsystem.platform.isDesktop
import com.graphite.torrent.ui.designsystem.theme.GraphiteTheme
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

/**
 * Root of the user interface shared by Android and Windows.
 *
 * Layout adapts to the width: phones get a bottom bar; desktop, tablets and unfolded foldables get the
 * collapsible sidebar, and from 1000dp the list and the details are shown side by side.
 */
@Composable
fun GraphiteApp(
    env: AppEnvironment,
    navigator: Navigator = remember { Navigator() },
    showSplash: Boolean = true,
    extraScreens: @Composable (destination: Destination, onBack: () -> Unit) -> Boolean = { _, _ -> false },
    /** Called once the splash is gone and the real screens are visible (startup diagnostics). */
    onContentShown: () -> Unit = {},
) {
    val settings by env.settings.settings.collectAsStateWithLifecycle()
    GraphiteTheme(amoled = settings.amoledBlack, reduceMotion = settings.reduceMotion, units = currentUnitLabels()) {
        ProvideViewModelStore {
            var splashDone by remember { mutableStateOf(!showSplash) }
            LaunchedEffect(splashDone) { if (splashDone) onContentShown() }
            Box(Modifier.fillMaxSize().background(GraphiteTheme.colors.bg)) {
                CompositionLocalProvider(LocalErrorHandler provides env.errorHandler) {
                    AppContent(env, navigator, extraScreens)
                }
                AnimatedVisibility(!splashDone, enter = fadeIn(), exit = fadeOut(tween(GraphiteTheme.motion.duration(300)))) {
                    Box(Modifier.fillMaxSize().background(GraphiteTheme.colors.bg), contentAlignment = Alignment.Center) {
                        AnimatedCrystalLogo(size = 120.dp, onFinished = { splashDone = true })
                    }
                }
            }
        }
    }
}

@Composable
private fun ProvideViewModelStore(content: @Composable () -> Unit) {
    if (LocalViewModelStoreOwner.current != null) {
        content()
    } else {
        val owner = remember {
            object : ViewModelStoreOwner {
                override val viewModelStore: ViewModelStore = ViewModelStore()
            }
        }
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
    }
}

/**
 * View models of one screen instance: created with it and cleared when it leaves, so that a closed details
 * page does not keep asking the engine about its torrent for as long as the app runs.
 */
@Composable
private fun rememberScreenStore(key: Any): ViewModelStoreOwner {
    val owner = remember(key) {
        object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    return owner
}

@Composable
private fun AppContent(
    env: AppEnvironment,
    navigator: Navigator,
    extraScreens: @Composable (Destination, () -> Unit) -> Boolean,
) {
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberAppScope()
    val addViewModel = viewModel { AddViewModel(env) }
    val downloadsViewModel = viewModel { DownloadsViewModel(env) }
    var showAdd by remember { mutableStateOf(false) }
    var sidebarExpanded by remember { mutableStateOf(false) }
    val settings by env.settings.settings.collectAsStateWithLifecycle()
    val openAddRequest by env.openAddSheet.collectAsStateWithLifecycle()

    fun showMessage(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    fun openAdd() {
        addViewModel.reset()
        showAdd = true
    }

    LaunchedEffect(openAddRequest) {
        if (openAddRequest) {
            openAdd()
            env.openAddSheet.value = false
        }
    }

    // Links and files opened from outside (share, magnet:, .torrent association, drag and drop).
    LaunchedEffect(Unit) {
        env.incoming.collect { request ->
            addViewModel.reset()
            addViewModel.handle(request)
            showAdd = true
            env.consumeIncoming()
        }
    }

    // Notices from the engine.
    LaunchedEffect(Unit) {
        env.controller.notices.collect { notice ->
            val text = when (notice) {
                is ControllerNotice.Completed -> {
                    if (env.settings.settings.value.vibrateOnComplete) env.platform.haptic()
                    getString(Res.string.notice_completed, notice.name)
                }
                is ControllerNotice.Added -> getString(Res.string.notice_added, notice.name)
                is ControllerNotice.Failed -> notice.name + ": " + getString(notice.error.kind.titleRes()) + ". " + getString(notice.error.kind.adviceRes())
                is ControllerNotice.Moved -> getString(Res.string.notice_moved, notice.name)
            }
            snackbar.showSnackbar(text)
        }
    }

    // Unexpected failures of user actions: say what happened instead of crashing.
    LaunchedEffect(Unit) {
        env.uiErrors.collect { e ->
            val error = (e as? GraphiteException)?.error ?: ErrorMapper.fromException(e)
            runCatching { env.eventLog.error(EventType.INTERNAL_ERROR, e::class.simpleName.orEmpty(), error) }
            snackbar.showSnackbar(getString(error.kind.titleRes()) + ". " + getString(error.kind.adviceRes()))
        }
    }

    val navItems = listOf(
        NavItem(RootTab.DOWNLOADS.name, GraphiteIcons.Download, stringResource(Res.string.tab_downloads)),
        NavItem(RootTab.STATS.name, GraphiteIcons.ChartLine, stringResource(Res.string.tab_stats)),
        NavItem(RootTab.SETTINGS.name, GraphiteIcons.Settings, stringResource(Res.string.tab_settings)),
    )

    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        val wide = maxWidth >= 600.dp || isDesktop
        val twoPane = maxWidth >= GraphiteTheme.dimens.twoPaneBreakpoint
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.weight(1f)) {
                if (wide) {
                    GraphiteSidebar(
                        items = navItems,
                        selectedKey = navigator.tab.name,
                        expanded = sidebarExpanded,
                        onToggleExpanded = { sidebarExpanded = !sidebarExpanded },
                        onSelect = { navigator.selectTab(RootTab.valueOf(it)) },
                        expandLabel = stringResource(Res.string.menu),
                    )
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    TabContent(
                        env = env,
                        navigator = navigator,
                        twoPane = twoPane,
                        downloadsViewModel = downloadsViewModel,
                        onAdd = ::openAdd,
                        onShowMessage = ::showMessage,
                        extraScreens = extraScreens,
                    )
                }
            }
            if (!wide) {
                GraphiteBottomBar(navItems, navigator.tab.name, { navigator.selectTab(RootTab.valueOf(it)) })
            }
        }
        // The player covers the whole window, navigation included.
        (navigator.current as? Destination.Player)?.let { p ->
            PlayerScreen(env, p.id, p.fileIndex, p.title, onBack = { navigator.back() })
        }
        GraphiteSnackbarHost(
            snackbar,
            if (wide) Modifier.align(Alignment.BottomEnd).padding(24.dp).widthIn(max = 480.dp) else Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp),
        )
    }

    if (showAdd) {
        AddSheet(addViewModel, onDismiss = { showAdd = false })
    }
    if (settings.firstRunDone.not()) {
        LaunchedEffect(Unit) {
            env.platform.requestNotificationPermission()
            env.settings.update { it.copy(firstRunDone = true) }
        }
    }
}

@Composable
private fun TabContent(
    env: AppEnvironment,
    navigator: Navigator,
    twoPane: Boolean,
    downloadsViewModel: DownloadsViewModel,
    onAdd: () -> Unit,
    onShowMessage: (String) -> Unit,
    extraScreens: @Composable (Destination, () -> Unit) -> Boolean,
) {
    val current = navigator.current
    val back: () -> Unit = { navigator.back() }

    // Destinations that cover the whole content area regardless of the tab.
    if (current != null && current !is Destination.Details) {
        when (current) {
            Destination.EventLog -> {
                EventLogScreen(env, back)
                return
            }
            Destination.Scheduler -> env.features.scheduler?.let {
                SchedulerScreen(env, it, back)
                return
            }
            Destination.Rss -> env.features.rss?.let {
                RssScreen(env, it, back, onShowMessage)
                return
            }
            Destination.Remote -> env.features.remote?.let {
                RemoteScreen(env, it, back, onShowMessage)
                return
            }
            is Destination.Player -> Unit
            else -> if (extraScreens(current, back)) return
        }
    }

    val motion = GraphiteTheme.motion
    AnimatedContent(
        targetState = navigator.tab,
        transitionSpec = { fadeIn(tween(motion.duration(200))) togetherWith fadeOut(tween(motion.duration(150))) },
        label = "tab",
    ) { tab ->
        when (tab) {
            RootTab.DOWNLOADS -> DownloadsPane(env, navigator, twoPane, downloadsViewModel, onAdd, onShowMessage)
            RootTab.STATS -> StatsScreen(viewModel { StatsViewModel(env) })
            RootTab.SETTINGS -> SettingsScreen(viewModel { SettingsViewModel(env) }, onNavigate = navigator::navigate, onShowMessage = onShowMessage)
        }
    }
}

@Composable
private fun DownloadsPane(
    env: AppEnvironment,
    navigator: Navigator,
    twoPane: Boolean,
    downloadsViewModel: DownloadsViewModel,
    onAdd: () -> Unit,
    onShowMessage: (String) -> Unit,
) {
    val details = navigator.current as? Destination.Details
    val onPlay: (String, Int, String) -> Unit = { id, index, title -> navigator.navigate(Destination.Player(id, index, title)) }
    if (twoPane) {
        Row(Modifier.fillMaxSize()) {
            DownloadsScreen(
                downloadsViewModel,
                onOpenDetails = { navigator.replaceTop(Destination.Details(it.id)) },
                onAdd = onAdd,
                onShowError = onShowMessage,
                modifier = Modifier.width(GraphiteTheme.dimens.listPaneWidth),
                selectedId = details?.id,
            )
            Box(Modifier.width(1.dp).fillMaxHeight().background(GraphiteTheme.colors.border))
            Box(Modifier.weight(1f).fillMaxHeight()) {
                if (details != null) {
                    DetailsScreen(
                        viewModel(rememberScreenStore(details.id)) { DetailsViewModel(env, details.id) },
                        onBack = null,
                        onShowMessage = onShowMessage,
                        onPlay = { index, title -> onPlay(details.id, index, title) },
                    )
                } else {
                    NoSelection()
                }
            }
        }
    } else if (details != null) {
        DetailsScreen(
            viewModel(rememberScreenStore(details.id)) { DetailsViewModel(env, details.id) },
            onBack = { navigator.back() },
            onShowMessage = onShowMessage,
            onPlay = { index, title -> onPlay(details.id, index, title) },
        )
    } else {
        DownloadsScreen(
            downloadsViewModel,
            onOpenDetails = { navigator.navigate(Destination.Details(it.id)) },
            onAdd = onAdd,
            onShowError = onShowMessage,
        )
    }
}

@Composable
private fun NoSelection() {
    Column(
        Modifier.fillMaxSize().padding(GraphiteTheme.spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CrystalOutline(size = 96.dp)
        Spacer(Modifier.height(GraphiteTheme.spacing.md))
        Text(
            stringResource(Res.string.select_download_hint),
            style = GraphiteTheme.typography.body,
            color = GraphiteTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}
