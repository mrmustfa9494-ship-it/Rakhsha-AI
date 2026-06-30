// ai-assistant-impl: the on-device inference engine. Wraps llama.cpp (vendored as a git submodule
// under src/main/cpp/llama.cpp — see README in this module) behind a small JNI bridge, and implements
// AiAssistant on top of it. This is the ONLY module that knows llama.cpp exists; everything else in
// the IDE talks to ai-assistant-api.
plugins {
    id("com.android.library")
    // No kotlin("android") plugin: AGP 9.0+ builds Kotlin in automatically — applying the old plugin
    // alongside it is now an error ("no longer required for Kotlin support since AGP 9.0").
}

android {
    namespace = "dev.ide.ai.impl"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        externalNativeBuild {
            cmake {
                // -O3 + NEON: llama.cpp's CPU kernels are hand-vectorized; without these flags
                // inference is several times slower on the same device.
                cppFlags += "-O3"
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
        ndk {
            // arm64-v8a covers the overwhelming majority of phones from the last ~6 years; the other
            // ABIs are commented out in the root ide-android build to keep the debug APK small (see
            // the size discussion — re-enable per-ABI splits there if x86_64 emulator testing is needed).
            abiFilters += listOf("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":ai-assistant-api"))
    implementation(project(":platform-core"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
}
