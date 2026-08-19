plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

/**
 * The Google sign-in is a **module of its own** rather than a class inside the server.
 *
 * Precisely what the project was started for: adding a sign-in method takes a module, a line where
 * the distribution is assembled and a section in the configuration — not a jar built against
 * somebody else's SPI and not debugging inside somebody else's process.
 */
kotlin {
    jvm()
    macosArm64()
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation(ktorLibs.client.core)
            implementation(ktorLibs.client.contentNegotiation)
            implementation(ktorLibs.serialization.kotlinx.json)
            implementation(libs.kotlinx.serialization.json)
        }
        // An HTTP engine is mandatory: without one `HttpClient()` fails at construction — but only
        // in production, because tests substitute a MockEngine. That is exactly how this surfaced:
        // the pod crash-looped at start-up while the tests stayed green.
        jvmMain.dependencies { implementation(ktorLibs.client.cio) }
        macosMain.dependencies { implementation(ktorLibs.client.darwin) }
        linuxMain.dependencies { implementation(ktorLibs.client.curl) }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(ktorLibs.client.mock)
        }
    }
}
