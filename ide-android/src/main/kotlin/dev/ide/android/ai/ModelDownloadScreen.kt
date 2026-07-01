package dev.ide.android.ai

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Where all downloaded GGUF models are stored inside the app's private files directory. */
fun modelsDir(context: Context): File = File(context.filesDir, "models").apply { mkdirs() }

private enum class DownloadState { IDLE, DOWNLOADING, ERROR }

/**
 * MNN-Chat-style model browser: shows [RECOMMENDED_MODELS] with download/installed status,
 * a progress bar while downloading, and an option to pick a locally-existing .gguf file.
 *
 * Downloads go into [modelsDir] — the same folder [dev.ide.android.RakshaAiAssistantOverlay] reads
 * from — so a freshly-downloaded model immediately appears in the "load model" screen without the
 * user having to navigate anywhere.
 */
@Composable
fun ModelDownloadScreen(
    context: Context,
    onModelReady: (File) -> Unit,  // called when user picks or finishes downloading a model
    onPickFromFiles: () -> Unit,   // opens SAF file picker for an existing .gguf
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val downloadStates = remember { mutableStateMapOf<String, DownloadState>() }
    val downloadProgress = remember { mutableStateMapOf<String, Float>() }
    val errorMessages = remember { mutableStateMapOf<String, String>() }

    // Check which models are already downloaded
    val installedFiles = remember { mutableStateMapOf<String, File>() }
    RECOMMENDED_MODELS.forEach { model ->
        val f = File(modelsDir(context), model.fileName)
        if (f.exists()) installedFiles[model.id] = f
    }

    Column(modifier = modifier.fillMaxSize()) {
        Text("Models", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(RECOMMENDED_MODELS) { model ->
                val state = downloadStates[model.id] ?: DownloadState.IDLE
                val progress = downloadProgress[model.id] ?: 0f
                val installed = installedFiles.containsKey(model.id)

                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(model.name, style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    // Tag chip
                                    Surface(shape = RoundedCornerShape(4.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer) {
                                        Text(model.tag, style = MaterialTheme.typography.labelSmall,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                                    }
                                    if (installed) {
                                        Surface(shape = RoundedCornerShape(4.dp),
                                            color = Color(0xFF2E7D32)) {
                                            Text("Installed", style = MaterialTheme.typography.labelSmall,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                                color = Color.White)
                                        }
                                    }
                                }
                                Text(model.description, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                    modifier = Modifier.padding(top = 4.dp))
                                Row(modifier = Modifier.padding(top = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(model.sizeLabel, style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary)
                                    Text(model.ramRequired, style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                                }
                            }
                        }

                        // Progress bar while downloading
                        if (state == DownloadState.DOWNLOADING) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                            )
                            Text("${(progress * 100).toInt()}% — ${model.sizeLabel}",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 4.dp))
                        }

                        if (state == DownloadState.ERROR) {
                            Text(errorMessages[model.id] ?: "Download failed",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 4.dp))
                        }

                        Row(modifier = Modifier.padding(top = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when {
                                state == DownloadState.DOWNLOADING -> {
                                    OutlinedButton(onClick = {}, enabled = false,
                                        modifier = Modifier.fillMaxWidth()) { Text("Downloading...") }
                                }
                                installed -> {
                                    // Already downloaded — one tap loads it directly
                                    Button(
                                        onClick = { onModelReady(installedFiles[model.id]!!) },
                                        modifier = Modifier.weight(1f),
                                    ) { Text("Load Now") }
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                downloadModel(context, model, downloadStates,
                                                    downloadProgress, errorMessages, installedFiles, onModelReady)
                                            }
                                        },
                                        modifier = Modifier.weight(1f),
                                    ) { Text("Re-download") }
                                }
                                else -> {
                                    Button(
                                        onClick = {
                                            scope.launch {
                                                downloadModel(context, model, downloadStates,
                                                    downloadProgress, errorMessages, installedFiles, onModelReady)
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text("Download ${model.sizeLabel}") }
                                }
                            }
                        }
                    }
                }
            }

            item {
                OutlinedButton(
                    onClick = onPickFromFiles,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                ) { Text("Pick an existing .gguf file from storage") }
            }
        }
    }
}

private suspend fun downloadModel(
    context: Context,
    model: ModelInfo,
    states: MutableMap<String, DownloadState>,
    progress: MutableMap<String, Float>,
    errors: MutableMap<String, String>,
    installed: MutableMap<String, File>,
    onModelReady: (File) -> Unit,
) {
    states[model.id] = DownloadState.DOWNLOADING
    progress[model.id] = 0f
    val outFile = File(modelsDir(context), model.fileName)
    try {
        withContext(Dispatchers.IO) {
            val conn = URL(model.downloadUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 30_000
            conn.readTimeout = 60_000
            conn.connect()
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: -1L
            conn.inputStream.use { inp ->
                outFile.outputStream().use { out ->
                    val buf = ByteArray(8192)
                    var downloaded = 0L
                    var n: Int
                    while (inp.read(buf).also { n = it } >= 0) {
                        out.write(buf, 0, n)
                        downloaded += n
                        if (total > 0) progress[model.id] = downloaded.toFloat() / total
                    }
                }
            }
        }
        installed[model.id] = outFile
        states[model.id] = DownloadState.IDLE // reset so button shows "Load" (via installed check)
        onModelReady(outFile) // auto-loads the model immediately after download
    } catch (e: Exception) {
        states[model.id] = DownloadState.ERROR
        errors[model.id] = "Failed: ${e.message}"
        if (outFile.exists()) outFile.delete()
    }
}
