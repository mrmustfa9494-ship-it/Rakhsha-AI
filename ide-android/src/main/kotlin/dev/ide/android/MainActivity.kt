package dev.ide.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import dev.ide.android.daemon.BuildDaemonProof
import dev.ide.core.IdeServicesBackend
import dev.ide.ui.RakshaAiApp
import dev.ide.ui.backend.FileActions
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.TreeNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Android host. Bootstraps the real on-device engine ([AndroidIde]) off the main thread, then
 * renders the shared Compose IDE UI ([RakshaAiApp]) over the resulting [IdeBackend] — the same code
 * path the desktop runs. Provides the Android [FileActions]: SAF import (copy external files into the
 * active project) and FileProvider-backed Share/"Open with". A splash shows while the engine starts.
 */
class MainActivity : ComponentActivity() {

    private var session: AndroidIde.Session? = null

    /** A file handed in by another app ("Open with" / "Share to"), pending import once the engine is up. */
    private val inbound = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(0xfff)
        )

        super.onCreate(savedInstanceState)
        inbound.value = extractStream(intent)

        setContent {
            var backend by remember { mutableStateOf<IdeBackend?>(null) }
            var error by remember { mutableStateOf<String?>(null) }
            LaunchedEffect(Unit) {
                runCatching { withContext(Dispatchers.IO) { AndroidIde.bootstrap(applicationContext) } }.onSuccess { s ->
                        session = s; backend = s.backend
                        // Phase-3a build-process-isolation proof (docs/build-process-isolation.md): bind the
                        // :build daemon, open the first on-device project there, and run its default build in
                        // that process — streaming state back over IPC. Verify via `adb logcat -s ide.daemon
                        // ide.mem`. Started only AFTER bootstrap finishes so the main process provisions the
                        // shared kotlinc-home/assets first and the daemon takes the no-delete fast path (a
                        // concurrent first-run provision would race and corrupt the kotlinc-home). Debug-only +
                        // flag-gated; replaced in Phase 3b by RemoteBuildRunner wired into the UI Run button.
                        if (BuildConfig.DEBUG && BuildDaemonProof.ENABLED) BuildDaemonProof.run(applicationContext)
                    }.onFailure { e -> error = e.message ?: e.toString() }
            }

            var pendingTarget by remember { mutableStateOf<String?>(null) }
            var pendingCallback by remember { mutableStateOf<((List<String>) -> Unit)?>(null) }
            val importLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                    val b = backend
                    val target = pendingTarget
                    val created = if (b != null && target != null) uris.mapNotNull {
                        importUri(
                            it, target, b
                        )
                    } else emptyList()
                    pendingCallback?.invoke(created)
                    pendingTarget = null; pendingCallback = null
                }
            var pendingPick by remember { mutableStateOf<((String?) -> Unit)?>(null) }
            val pickLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    val path = uri?.let { copyUriToCache(it) }
                    pendingPick?.invoke(path)
                    pendingPick = null
                }
            // "Save As" export: the user picks a destination (Files/Drive/Downloads); we copy the bytes there.
            var pendingExport by remember { mutableStateOf<String?>(null) }
            val exportLauncher =
                rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
                    val src = pendingExport
                    pendingExport = null
                    if (uri != null && src != null) exportTo(uri, src)
                }
            val fileActions = remember {
                object : FileActions {
                    override val canImport: Boolean = true
                    override fun importInto(targetDir: String, onImported: (List<String>) -> Unit) {
                        pendingTarget = targetDir
                        pendingCallback = onImported
                        importLauncher.launch(
                            arrayOf(
                                "text/*", "application/json", "application/xml", "*/*"
                            )
                        )
                    }

                    override val canPickFile: Boolean = true
                    override fun pickFile(onPicked: (String?) -> Unit) {
                        pendingPick = onPicked
                        pickLauncher.launch(arrayOf("*/*"))
                    }

                    override val canShare: Boolean = true
                    override fun share(path: String) = shareFile(path)

                    override val canExport: Boolean = true
                    override fun exportFile(path: String) {
                        pendingExport = path
                        exportLauncher.launch(File(path).name)
                    }

                    override val canOpenUrl: Boolean = true
                    override fun openUrl(url: String) = openInBrowser(url)

                    override val canReveal: Boolean = true
                    override fun reveal(path: String) = openInFiles(path)

                    override val canInstallApk: Boolean = true
                    override fun installApk(path: String) = promptInstall(path)
                }
            }

            LaunchedEffect(backend, inbound.value) {
                val b = backend
                val uri = inbound.value
                if (b != null && uri != null) {
                    val target = firstSourceRoot(b.files.fileTree())
                    val path = if (target != null) importUri(uri, target, b) else null
                    Toast.makeText(
                        this@MainActivity,
                        if (path != null) "Imported ${File(path).name}" else "Couldn't import file",
                        Toast.LENGTH_SHORT,
                    ).show()
                    inbound.value = null
                }
            }

            val b = backend
            when {
                b != null -> Box(modifier = Modifier.fillMaxSize()) {
                    RakshaAiApp(
                        b,
                        fileActions = fileActions,
                        // On-device Compose preview: render @Preview composables through the interpreter. The
                        // backend instance is stable across project switches (it swaps services internally), so
                        // one host suffices.
                        composePreviewHost = (b as? IdeServicesBackend)?.let {
                            AndroidComposePreviewHost(
                                it
                            )
                        },
                    )

                    var showAi by remember { mutableStateOf(false) }

                    // Wire the top-bar AI button (dev.ide.ui.components.AiLauncher, inside EditorChrome)
                    // to open the overlay. The old floating button is gone — the entry point now lives
                    // in the IDE's own top bar, so it never overlaps the toolbar/system bars.
                    androidx.compose.runtime.DisposableEffect(Unit) {
                        dev.ide.ui.components.AiLauncher.open = { showAi = true }
                        onDispose { dev.ide.ui.components.AiLauncher.open = null }
                    }

                    if (showAi) {
                        androidx.compose.material3.Surface(modifier = Modifier.fillMaxSize()) {
                            RakshaAiOverlay(backend = b, onClose = { showAi = false })
                        }
                    }
                }

                error != null -> Splash("Failed to start: $error")
                else -> Splash("Starting Rakhsha AI IDE…")
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractStream(intent)?.let { inbound.value = it }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Close the *active* engine (a project switch may have swapped it), not just the initial one.
        session?.backend?.close()
    }

    /** Hand the APK at [path] to the system package installer (the OS install-confirmation UI). */
    private fun promptInstall(path: String) = runCatching {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", File(path))
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }.getOrElse { Toast.makeText(this, "Couldn't open the installer for ${File(path).name}", Toast.LENGTH_SHORT).show() }

    /** Copy [uri]'s bytes into a new file under [targetDir]; returns the new path or null. */
    private fun importUri(uri: Uri, targetDir: String, backend: IdeBackend): String? = runCatching {
        val name = queryDisplayName(uri) ?: "imported-${System.currentTimeMillis()}"
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        backend.files.createFileBytes(targetDir, name, bytes)
    }.getOrNull()

    /** Copy a picked content:// file into the app cache and return its real path (for keystore import). */
    private fun copyUriToCache(uri: Uri): String? = runCatching {
        val name = queryDisplayName(uri) ?: "keystore-${System.currentTimeMillis()}"
        val dest = File(cacheDir, "picked-$name")
        contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } } ?: return null
        dest.absolutePath
    }.getOrNull()

    /** Share [path] to another app via a FileProvider content:// URI (no FileUriExposedException). */
    private fun shareFile(path: String) = runCatching {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", File(path))
        val send = Intent(Intent.ACTION_SEND).apply {
            type = mimeFor(path)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share ${File(path).name}"))
    }.getOrElse { Toast.makeText(this, "Can't share this file", Toast.LENGTH_SHORT).show() }

    /** Copy [src]'s bytes into the user-chosen document [uri] (the "Save As"/export destination). */
    private fun exportTo(uri: Uri, src: String) = runCatching {
        contentResolver.openOutputStream(uri)?.use { out -> File(src).inputStream().use { it.copyTo(out) } }
        Toast.makeText(this, "Exported ${File(src).name}", Toast.LENGTH_SHORT).show()
    }.getOrElse { Toast.makeText(this, "Couldn't export file", Toast.LENGTH_SHORT).show() }

    /** A best-effort content MIME type from the file extension (drives the share-sheet target list). */
    private fun mimeFor(path: String): String = when {
        path.endsWith(".apk") -> "application/vnd.android.package-archive"
        path.endsWith(".zip") || path.endsWith(".jar") -> "application/zip"
        path.endsWith(".txt") || path.endsWith(".kt") || path.endsWith(".java") || path.endsWith(".xml") -> "text/plain"
        else -> "application/octet-stream"
    }

    /**
     * Open the system Files app on Rakhsha AI IDE's storage (served by [ProjectsDocumentsProvider]) so the user
     * can browse/manage there. Best-effort across OEM file managers: first tries to deep-link to [path]'s
     * own directory (a document-URI VIEW — DocumentsUI honors it; our doc ids are absolute paths), then the
     * provider root, then a generic Files launch, and finally explains where to look.
     */
    private fun openInFiles(path: String?) {
        val auth = "$packageName.documents"
        // Deep-link to the file's containing directory when we can (so "Open in Files" lands near the APK).
        val dirId = path?.let { p -> File(p).let { if (it.isDirectory) it else it.parentFile }?.absolutePath }
        val dirUri = dirId?.let { runCatching { DocumentsContract.buildDocumentUri(auth, it) }.getOrNull() }
        if (dirUri != null) {
            val viewDir = Intent(Intent.ACTION_VIEW)
                .setDataAndType(dirUri, "vnd.android.document/directory")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            if (runCatching { startActivity(viewDir); true }.getOrDefault(false)) return
        }
        val rootUri = DocumentsContract.buildRootUri(auth, AndroidIde.externalHome(this).absolutePath)
        val viewRoot = Intent(Intent.ACTION_VIEW).setDataAndType(rootUri, "vnd.android.document/root")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        if (runCatching { startActivity(viewRoot); true }.getOrDefault(false)) return
        // Fall back to whatever handles the storage/Files browse action, else tell the user where to look.
        val browse = Intent("android.provider.action.BROWSE", rootUri)
        runCatching { startActivity(browse) }.getOrElse {
            Toast.makeText(
                this, "Open the Files app → Rakhsha AI IDE to browse your projects", Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Open [url] in the device browser (the Beta "Submit suggestions" action). */
    private fun openInBrowser(url: String) = runCatching {
        startActivity(
            Intent(
                Intent.ACTION_VIEW, Uri.parse(url)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.getOrElse { Toast.makeText(this, "No app to open links", Toast.LENGTH_SHORT).show() }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        if (uri.scheme == "file") return uri.lastPathSegment
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
    }.getOrNull()

    private fun extractStream(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
        else -> null
    }
}

/** Depth-first search for the first source root (a node carrying a source-root path) in the tree. */
private fun firstSourceRoot(root: TreeNode): String? {
    root.sourceRootPath?.let { return it }
    for (child in root.children) firstSourceRoot(child)?.let { return it }
    return null
}

/** Minimal dark splash shown while the engine boots (before the themed UI is available). */
@Composable
private fun Splash(message: String) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0E0E12)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(color = Color(0xFF8B7BF0))
            Text(
                message,
                color = Color(0xFFB9B9C6),
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }
    }
}

/** Where the AI assistant's connection settings (mode, model path or provider+API key) are remembered
 *  across app restarts, so the user doesn't have to re-pick a model / re-enter an API key every time. */

// --- TEMP DIAGNOSTIC: writes model-load progress to a file the user can open in their file manager
// at Android/data/com.rakshaai.ide/files/rakshaai_ai_log.txt. Lets us see EXACTLY where the load
// stalls (native load vs context creation vs an exception) instead of guessing. Remove once fixed.
private var aiDbgCtx: android.content.Context? = null
private fun aiDbg(msg: String) {
    val c = aiDbgCtx ?: return
    runCatching {
        val f = java.io.File(c.getExternalFilesDir(null), "rakshaai_ai_log.txt")
        f.appendText("${System.currentTimeMillis()}  $msg\n")
    }
}

private suspend fun loadModelOnThread(path: String): dev.ide.ai.impl.LlamaCppAssistant? {
    // Structured concurrency instead of a raw Thread + runBlocking + manual cont.resume.
    // LlamaCppAssistant.loadModel already switches to Dispatchers.IO internally; wrapping here too
    // is harmless and keeps the native call off the main thread. When it returns, the calling
    // coroutine resumes naturally on its original dispatcher (Main) — no manual continuation to
    // get stuck, which is what was freezing the UI after a successful load.
    val a = dev.ide.ai.impl.LlamaCppAssistant()
    return try {
        val f = java.io.File(path)
        aiDbg("loadModelOnThread START path=$path exists=${f.exists()} sizeBytes=${if (f.exists()) f.length() else -1}")
        val t0 = System.currentTimeMillis()
        aiDbg("calling a.loadModel ...")
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { a.loadModel(path) }
        aiDbg("a.loadModel RETURNED isReady=${a.isReady} took_ms=${System.currentTimeMillis() - t0}")
        if (a.isReady) a else null
    } catch (e: Throwable) {
        aiDbg("a.loadModel THREW ${e::class.java.simpleName}: ${e.message}")
        null
    }
}

private const val AI_PREFS = "rakshaai_ai_prefs"
private const val AI_PREF_MODE = "mode"
private const val AI_PREF_MODEL_PATH = "model_path"
private const val AI_PREF_PROVIDER = "provider"
private const val AI_PREF_API_KEY = "api_key"

private fun loadSavedMessages(ctx: android.content.Context): List<dev.ide.ai.ChatMessage> {
    val f = java.io.File(ctx.filesDir, "ai_chat_history.json")
    if (!f.exists()) return emptyList()
    return try {
        val arr = org.json.JSONArray(f.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            dev.ide.ai.ChatMessage(dev.ide.ai.ChatMessage.Role.valueOf(o.getString("role")), o.getString("content"))
        }
    } catch (e: Exception) { emptyList() }
}

private fun saveMessages(ctx: android.content.Context, msgs: List<dev.ide.ai.ChatMessage>) {
    try {
        val arr = org.json.JSONArray()
        msgs.forEach { m -> arr.put(org.json.JSONObject().put("role", m.role.name).put("content", m.content)) }
        java.io.File(ctx.filesDir, "ai_chat_history.json").writeText(arr.toString())
    } catch (_: Exception) {}
}

private enum class AiMode(val label: String) {
    OFFLINE("Offline (on-device model)"),
    GEMINI("Online -- Google Gemini"),
    OPENAI("Online -- OpenAI"),
    CLAUDE("Online -- Anthropic Claude"),
}

@Composable
private fun RakshaAiOverlay(backend: dev.ide.ui.backend.IdeBackend, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) { aiDbgCtx = ctx.applicationContext }
    val prefs = remember { ctx.getSharedPreferences(AI_PREFS, android.content.Context.MODE_PRIVATE) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var showScreen by remember { mutableStateOf<String>("MODE_SELECT") } // MODE_SELECT | CONNECTING | CHAT
    var selectedMode by remember { mutableStateOf<AiMode?>(null) }
    var statusMsg by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var apiKeyInput by remember { mutableStateOf("") }
    var assistant by remember { mutableStateOf<dev.ide.ai.AiAssistant?>(null) }
    var savedMsgs by remember { mutableStateOf<List<dev.ide.ai.ChatMessage>>(emptyList()) }

    // Intercept the system Back button so it navigates *within* the AI overlay instead of
    // finishing the whole Activity (which was closing the app). At the top level (MODE_SELECT)
    // Back closes just the overlay via onClose().
    androidx.activity.compose.BackHandler {
        when (showScreen) {
            "CHAT" -> showScreen = "CONNECTING"
            "CONNECTING" -> { selectedMode = null; statusMsg = ""; showScreen = "MODE_SELECT" }
            else -> onClose()
        }
    }

    // Restore on open
    androidx.compose.runtime.LaunchedEffect(Unit) {
        savedMsgs = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadSavedMessages(ctx) }
        val m = prefs.getString(AI_PREF_MODE, null)?.let { runCatching { AiMode.valueOf(it) }.getOrNull() }
        if (m != null) {
            selectedMode = m
            when (m) {
                AiMode.OFFLINE -> statusMsg = "Tap 'Load' to reload your model."
                else -> prefs.getString(AI_PREF_API_KEY, null)?.let { if (it.isNotBlank()) apiKeyInput = it }
            }
            showScreen = "CONNECTING"
        }
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            val a = assistant
            if (a != null && a.isReady) {
                // Unload off the main thread; blocking here (runBlocking) froze the UI on close/back.
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { runCatching { a.unloadModel() } }
            }
        }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: android.net.Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isLoading = true; statusMsg = "Copying model..."
            val name = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null } ?: "model.gguf"
            val outFile = java.io.File(ctx.filesDir, "models").apply { mkdirs() }.resolve(name)
            try {
                ctx.contentResolver.openInputStream(uri)?.use { inp ->
                    java.io.FileOutputStream(outFile).use { out -> inp.copyTo(out) }
                }
            } catch (e: Exception) { statusMsg = "Failed to copy: ${e.message}"; isLoading = false; return@launch }
            statusMsg = "Loading model (30-60s)..."
            val a = loadModelOnThread(outFile.absolutePath)
            if (a != null) {
                prefs.edit().putString(AI_PREF_MODE, AiMode.OFFLINE.name).putString(AI_PREF_MODEL_PATH, outFile.absolutePath).apply()
                assistant = a; showScreen = "CHAT"
            } else statusMsg = "Model failed to load."
            isLoading = false
        }
    }

    androidx.compose.material3.Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            // Top bar — always responsive
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                if (showScreen != "MODE_SELECT") {
                    androidx.compose.material3.TextButton(onClick = {
                        if (showScreen == "CHAT") showScreen = "CONNECTING"
                        else { selectedMode = null; statusMsg = ""; showScreen = "MODE_SELECT" }
                    }) { Text("← Back") }
                } else Text("  ")
                Text("Rakhsha AI", style = MaterialTheme.typography.titleLarge)
                androidx.compose.material3.TextButton(onClick = onClose) { Text("Close") }
            }
            androidx.compose.material3.HorizontalDivider()

            when (showScreen) {
                "MODE_SELECT" -> Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Text("Choose AI mode", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 16.dp))
                    AiMode.values().forEach { m ->
                        androidx.compose.material3.Surface(
                            onClick = { selectedMode = m; statusMsg = ""; showScreen = "CONNECTING" },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(m.label, style = MaterialTheme.typography.bodyLarge)
                                    Text(if (m == AiMode.OFFLINE) "No internet, needs .gguf file" else "Needs API key",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                                }
                                Text(">", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }

                "CONNECTING" -> Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Text(selectedMode?.label ?: "", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 12.dp))

                    if (isLoading) {
                        var elapsedSecs by remember { mutableIntStateOf(0) }
                        androidx.compose.runtime.LaunchedEffect(isLoading) {
                            elapsedSecs = 0
                            while (true) {
                                kotlinx.coroutines.delay(1000)
                                elapsedSecs++
                            }
                        }
                        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                            Text(statusMsg, modifier = Modifier.padding(top = 8.dp),
                                style = MaterialTheme.typography.bodyMedium)
                            Text("${elapsedSecs}s elapsed...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.padding(top = 4.dp))
                            if (selectedMode == AiMode.OFFLINE) {
                                Text("Large models take 30-60s on first load.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                                    modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                    } else {
                        if (statusMsg.isNotBlank()) {
                            androidx.compose.material3.Surface(
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                                Text(statusMsg, modifier = Modifier.padding(12.dp))
                            }
                        }

                        when (selectedMode) {
                            AiMode.OFFLINE -> {
                                val savedPath = prefs.getString(AI_PREF_MODEL_PATH, null)
                                if (savedPath != null && java.io.File(savedPath).exists()) {
                                    androidx.compose.material3.Button(
                                        onClick = {
                                            scope.launch {
                                                isLoading = true; statusMsg = "Loading model..."
                                                val a = loadModelOnThread(savedPath)
                                                if (a != null) { assistant = a; showScreen = "CHAT" }
                                                else statusMsg = "Failed. Try re-downloading."
                                                isLoading = false
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                    ) { Text("Load: ${java.io.File(savedPath).name}") }
                                }
                                dev.ide.android.ai.ModelDownloadScreen(
                                    context = ctx,
                                    onModelReady = { file ->
                                        prefs.edit().putString(AI_PREF_MODE, AiMode.OFFLINE.name)
                                            .putString(AI_PREF_MODEL_PATH, file.absolutePath).apply()
                                        scope.launch {
                                            isLoading = true; statusMsg = "Loading model..."
                                            val a = loadModelOnThread(file.absolutePath)
                                            if (a != null) { assistant = a; showScreen = "CHAT" }
                                            else statusMsg = "Failed to load."
                                            isLoading = false
                                        }
                                    },
                                    onPickFromFiles = { pickFile.launch(arrayOf("application/octet-stream", "*/*")) },
                                )
                            }
                            AiMode.GEMINI, AiMode.OPENAI, AiMode.CLAUDE -> Column {
                                androidx.compose.material3.OutlinedTextField(
                                    value = apiKeyInput, onValueChange = { apiKeyInput = it },
                                    label = { Text("${selectedMode?.label?.substringAfter("-- ") ?: ""} API key") },
                                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                                androidx.compose.material3.Button(
                                    enabled = apiKeyInput.isNotBlank(),
                                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                                    onClick = {
                                        scope.launch {
                                            isLoading = true; statusMsg = "Connecting to ${selectedMode?.label?.substringAfter("-- ") ?: ""}..."
                                            try {
                                                val provider = when (selectedMode) {
                                                    AiMode.GEMINI -> dev.ide.ai.online.OnlineProvider.GEMINI
                                                    AiMode.OPENAI -> dev.ide.ai.online.OnlineProvider.OPENAI
                                                    else -> dev.ide.ai.online.OnlineProvider.CLAUDE
                                                }
                                                val a = dev.ide.ai.online.OnlineAssistant(provider)
                                                // loadModel now does a live test ping — run on IO thread
                                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                                    a.loadModel(apiKeyInput)
                                                }
                                                if (a.isReady) {
                                                    prefs.edit().putString(AI_PREF_MODE, selectedMode!!.name)
                                                        .putString(AI_PREF_PROVIDER, provider.name)
                                                        .putString(AI_PREF_API_KEY, apiKeyInput).apply()
                                                    assistant = a; showScreen = "CHAT"
                                                } else statusMsg = "Failed to connect — check your API key."
                                            } catch (e: Exception) {
                                                statusMsg = "❌ ${e.message ?: "Connection failed"}"
                                            }
                                            isLoading = false
                                        }
                                    }) { Text("Connect") }
                            }
                            null -> {}
                        }
                    }
                }

                "CHAT" -> {
                    aiDbg("CHAT branch composing (assistant != null = ${assistant != null})")
                    val a = assistant
                    if (a != null) {
                        val modelsDir = dev.ide.android.ai.modelsDir(ctx)
                        val allModels = remember(modelsDir) {
                            modelsDir.listFiles()?.filter { it.extension == "gguf" }
                                ?.map { it.absolutePath to it.nameWithoutExtension } ?: emptyList()
                        }
                        val modelName = prefs.getString(AI_PREF_MODEL_PATH, null)
                            ?.let { java.io.File(it).nameWithoutExtension } ?: selectedMode?.label ?: "Rakhsha AI"
                        dev.ide.ui.ai.AiChatScreen(
                            assistant = a, backend = backend,
                            modifier = Modifier.fillMaxSize(),
                            initialMessages = savedMsgs,
                            modelName = modelName,
                            availableModels = allModels,
                            onSwitchModel = { path ->
                                scope.launch {
                                    showScreen = "CONNECTING"; isLoading = true; statusMsg = "Switching model..."
                                    val newA = loadModelOnThread(path)
                                    if (newA != null) {
                                        prefs.edit().putString(AI_PREF_MODEL_PATH, path).apply()
                                        assistant = newA; showScreen = "CHAT"
                                    } else { statusMsg = "Model failed to load."; isLoading = false }
                                    isLoading = false
                                }
                            },
                            onMessagesChanged = { msgs ->
                                scope.launch(kotlinx.coroutines.Dispatchers.IO) { saveMessages(ctx, msgs) }
                            },
                            onDbg = { m -> aiDbg(m) },
                        )
                    }
                }
            }
        }
    }
}
