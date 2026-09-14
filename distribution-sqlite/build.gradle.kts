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
    // Off unless asked; `docker/native.Dockerfile` asks. The reasons, and what the overrides below
    // do, are written out once in `distribution/build.gradle.kts` — the two blocks are the same
    // block and must stay identical, because the two images are the same image.
    val staticLink = providers.gradleProperty("shildik.staticLink").orNull.toBoolean()

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

            // A static link, and with it no base image at all (#48). Why each of the five
            // overrides is here, what the build stage has to install for them to work, and why
            // `scratch` still needs the shared glibc on disk: `distribution/build.gradle.kts`.
            if (staticLink) {
                linkerOpts("-static", "--no-dynamic-linker", "-L/usr/lib/x86_64-linux-gnu")
                freeCompilerArgs +=
                    "-Xoverride-konan-properties=" +
                    "targetSysRoot.linux_x64=/;" +
                    "crtFilesLocation.linux_x64=usr/lib/x86_64-linux-gnu;" +
                    "libGcc.linux_x64=usr/lib/gcc/x86_64-linux-gnu/13;" +
                    "linkerGccFlags=-lgcc -lgcc_eh -lc;" +
                    "linkerKonanFlags.linux_x64=-Bstatic -lstdc++ -ldl -lm -lpthread " +
                    "--defsym __cxa_demangle=Konan_cxa_demangle --gc-sections"
            }
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

// Built the same way as the Postgres one — and with the **SQLite** schema.
//
// The two migration sets are not interchangeable: one is written in Postgres types and one in
// SQLite's, and the argument below names the storage module this binary actually depends on. The
// Dockerfile's default is the other one, which is why every argument is passed and none is left to
// it.
val image =
    tasks.register<Exec>("image") {
        workingDir(rootProject.projectDir)
        commandLine(
            "docker",
            "build",
            "--platform",
            "linux/amd64",
            "--file",
            "docker/native.Dockerfile",
            "--build-arg",
            "MODULE=distribution-sqlite",
            "--build-arg",
            "BINARY=shildik-sqlite.kexe",
            "--build-arg",
            "MIGRATIONS=storage-sqlx4k-sqlite/src/commonMain/resources/migrations",
            "-t",
            "shildik-sqlite:${project.version}",
            ".",
        )
    }
