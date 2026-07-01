package dev.ide.ai.online

import dev.ide.ai.AiAssistant
import dev.ide.ai.ChatMessage
import dev.ide.ai.CodeContext
import dev.ide.ai.CodeEdit
import dev.ide.ai.ModelConfig
import dev.ide.analysis.Diagnostic
import dev.ide.build.BuildDiagnostic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/** The cloud chat/completion providers Rakhsha AI's "online mode" can talk to. */
enum class OnlineProvider(val displayName: String) {
    GEMINI("Google Gemini"),
    OPENAI("OpenAI"),
    CLAUDE("Anthropic Claude"),
}

/**
 * The "online mode" [AiAssistant]: sends chat/code requests to a cloud LLM API instead of running
 * inference on-device. Counterpart to [dev.ide.ai.impl.LlamaCppAssistant] (offline mode) — same
 * interface, so the rest of the IDE (the chat screen, the build-error-fix action) doesn't need to know
 * or care which one is active.
 *
 * Unlike the offline engine, there's no "load a model file" step: [loadModel] just validates the API
 * key looks non-empty (the actual key/model-path argument is reused as the API key for simplicity, so
 * the existing AiAssistant.loadModel(path, config) call site in the chat UI doesn't need a second
 * "online vs offline" branch). [unloadModel] is a no-op.
 *
 * Network calls are synchronous (HttpURLConnection, no streaming) and run on [Dispatchers.IO]; the full
 * reply is emitted as a single [Flow] value rather than token-by-token. True server-sent-event streaming
 * differs per provider (Gemini/OpenAI/Claude each have their own SSE framing) — call-and-wait keeps this
 * first cut correct and provider-agnostic. (Worth revisiting per-provider streaming later if the
 * non-streamed latency feels slow for longer replies.)
 */
class OnlineAssistant(private val provider: OnlineProvider) : AiAssistant {

    private var apiKey: String? = null

    override val isReady: Boolean get() = !apiKey.isNullOrBlank()

