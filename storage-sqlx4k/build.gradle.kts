plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("ru.workinprogress.sborka.kmp")
    id("ru.workinprogress.sborka.lint")
    id("ru.workinprogress.sborka.publish")
}

// PostgreSQL: the driver, the schema, and the lock migrations take.
//
// The storage appeared for native's sake (JDBC is a JVM interface, not a protocol, and Exposed does
// not travel there) but replaced Exposed entirely. The order was: first the replacement was checked
// by the same tests on the JVM, then the platform changed, and only once both installations ran on
// native was the second adapter removed — keeping it would have meant testing one thing and
// shipping another.
//
// **The coordinates are unchanged on purpose.** Everything a consumer imports from here it still
// gets from here: `storage-sqlx4k-core` arrives through `api`, so `sqlx4kStorageModule` and the
// repositories are where they were, and the split is invisible to a build that wanted Postgres.
kotlin {
    jvm()

    // This target is why the module exists: JDBC is a JVM interface, not a protocol, and Exposed
    // does not travel here. There is one native target, `linuxX64`: images run on linux/amd64.
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            // The repositories, the query helpers and the migration runner live there; what is
            // here is the driver, the schema and the advisory lock only Postgres has.
            api(project(":storage-sqlx4k-core"))
            implementation(libs.sqlx4k.postgres)
            implementation(libs.koin.core)
        }
        commonTest.dependencies {
            // `kotlin-test` is not declared here any more: `ru.workinprogress.sborka.kmp` puts it on
            // `commonTest`, version-managed by the Kotlin plugin. Declaring it here as well is the
            // SAME module with two different version constraints, and the metadata compilation then
            // resolves neither — `Unresolved reference 'Test'` on a dependency that is plainly listed.
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
