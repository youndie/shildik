plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

/**
 * A service token — `client_credentials` with a cache — and an HTTP client that carries it.
 *
 * **Why multiplatform.** A JVM-only client would force a service running as a native binary to
 * fetch tokens with its own code, which means a second place where the token lifetime and the
 * refresh window live. There was exactly JVM-specific in the original: `slf4j` and
 * `System.currentTimeMillis()`; both have multiplatform equivalents and the behaviour is the same.
 */
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
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(ktorLibs.client.mock)
        }
    }
}
