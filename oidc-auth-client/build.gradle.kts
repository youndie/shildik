plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("ru.workinprogress.sborka.kmp")
    id("ru.workinprogress.sborka.lint")
    id("ru.workinprogress.sborka.publish")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// A service token — `client_credentials` with a cache — and an HTTP client that carries it.
//
// **Why multiplatform.** A JVM-only client would force a service running as a native binary to
// fetch tokens with its own code, which means a second place where the token lifetime and the
// refresh window live. There was exactly JVM-specific in the original: `slf4j` and
// `System.currentTimeMillis()`; both have multiplatform equivalents and the behaviour is the same.
kotlin {
    jvm()
    macosArm64()
    linuxX64()
    linuxArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":oidc-auth-core"))

            // The token address comes from here — as a type rather than a string: one description
            // of the surface for the provider and its client alike.
            implementation(project(":shared-oidc"))

            implementation(libs.kotlinx.coroutines.core)
            implementation(ktorLibs.client.core)
            // `BearerTokens` is used by the caller, hence api rather than implementation.
            api(ktorLibs.client.auth)
            implementation(ktorLibs.client.contentNegotiation)
            implementation(ktorLibs.serialization.kotlinx.json)
            // Needed by `provideClient`: typed addresses at the call site, and request logging
            // with `Authorization` scrubbed.
            api(ktorLibs.client.resources)
            implementation(ktorLibs.client.logging)
        }

        commonTest.dependencies {
            // `kotlin-test` is not declared here any more: `ru.workinprogress.sborka.kmp` puts it on
            // `commonTest`, version-managed by the Kotlin plugin. Declaring it here as well is the
            // SAME module with two different version constraints, and the metadata compilation then
            // resolves neither — `Unresolved reference 'Test'` on a dependency that is plainly listed.
            implementation(libs.kotlinx.coroutines.test)
            implementation(ktorLibs.client.mock)
        }
    }
}
