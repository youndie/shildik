plugins {
    id("org.jetbrains.kotlin.jvm")
    id("ru.workinprogress.sborka.jvm")
    id("ru.workinprogress.sborka.lint")
    id("ru.workinprogress.sborka.publish")
}

dependencies {
    // Only `ktor-server-auth`, deliberately. The plugin knows nothing about how a principal was
    // obtained — JWT, session, API key — it only asks it for roles. Pulling `auth-jwt` in here
    // would tie route-level authorization to one way of authenticating.
    implementation(ktorLibs.server.auth)
    implementation(ktorLibs.server.core)

    // `kotlin-test` is not declared here any more: `ru.workinprogress.sborka.jvm` puts it on the
    // test classpath, version-managed by the Kotlin plugin. Declaring it here as well is the same
    // module with two different version constraints, and neither resolves.
    testImplementation(ktorLibs.server.testHost)
}

tasks.withType<Test> { useJUnitPlatform() }
