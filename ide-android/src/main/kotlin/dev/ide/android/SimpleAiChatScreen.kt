package dev.ide.android

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
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
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class Msg(val msg: ChatMessage, val ts: Long = System.currentTimeMillis())

@Composable
fun SimpleAiChatScreen(
    assistant: AiAssistant,
    modelName: String = "Rakhsha AI",
    availableModels: List<Pair<String, String>> = emptyList(),
    onSwitchModel: (path: String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val messages = remember { mutableStateListOf<Msg>() }
    var input by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    var showModelMenu by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    fun send(prompt: String) {
        messages.add(Msg(ChatMessage(ChatMessage.Role.USER, prompt)))
        scope.launch {
            isGenerating = true
            val idx = messages.size
            messages.add(Msg(ChatMessage(ChatMessage.Role.ASSISTANT, "")))
            assistant.chat(messages.map { it.msg }).collect { token ->
                val cur = messages[idx]
                messages[idx] = cur.copy(msg = cur.msg.copy(content = cur.msg.content + token))
            }
            isGenerating = false
        }
    }

    Column(modifier = modifier.fillMaxSize().imePadding()) {
        // Model name header with dropdown
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TextButton(onClick = { if (availableModels.isNotEmpty()) showModelMenu = true }) {
                Text(modelName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (availableModels.isNotEmpty()) Text(" ▾", color = MaterialTheme.colorScheme.primary)
            }
            DropdownMenu(expanded = showModelMenu, onDismissRequest = { showModelMenu = false }) {
                availableModels.forEach { (path, name) ->
                    DropdownMenuItem(
                        text = { Text(name, fontWeight = if (name == modelName) FontWeight.Bold else FontWeight.Normal) },
                        onClick = { onSwitchModel(path); showModelMenu = false },
                    )
                }
            }
        }
        HorizontalDivider()

        LazyColumn(state = listState, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item { Spacer(Modifier.height(8.dp)) }
            items(messages) { m -> ChatBubble(m) }
            if (isGenerating) {
                item {
                    Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Avatar("R", MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }

        HorizontalDivider()
        Row(modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input, onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask Rakhsha AI...") },
                enabled = !isGenerating,
                shape = RoundedCornerShape(24.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                ),
            )
            Button(
                onClick = { val p = input.trim(); if (p.isNotEmpty()) { input = ""; send(p) } },
                enabled = input.isNotBlank() && !isGenerating,
                shape = CircleShape,
                modifier = Modifier.size(48.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) { Text("→") }
        }
    }
}

@Composable
private fun ChatBubble(m: Msg) {
    val isUser = m.msg.role == ChatMessage.Role.USER
    val time = remember(m.ts) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.ts)) }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
        verticalAlignment = Alignment.Bottom) {
        if (!isUser) { Avatar("R", MaterialTheme.colorScheme.primary); Spacer(Modifier.width(6.dp)) }
        Column(modifier = Modifier.widthIn(max = 300.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
            val code = extractCode(m.msg.content)
            if (code != null) {
                val before = m.msg.content.substringBefore("```").trim()
                if (before.isNotBlank()) Bubble(before, isUser)
                Spacer(Modifier.height(4.dp))
                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(12.dp), tonalElevation = 2.dp) {
                    Text(code, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            } else Bubble(m.msg.content, isUser)
            Text(time, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
        }
        if (isUser) { Spacer(Modifier.width(6.dp)); Avatar("U", MaterialTheme.colorScheme.tertiary) }
    }
}

@Composable
private fun Bubble(text: String, isUser: Boolean) {
    Surface(
        color = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp,
            bottomStart = if (isUser) 16.dp else 4.dp, bottomEnd = if (isUser) 4.dp else 16.dp),
    ) {
        Text(text, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            color = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Avatar(letter: String, color: androidx.compose.ui.graphics.Color) {
    Box(modifier = Modifier.size(28.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
        Text(letter, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.surface)
    }
}

private fun extractCode(text: String): String? {
    val s = text.indexOf("```").takeIf { it >= 0 } ?: return null
    val a = text.indexOf('\n', s).takeIf { it >= 0 } ?: return null
    val e = text.indexOf("```", a).takeIf { it >= 0 } ?: return null
    return text.substring(a + 1, e).trimEnd()
}
