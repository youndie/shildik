plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

/**
 * A token validator for Ktor services.
 *
 * **It used to be JVM-only, and that was a limit of two libraries rather than of the problem.**
 * Verification rested on `jwks-rsa` and `ktor-server-auth-jwt`, neither of which exists for
 * Kotlin/Native — so no service could move to a native binary, however much it wanted to. The
 * verification here is its own, on top of `:crypto`: the same JWS and RSA code that signs tokens.
 *
 * The JVM loses nothing by it: the `jwt` dependencies stay in `jvmMain`, where they build a
 * principal in the shape existing consumers expect.
 */
kotlin {
    jvm()
    macosArm64()
    linuxX64()
    linuxArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":oidc-auth-core"))
            // The JWKS address comes from here — as a type rather than a string.
            implementation(project(":shared-oidc"))
            implementation(project(":crypto"))

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(ktorLibs.server.core)
            api(ktorLibs.server.auth)
            // Fetching JWKS goes through the Ktor client rather than `java.net.URL`: it is the
            // only way to make a request that exists on every target.
            implementation(ktorLibs.client.core)
        }

        jvmMain.dependencies {
            // `api`, not `implementation`: consumers write `withRole(...)` next to
            // `authenticate(JWT_AUTH_OIDC)`, so the interface has to reach them.
            api(project(":ktor-role-based-auth"))
            // `JWTPrincipal` and its payload — expected by services written against the previous,
            // JVM-only validator. These libraries no longer verify anything; they only shape the
            // principal.
            api(ktorLibs.server.auth.jwt)

            // An HTTP engine is mandatory: without one `HttpClient()` throws at construction —
            // and it throws in production, not at build time.
            implementation(ktorLibs.client.cio)
        }

        nativeMain.dependencies {
            implementation(ktorLibs.client.curl)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":crypto"))
            implementation(ktorLibs.server.testHost)
            implementation(ktorLibs.client.mock)
        }
    }
}
