plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("ru.workinprogress.sborka.kmp")
    id("ru.workinprogress.sborka.lint")
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
            entryPoint = "ru.workinprogress.shildik.distribution.main"
            baseName = "shildik"
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
