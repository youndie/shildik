// `wasmJs` is still behind an opt-in in the Kotlin DSL (Kotlin 2.4.10). Without this the build
// script compiles with a warning, and warnings here are the kind that get read once and then not.
@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("ru.workinprogress.sborka.kmp")
    id("ru.workinprogress.sborka.lint")
    id("ru.workinprogress.sborka.publish")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// The wire contract of the OIDC surface: addresses and response models, described once for
// everyone who speaks it — the provider, its client and its validator.
kotlin {
    jvm()
    macosArm64()
    linuxX64()
    linuxArm64()
    wasmJs { browser() }

    sourceSets {
        commonMain.dependencies {
            api(ktorLibs.resources)
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            // `kotlin-test` is not declared here any more: `ru.workinprogress.sborka.kmp` puts it on
            // `commonTest`, version-managed by the Kotlin plugin. Declaring it here as well is the
            // SAME module with two different version constraints, and the metadata compilation then
            // resolves neither — `Unresolved reference 'Test'` on a dependency that is plainly listed.
        }
    }
}
