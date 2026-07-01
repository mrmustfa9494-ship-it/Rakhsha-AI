cmake_minimum_required(VERSION 3.22.1)
project(rakshaai_llama)

set(CMAKE_CXX_STANDARD 17)

# --- RakshaAI: force optimized native code even in `assembleDebug` -------------------------------
# GitHub Actions builds :ide-android:assembleDebug, which makes AGP configure CMake with
# CMAKE_BUILD_TYPE=Debug. In Debug, CMake compiles EVERY target here — including llama.cpp and ggml
# pulled in via add_subdirectory below — at -O0. Unoptimized ggml makes model load AND inference
# 5-20x slower (this is why a 1GB model took 30-60s to "load" even on a flagship, while MNN Chat,
# which ships release-optimized native libs, loads an 8GB model smoothly).
# Redefining the *_DEBUG flag variables here (before add_subdirectory) forces -O3 onto llama.cpp/ggml
# too, regardless of the debug/release variant AGP picks.
# The earlier per-DEBUG-flag override didn't reliably reach ggml's compute kernels, which is why the
# model *loaded* fast (0.6s) but *inference* was ~0.3 tok/s (a full reply took ~an hour). Two real
# causes, both fixed below:
#   1) optimization: add_compile_options(-O3 -DNDEBUG) is applied to THIS directory and every
#      add_subdirectory() below it (llama.cpp + ggml), and lands AFTER any -O0 the Debug config adds,
#      so -O3 wins no matter which build type AGP picks.
#   2) ARM SIMD: quantized (Q4_K_M) matmul on arm64 is 10-20x faster with the dotprod instructions.
#      This module is arm64-v8a only (see build.gradle abiFilters), so enabling armv8.2-a+dotprod is
#      safe for every realistic modern phone. This is the same class of CPU acceleration MNN / Google
#      AI Edge use to run big models smoothly.
add_compile_options(-O3 -DNDEBUG -march=armv8.2-a+dotprod)
# ------------------------------------------------------------------------------------------------

# llama.cpp itself is NOT vendored in this repo (it's a large, separately-versioned C++ project). Add it
# as a git submodule before building:
#
#   git submodule add https://github.com/ggerganov/llama.cpp ai-assistant-impl/src/main/cpp/llama.cpp
#   git submodule update --init --recursive
#
# Once the submodule is present this picks up llama.cpp's own CMakeLists.txt (which builds libllama +
# libcommon as static libs we link against below). Without it, configure will fail with a clear error
# instead of silently producing a broken build.
if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/CMakeLists.txt")
    message(FATAL_ERROR
        "llama.cpp submodule not found at ai-assistant-impl/src/main/cpp/llama.cpp.\n"
        "Run: git submodule add https://github.com/ggerganov/llama.cpp ai-assistant-impl/src/main/cpp/llama.cpp\n"
        "     git submodule update --init --recursive")
endif()

# We only need the inference library, not llama.cpp's own example binaries/server.
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp)

# Our thin JNI bridge: translates dev.ide.ai.impl.LlamaBridge native calls into llama.cpp API calls
# (llama_model_load, llama_decode, sampling, …). Kept deliberately small — all chat-formatting / prompt
# construction / streaming-flow logic stays in Kotlin (LlamaCppEngine.kt); this layer is just the
# call-and-marshal boundary.
add_library(rakshaai_llama_jni SHARED
    jni_bridge.cpp
)

target_link_libraries(rakshaai_llama_jni
    llama
    android
    log
)

target_include_directories(rakshaai_llama_jni PRIVATE
    llama.cpp/include
)
