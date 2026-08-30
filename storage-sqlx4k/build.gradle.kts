plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("ru.workinprogress.sborka.kmp")
    id("ru.workinprogress.sborka.lint")
    id("ru.workinprogress.sborka.publish")
}

// One storage for **every build**: on the JVM and on native alike.
//
// It appeared for native's sake (JDBC is a JVM interface, not a protocol, and Exposed does not
// travel there) but replaced Exposed entirely. The order was: first the replacement was checked by
// the same tests on the JVM, then the platform changed, and only once both installations ran on
// native was the second adapter removed — keeping it would have meant testing one thing and
// shipping another.
kotlin {
    // OPTED IN OUT LOUD. `newFixedThreadPoolContext` is delicate because the pool it creates has to
    // be closed by hand; this one lives for the process, which is the case the documentation calls
    // acceptable. The conventions compile with `allWarningsAsErrors`, so it is said here rather than
    // produced on every build and read by nobody.
    compilerOptions {
        optIn.add("kotlinx.coroutines.DelicateCoroutinesApi")
    }

    jvm()

    // This target is why the module exists: JDBC is a JVM interface, not a protocol, and Exposed
    // does not travel here. There is one native target, `linuxX64`: images run on linux/amd64.
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.sqlx4k.postgres)
            // We read the migration files **ourselves** — `Migrations.kt` explains why.
            implementation(libs.kotlinx.io)
            implementation(libs.koin.core)
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
