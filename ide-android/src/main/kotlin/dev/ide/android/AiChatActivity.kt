package dev.ide.android

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ide.ai.impl.LlamaCppAssistant
import dev.ide.android.ai.ModelDownloadScreen
import dev.ide.android.ai.modelsDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Standalone Activity for the Rakhsha AI chat assistant.
 * Kept as a separate Activity (not embedded in MainActivity's Compose tree)
 * because llama.cpp's JNI model load blocks the calling thread for 10-60s,
 * which freezes the entire Compose window if done inside MainActivity.
 * A separate Activity has its own window/thread budget — the IDE stays responsive.
 */
class AiChatActivity : ComponentActivity() {

    private val prefs by lazy { getSharedPreferences("rakshaai_ai_prefs", MODE_PRIVATE) }
    private val AI_PREF_MODEL_PATH = "model_path"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var status by remember { mutableStateOf("") }
                var isLoading by remember { mutableStateOf(false) }
                var assistant by remember { mutableStateOf<LlamaCppAssistant?>(null) }
                val scope = rememberCoroutineScope()

                val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    scope.launch {
                        isLoading = true; status = "Copying model..."
                        val localFile = withContext(Dispatchers.IO) { copyToLocal(uri) }
                        if (localFile == null) { status = "Failed to read file."; isLoading = false; return@launch }
                        prefs.edit().putString(AI_PREF_MODEL_PATH, localFile.absolutePath).apply()
                        status = "Loading model (may take 30-60s)..."
                        val a = LlamaCppAssistant()
                        val ok = loadOnThread(a, localFile.absolutePath)
                        if (ok) { assistant = a; status = "" }
                        else status = "Model failed to load. Try a different file."
                        isLoading = false
                    }
                }

                // Auto-load previously used model
                if (assistant == null && !isLoading) {
                    val saved = prefs.getString(AI_PREF_MODEL_PATH, null)
                    if (saved != null && File(saved).exists() && status.isEmpty()) {
                        status = "Tap 'Load' to reload your model."
                    }
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                        // Header
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = { finish() }) { Text("← Back") }
                            Text("Rakhsha AI", style = MaterialTheme.typography.titleLarge)
                            TextButton(onClick = { finish() }) { Text("Close") }
                        }
                        androidx.compose.material3.HorizontalDivider()

                        when {
                            // Chat screen
                            assistant != null -> {
                                val modelsDir = modelsDir(this@AiChatActivity)
                                val availableModels = remember(modelsDir) {
                                    modelsDir.listFiles()
                                        ?.filter { it.extension == "gguf" }
                                        ?.map { it.absolutePath to it.nameWithoutExtension }
                                        ?: emptyList()
                                }
                                val currentModel = prefs.getString(AI_PREF_MODEL_PATH, null)
                                    ?.let { File(it).nameWithoutExtension } ?: "Rakhsha AI"

                                // Note: AiChatScreen needs IdeBackend which is only in MainActivity.
                                // For the standalone activity we use a simplified chat-only screen.
                                SimpleAiChatScreen(
                                    assistant = assistant!!,
                                    modelName = currentModel,
                                    availableModels = availableModels,
                                    onSwitchModel = { path ->
                                        scope.launch {
                                            isLoading = true; status = "Switching model..."
                                            val a = LlamaCppAssistant()
                                            val ok = loadOnThread(a, path)
                                            if (ok) {
                                                prefs.edit().putString(AI_PREF_MODEL_PATH, path).apply()
                                                assistant = a
                                            } else status = "Model failed to load."
                                            isLoading = false
                                        }
                                    },
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }

                            // Loading screen
                            isLoading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    CircularProgressIndicator(modifier = Modifier.padding(8.dp))
                                    Text(status, modifier = Modifier.padding(top = 8.dp))
                                    Text("This may take 30-60 seconds...",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        modifier = Modifier.padding(top = 4.dp))
                                }
                            }

                            // Model selection screen
                            else -> {
                                val savedPath = prefs.getString(AI_PREF_MODEL_PATH, null)
                                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                                    if (savedPath != null && File(savedPath).exists()) {
                                        if (status.isNotBlank()) {
                                            Surface(color = MaterialTheme.colorScheme.surfaceVariant,
                                                shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
                                                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                                                Text(status, modifier = Modifier.padding(12.dp))
                                            }
                                        }
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    isLoading = true; status = "Loading model..."
                                                    val a = LlamaCppAssistant()
                                                    val ok = loadOnThread(a, savedPath)
                                                    if (ok) { assistant = a; status = "" }
                                                    else status = "Failed to load. Try re-downloading."
                                                    isLoading = false
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                        ) { Text("Load: ${File(savedPath).name}") }
                                    }

                                    ModelDownloadScreen(
                                        context = this@AiChatActivity,
                                        onModelReady = { file ->
                                            prefs.edit().putString(AI_PREF_MODEL_PATH, file.absolutePath).apply()
                                            scope.launch {
                                                isLoading = true; status = "Loading model..."
                                                val a = LlamaCppAssistant()
                                                val ok = loadOnThread(a, file.absolutePath)
                                                if (ok) { assistant = a; status = "" }
                                                else status = "Failed to load."
                                                isLoading = false
                                            }
                                        },
                                        onPickFromFiles = { pickModel.launch(arrayOf("application/octet-stream", "*/*")) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private suspend fun loadOnThread(a: LlamaCppAssistant, path: String): Boolean =
        suspendCancellableCoroutine { cont ->
            val t = Thread {
                try {
                    kotlinx.coroutines.runBlocking { a.loadModel(path) }
                    if (cont.isActive) cont.resume(a.isReady) {}
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(false) {}
                }
            }
            t.name = "rakshaai-model-loader"
            t.isDaemon = true
            t.start()
            cont.invokeOnCancellation { t.interrupt() }
        }

    private fun copyToLocal(uri: Uri): File? {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)) else null }
            ?: "model.gguf"
        val outFile = File(filesDir, "models").apply { mkdirs() }.resolve(name)
        return try {
            contentResolver.openInputStream(uri)?.use { inp ->
                FileOutputStream(outFile).use { out -> inp.copyTo(out) }
            }
            outFile
        } catch (e: Exception) { null }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}
