plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
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
            implementation(libs.kotlin.test)
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
