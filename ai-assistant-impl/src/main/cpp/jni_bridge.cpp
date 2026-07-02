// Thin JNI bridge between dev.ide.ai.impl.LlamaBridge (Kotlin) and llama.cpp's C API.
// Keeps one llama_model + llama_context alive per loaded model (single-model-at-a-time, matching
// AiAssistant.loadModel/unloadModel). All prompt formatting and streaming-loop orchestration lives in
// Kotlin; this file only does load/decode/sample/free.
#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <chrono>

#include "llama.h"

#define LOG_TAG "RakshaAiLlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
llama_model* g_model = nullptr;
llama_context* g_ctx = nullptr;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_dev_ide_ai_impl_LlamaBridge_nativeLoadModel(
    JNIEnv* env, jobject /* this */, jstring jModelPath, jint contextSize, jint threads, jint gpuLayers) {

    const char* modelPath = env->GetStringUTFChars(jModelPath, nullptr);

    llama_backend_init();

    llama_model_params modelParams = llama_model_default_params();
    modelParams.n_gpu_layers = gpuLayers;
    // Explicit rather than relying on defaults (which have flipped across llama.cpp versions):
    // mmap the weights so load is a near-instant page-table setup, not a full 1GB read into RAM;
    // don't mlock (would force-fault every page up-front, defeating the point on a phone).
    modelParams.use_mmap  = true;
    modelParams.use_mlock = false;

    auto t0 = std::chrono::steady_clock::now();
    g_model = llama_load_model_from_file(modelPath, modelParams);
    auto t1 = std::chrono::steady_clock::now();
    LOGI("llama_load_model_from_file took %lld ms",
         (long long) std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count());
    env->ReleaseStringUTFChars(jModelPath, modelPath);

    if (g_model == nullptr) {
        LOGE("Failed to load model");
        return JNI_FALSE;
    }

    llama_context_params ctxParams = llama_context_default_params();
    ctxParams.n_ctx = contextSize;
    ctxParams.n_threads = threads;
    ctxParams.n_threads_batch = threads;

    auto c0 = std::chrono::steady_clock::now();
    g_ctx = llama_new_context_with_model(g_model, ctxParams);
    auto c1 = std::chrono::steady_clock::now();
    LOGI("llama_new_context_with_model took %lld ms",
         (long long) std::chrono::duration_cast<std::chrono::milliseconds>(c1 - c0).count());
    if (g_ctx == nullptr) {
        LOGE("Failed to create context");
        llama_free_model(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    LOGI("Model loaded: ctx=%d threads=%d gpuLayers=%d", contextSize, threads, gpuLayers);
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_dev_ide_ai_impl_LlamaBridge_nativeUnloadModel(JNIEnv* /* env */, jobject /* this */) {
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_free_model(g_model); g_model = nullptr; }
    llama_backend_free();
    LOGI("Model unloaded");
}

// Tokenizes `prompt`, runs it through llama_decode, then greedy/temperature-samples one token at a
// time, invoking the Kotlin `onToken` callback (a kotlin.jvm.functions.Function1<String, Boolean>,
// where returning false stops generation early — e.g. the user cancelled) after each one, until EOS or
// maxTokens is hit. This keeps the whole generation loop on the calling (background) thread; the
// Kotlin side wraps it in a Flow via callbackFlow.
JNIEXPORT void JNICALL
Java_dev_ide_ai_impl_LlamaBridge_nativeGenerate(
    JNIEnv* env, jobject /* this */, jstring jPrompt, jint maxTokens, jfloat temperature, jobject onToken) {

    if (g_ctx == nullptr || g_model == nullptr) {
        LOGE("nativeGenerate called with no model loaded");
        return;
    }

    const char* promptChars = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(promptChars);
    env->ReleaseStringUTFChars(jPrompt, promptChars);

    const llama_vocab* vocab = llama_model_get_vocab(g_model);

    std::vector<llama_token> tokens(prompt.size() + 16);
    int nTokens = llama_tokenize(vocab, prompt.c_str(), (int32_t) prompt.size(),
                                  tokens.data(), (int32_t) tokens.size(), true, true);
    tokens.resize(nTokens);

    llama_batch batch = llama_batch_get_one(tokens.data(), nTokens);
    if (llama_decode(g_ctx, batch) != 0) {
        LOGE("llama_decode failed on prompt");
        return;
    }

    jclass fn1Class = env->GetObjectClass(onToken);
    jmethodID invoke = env->GetMethodID(fn1Class, "invoke", "(Ljava/lang/Object;)Ljava/lang/Object;");
    jclass boolClass = env->FindClass("java/lang/Boolean");
    jmethodID boolValue = env->GetMethodID(boolClass, "booleanValue", "()Z");

    llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // Streamed generation with a STOP-STRING guard. Some models (e.g. DeepSeek-R1-Distill) do NOT
    // register the ChatML turn markers as EOG tokens, so relying only on llama_vocab_is_eog let the
    // model loop forever emitting "<|im_end|><|im_start|>assistant...". We accumulate the output and
    // stop at the first turn/end marker for ANY model, and never emit the marker text itself. A short
    // tail is held back each step so a marker split across tokens is caught before it leaks to the UI.
    std::string acc;
    size_t emitted = 0;
    static const char* kStops[] = { "<|im_end|>", "<|im_start|>", "<|endoftext|>", "<\xef\xbd\x9c" };
    const size_t kMaxStopLen = 12;

    auto findStop = [&](const std::string& text) -> size_t {
        size_t best = std::string::npos;
        for (const char* st : kStops) {
            size_t pos = text.find(st);
            if (pos != std::string::npos && (best == std::string::npos || pos < best)) best = pos;
        }
        return best;
    };

    for (int i = 0; i < maxTokens; i++) {
        llama_token nextToken = llama_sampler_sample(sampler, g_ctx, -1);
        if (llama_vocab_is_eog(vocab, nextToken)) break;

        char piece[256];
        int n = llama_token_to_piece(vocab, nextToken, piece, sizeof(piece), 0, true);
        if (n > 0) acc.append(piece, (size_t) n);

        size_t stopPos = findStop(acc);
        size_t safe = (stopPos != std::string::npos)
                        ? stopPos
                        : (acc.size() > kMaxStopLen ? acc.size() - kMaxStopLen : emitted);

        if (safe > emitted) {
            std::string chunk = acc.substr(emitted, safe - emitted);
            emitted = safe;
            jstring jPiece = env->NewStringUTF(chunk.c_str());
            jobject result = env->CallObjectMethod(onToken, invoke, jPiece);
            bool keepGoing = result ? env->CallBooleanMethod(result, boolValue) : true;
            env->DeleteLocalRef(jPiece);
            if (result) env->DeleteLocalRef(result);
            if (!keepGoing) break;
        }

        if (stopPos != std::string::npos) break;

        llama_batch nextBatch = llama_batch_get_one(&nextToken, 1);
        if (llama_decode(g_ctx, nextBatch) != 0) {
            LOGE("llama_decode failed during generation");
            break;
        }
    }

    // Flush any held-back tail that isn't part of a stop marker.
    {
        size_t stopPos = findStop(acc);
        size_t endPos = (stopPos == std::string::npos) ? acc.size() : stopPos;
        if (endPos > emitted) {
            std::string chunk = acc.substr(emitted, endPos - emitted);
            jstring jPiece = env->NewStringUTF(chunk.c_str());
            jobject result = env->CallObjectMethod(onToken, invoke, jPiece);
            env->DeleteLocalRef(jPiece);
            if (result) env->DeleteLocalRef(result);
        }
    }

    llama_sampler_free(sampler);
}

} // extern "C"
