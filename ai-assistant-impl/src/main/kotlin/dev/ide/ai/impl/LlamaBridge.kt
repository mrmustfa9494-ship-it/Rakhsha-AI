package dev.ide.ai.impl

/** Thin Kotlin-side declaration of the native functions implemented in jni_bridge.cpp. No logic here. */
internal class LlamaBridge {
    companion object {
        init {
            System.loadLibrary("rakshaai_llama_jni")
        }
    }

    external fun nativeLoadModel(modelPath: String, contextSize: Int, threads: Int, gpuLayers: Int): Boolean
    external fun nativeUnloadModel()

    /**
     * Runs generation synchronously on the calling thread, invoking [onToken] per token. [onToken]
     * returns `true` to continue, `false` to stop early (cancellation). Call off the main thread.
     */
    external fun nativeGenerate(prompt: String, maxTokens: Int, temperature: Float, onToken: (String) -> Boolean)
}
