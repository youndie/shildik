plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("ru.workinprogress.sborka.kmp")
    id("ru.workinprogress.sborka.lint")
    id("ru.workinprogress.sborka.publish")
}

// The second storage: the same ports, the same SQL, a different database.
//
// **A module of its own rather than a branch inside `storage-sqlx4k`.** The driver is a
// dependency, and a dependency is what a build carries: were both drivers to live in one module,
// every Postgres installation would ship the SQLite backend and vice versa — "which database this
// image can talk to" would become a setting instead of a fact of the build. That is the rule this
// repository holds for sign-in methods, and a database backend is not a lesser case.
//
// What is **not** duplicated here: the repositories, the query helpers and the migration runner
// all come from `storage-sqlx4k` unchanged. The dialect the domain's SQL is written in is common
// to both databases — including the `on conflict … do update set … excluded.…` upsert, which
// SQLite has had since 3.24. Only three things differ, and they are all in this module: how the
// driver is opened, that the schema is stated in SQLite's own types, and that the migration lock
// Postgres needs has nobody to keep out here.
kotlin {
    jvm()

    // One native target, as in `storage-sqlx4k`: images run on linux/amd64.
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            // `api`, not `implementation`: a consumer assembling this storage names the ports from
            // `core` and the repositories from `storage-sqlx4k` in its own `main()`.
            api(project(":storage-sqlx4k"))
            implementation(libs.sqlx4k.sqlite)
            implementation(libs.kotlinx.io)
            implementation(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// The tests read **the migration files this module ships**, and open their databases as files.
//
// Both paths are handed over as environment variables rather than resolved from the test's working
// directory: that directory is not the same for the JVM runner and the native one, and a test that
// finds the schema on one platform and not on the other is a test nobody trusts. The directory for
// the databases is created by the driver itself (`ensureDirectoryFor`), so there is nothing to make
// here.
val testMigrations =
    layout.projectDirectory
        .dir("src/commonMain/resources/migrations")
        .asFile.absolutePath
val testDatabases =
    layout.buildDirectory
        .dir("test-databases")
        .get()
        .asFile.absolutePath

// The Postgres set, for `SchemaParityTest`: the two schemas are compared as files, because
// that is the form in which they can drift. Reaching into a sibling module's sources is
// deliberate and narrow — the test reads the schema, not the module.
val postgresMigrations =
    project(":storage-sqlx4k")
        .layout.projectDirectory
        .dir("src/commonMain/resources/migrations")
        .asFile.absolutePath

tasks.withType<Test>().configureEach {
    environment("SHILDIK_TEST_MIGRATIONS", testMigrations)
    environment("SHILDIK_TEST_PG_MIGRATIONS", postgresMigrations)
    environment("SHILDIK_TEST_TMP", testDatabases)
}

tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest>().configureEach {
    environment("SHILDIK_TEST_MIGRATIONS", testMigrations)
    environment("SHILDIK_TEST_PG_MIGRATIONS", postgresMigrations)
    environment("SHILDIK_TEST_TMP", testDatabases)
}
