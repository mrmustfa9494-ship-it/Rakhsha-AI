package dev.ide.android.ai

/** A pre-vetted model the "Browse models" screen shows. All are GGUF format, tested on Android. */
data class ModelInfo(
    val id: String,
    val name: String,
    val description: String,
    val sizeLabel: String,  // e.g. "1.9 GB"
    val ramRequired: String, // e.g. "3 GB RAM"
    val tag: String,         // e.g. "Coding", "General", "Fast"
    val downloadUrl: String, // direct GGUF download URL from HuggingFace
    val fileName: String,    // how to save it locally
)

val RECOMMENDED_MODELS = listOf(
    ModelInfo(
        id = "qwen25-coder-3b",
        name = "Qwen2.5-Coder 3B",
        description = "Best for coding tasks — writes & fixes code, trained specifically on programming.",
        sizeLabel = "1.9 GB",
        ramRequired = "3 GB RAM",
        tag = "Coding",
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-3B-Instruct-GGUF/resolve/main/qwen2.5-coder-3b-instruct-q4_k_m.gguf",
        fileName = "qwen2.5-coder-3b-instruct-q4_k_m.gguf",
    ),
    ModelInfo(
        id = "qwen25-coder-1.5b",
        name = "Qwen2.5-Coder 1.5B",
        description = "Smaller coding model — faster on older phones, still great for code completion.",
        sizeLabel = "1.0 GB",
        ramRequired = "2 GB RAM",
        tag = "Coding · Fast",
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B-Instruct-GGUF/resolve/main/qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
        fileName = "qwen2.5-coder-1.5b-instruct-q4_k_m.gguf",
    ),
    ModelInfo(
        id = "qwen25-3b",
        name = "Qwen2.5 3B",
        description = "General-purpose assistant — good for chat, explanation, and non-coding tasks.",
        sizeLabel = "2.1 GB",
        ramRequired = "3 GB RAM",
        tag = "General",
        downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
        fileName = "qwen2.5-3b-instruct-q4_k_m.gguf",
    ),
    ModelInfo(
        id = "deepseek-r1-1.5b",
        name = "DeepSeek-R1 1.5B",
        description = "Reasoning model — thinks step-by-step before answering. Great for complex problems.",
        sizeLabel = "1.0 GB",
        ramRequired = "2 GB RAM",
        tag = "Reasoning · Fast",
        downloadUrl = "https://huggingface.co/bartowski/DeepSeek-R1-Distill-Qwen-1.5B-GGUF/resolve/main/DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
        fileName = "DeepSeek-R1-Distill-Qwen-1.5B-Q4_K_M.gguf",
    ),
    ModelInfo(
        id = "phi3-mini",
        name = "Phi-3 Mini 3.8B",
        description = "Microsoft's compact model — punches above its weight for its size.",
        sizeLabel = "2.2 GB",
        ramRequired = "4 GB RAM",
        tag = "General",
        downloadUrl = "https://huggingface.co/microsoft/Phi-3-mini-4k-instruct-gguf/resolve/main/Phi-3-mini-4k-instruct-q4.gguf",
        fileName = "Phi-3-mini-4k-instruct-q4.gguf",
    ),
)
