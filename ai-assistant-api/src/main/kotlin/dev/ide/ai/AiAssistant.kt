package dev.ide.ai

import dev.ide.analysis.Diagnostic
import dev.ide.build.BuildDiagnostic
import kotlinx.coroutines.flow.Flow

/**
 * The SPI for the offline, on-device AI coding assistant ("Rakhsha AI"). An [AiAssistant] wraps a
 * local LLM (served by ai-assistant-impl over a llama.cpp JNI bridge) and exposes three things the
 * IDE shell uses: free-form chat, "write/modify this code", and "fix these errors" — all running with
 * no network call, entirely on-device.
 */
interface AiAssistant {

    /** Whether a model is currently loaded and ready to answer. */
    val isReady: Boolean

    /** Load a model from [modelPath] (a local .gguf file) into memory. Heavy — call off the main thread. */
    suspend fun loadModel(modelPath: String, config: ModelConfig = ModelConfig())

    /** Release the loaded model and free native memory. */
    suspend fun unloadModel()

    /**
     * Free-form chat turn. Streams tokens as they're generated so the UI can render incrementally,
     * the way a chat app does, instead of blocking until the full reply is ready.
     */
    fun chat(history: List<ChatMessage>): Flow<String>

    /**
     * Ask the model to write or modify code for [request] given the surrounding [context] (the open
     * file's content, language, and any selection). Returns one or more proposed [CodeEdit]s the
     * caller applies to the editor/VFS — the model never writes files directly.
     */
    suspend fun generateCode(request: String, context: CodeContext): List<CodeEdit>

    /**
     * The "auto-fix build errors" entry point: given the [BuildDiagnostic]s from a failed build (and
     * optionally [Diagnostic]s from the live analysis pass for the same files), ask the model to
     * propose concrete [CodeEdit]s that resolve them. Diagnostics carry file + line + message, so the
     * model gets the same structured context a human would read off the Problems panel — not a raw
     * log dump.
     */
    suspend fun proposeFixes(
        buildErrors: List<BuildDiagnostic>,
        relatedDiagnostics: List<Diagnostic> = emptyList(),
        readFile: suspend (path: String) -> String?,
    ): List<CodeEdit>
}

data class ModelConfig(
    /** Context window in tokens. Larger = more file context fits, but more RAM. */
    val contextSize: Int = 4096,
    /** CPU threads for inference. 4 is a safe default for mid-range phones. */
    val threads: Int = 4,
    /** Layers offloaded to GPU via the device's OpenCL/Vulkan backend, if llama.cpp was built with it. 0 = CPU-only. */
    val gpuLayers: Int = 0,
    val temperature: Float = 0.2f, // low temperature: deterministic, code-focused completions
)

data class ChatMessage(val role: Role, val content: String) {
    enum class Role { SYSTEM, USER, ASSISTANT }
}

data class CodeContext(
    val filePath: String,
    val language: String,
    val fullText: String,
    val selectionStart: Int? = null,
    val selectionEnd: Int? = null,
)

/** A single proposed change to one file, in the same shape the editor/VFS write path expects. */
data class CodeEdit(
    val filePath: String,
    val newContent: String,
    val description: String,
)
