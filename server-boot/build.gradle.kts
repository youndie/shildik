plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

/**
 * Shared start-up for a distribution.
 *
 * A distribution is a `main()` that says which storage and which sign-in methods it wants; this
 * module is everything the two of them do not differ in — reading the environment, building the
 * configuration, starting both engines.
 *
 * **There is no observability here.** It is a lambda the caller passes: attaching a particular
 * agent would make every consumer depend on it, and a provider that refuses to start over
 * unreachable telemetry takes sign-in down for the sake of a graph.
 */
kotlin {
    // The same targets as `:server`, and no more: a start-up module that declares a platform the
    // server itself does not have resolves to nothing on that platform.
    jvm()
    linuxX64()
    macosArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":server"))
            api(libs.koin.core)
            implementation(ktorLibs.server.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
