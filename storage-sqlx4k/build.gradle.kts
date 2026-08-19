plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

/**
 * One storage for **every build**: on the JVM and on native alike.
 *
 * It appeared for native's sake (JDBC is a JVM interface, not a protocol, and Exposed does not
 * travel there) but replaced Exposed entirely. The order was: first the replacement was checked by
 * the same tests on the JVM, then the platform changed, and only once both installations ran on
 * native was the second adapter removed — keeping it would have meant testing one thing and
 * shipping another.
 */
kotlin {
    jvm()

    // This target is why the module exists: JDBC is a JVM interface, not a protocol, and Exposed
    // does not travel here. There is one native target, `linuxX64`: images run on linux/amd64.
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation(libs.sqlx4k.postgres)
            // We read the migration files **ourselves** — `Migrations.kt` explains why.
            implementation(libs.kotlinx.io)
            implementation(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
