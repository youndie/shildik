plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("io.github.youndie.sborka.kmp")
    id("io.github.youndie.sborka.lint")
}

// The app contour accepted the way the browser contour was: by running SOMEBODY ELSE'S client
// against a running shildik (api/protocol-oidc-browser.md §5, issue #66).
//
// The client is kalinjul/kotlin-multiplatform-oidc — the library a Compose Multiplatform app would
// actually use. Its protocol half (`oidc-core`: discovery, PKCE, exchange, refresh, end session) is
// common code and runs on the JVM; only "open a browser and catch the redirect" is per platform, and
// that half is played here by an HTTP client that fills in the sign-in form the way a person would.
//
// Not a test task on purpose. It needs a running server, and a test that skips itself when the
// server is absent is green in exactly the case it has nothing to say. So it is a program, run by
// name — `./gradlew :acceptance-kmp-oidc:acceptance` — from the image smoke workflow after the
// container is up, and it fails loudly or not at all.
kotlin {
    explicitApi = null

    jvm()

    sourceSets {
        jvmMain.dependencies {
            implementation(libs.kmp.oidc.core)
            implementation(ktorLibs.client.core)
            implementation(ktorLibs.client.cio)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

val acceptance =
    tasks.register<JavaExec>("acceptance") {
        group = "verification"
        description =
            "Runs kotlin-multiplatform-oidc against a running shildik (SHILDIK_PUBLIC_URL, SHILDIK_MANAGEMENT_URL)."
        val main = kotlin.jvm().compilations.getByName("main")
        classpath = files(main.output.allOutputs, main.runtimeDependencyFiles)
        mainClass.set("io.github.youndie.shildik.acceptance.MainKt")
        dependsOn(main.compileTaskProvider)
    }
