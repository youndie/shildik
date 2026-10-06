plugins {
    alias(wip.plugins.androidApplication)
    alias(wip.plugins.composeCompiler)
}

// The thin activity that hosts `:app` on Android — AGP 9 does not let a Kotlin Multiplatform module be
// the application itself.
android {
    namespace = "io.github.youndie.shildik.sample.android"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.youndie.shildik.sample"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
        // The scheme Custom Tabs return to; the library's redirect activity declares the intent filter
        // with this placeholder. A reversed domain name — shildik refuses a dotless private-use scheme
        // for a public client (RFC 8252 §7.1).
        addManifestPlaceholders(mapOf("oidcRedirectScheme" to "io.github.youndie.shildik.sample"))
    }

    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":app"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("io.github.kalinjul.kotlin.multiplatform:oidc-appsupport:0.18.4")
}
