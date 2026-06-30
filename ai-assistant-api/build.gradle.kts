// ai-assistant-api: the pure-Kotlin SPI for the on-device offline AI coding assistant.
// No engine/model code lives here — that's ai-assistant-impl (llama.cpp via JNI). This module is the
// stable contract the IDE shells (ide-core/ide-ui) and the build-engine integration depend on, so the
// inference backend can be swapped (llama.cpp -> MNN -> MLC) without touching callers.
plugins {
    kotlin("jvm")
}

dependencies {
    api(project(":build-api"))     // to consume BuildDiagnostic for the "fix build errors" feature
    api(project(":analysis-api"))  // to consume Diagnostic + QuickFix for the "fix code" feature

    testImplementation(kotlin("test"))
}
