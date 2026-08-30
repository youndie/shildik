plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("ru.workinprogress.sborka.kmp")
    id("ru.workinprogress.sborka.lint")
    id("ru.workinprogress.sborka.publish")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvm()
    macosArm64()
    linuxX64()
    linuxArm64()

    sourceSets {
        commonMain.dependencies {
            api(libs.cryptography.core)
            api(libs.cryptography.random)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmMain.dependencies {
            implementation(libs.cryptography.provider.jdk)
        }
        nativeMain.dependencies {
            // The prebuilt OpenSSL 3 provider: it carries the library with it, so a native
            // binary does not depend on what happens to be installed on the host.
            implementation(libs.cryptography.provider.openssl3)
        }
        commonTest.dependencies {
            // `kotlin-test` is not declared here any more: `ru.workinprogress.sborka.kmp` puts it on
            // `commonTest`, version-managed by the Kotlin plugin. Declaring it here as well is the
            // SAME module with two different version constraints, and the metadata compilation then
            // resolves neither — `Unresolved reference 'Test'` on a dependency that is plainly listed.
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
