plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

/**
 * The wire of the admin API: URLs and models for the server and the CLI. The OIDC contour lives
 * apart (`:shared-oidc`) — its description travels to external clients, this one stays inside.
 */
kotlin {
    jvm()
    macosArm64()
    linuxX64()
    linuxArm64()

    sourceSets {
        commonMain.dependencies {
            // Resources and wire models, shared by the server and the CLI. One description
            // instead of two: the CLI used to assemble JSON from strings and parse it by name.
            api(ktorLibs.resources)
            api(libs.kotlinx.serialization.json)
        }
    }
}
