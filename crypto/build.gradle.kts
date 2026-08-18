plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
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
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
