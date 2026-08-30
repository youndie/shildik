plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("ru.workinprogress.sborka.kmp")
    id("ru.workinprogress.sborka.lint")
    id("ru.workinprogress.sborka.publish")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    // The server is shared code. The JVM target exists so a distribution can package it; the
    // native ones so that the "it compiles in common" constraint keeps working — that constraint is
    // what pushes JVM specifics out to the edges of modules.
    jvm()
    macosArm64()
    linuxX64()

    sourceSets {
        commonTest.dependencies {
            // `kotlin-test` is not declared here any more: `ru.workinprogress.sborka.kmp` puts it on
            // `commonTest`, version-managed by the Kotlin plugin. Declaring it here as well is the
            // SAME module with two different version constraints, and the metadata compilation then
            // resolves neither — `Unresolved reference 'Test'` on a dependency that is plainly listed.
            implementation(ktorLibs.server.testHost)
        }
        commonMain.dependencies {
            api(project(":core"))
            api(project(":shared"))
            api(project(":shared-oidc"))
            implementation(project(":crypto"))

            implementation(ktorLibs.server.core)
            implementation(ktorLibs.server.cio)
            implementation(ktorLibs.server.resources)
            implementation(ktorLibs.server.contentNegotiation)
            implementation(ktorLibs.serialization.kotlinx.json)
            implementation(ktorLibs.server.statusPages)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
