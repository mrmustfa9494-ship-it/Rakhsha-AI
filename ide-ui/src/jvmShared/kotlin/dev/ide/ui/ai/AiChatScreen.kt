package dev.ide.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.ide.ai.AiAssistant
import dev.ide.ai.ChatMessage
import dev.ide.ui.backend.IdeBackend
import dev.ide.ui.backend.UiSeverity
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class TimestampedMessage(val msg: ChatMessage, val timeMs: Long = System.currentTimeMillis())

@Composable
fun AiChatScreen(
    assistant: AiAssistant,
    backend: IdeBackend,
    modifier: Modifier = Modifier,
    initialMessages: List<ChatMessage> = emptyList(),
    onMessagesChanged: (List<ChatMessage>) -> Unit = {},
    /** Display name of the currently active model shown in the header. */
    modelName: String = "Rakhsha AI",
    /** List of (id, displayName) pairs for the model-switch dropdown. Empty = no dropdown shown. */
    availableModels: List<Pair<String, String>> = emptyList(),
    /** Called when user picks a different model from the dropdown. */
    onSwitchModel: (id: String) -> Unit = {},
) {
    val messages = remember {
        mutableStateListOf<TimestampedMessage>().apply {
            addAll(initialMessages.map { TimestampedMessage(it) })
        }
    }
    var input by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var targetFilePath by remember { mutableStateOf(backend.project.rootPath.trimEnd('/') + "/") }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Auto-scroll to bottom when new message arrives
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun send(prompt: String) {
        messages.add(TimestampedMessage(ChatMessage(ChatMessage.Role.USER, prompt)))
        onMessagesChanged(messages.map { it.msg })
        scope.launch {
            isGenerating = true
            val replyIndex = messages.size
            messages.add(TimestampedMessage(ChatMessage(ChatMessage.Role.ASSISTANT, "")))
            assistant.chat(messages.map { it.msg }).collect { token ->
                val current = messages[replyIndex]
                messages[replyIndex] = current.copy(msg = current.msg.copy(content = current.msg.content + token))
            }
            isGenerating = false
            onMessagesChanged(messages.map { it.msg })
        }
    }

    var showModelMenu by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding(),
    ) {
        // ── Header: model name + dropdown (MNN Chat style) ────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Box {
                TextButton(onClick = { if (availableModels.isNotEmpty()) showModelMenu = true }) {
                    Text(modelName, style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface)
                    if (availableModels.isNotEmpty()) {
                        Text(" ▾", style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
                DropdownMenu(expanded = showModelMenu, onDismissRequest = { showModelMenu = false }) {
                    availableModels.forEach { (id, name) ->
                        DropdownMenuItem(
                            text = {
                                Text(name, fontWeight = if (name == modelName) FontWeight.Bold else FontWeight.Normal)
                            },
                            onClick = { onSwitchModel(id); showModelMenu = false },
                        )
                    }
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Target file bar
        OutlinedTextField(
            value = targetFilePath,
            onValueChange = { targetFilePath = it },
            label = { Text("Insert target file", style = MaterialTheme.typography.labelSmall) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
            ),
        )

        // Check build errors button
        TextButton(
            onClick = {
                scope.launch {
                    isGenerating = true
                    val errors = backend.build.buildState.value.diagnostics
                        .filter { it.severity == UiSeverity.Error }
                    val reply = if (errors.isEmpty()) {
                        "No build errors right now. Run a build first to check for errors."
                    } else {
                        val byFile = errors.filter { it.file != null }.groupBy { it.file!! }
                        buildString {
                            appendLine("Found **${errors.size} build error(s)** across ${byFile.size} file(s):")
                            byFile.forEach { (file, ds) ->
                                appendLine()
                                appendLine("**${file.substringAfterLast('/')}** (${ds.size} error(s)):")
                                ds.take(3).forEach { d -> appendLine("• Line ${d.line}: ${d.message}") }
                                if (ds.size > 3) appendLine("• ...and ${ds.size - 3} more")
                            }
                            appendLine()
                            appendLine("Tell me which file to fix and I'll write the corrected code.")
                        }
                    }
                    messages.add(TimestampedMessage(ChatMessage(ChatMessage.Role.ASSISTANT, reply)))
                    isGenerating = false
                    onMessagesChanged(messages.map { it.msg })
                }
            },
            enabled = !isGenerating,
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Text("Check build errors", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Messages
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { Spacer(Modifier.height(8.dp)) }
            items(messages) { tm ->
                MessageBubble(
                    tm = tm,
                    onInsert = { code ->
                        scope.launch {
                            backend.editor.saveFile(targetFilePath, code)
                        }
                    }
                )
            }
            if (isGenerating) {
                item {
                    Row(modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AiAvatar()
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        // Input bar — modern style like a chat app
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text("Ask Rakhsha AI...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                },
                enabled = assistant.isReady && !isGenerating,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                ),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            )
            Button(
                onClick = {
                    val prompt = input.trim()
                    if (prompt.isNotEmpty()) { input = ""; send(prompt) }
                },
                enabled = assistant.isReady && input.isNotBlank() && !isGenerating,
                shape = CircleShape,
                modifier = Modifier.size(48.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                Text("→", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun MessageBubble(tm: TimestampedMessage, onInsert: (String) -> Unit) {
    val msg = tm.msg
    val isUser = msg.role == ChatMessage.Role.USER
    val timeStr = remember(tm.timeMs) {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(tm.timeMs))
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom,
    ) {
        if (!isUser) {
            AiAvatar()
            Spacer(Modifier.width(6.dp))
        }

        Column(
            modifier = Modifier.widthIn(max = 300.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            val codeBlock = extractCodeBlock(msg.content)
            if (codeBlock != null) {
                // Text before code
                val before = msg.content.substringBefore("```").trim()
                if (before.isNotBlank()) {
                    BubbleSurface(isUser = isUser) {
                        Text(before, style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (isUser) MaterialTheme.colorScheme.onPrimary
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(4.dp))
                }
                // Code block
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 2.dp,
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Code", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary)
                            TextButton(onClick = { onInsert(codeBlock) }) {
                                Text("Insert into project", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(
                            codeBlock,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            } else {
                BubbleSurface(isUser = isUser) {
                    Text(
                        msg.content,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (isUser) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Timestamp
            Text(
                timeStr,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }

        if (isUser) {
            Spacer(Modifier.width(6.dp))
            UserAvatar()
        }
    }
}

@Composable
private fun BubbleSurface(isUser: Boolean, content: @Composable () -> Unit) {
    Surface(
        color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(
            topStart = 16.dp, topEnd = 16.dp,
            bottomStart = if (isUser) 16.dp else 4.dp,
            bottomEnd = if (isUser) 4.dp else 16.dp,
        ),
    ) {
        Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) { content() }
    }
}

@Composable
private fun AiAvatar() {
    Box(
        modifier = Modifier.size(28.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text("R", style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
    }
}

@Composable
private fun UserAvatar() {
    Box(
        modifier = Modifier.size(28.dp).clip(CircleShape)
            .background(MaterialTheme.colorScheme.tertiary),
        contentAlignment = Alignment.Center,
    ) {
        Text("U", style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onTertiary)
    }
}

private fun extractCodeBlock(text: String): String? {
    val start = text.indexOf("```").takeIf { it >= 0 } ?: return null
    val afterFence = text.indexOf('\n', start).takeIf { it >= 0 } ?: return null
    val end = text.indexOf("```", afterFence).takeIf { it >= 0 } ?: return null
    return text.substring(afterFence + 1, end).trimEnd()
}
