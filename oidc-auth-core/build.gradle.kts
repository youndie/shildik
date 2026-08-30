plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("ru.workinprogress.sborka.kmp")
    id("ru.workinprogress.sborka.lint")
    id("ru.workinprogress.sborka.publish")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Connection settings, shared by the client and the validator.
//
// A module of its own rather than a field inside each of them: a service usually takes both — a
// service token and token verification — and reads them from **one** section of its configuration.
kotlin {
    jvm()
    macosArm64()
    linuxX64()
    linuxArm64()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
    }
}
