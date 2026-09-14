plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("io.github.youndie.sborka.kmp")
    id("io.github.youndie.sborka.lint")
    // Generates `KoreBuildIdentity` — version, commit and build time as compiled-in source, because
    // Kotlin/Native has neither resources nor a manifest for `/version` to read them from. It lives
    // here rather than in `:server` because the generated object belongs to the module that becomes
    // a binary; `main()` hands it to `runShildik`.
    alias(libs.plugins.koreBuild)
}

// AN APPLICATION, not a library, and the only one here: nothing resolves this module, so there is no
// consumer for a spelled-out public API to be spelled out for. It is not published either — the root
// used to apply `maven-publish` to every subproject, this one included.
kotlin {
    explicitApi = null
}

// A runnable distribution — the only application in a repository of libraries.
//
// One target, `linuxX64`: this exists to produce the binary that goes into a container, and a
// container runs on linux/amd64. A distribution of your own is free to declare whatever it likes.
kotlin {
    linuxX64 {
        binaries.executable {
            entryPoint = "io.github.youndie.shildik.distribution.main"
            baseName = "shildik"

            // 16 KiB PAGES INSTEAD OF THE DEFAULT 256. Kotlin/Native's allocator keeps a page per
            // block-size class PER THREAD — a thread holds it for as long as it lives, occupied or
            // not — so the resident set follows the thread count rather than the live heap, and
            // `Dispatchers.IO` grows threads under concurrency. No GC setting bounds it: these are
            // pages, not objects.
            //
            // Measured on katcher, the same stack, `--memory=192m --cpus=1` under fifty concurrent
            // requests: the default build was killed with `exit=137` in eight runs out of eight,
            // peaking at 252-329 MB where a limit allowed it; with this option, 22-26 MB at rest and
            // 47-62 MB under the same load. `charts/shildik` limits the container to 128Mi on the
            // internal contour, which is below the number that killed it.
            //
            // `-Xallocator=std` was the other candidate and is not taken: with a store on the
            // request path its peak came out HIGHER, the opposite of its behaviour on a service
            // without one. The mechanism transfers between services; the constant does not.
            binaryOption("fixedBlockPageSize", "16")
        }
    }

    sourceSets {
        linuxX64Main.dependencies {
            implementation(project(":server-boot"))
            implementation(project(":storage-sqlx4k"))
            implementation(project(":auth-google"))
            implementation(project(":auth-magic-link"))
            implementation(project(":auth-password"))
            implementation(ktorLibs.server.core)
            implementation(ktorLibs.server.cio)
            implementation(libs.koin.core)
        }
    }
}

// The build context for the image, assembled explicitly.
//
// Three things go in and nothing else: the Dockerfile, the binary, and the schema. The schema is
// taken from the storage module's resources — the same files it applies at start-up, so the image
// cannot ship a schema the code has never seen.
val imageContext =
    tasks.register<Sync>("imageContext") {
        dependsOn("linkReleaseExecutableLinuxX64")
        from(rootProject.file("docker/native.Dockerfile")) { rename { "Dockerfile" } }
        from(layout.buildDirectory.file("bin/linuxX64/releaseExecutable/shildik.kexe"))
        from(project(":storage-sqlx4k").file("src/commonMain/resources/migrations")) { into("migrations") }
        into(layout.buildDirectory.dir("image"))
    }

// Builds the container. Requires docker; there is no emulation and no fallback, because a
// "successful" build that produced no image is worse than an error.
val image =
    tasks.register<Exec>("image") {
        dependsOn(imageContext)
        workingDir(layout.buildDirectory.dir("image"))
        commandLine(
            "docker",
            "build",
            "--platform",
            "linux/amd64",
            "--build-arg",
            "BINARY=shildik.kexe",
            "-t",
            "shildik:${project.version}",
            ".",
        )
    }
