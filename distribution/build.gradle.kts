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
    // OFF UNLESS ASKED, and the image build asks — `docker/native.Dockerfile` passes
    // `-Pshildik.staticLink=true`. It cannot be the default here the way it is in a Linux-only
    // repository: the overrides below point the toolchain at the HOST filesystem
    // (`targetSysRoot.linux_x64=/`), and a `linkLinuxX64` on the macOS laptop this repository is
    // also developed on has no `/usr/lib/x86_64-linux-gnu` to find. Unset, the link is the ordinary
    // one against Kotlin/Native's own sysroot and works everywhere it used to.
    val staticLink = providers.gradleProperty("shildik.staticLink").orNull.toBoolean()

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

            // A STATIC LINK, AND WITH IT NO BASE IMAGE AT ALL (#48). glibc, libstdc++ and libgcc
            // move inside the binary; `gcr.io/distroless/cc-debian12` and the `libz.so.1` carried
            // across from a donor disappear from under it, and with them the rule that the two
            // images had to be paired by glibc version. That rule was only ever necessary because
            // the binary arrived from outside — see the head of `docker/native.Dockerfile`.
            //
            // STATIC HERE DOES NOT MEAN SELF-CONTAINED. glibc's `iconv` loads its converters with
            // `dlopen`, so the process still needs the shared glibc, the loader and the gconv
            // modules on disk at the paths glibc compiled in — which is why the runtime stage
            // carries five system files across rather than none. Read that file before changing
            // this block; the two are one decision.
            //
            // The link needs static archives the stock `gradle:…-noble` image does not carry:
            // `libc.a`, `crt1.o`, `libstdc++.a`, `libgcc.a`, `libgcc_eh.a` from `g++`, and
            // `libz.a` from `zlib1g-dev` because `ktor-client-curl` names `-lz`. Without them the
            // link says `unable to find library -lc`, which looks like a flag problem and is not.
            //
            // `-Xoverride-konan-properties` IS NOT A STABLE INTERFACE: the five keys below are
            // JetBrains' to change in any patch release, so a Kotlin bump can break this. It breaks
            // loudly, as a link error — but only where the image is built, which is why
            // `.github/workflows/image-smoke.yaml` builds it on every pull request rather than
            // leaving it to a release. `linkerKonanFlags` is the stock value with `-Bdynamic`
            // removed and nothing else changed; read the key in `konan.properties` before editing
            // it, its value continues onto a second line, and rewriting it from memory drops
            // `--gc-sections`. Two of the five exist only because `-linker-option -static` does not
            // yet mean static upstream (KT-89362); if that lands, `--no-dynamic-linker` and the
            // `linkerKonanFlags` line go away.
            //
            // The gcc directory is pinned to 13 — what noble ships, and what the builder image has.
            // Another gcc fails the link naming the path it could not find, which is the readable
            // half of a trade against globbing a filesystem at configuration time.
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

// Builds the container. Requires docker; there is no emulation and no fallback, because a
// "successful" build that produced no image is worse than an error.
//
// THERE IS NO GRADLE STEP BEFORE THIS ONE ANY MORE. `imageContext` used to link the binary and sync
// it, the Dockerfile and the schema into `build/image`, and docker built from there. The compilation
// happens inside the Dockerfile now (#48), so this task does not depend on `linkReleaseExecutable…`
// and does not produce a context — it passes the three arguments that say which distribution to
// build and gets out of the way. The context is the repository root, and what it costs is on the
// other side: nothing is cached between runs, so this is minutes rather than seconds.
//
// The paths are spelled out here rather than derived, and only here: the Dockerfile has defaults for
// all three, but a default that silently builds the Postgres distribution when the SQLite one was
// asked for is worse than a repetition.
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
            "MODULE=distribution",
            "--build-arg",
            "BINARY=shildik.kexe",
            "--build-arg",
            "MIGRATIONS=storage-sqlx4k/src/commonMain/resources/migrations",
            "-t",
            "shildik:${project.version}",
            ".",
        )
    }
