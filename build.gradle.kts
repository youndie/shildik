plugins {
    // Both Kotlin plugins are declared here with `apply false`. Without that, a module taking
    // `kotlinJvm` runs into "plugin is already on the classpath with an unknown version": the
    // multiplatform plugin has already put Kotlin on the build classpath. The sborka plugins are
    // declared the same way and for the same reason.
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.sborkaKmp) apply false
    alias(libs.plugins.sborkaJvm) apply false
    alias(libs.plugins.sborkaLint) apply false
    alias(libs.plugins.sborkaPublish) apply false
}

// The group, the version, the toolchain, the ktlint wiring, the test logging and the whole
// publishing block used to live here, in `allprojects` and `subprojects`. They come from
// `ru.workinprogress.sborka` now, applied per module — and the numbers behind them are one line each
// in `gradle.properties`, with the reasons written beside them rather than around the code that used
// to read them.
//
// What stays is the one check that is this repository's own.

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

    // Everything the action touches is pulled out of the script HERE, at configuration time. Reading
    // `sources` or `rootDir` from inside `doLast` reaches back into the project object, and the
    // configuration cache refuses to serialize that: "cannot serialize Gradle script object
    // references". Nobody met it because the task never ran — see below.
    val files = sources.elements
    val root = rootDir

    doLast {
        val offenders =
            files.get().map { it.asFile }.flatMap { file ->
                file.readLines().withIndex().mapNotNull { (index, line) ->
                    val name = Regex("fun\\s+`([^`]*)`").find(line)?.groupValues?.get(1)
                    if (name != null && name.contains(',')) "${file.relativeTo(root)}:${index + 1}: $name" else null
                }
            }
        require(offenders.isEmpty()) {
            "A comma in a test name breaks Kotlin/Native:\n" + offenders.joinToString("\n")
        }
    }
}

// AND IT HAS TO BE THE SUBPROJECTS' `check`, not the root's.
//
// This used to read `tasks.matching { ... }` against the ROOT project's task container, which has no
// `check` task at all unless some plugin puts a lifecycle plugin on the root. So the guard above was
// registered, described — and never once executed. It woke up by accident when `shared-oidc` took a
// `wasmJs` target and the Kotlin plugin applied its root-level plumbing, then failed on the
// configuration cache. That failure is how the dead wiring was found.
subprojects {
    tasks.matching { it.name == "check" }.configureEach { dependsOn(checkTestNames) }
}