    override suspend fun loadModel(modelPath: String, config: ModelConfig) {
        require(modelPath.isNotBlank()) { "API key is empty." }
        // Validate the key by sending a minimal test ping (with a short timeout) before
        // marking isReady = true. This surfaces wrong keys or network errors immediately
        // instead of hanging silently when the user tries to chat.
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val testMessages = listOf(ChatMessage(ChatMessage.Role.USER, "hi"))
            val response = when (provider) {
                OnlineProvider.GEMINI -> {
                    val url = java.net.URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$modelPath")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 15_000
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    val body = org.json.JSONObject().put("contents",
                        org.json.JSONArray().put(org.json.JSONObject()
                            .put("role", "user")
                            .put("parts", org.json.JSONArray().put(org.json.JSONObject().put("text", "hi")))))
                    java.io.OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
                    val code = conn.responseCode
                    if (code != 200) {
                        val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
                        throw Exception("Gemini error $code: ${org.json.JSONObject(err).optJSONArray("error")?.optJSONObject(0)?.optString("message") ?: err.take(200)}")
                    }
                    conn.disconnect()
                    true
                }
                OnlineProvider.OPENAI -> {
                    val url = java.net.URL("https://api.openai.com/v1/models")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 10_000; conn.readTimeout = 15_000
                    conn.setRequestProperty("Authorization", "Bearer $modelPath")
                    val code = conn.responseCode
                    if (code != 200) throw Exception("OpenAI error $code — check your API key")
                    conn.disconnect(); true
                }
                OnlineProvider.CLAUDE -> {
                    val url = java.net.URL("https://api.anthropic.com/v1/messages")
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 10_000; conn.readTimeout = 15_000
                    conn.requestMethod = "POST"; conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.setRequestProperty("x-api-key", modelPath)
                    conn.setRequestProperty("anthropic-version", "2023-06-01")
                    val body = org.json.JSONObject().put("model", "claude-3-5-sonnet-20241022")
                        .put("max_tokens", 10)
                        .put("messages", org.json.JSONArray().put(org.json.JSONObject().put("role", "user").put("content", "hi")))
                    java.io.OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
                    val code = conn.responseCode
                    if (code != 200) throw Exception("Claude error $code — check your API key")
                    conn.disconnect(); true
                }
            }
        }
        apiKey = modelPath
    }

    override suspend fun unloadModel() {
        apiKey = null
    }

    override fun chat(history: List<ChatMessage>): Flow<String> = flow {
        val key = apiKey ?: error("No API key set — call loadModel() with the key first.")
        val reply = withContext(Dispatchers.IO) {
            when (provider) {
                OnlineProvider.GEMINI -> callGemini(key, history)
                OnlineProvider.OPENAI -> callOpenAi(key, history)
                OnlineProvider.CLAUDE -> callClaude(key, history)
            }
        }
        emit(reply)
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
        val raw = collectChat(prompt)
        val newContent = extractCodeBlock(raw) ?: raw
        return listOf(CodeEdit(context.filePath, newContent, description = request))
    }

    override suspend fun proposeFixes(
        buildErrors: List<BuildDiagnostic>,
        relatedDiagnostics: List<Diagnostic>,
        readFile: suspend (path: String) -> String?,
    ): List<CodeEdit> {
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
                errors.forEach { d -> appendLine("- line ${d.location?.line ?: "?"}: ${d.message}") }
                appendLine()
                appendLine("Respond with ONLY the complete, corrected file content in a single code block — no explanation.")
            }
            val raw = collectChat(prompt)
            val fixed = extractCodeBlock(raw) ?: return@mapNotNull null
            CodeEdit(path, fixed, description = "Rakhsha AI (${provider.displayName}): fix ${errors.size} build error(s)")
        }
    }

    private suspend fun collectChat(prompt: String): String {
        val sb = StringBuilder()
        chat(listOf(ChatMessage(ChatMessage.Role.USER, prompt))).collect { sb.append(it) }
        return sb.toString()
    }

    // --- Provider-specific HTTP calls -----------------------------------------------------------

    private fun callGemini(key: String, history: List<ChatMessage>): String {
        val contents = JSONArray()
        history.forEach { msg ->
            if (msg.role == ChatMessage.Role.SYSTEM) return@forEach // Gemini takes system text separately; skipped for simplicity
            val role = if (msg.role == ChatMessage.Role.USER) "user" else "model"
            contents.put(JSONObject().put("role", role).put(
                "parts", JSONArray().put(JSONObject().put("text", msg.content))
            ))
        }
        val body = JSONObject().put("contents", contents)

        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$key")
        val responseJson = JSONObject(httpPostJson(url, headers = emptyMap(), body = body))
        return responseJson.getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts").getJSONObject(0).getString("text")
    }

    private fun callOpenAi(key: String, history: List<ChatMessage>): String {
        val messages = JSONArray()
        history.forEach { msg ->
            val role = when (msg.role) {
                ChatMessage.Role.SYSTEM -> "system"
                ChatMessage.Role.USER -> "user"
                ChatMessage.Role.ASSISTANT -> "assistant"
            }
            messages.put(JSONObject().put("role", role).put("content", msg.content))
        }
        val body = JSONObject()
            .put("model", "gpt-4o-mini")
            .put("messages", messages)

        val url = URL("https://api.openai.com/v1/chat/completions")
        val responseJson = JSONObject(httpPostJson(url, headers = mapOf("Authorization" to "Bearer $key"), body = body))
        return responseJson.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
    }

    private fun callClaude(key: String, history: List<ChatMessage>): String {
        val messages = JSONArray()
        var systemPrompt: String? = null
        history.forEach { msg ->
            if (msg.role == ChatMessage.Role.SYSTEM) {
                systemPrompt = msg.content; return@forEach
            }
            val role = if (msg.role == ChatMessage.Role.USER) "user" else "assistant"
            messages.put(JSONObject().put("role", role).put("content", msg.content))
        }
        val body = JSONObject()
            .put("model", "claude-3-5-sonnet-20241022")
            .put("max_tokens", 1024)
            .put("messages", messages)
        systemPrompt?.let { body.put("system", it) }

        val url = URL("https://api.anthropic.com/v1/messages")
        val responseJson = JSONObject(
            httpPostJson(
                url,
                headers = mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01"),
                body = body,
            )
        )
        return responseJson.getJSONArray("content").getJSONObject(0).getString("text")
    }

    /** Minimal synchronous JSON POST. No retry/backoff — a first cut; the caller surfaces failures as exceptions. */
    private fun httpPostJson(url: URL, headers: Map<String, String>, body: JSONObject): String {
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }

            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseText = BufferedReader(stream.reader(Charsets.UTF_8)).use { it.readText() }
            if (status !in 200..299) {
                throw RuntimeException("HTTP $status from ${url.host}: $responseText")
            }
            return responseText
        } finally {
            connection.disconnect()
        }
    }

    private fun extractCodeBlock(text: String): String? {
        val start = text.indexOf("```").takeIf { it >= 0 } ?: return null
        val afterFence = text.indexOf('\n', start).takeIf { it >= 0 } ?: return null
        val end = text.indexOf("```", afterFence).takeIf { it >= 0 } ?: return null
        return text.substring(afterFence + 1, end).trimEnd()
    }
}
