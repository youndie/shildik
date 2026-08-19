plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    // Both Kotlin plugins are declared here with `apply false`. Without that, a module taking
    // `kotlinJvm` runs into "plugin is already on the classpath with an unknown version": the
    // multiplatform plugin has already put Kotlin on the build classpath.
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.ktlint)
}

allprojects {
    group = "ru.workinprogress.shildik"

    // 0.2.x, not 0.1.x. The same coordinates were published from the private repository up to
    // 0.1.0.5, and the run numbers here start from one — continuing the old line would mean
    // handing consumers a lower version of the same artifact from a different repository. The
    // major line makes the move visible and keeps the ordering monotonic.
    version = "0.2.0"
}

/** Java 25 — the same version the services that consume these libraries run on. */
val toolchainVersion = 25

subprojects {
    apply(
        plugin =
            rootProject.libs.plugins.ktlint
                .get()
                .pluginId,
    )
    apply(plugin = "maven-publish")

    /**
     * One toolchain version for every module, set here rather than in each of them.
     *
     * Six identical `jvmToolchain(25)` calls are six places where one day there will be five.
     * What differs between modules — targets, dependencies — stays with the modules: a
     * convention takes what is shared, it does not decide for the module.
     */
    plugins.withId("org.jetbrains.kotlin.multiplatform") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
            jvmToolchain(toolchainVersion)
        }
    }
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(toolchainVersion)
        }
    }

    /**
     * A failing test must be readable **in the run log**, not only in the HTML report.
     *
     * The Gradle default prints the exception type and a line, and nothing else. CI does not keep
     * the report, and you cannot hold the runner machine in your hands — so a test that only
     * fails there gets debugged blind.
     */
    tasks.withType<Test>().configureEach {
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showExceptions = true
            showCauses = true
            showStackTraces = true
        }
    }

    // Artifact version is `0.1.0.<CI run number>`. A local build stays at `0.1.0`: there is
    // nowhere to get a run number locally, and inventing one means overwriting somebody else's
    // artifact in the registry one day.
    providers.gradleProperty("VERSION").orNull?.let { version = it }

    extensions.configure<PublishingExtension> {
        repositories {
            maven {
                name = "reposilite"
                // Snapshots, not the private repository: the code is public and the artifacts
                // have to be readable without credentials, otherwise this is a read-only
                // repository.
                url = uri("https://reposilite.kotlin.website/snapshots")
                credentials {
                    username = providers.gradleProperty("REPOSILITE_USER").orNull ?: System.getenv("REPOSILITE_USER")
                    password = providers.gradleProperty("REPOSILITE_SECRET").orNull ?: System.getenv("REPOSILITE_SECRET")
                }
            }
        }
    }

    // The multiplatform plugin creates publications itself — one per target plus the common one.
    // A JVM module has a single component and has to declare its own.
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<JavaPluginExtension> { withSourcesJar() }
        extensions.configure<PublishingExtension> {
            publications.create<MavenPublication>("maven") { from(components["java"]) }
        }
    }
}

/**
 * A comma inside a backticked test name breaks Kotlin/Native compilation — `Name contains illegal
 * characters`. The same test compiles on the JVM, so the error surfaces away from where it was
 * made and away from whoever made it.
 */
val checkTestNames by tasks.registering {
    group = "verification"
    description = "Backticked test names must not contain commas — Kotlin/Native refuses them"

    val sources =
        fileTree(rootDir) {
            include("*/src/*Test/**/*.kt")
            exclude("**/build/**")
        }
    inputs.files(sources)
    outputs.upToDateWhen { true }

    doLast {
        val offenders =
            sources.files.flatMap { file ->
                file.readLines().withIndex().mapNotNull { (index, line) ->
                    val name = Regex("fun\\s+`([^`]*)`").find(line)?.groupValues?.get(1)
                    if (name != null && name.contains(',')) "${file.relativeTo(rootDir)}:${index + 1}: $name" else null
                }
            }
        require(offenders.isEmpty()) {
            "A comma in a test name breaks Kotlin/Native:\n" + offenders.joinToString("\n")
        }
    }
}

tasks.matching { it.name == "check" }.configureEach { dependsOn(checkTestNames) }
