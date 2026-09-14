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

// AN APPLICATION, not a library — like `:distribution`, and not published for the same reason:
// nothing resolves it.
kotlin {
    explicitApi = null
}

// The reference distribution on SQLite.
//
// **A module of its own rather than a second binary inside `:distribution`.** Two executables in
// one module would share its dependencies, so both images would carry both database drivers — and
// "this image cannot talk to Postgres" would stop being a fact of the build. The duplication that
// buys it is one `main()` and a dependency list; the two files differ by the line naming the
// storage, which is exactly the difference the images have.
kotlin {
    linuxX64 {
        binaries.executable {
            entryPoint = "io.github.youndie.shildik.distribution.sqlite.main"
            baseName = "shildik-sqlite"

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
            implementation(project(":storage-sqlx4k-sqlite"))
            implementation(project(":auth-google"))
            implementation(project(":auth-magic-link"))
            implementation(project(":auth-password"))
            implementation(ktorLibs.server.core)
            implementation(ktorLibs.server.cio)
            implementation(libs.koin.core)
        }
    }
}

// The build context, assembled the same way as the Postgres one — and with the **SQLite** schema.
//
// The two migration sets are not interchangeable: one is written in Postgres types and one in
// SQLite's. Taking them from the storage module this binary actually depends on is what keeps the
// pair honest; a path spelled out by hand would be a third place to keep in step.
val imageContext =
    tasks.register<Sync>("imageContext") {
        dependsOn("linkReleaseExecutableLinuxX64")
        from(rootProject.file("docker/native.Dockerfile")) { rename { "Dockerfile" } }
        from(layout.buildDirectory.file("bin/linuxX64/releaseExecutable/shildik-sqlite.kexe"))
        from(project(":storage-sqlx4k-sqlite").file("src/commonMain/resources/migrations")) { into("migrations") }
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
            "BINARY=shildik-sqlite.kexe",
            "-t",
            "shildik-sqlite:${project.version}",
            ".",
        )
    }
