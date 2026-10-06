// A build of its own, not a module of shildik's: an app needs the Android and Compose plugins, and the
// provider's build — a native server and its libraries — has no reason to resolve either.
rootProject.name = "compose-sign-in"

pluginManagement {
    repositories {
        // The Android Gradle plugin is published here and nowhere else. Filtered: an unfiltered
        // repository takes part in resolving every plugin.
        google {
            content {
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        maven("https://reposilite.kotlin.website/snapshots") {
            name = "wip-snapshots"
            content { includeGroupByRegex("io\\.github\\.youndie.*") }
        }
    }
}

plugins {
    // The portfolio's repositories with their filters and the shared `wip` catalog — the same Kotlin,
    // Compose and AGP the rest of it builds with.
    id("io.github.youndie.sborka.settings") version "0.5.0.133"
}

dependencyResolutionManagement {
    // The Kotlin plugin registers its own repositories for the Node and Yarn the wasm target runs on;
    // settings repositories win, so they are named here or the lookup fails on Maven Central.
    repositories {
        ivy("https://nodejs.org/dist/") {
            name = "Node distributions"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }
        ivy("https://github.com/yarnpkg/yarn/releases/download") {
            name = "Yarn distributions"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.yarnpkg", "yarn") }
        }
        ivy("https://github.com/WebAssembly/binaryen/releases/download") {
            name = "Binaryen distributions"
            patternLayout { artifact("version_[revision]/[artifact]-version_[revision]-[classifier].[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.github.webassembly", "binaryen") }
        }
    }
}

include(":app")
include(":android")
