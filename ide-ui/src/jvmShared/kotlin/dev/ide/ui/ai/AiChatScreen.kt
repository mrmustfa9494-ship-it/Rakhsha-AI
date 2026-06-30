package dev.ide.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.ide.ai.AiAssistant
import dev.ide.ai.ChatMessage
import kotlinx.coroutines.launch

/**
 * The "Rakhsha AI" chat panel: a minimal offline chat UI over [assistant]. Lives in commonMain so it
 * renders identically on the Android and desktop launchers, same as the rest of [dev.ide.ui.RakshaAiApp].
 *
 * This is intentionally a thin first cut — message list + input box + streaming reply. The
 * "write code" / "fix build errors" entry points in [AiAssistant] are wired from elsewhere in the IDE
 * (the editor's context menu / the Problems panel's "Fix with AI" action), not from this screen.
 */
@Composable
fun AiChatScreen(assistant: AiAssistant, modifier: Modifier = Modifier) {
    val messages = remember { mutableStateListOf<ChatMessage>() }
    var input by remember { mutableStateOf("") }
    var isGenerating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxSize().padding(12.dp)) {
        if (!assistant.isReady) {
            Surface { Text("Model not loaded. Download a .gguf model in Settings → Rakhsha AI to start chatting offline.") }
        }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages) { msg ->
                Surface(tonalElevation = if (msg.role == ChatMessage.Role.USER) 0.dp else 2.dp) {
                    Text(
                        text = "${if (msg.role == ChatMessage.Role.USER) "You" else "Rakhsha AI"}: ${msg.content}",
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask Rakhsha AI…") },
                enabled = assistant.isReady && !isGenerating,
            )
            if (isGenerating) {
                CircularProgressIndicator(modifier = Modifier.padding(8.dp))
            } else {
                Button(
                    enabled = assistant.isReady && input.isNotBlank(),
                    onClick = {
                        val userMsg = ChatMessage(ChatMessage.Role.USER, input)
                        messages.add(userMsg)
                        input = ""
                        scope.launch {
                            isGenerating = true
                            val replyIndex = messages.size
                            messages.add(ChatMessage(ChatMessage.Role.ASSISTANT, ""))
                            assistant.chat(messages.toList()).collect { token ->
                                val current = messages[replyIndex]
                                messages[replyIndex] = current.copy(content = current.content + token)
                            }
                            isGenerating = false
                        }
                    },
                ) { Text("Send") }
            }
        }
    }
}
