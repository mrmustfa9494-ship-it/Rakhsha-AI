package dev.ide.android

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ide.ai.AiAssistant
import dev.ide.ai.impl.LlamaCppAssistant
import dev.ide.ui.ai.AiChatScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Standalone entry point for the offline AI assistant — kept separate from [MainActivity] (whose
 * Compose tree already boots the full IDE engine) so this can be wired up and tested independently.
 * Launch it from MainActivity (e.g. a toolbar/menu action — not yet added there) or directly via:
 *   adb shell am start -n com.rakshaai.ide/.AiChatActivity
 *
 * Flow: pick a .gguf model file from device storage (SAF) -> copy it into app-private storage (llama.cpp
 * needs a real filesystem path, not a content:// Uri) -> load it -> show [AiChatScreen].
 *
 * TODO (next step): replace the SAF "pick any file" entry point with a proper in-app model download
 * manager (browse/download known GGUF models, e.g. Qwen2.5-Coder-3B-Instruct-Q4_K_M, with progress UI),
 * so the user doesn't have to manually source the file first.
 */
class AiChatActivity : ComponentActivity() {

    private val assistant: AiAssistant = LlamaCppAssistant()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var status by remember { mutableStateOf("No model loaded.") }
                var isReady by remember { mutableStateOf(assistant.isReady) }
                val scope = rememberCoroutineScope()

                val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    scope.launch {
                        status = "Copying model into app storage…"
                        val localFile = withContext(Dispatchers.IO) { copyToLocalFile(uri) }
                        if (localFile == null) {
                            status = "Failed to read the selected file."
                            return@launch
                        }
                        status = "Loading model (this can take 10-30s)…"
                        try {
                            assistant.loadModel(localFile.absolutePath)
                            isReady = assistant.isReady
                            status = if (isReady) "Model loaded — ready to chat." else "Model failed to load."
                        } catch (e: Exception) {
                            status = "Error loading model: ${e.message}"
                            Toast.makeText(this@AiChatActivity, status, Toast.LENGTH_LONG).show()
                        }
                    }
                }

                if (!isReady) {
                    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                        Text("Rakhsha AI — Offline Assistant", style = MaterialTheme.typography.headlineSmall)
                        Surface(modifier = Modifier.padding(top = 12.dp, bottom = 12.dp)) { Text(status) }
                        if (status.startsWith("Loading") || status.startsWith("Copying")) {
                            CircularProgressIndicator()
                        } else {
                            Button(onClick = { pickModel.launch(arrayOf("application/octet-stream", "*/*")) }) {
                                Text("Pick a .gguf model file")
                            }
                        }
                    }
                } else {
                    AiChatScreen(assistant = assistant)
                }
            }
        }
    }

    /** llama.cpp's loader needs a real filesystem path, so SAF content:// Uris are copied in first. */
    private fun copyToLocalFile(uri: Uri): File? {
        val name = queryDisplayName(uri) ?: "model.gguf"
        val outFile = File(filesDir, "models").apply { mkdirs() }.resolve(name)
        return try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outFile).use { output -> input.copyTo(output) }
            }
            outFile
        } catch (e: Exception) {
            null
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        // Free native memory promptly — a loaded GGUF model can be 1-4GB resident.
        if (assistant.isReady) {
            kotlinx.coroutines.runBlocking { assistant.unloadModel() }
        }
    }
}
