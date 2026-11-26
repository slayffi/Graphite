package com.graphite.torrent.android

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.graphite.torrent.ui.app.GraphiteApp
import com.graphite.torrent.ui.app.IncomingRequest
import com.graphite.torrent.ui.app.navigation.Navigator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity(), BridgeHost {
    private val app get() = application as GraphiteApplication
    private val navigator = Navigator()

    private var pendingUris: CompletableDeferred<List<Uri>>? = null
    private var pendingUri: CompletableDeferred<Uri?>? = null
    private var pendingText: CompletableDeferred<String?>? = null

    private val openDocs = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { pendingUris?.complete(it) }
    private val openDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { pendingUris?.complete(listOfNotNull(it)) }
    private val openTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { pendingUri?.complete(it) }
    private val createDoc = registerForActivityResult(CreateDocumentAnyType()) { pendingUri?.complete(it) }
    private val scanQr = registerForActivityResult(QrScanContract()) { pendingText?.complete(it) }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        app.safeModeReport?.let { report ->
            // The previous start failed: show why, right here (no other process or activity is needed,
            // some firmwares block those), instead of failing again.
            StartupGuard.acknowledge(this)
            ReportScreen.show(this, report)
            return
        }
        StartupGuard.step("activity")
        app.bridge.host = this

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!navigator.back()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        // The logo animation greets a fresh start only. Coming back while the app already runs (from the
        // notification, the widget, or a link opened in the browser) goes straight to the screen.
        val opensSomething = intent?.action in setOf(Intent.ACTION_VIEW, Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)
        val showSplash = !app.splashShown && !opensSomething && savedInstanceState == null
        app.splashShown = true
        setContent { GraphiteApp(app.env, navigator, showSplash = showSplash, onContentShown = ::onContentShown) }
        window.decorView.post { StartupGuard.step("first frame posted") }
        lifecycleScope.launch {
            StartupGuard.step("ui")
            // The main thread works but the screens never appeared: replace them with the report.
            delay(UI_TIMEOUT_MS)
            if (!contentShown) {
                StartupGuard.step("screens did not appear in ${UI_TIMEOUT_MS / 1000} s")
                val report = withContext(Dispatchers.IO) { StartupGuard.reportNow(this@MainActivity) }
                StartupGuard.acknowledge(this@MainActivity)
                ReportScreen.show(this@MainActivity, report)
            }
        }

        lifecycleScope.launch { app.exitRequests.collect { finishAndRemoveTask() } }
        if (savedInstanceState == null) handleIntent(intent)
    }

    private var contentShown = false

    private fun onContentShown() {
        if (contentShown) return
        contentShown = true
        StartupGuard.step("screens shown")
        // A start that shows the screens and keeps running for a few seconds counts as good.
        lifecycleScope.launch {
            delay(HEALTHY_AFTER_MS)
            StartupGuard.healthy()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (!app.ready) return
        app.bridge.host = this
        // Restarts the engine if the user chose "Exit" from the notification earlier.
        app.services.scope.launch {
            app.ensureRunning()
            // Android refuses to start the service while the app is in the background (a download added by
            // RSS or the watched folder, for example). Now it is allowed: make sure it runs if it is needed.
            val controller = app.services.controller
            if (TorrentService.needsService(controller.torrents.value, controller.session.value)) TorrentService.start(this@MainActivity)
        }
    }

    override fun onDestroy() {
        if (app.bridge.host === this) app.bridge.host = null
        super.onDestroy()
    }

    /** magnet: links, .torrent files and shared text or files. */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        // In safe mode (showing an error report) there are no services to hand the request to.
        if (!app.ready) return
        val env = app.env
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val data = intent.data ?: return
                if (data.scheme.equals("magnet", ignoreCase = true)) {
                    env.submit(IncomingRequest.Magnet(data.toString()))
                } else {
                    lifecycleScope.launch {
                        app.platform.readIncoming(data)?.let { env.submit(IncomingRequest.TorrentFile(it.name, it.bytes)) }
                    }
                }
            }
            Intent.ACTION_SEND -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { env.submit(IncomingRequest.Text(it)) }
                streamExtra(intent)?.let { uri ->
                    lifecycleScope.launch { app.platform.readIncoming(uri)?.let { env.submit(IncomingRequest.TorrentFile(it.name, it.bytes)) } }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                // Extras come from another app and may hold anything: a malformed list is ignored, not fatal.
                val uris = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                    }
                }.getOrNull()?.take(MAX_SHARED_FILES)
                lifecycleScope.launch {
                    uris.orEmpty().forEach { uri -> app.platform.readIncoming(uri)?.let { env.submit(IncomingRequest.TorrentFile(it.name, it.bytes)) } }
                }
            }
            ACTION_ADD -> env.openAddSheet.value = true
        }
    }

    private fun streamExtra(intent: Intent): Uri? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        }
    }.getOrNull()

    // ---------------------------------------------------------------- BridgeHost

    override suspend fun openDocuments(mimeTypes: Array<String>, single: Boolean): List<Uri> {
        val d = CompletableDeferred<List<Uri>>()
        pendingUris = d
        if (single) openDoc.launch(mimeTypes) else openDocs.launch(mimeTypes)
        return d.await()
    }

    override suspend fun openTree(): Uri? {
        val d = CompletableDeferred<Uri?>()
        pendingUri = d
        openTree.launch(null)
        return d.await()
    }

    override suspend fun createDocument(name: String, mimeType: String): Uri? {
        val d = CompletableDeferred<Uri?>()
        pendingUri = d
        createDoc.launch(name to mimeType)
        return d.await()
    }

    override suspend fun scanQr(): String? {
        val d = CompletableDeferred<String?>()
        pendingText = d
        scanQr.launch(Unit)
        return d.await()
    }

    override fun requestPermission(permission: String) {
        this.permission.launch(permission)
    }

    /** CreateDocument with a MIME type chosen at launch time. */
    private class CreateDocumentAnyType : ActivityResultContract<Pair<String, String>, Uri?>() {
        override fun createIntent(context: android.content.Context, input: Pair<String, String>): Intent =
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(input.second)
                .putExtra(Intent.EXTRA_TITLE, input.first)

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
            if (resultCode == RESULT_OK) intent?.data else null
    }

    private class QrScanContract : ActivityResultContract<Unit, String?>() {
        override fun createIntent(context: android.content.Context, input: Unit): Intent = Intent(context, QrScannerActivity::class.java)
        override fun parseResult(resultCode: Int, intent: Intent?): String? =
            if (resultCode == RESULT_OK) intent?.getStringExtra(QrScannerActivity.EXTRA_TEXT) else null
    }

    companion object {
        const val ACTION_ADD = "com.graphite.torrent.ADD"
        private const val HEALTHY_AFTER_MS = 3_000L
        private const val MAX_SHARED_FILES = 50
        private const val UI_TIMEOUT_MS = 20_000L
    }
}
