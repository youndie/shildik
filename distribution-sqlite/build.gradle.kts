plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("ru.workinprogress.sborka.kmp")
    id("ru.workinprogress.sborka.lint")
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
            entryPoint = "ru.workinprogress.shildik.distribution.sqlite.main"
            baseName = "shildik-sqlite"
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
