rootProject.name = "shildik-public"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    versionCatalogs {
        create("ktorLibs") {
            from("io.ktor:ktor-version-catalog:3.5.2")
        }
    }
    repositories {
        mavenCentral()
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
include(":storage-sqlx4k")
include(":server")
include(":auth-password")
include(":auth-google")
include(":auth-magic-link")
include(":cli")
