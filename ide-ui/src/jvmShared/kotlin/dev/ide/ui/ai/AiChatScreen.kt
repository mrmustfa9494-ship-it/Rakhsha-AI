package dev.ide.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ai.AiAssistant
import dev.ide.ai.ChatMessage
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiSeverity
import kotlinx.coroutines.launch

/**
 * The "Rakhsha AI" chat panel — embedded directly inside the IDE's own window (not a separate screen),
 * with the [backend] it needs to act on the real project: writing AI-generated code into a file
 * ([IdeBackend.editor]), and reading live build errors ([IdeBackend.build]) to offer an AI fix.
 *
 * Every AI reply that contains a fenced code block gets an inline "Insert into project" action next to
 * it — the user reviews the code, picks/confirms the target file path, and one tap writes it via
 * [IdeBackend.editor.saveFile] (which both persists to disk and updates the live editor buffer, so an
 * already-open tab picks up the change immediately).
 */
@Composable
fun AiChatScreen(
    assistant: AiAssistant,
    backend: IdeBackend,
    modifier: Modifier = Modifier,
    initialMessages: List<ChatMessage> = emptyList(),
    /** Called after every change to the conversation (new message, streamed update) so the host can
     *  persist it — e.g. to a file — and restore it next time the app/assistant is opened. */
    onMessagesChanged: (List<ChatMessage>) -> Unit = {},
) {
    val messages = remember { mutableStateListOf<ChatMessage>().apply { addAll(initialMessages) } }
    var input by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var targetFilePath by remember { mutableStateOf(backend.project.rootPath.trimEnd('/') + "/") }
    val scope = rememberCoroutineScope()

    fun send(prompt: String) {
        val userMsg = ChatMessage(ChatMessage.Role.USER, prompt)
        messages.add(userMsg)
        onMessagesChanged(messages.toList())
        scope.launch {
            isGenerating = true
            val replyIndex = messages.size
            messages.add(ChatMessage(ChatMessage.Role.ASSISTANT, ""))
            assistant.chat(messages.toList()).collect { token ->
                val current = messages[replyIndex]
                messages[replyIndex] = current.copy(content = current.content + token)
            }
            isGenerating = false
            onMessagesChanged(messages.toList())
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding() // keep content clear of the status bar / nav bar (forced edge-to-edge on API 35+)
            .imePadding() // push the input row above the on-screen keyboard instead of letting it cover it
            .padding(12.dp),
    ) {
        if (!assistant.isReady) {
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Text(
                    "Model not connected.",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }

        // Where AI-generated code lands when "Insert" is tapped. Pre-filled with the project root; the
        // user edits it to the actual target (e.g. .../app/src/main/.../Foo.kt). A future improvement
        // is defaulting this to whichever file is open in the editor tab the user last had focused.
        OutlinedTextField(
            value = targetFilePath,
            onValueChange = { targetFilePath = it },
            label = { Text("Insert target file") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        )

        Button(
            enabled = assistant.isReady && !isGenerating,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            onClick = {
                scope.launch {
                    isGenerating = true
                    val diagnostics = backend.build.buildState.value.diagnostics
                        .filter { it.severity == UiSeverity.Error }
                    if (diagnostics.isEmpty()) {
                        messages.add(ChatMessage(ChatMessage.Role.ASSISTANT, "No build errors right now — run a build first if you want me to check."))
                        isGenerating = false
                        return@launch
                    }
                    val byFile = diagnostics.filter { it.file != null }.groupBy { it.file!! }
                    val summary = buildString {
                        appendLine("Found ${diagnostics.size} build error(s) across ${byFile.size} file(s):")
                        byFile.forEach { (file, ds) -> appendLine("- $file: ${ds.size} error(s)") }
                        appendLine()
                        appendLine("Ask me to fix a specific file and I'll propose the corrected content with an Insert button.")
                    }
                    messages.add(ChatMessage(ChatMessage.Role.ASSISTANT, summary))
                    isGenerating = false
                }
            },
        ) { Text("Check build errors") }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages) { msg ->
                ChatBubble(
                    msg = msg,
                    onInsert = { code -> backend.editor.saveFile(targetFilePath, code) },
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask Rakhsha AI to write or fix code…") },
                enabled = assistant.isReady && !isGenerating,
            )
            if (isGenerating) {
                CircularProgressIndicator(modifier = Modifier.padding(8.dp))
            } else {
                Button(
                    enabled = assistant.isReady && input.isNotBlank(),
                    onClick = {
                        val prompt = input
                        input = ""
                        send(prompt)
                    },
                ) { Text("Send") }
            }
        }
    }
}

@Composable
private fun ChatBubble(msg: ChatMessage, onInsert: (String) -> Unit) {
    val isUser = msg.role == ChatMessage.Role.USER
    // Theme-aware: follows the app's current light/dark MaterialTheme instead of a fixed light bubble,
    // so dark mode shows light text on a dark bubble (and vice versa) rather than dark-on-dark.
    val bubbleColor = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(color = bubbleColor, shape = RoundedCornerShape(8.dp)) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(
                text = "${if (isUser) "You" else "Rakhsha AI"}:",
                color = textColor,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelMedium,
            )

            val codeBlock = extractCodeBlock(msg.content)
            if (codeBlock != null) {
                val before = msg.content.substringBefore("```").trim()
                if (before.isNotBlank()) {
                    Text(
                        before,
                        color = textColor,
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) {
                    Text(
                        codeBlock,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(8.dp),
                    )
                }
                TextButton(onClick = { onInsert(codeBlock) }, modifier = Modifier.padding(top = 4.dp)) {
                    Text("Insert into project")
                }
            } else {
                Text(
                    msg.content,
                    color = textColor,
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

private fun extractCodeBlock(text: String): String? {
    val start = text.indexOf("```").takeIf { it >= 0 } ?: return null
    val afterFence = text.indexOf('\n', start).takeIf { it >= 0 } ?: return null
    val end = text.indexOf("```", afterFence).takeIf { it >= 0 } ?: return null
    return text.substring(afterFence + 1, end).trimEnd()
}
