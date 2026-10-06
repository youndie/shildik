plugins {
    alias(wip.plugins.kotlinMultiplatform)
    alias(wip.plugins.composeMultiplatform)
    alias(wip.plugins.composeCompiler)
    alias(wip.plugins.androidKotlinMultiplatformLibrary)
}

// The screen and the sign-in, once, for four platforms. The client is kalinjul's
// kotlin-multiplatform-oidc — the version shildik's app contour was accepted against
// (docs/api/protocol-oidc-app.md).
val oidc = "0.18.4"

kotlin {
    jvm("desktop")

    // A library on Android, hosted by `:android`: since AGP 9 the application plugin refuses a Kotlin
    // Multiplatform module.
    androidLibrary {
        namespace = "io.github.youndie.shildik.sample"
        compileSdk = 37
        minSdk = 24
    }

    // Built on demand on a Mac and run from Xcode; nothing here needs a project file to compile.
    iosArm64()
    iosSimulatorArm64()

    wasmJs {
        browser {
            commonWebpackConfig {
                // The redirect address registered for the browser is on this port (README).
                devServer = (devServer ?: org.jetbrains.kotlin.gradle.targets.js.webpack.KotlinWebpackConfig.DevServer()).copy(port = 8081)
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(wip.compose.runtime)
            implementation(wip.compose.foundation)
            implementation(wip.compose.ui)
            implementation("io.github.kalinjul.kotlin.multiplatform:oidc-appsupport:$oidc")
        }
        getByName("desktopMain").dependencies {
            implementation(compose.desktop.currentOs)
        }
    }
}

compose.desktop {
    application {
        mainClass = "io.github.youndie.shildik.sample.MainKt"
    }
}
