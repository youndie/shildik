@file:OptIn(org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeCacheApi::class)

plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("ru.workinprogress.sborka.kmp")
    id("ru.workinprogress.sborka.lint")
    id("ru.workinprogress.sborka.publish")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    // The CLI is native. Every target is declared **at once** rather than per host:
    // `kotlin.native.enableKlibsCrossCompilation=true` in gradle.properties allows building for
    // another platform from this machine. That removed both the host check (which brought the whole
    // build down inside a container) and the need to raise docker for a Linux binary.
    macosArm64()
    linuxX64()
    linuxArm64()

    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget>().configureEach {
        binaries {
            executable {
                entryPoint = "ru.workinprogress.shildik.cli.main"
                baseName = "shildik"
            }

            // `all`, not just `executable`: the test binary is linked by the same linker with the
            // same caches, and the duplicate showed up there just the same —
            // `:cli:linkDebugTestLinuxX64` failed `./gradlew check` on Linux while the switch was
            // only on the executable. On macOS it still stays invisible: cross-compilation does not
            // use the caches.
            all {
                // `clikt` and `clikt-mordant` put `selfAndAncestors` into both Kotlin/Native cache
                // archives, and `ld.lld` fails on the duplicate symbol. On macOS this is invisible:
                // cross-compilation does not use the caches, and the find only appeared once CI
                // moved to a Linux runner.
                //
                // The version constant is a gate that says "revisit on a Kotlin upgrade", and it
                // fired: the bump to 2.4.10 made the switch stop applying, the cache came back and
                // linking the CLI failed on the duplicate again. Updating the constant together with
                // Kotlin is a mandatory part of the bump for as long as the duplicate lives in
                // clikt itself.
                disableNativeCache(
                    org.jetbrains.kotlin.gradle.plugin.mpp.DisableCacheInKotlinVersion.`2_4_10`,
                    "clikt and clikt-mordant produce a duplicate selfAndAncestors symbol at link time",
                )
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.clikt)
            implementation(ktorLibs.client.resources)
            implementation(ktorLibs.client.core)
            implementation(ktorLibs.client.contentNegotiation)
            implementation(ktorLibs.serialization.kotlinx.json)
            implementation(libs.kotlinx.serialization.json)
        }
        macosMain.dependencies { implementation(ktorLibs.client.darwin) }
        linuxMain.dependencies { implementation(ktorLibs.client.curl) }
        commonTest.dependencies {
            // `kotlin-test` is not declared here any more: `ru.workinprogress.sborka.kmp` puts it on
            // `commonTest`, version-managed by the Kotlin plugin. Declaring it here as well is the
            // SAME module with two different version constraints, and the metadata compilation then
            // resolves neither — `Unresolved reference 'Test'` on a dependency that is plainly listed.
            implementation(libs.kotlinx.coroutines.test)
            // The reader's paging is checked without a network: raising a real Keycloak for that
            // is out of proportion, and the mistake it guards against is a silent one.
            implementation(ktorLibs.client.mock)
        }
    }
}
