package dev.ide.ai.impl

import dev.ide.ai.AiAssistant
import dev.ide.ai.ChatMessage
import dev.ide.ai.CodeContext
import dev.ide.ai.CodeEdit
import dev.ide.ai.ModelConfig
import dev.ide.analysis.Diagnostic
import dev.ide.build.BuildDiagnostic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * The default [AiAssistant], backed entirely by an on-device llama.cpp model — no network call is ever
 * made by this class. Prompt formatting follows the Qwen2.5-Coder chat template (ChatML-style); if a
 * different base model is swapped in later, [buildChatPrompt] is the one place to adjust.
 */
class LlamaCppAssistant : AiAssistant {

    private val bridge = LlamaBridge()
    private var loaded = false

    override val isReady: Boolean get() = loaded

    override suspend fun loadModel(modelPath: String, config: ModelConfig) {
        withContext(Dispatchers.IO) {
            loaded = bridge.nativeLoadModel(modelPath, config.contextSize, config.threads, config.gpuLayers)
            if (!loaded) error("Failed to load model at $modelPath — check the file is a valid .gguf and not truncated/corrupt.")
        }
        this.config = config
    }

    override suspend fun unloadModel() {
        withContext(Dispatchers.IO) { bridge.nativeUnloadModel() }
        loaded = false
    }

    private var config: ModelConfig = ModelConfig()

    override fun chat(history: List<ChatMessage>): Flow<String> = callbackFlow {
        check(loaded) { "No model loaded — call loadModel() first." }
        val prompt = buildChatPrompt(history)

        // nativeGenerate blocks the calling thread until done/cancelled, so run it on Dispatchers.IO
        // and stream pieces back into the Flow via trySend — this is the standard callbackFlow bridge
        // pattern for a synchronous, callback-driven native API.
        withContext(Dispatchers.IO) {
            bridge.nativeGenerate(prompt, maxTokens = 1024, temperature = config.temperature) { token ->
                val sendResult = trySend(token)
                sendResult.isSuccess // false (stop) only if the Flow's collector cancelled
            }
        }
        close()
        awaitClose { /* native generation already finished or was stopped via the onToken return value */ }
    }.flowOn(Dispatchers.IO)

    override suspend fun generateCode(request: String, context: CodeContext): List<CodeEdit> {
        val prompt = buildString {
            appendLine("You are an expert ${context.language} programmer working inside an IDE.")
            appendLine("File: ${context.filePath}")
            appendLine("Current content:")
            appendLine("```${context.language}")
            appendLine(context.fullText)
            appendLine("```")
            appendLine()
            appendLine("Task: $request")
            appendLine()
            appendLine("Respond with ONLY the complete, updated file content in a single code block — no explanation.")
        }
        val raw = collectChat(listOf(ChatMessage(ChatMessage.Role.USER, prompt)))
        val newContent = extractCodeBlock(raw) ?: raw
        return listOf(CodeEdit(context.filePath, newContent, description = request))
    }

    override suspend fun proposeFixes(
        buildErrors: List<BuildDiagnostic>,
        relatedDiagnostics: List<Diagnostic>,
        readFile: suspend (path: String) -> String?,
    ): List<CodeEdit> {
        // Group errors by file so the model gets one prompt per file, with that file's full source —
        // a model can't fix what it can't see, and dumping every error from every file into one prompt
        // would blow the context window on anything but a one-line fix.
        val byFile = buildErrors.mapNotNull { d -> d.location?.path?.let { it to d } }.groupBy({ it.first }, { it.second })

        return byFile.mapNotNull { (path, errors) ->
            val source = readFile(path) ?: return@mapNotNull null
            val prompt = buildString {
                appendLine("You are an expert programmer fixing a broken build inside an IDE.")
                appendLine("File: $path")
                appendLine("Current content:")
                appendLine("```")
                appendLine(source)
                appendLine("```")
                appendLine()
                appendLine("Build errors in this file:")
                errors.forEach { d ->
                    appendLine("- line ${d.location?.line ?: "?"}: ${d.message}")
                }
                appendLine()
                appendLine("Respond with ONLY the complete, corrected file content in a single code block — no explanation.")
            }
            val raw = collectChat(listOf(ChatMessage(ChatMessage.Role.USER, prompt)))
            val fixed = extractCodeBlock(raw) ?: return@mapNotNull null
            CodeEdit(path, fixed, description = "Rakhsha AI: fix ${errors.size} build error(s)")
        }
    }

    private suspend fun collectChat(history: List<ChatMessage>): String {
        val sb = StringBuilder()
        chat(history).collect { sb.append(it) }
        return sb.toString()
    }

    private fun buildChatPrompt(history: List<ChatMessage>): String = buildString {
        history.forEach { msg ->
            val role = when (msg.role) {
                ChatMessage.Role.SYSTEM -> "system"
                ChatMessage.Role.USER -> "user"
                ChatMessage.Role.ASSISTANT -> "assistant"
            }
            append("<|im_start|>").append(role).append('\n')
            append(msg.content)
            append("<|im_end|>\n")
        }
        append("<|im_start|>assistant\n")
    }

    private fun extractCodeBlock(text: String): String? {
        val start = text.indexOf("```").takeIf { it >= 0 } ?: return null
        val afterFence = text.indexOf('\n', start).takeIf { it >= 0 } ?: return null
        val end = text.indexOf("```", afterFence).takeIf { it >= 0 } ?: return null
        return text.substring(afterFence + 1, end).trimEnd()
    }
}
