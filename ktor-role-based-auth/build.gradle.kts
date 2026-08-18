plugins {
    alias(libs.plugins.kotlinJvm)
}

dependencies {
    // Only `ktor-server-auth`, deliberately. The plugin knows nothing about how a principal was
    // obtained — JWT, session, API key — it only asks it for roles. Pulling `auth-jwt` in here
    // would tie route-level authorization to one way of authenticating.
    implementation(ktorLibs.server.auth)
    implementation(ktorLibs.server.core)

    testImplementation(libs.kotlin.test)
    testImplementation(ktorLibs.server.testHost)
}

tasks.withType<Test> { useJUnitPlatform() }
