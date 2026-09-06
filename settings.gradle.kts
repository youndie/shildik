rootProject.name = "shildik"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        // Written out by hand, and it has to be: `pluginManagement` is evaluated before any settings
        // plugin is applied — including the sborka one, which is fetched through it.
        maven("https://reposilite.kotlin.website/snapshots") {
            name = "wip-snapshots"
            content { includeGroupByRegex("ru\\.workinprogress.*") }
        }
    }
}

plugins {
    // mavenCentral() and google() with their content filters, the shared `wip` catalog, and the
    // check that this repository's `.editorconfig` is the one the rest of them use.
    id("ru.workinprogress.sborka.settings") version "0.2.0.30"
}

dependencyResolutionManagement {
    versionCatalogs {
        create("ktorLibs") {
            from("io.ktor:ktor-version-catalog:3.5.2")
        }
    }
}

include(":ktor-role-based-auth")
include(":crypto")
include(":shared-oidc")
include(":oidc-auth-core")
include(":oidc-auth-client")
include(":oidc-auth-server")

// The identity provider itself: the domain, its storage, the HTTP layer, the sign-in methods and
// the CLI. What is *not* here is a distribution: which sign-in methods an installation carries is
// its own decision, and assembling them is the consumer's `main()`.
include(":shared")
include(":core")
// The storage in three modules: what both databases share, and a module per driver. The split
// is forced by linking rather than chosen for tidiness — two of sqlx4k's drivers in one native
// binary define the same Rust symbols and fail at the linker.
include(":storage-sqlx4k-core")
include(":storage-sqlx4k")
// The second storage, and a module of its own for the same reason a sign-in method is: a build
// that does not depend on it cannot be pointed at SQLite by a setting, and the Postgres
// installations carry no second driver.
include(":storage-sqlx4k-sqlite")
include(":server")
include(":auth-password")
include(":auth-google")
include(":auth-magic-link")
include(":server-boot")
include(":cli")

// A distribution, and the only module here that is an application rather than a library: it exists
// so the repository can be run rather than only read.
include(":distribution")
// The same distribution on SQLite. A module of its own rather than a second binary in the one
// above: two executables in one module would share its dependencies, and both images would carry
// both database drivers.
include(":distribution-sqlite")
