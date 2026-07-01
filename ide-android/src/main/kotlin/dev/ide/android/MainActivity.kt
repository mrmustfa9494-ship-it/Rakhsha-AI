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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

                    // Entry point to the Rakhsha AI assistant — embedded in this same Activity/window (not
                    // a separate screen) so it has direct access to `b: IdeBackend` for inserting AI-written
                    // code into the project and reading live build errors. Labeled "AI" (an extended FAB,
                    // not a bare icon) so it reads clearly as the assistant entry point, not a generic action.
                    ExtendedFloatingActionButton(
                        onClick = { showAi = true },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
                        icon = { Icon(Icons.Filled.Build, contentDescription = null) },
                        text = { Text("AI") },
                    )

                    if (showAi) {
                        Surface(modifier = Modifier.fillMaxSize()) {
                            RakshaAiAssistantOverlay(backend = b, onClose = { showAi = false })
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
private const val AI_PREFS = "rakshaai_ai_prefs"
private const val AI_PREF_MODE = "mode"
private const val AI_PREF_MODEL_PATH = "model_path"
private const val AI_PREF_PROVIDER = "provider"
private const val AI_PREF_API_KEY = "api_key"
private const val AI_CHAT_HISTORY_FILE = "ai_chat_history.json"

private fun loadSavedChatMessages(context: android.content.Context): List<dev.ide.ai.ChatMessage> {
    val file = File(context.filesDir, AI_CHAT_HISTORY_FILE)
    if (!file.exists()) return emptyList()
    return try {
        val arr = org.json.JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            dev.ide.ai.ChatMessage(dev.ide.ai.ChatMessage.Role.valueOf(o.getString("role")), o.getString("content"))
        }
    } catch (e: Exception) {
        emptyList()
    }
}

private fun saveChatMessages(context: android.content.Context, messages: List<dev.ide.ai.ChatMessage>) {
    try {
        val arr = org.json.JSONArray()
        messages.forEach { m -> arr.put(org.json.JSONObject().put("role", m.role.name).put("content", m.content)) }
        File(context.filesDir, AI_CHAT_HISTORY_FILE).writeText(arr.toString())
    } catch (e: Exception) {
        // Best-effort — losing chat history on a write failure isn't worth crashing over.
    }
}


@Composable
private fun RakshaAiAssistantOverlay(backend: IdeBackend, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences(AI_PREFS, android.content.Context.MODE_PRIVATE) }

    var uiState by remember { mutableStateOf(AiUiState.SELECTING) }
    var mode by remember { mutableStateOf<AiMode?>(null) }
    var statusMsg by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var apiKeyInput by remember { mutableStateOf("") }
    var assistant by remember { mutableStateOf<dev.ide.ai.AiAssistant?>(null) }
    var savedMessages by remember { mutableStateOf<List<dev.ide.ai.ChatMessage>>(emptyList()) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    androidx.compose.runtime.LaunchedEffect(Unit) {
        savedMessages = withContext(Dispatchers.IO) { loadSavedChatMessages(context) }
        val savedMode = prefs.getString(AI_PREF_MODE, null)
            ?.let { runCatching { AiMode.valueOf(it) }.getOrNull() }
        if (savedMode != null) {
            mode = savedMode
            when (savedMode) {
                AiMode.OFFLINE -> statusMsg = "Tap 'Load model' to reload your previous model."
                else -> {
                    val key = prefs.getString(AI_PREF_API_KEY, null)
                    if (!key.isNullOrBlank()) apiKeyInput = key
                    statusMsg = "Tap 'Connect' to reconnect."
                }
            }
            uiState = AiUiState.CONNECTING
        }
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            val a = assistant
            if (a != null && a.isReady) kotlinx.coroutines.runBlocking { a.unloadModel() }
        }
    }

    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            isLoading = true; statusMsg = "Copying model…"
            val localFile = withContext(Dispatchers.IO) {
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null }
                    ?: "model.gguf"
                val outFile = File(context.filesDir, "models").apply { mkdirs() }.resolve(name)
                try {
                    context.contentResolver.openInputStream(uri)?.use { inp ->
                        java.io.FileOutputStream(outFile).use { out -> inp.copyTo(out) }
                    }
                    outFile
                } catch (e: Exception) { null }
            }
            if (localFile == null) { statusMsg = "Failed to read file."; isLoading = false; return@launch }
            statusMsg = "Loading model (10-30s)..."
            try {
                val a = dev.ide.ai.impl.LlamaCppAssistant()
                withContext(Dispatchers.IO) { a.loadModel(localFile.absolutePath) }
                if (a.isReady) {
                    prefs.edit().putString(AI_PREF_MODE, AiMode.OFFLINE.name)
                        .putString(AI_PREF_MODEL_PATH, localFile.absolutePath).apply()
                    assistant = a; uiState = AiUiState.CHATTING
                } else { statusMsg = "Model failed to load." }
            } catch (e: Exception) { statusMsg = "Error: ${e.message}" }
            isLoading = false
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                if (uiState != AiUiState.SELECTING) {
                    TextButton(onClick = {
                        if (uiState == AiUiState.CHATTING) uiState = AiUiState.CONNECTING
                        else { mode = null; statusMsg = ""; apiKeyInput = ""; uiState = AiUiState.SELECTING }
                    }) { Text("← Back") }
                } else { Text("  ") }
                Text("Rakhsha AI", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onClose) { Text("Close") }
            }
            androidx.compose.material3.HorizontalDivider()

            when (uiState) {
                AiUiState.SELECTING -> Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Text("Choose AI mode", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 16.dp))
                    AiMode.values().forEach { m ->
                        Surface(
                            onClick = { mode = m; uiState = AiUiState.CONNECTING },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Row(modifier = Modifier.padding(16.dp),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(m.label, style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        if (m == AiMode.OFFLINE) "No internet needed • Needs .gguf model file"
                                        else "Needs API key • Faster responses",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    )
                                }
                                Text(">", style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }

                AiUiState.CONNECTING -> Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Text(mode?.label ?: "", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(bottom = 12.dp))
                    if (statusMsg.isNotBlank()) {
                        Surface(color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                            Text(statusMsg, modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                    } else when (mode) {
                        AiMode.OFFLINE -> {
                            val savedPath = prefs.getString(AI_PREF_MODEL_PATH, null)
                            if (savedPath != null && File(savedPath).exists()) {
                                Surface(color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text("Previous model:", style = MaterialTheme.typography.labelMedium)
                                        Text(File(savedPath).name, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                                Button(
                                    onClick = {
                                        scope.launch {
                                            isLoading = true; statusMsg = "Loading model (10-30s)..."
                                            try {
                                                val a = dev.ide.ai.impl.LlamaCppAssistant()
                                                withContext(Dispatchers.IO) { a.loadModel(savedPath) }
                                                if (a.isReady) { assistant = a; uiState = AiUiState.CHATTING }
                                                else statusMsg = "Model failed to load."
                                            } catch (e: Exception) { statusMsg = "Error: ${e.message}" }
                                            isLoading = false
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                ) { Text("Load model") }
                            }
                            // Model download screen (MNN Chat style)
                            dev.ide.android.ai.ModelDownloadScreen(
                                context = context,
                                onModelReady = { file ->
                                    prefs.edit().putString(AI_PREF_MODE, AiMode.OFFLINE.name)
                                        .putString(AI_PREF_MODEL_PATH, file.absolutePath).apply()
                                    scope.launch {
                                        isLoading = true; statusMsg = "Loading model..."
                                        try {
                                            val a = dev.ide.ai.impl.LlamaCppAssistant()
                                            withContext(Dispatchers.IO) { a.loadModel(file.absolutePath) }
                                            if (a.isReady) { assistant = a; uiState = AiUiState.CHATTING }
                                            else statusMsg = "Model failed to load."
                                        } catch (e: Exception) { statusMsg = "Error: ${e.message}" }
                                        isLoading = false
                                    }
                                },
                                onPickFromFiles = { pickModel.launch(arrayOf("application/octet-stream", "*/*")) },
                            )
                        else -> Column {
                            OutlinedTextField(value = apiKeyInput, onValueChange = { apiKeyInput = it },
                                label = { Text("${mode?.label?.substringAfter("-- ") ?: ""} API key") },
                                modifier = Modifier.fillMaxWidth(), singleLine = true)
                            Button(
                                enabled = apiKeyInput.isNotBlank(),
                                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                                onClick = {
                                    scope.launch {
                                        isLoading = true; statusMsg = "Connecting..."
                                        try {
                                            val provider = when (mode) {
                                                AiMode.GEMINI -> dev.ide.ai.online.OnlineProvider.GEMINI
                                                AiMode.OPENAI -> dev.ide.ai.online.OnlineProvider.OPENAI
                                                AiMode.CLAUDE -> dev.ide.ai.online.OnlineProvider.CLAUDE
                                                else -> error("unreachable")
                                            }
                                            val a = dev.ide.ai.online.OnlineAssistant(provider)
                                            a.loadModel(apiKeyInput)
                                            if (a.isReady) {
                                                prefs.edit().putString(AI_PREF_MODE, mode!!.name)
                                                    .putString(AI_PREF_PROVIDER, provider.name)
                                                    .putString(AI_PREF_API_KEY, apiKeyInput).apply()
                                                assistant = a; uiState = AiUiState.CHATTING
                                            } else statusMsg = "Failed to connect."
                                        } catch (e: Exception) { statusMsg = "Error: ${e.message}" }
                                        isLoading = false
                                    }
                                },
                            ) { Text("Connect") }
                        }
                        null -> {}
                    }
                }

                AiUiState.CHATTING -> assistant?.let { a ->
                    // Build the list of available models for the in-chat dropdown.
                    // Includes all files in the models dir + any recommended models that are installed.
                    val modelsDir = dev.ide.android.ai.modelsDir(context)
                    val availableModels = remember(modelsDir) {
                        modelsDir.listFiles()
                            ?.filter { it.extension == "gguf" }
                            ?.map { it.absolutePath to it.nameWithoutExtension }
                            ?: emptyList()
                    }
                    val currentModelName = remember(prefs) {
                        prefs.getString(AI_PREF_MODEL_PATH, null)
                            ?.let { java.io.File(it).nameWithoutExtension }
                            ?: mode?.label ?: "Rakhsha AI"
                    }
                    dev.ide.ui.ai.AiChatScreen(
                        assistant = a,
                        backend = backend,
                        modifier = Modifier.fillMaxSize(),
                        initialMessages = savedMessages,
                        modelName = currentModelName,
                        availableModels = availableModels,
                        onSwitchModel = { path ->
                            // User picked a different model from the dropdown — reload it
                            scope.launch {
                                isLoading = true
                                statusMsg = "Switching model..."
                                uiState = AiUiState.CONNECTING
                                try {
                                    val newA = dev.ide.ai.impl.LlamaCppAssistant()
                                    withContext(Dispatchers.IO) { newA.loadModel(path) }
                                    if (newA.isReady) {
                                        prefs.edit().putString(AI_PREF_MODEL_PATH, path).apply()
                                        assistant = newA
                                        uiState = AiUiState.CHATTING
                                    } else { statusMsg = "Model failed to load." }
                                } catch (e: Exception) { statusMsg = "Error: ${e.message}" }
                                isLoading = false
                            }
                        },
                        onMessagesChanged = { msgs ->
                            scope.launch(Dispatchers.IO) { saveChatMessages(context, msgs) }
                        },
                    )
                }
            }
        }
    }
}

private enum class AiUiState { SELECTING, CONNECTING, CHATTING }

private enum class AiMode(val label: String) {
    OFFLINE("Offline (on-device model)"),
    GEMINI("Online -- Google Gemini"),
    OPENAI("Online -- OpenAI"),
    CLAUDE("Online -- Anthropic Claude"),
}
