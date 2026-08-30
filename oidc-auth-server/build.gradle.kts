plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("ru.workinprogress.sborka.kmp")
    id("ru.workinprogress.sborka.lint")
    id("ru.workinprogress.sborka.publish")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// A token validator for Ktor services.
//
// **It used to be JVM-only, and that was a limit of two libraries rather than of the problem.**
// Verification rested on `jwks-rsa` and `ktor-server-auth-jwt`, neither of which exists for
// Kotlin/Native — so no service could move to a native binary, however much it wanted to. The
// verification here is its own, on top of `:crypto`: the same JWS and RSA code that signs tokens.
//
// The JVM loses nothing by it: the `jwt` dependencies stay in `jvmMain`, where they build a
// principal in the shape existing consumers expect.
kotlin {
    // `expect`/`actual` CLASSES are in Beta, and this module has them: the principal is a class on
    // both sides rather than an interface. The flag is the acknowledgement the compiler asks for —
    // KT-61573 — and without it the warning fails the build under `allWarningsAsErrors`.
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

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
            // `kotlin-test` is not declared here any more: `ru.workinprogress.sborka.kmp` puts it on
            // `commonTest`, version-managed by the Kotlin plugin. Declaring it here as well is the
            // SAME module with two different version constraints, and the metadata compilation then
            // resolves neither — `Unresolved reference 'Test'` on a dependency that is plainly listed.
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":crypto"))
            implementation(ktorLibs.server.testHost)
            implementation(ktorLibs.client.mock)
        }
    }
}
