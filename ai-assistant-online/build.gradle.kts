// ai-assistant-online: cloud-backed AiAssistant implementations (Gemini / OpenAI / Claude) — the "online
// mode" counterpart to ai-assistant-impl's offline llama.cpp engine. An Android library module (not a
// pure kotlin("jvm") one like ai-assistant-api) purely so org.json — bundled with the Android platform —
// is on the compile classpath for free, without adding a new JSON dependency to the version catalog.
plugins {
    id("com.android.library")
    // No kotlin("android") plugin: AGP 9.0+ builds Kotlin in automatically.
}

android {
    namespace = "dev.ide.ai.online"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
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
}
