plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("ru.workinprogress.sborka.kmp")
    id("ru.workinprogress.sborka.lint")
    id("ru.workinprogress.sborka.publish")
}

// The storage without a database — every part of it that both adapters share.
//
// **Why it is a module and not a package.** A Kotlin/Native binary links each driver's Rust
// runtime statically, and two of them define the same symbols: a test binary that reached both
// `sqlx4k-postgres` and `sqlx4k-sqlite` failed at `ld.lld` with `duplicate symbol:
// std::panicking::EMPTY_PANIC`. So a module that depends on one driver cannot be the module the
// other one builds on, however well the code inside it is factored. This one depends on neither:
// `sqlx4k` alone is the database-agnostic half of the library — `Driver`, `Statement`, `ResultSet`.
//
// It is also the reason the split is worth having at all. With the repositories here, a build
// carries exactly the driver it asked for, rather than relying on the release linker's dead-code
// elimination to drop the one it never calls — which it does, silently, until the day it does not.
kotlin {
    // OPTED IN OUT LOUD. `newFixedThreadPoolContext` is delicate because the pool it creates has to
    // be closed by hand; this one lives for the process, which is the case the documentation calls
    // acceptable. The conventions compile with `allWarningsAsErrors`, so it is said here rather than
    // produced on every build and read by nobody.
    compilerOptions {
        optIn.add("kotlinx.coroutines.DelicateCoroutinesApi")
    }

    jvm()

    // One native target, `linuxX64`: images run on linux/amd64.
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            api(libs.sqlx4k.core)
            // We read the migration files **ourselves** — `Migrations.kt` explains why.
            implementation(libs.kotlinx.io)
            implementation(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
