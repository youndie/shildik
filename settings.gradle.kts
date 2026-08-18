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
