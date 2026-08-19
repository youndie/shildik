plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

/**
 * The magic-link sign-in is a module, not a class inside the server and not a jar against somebody
 * else's SPI.
 *
 * This very method is why an identity provider of our own happened: in Keycloak it lived as a
 * hand-built `magic-link-spi.jar` whose sources were lost.
 *
 * There is **deliberately** no HTTP client here: the method asks nobody anything, it checks what was
 * presented. Hence no engine — the thing `:auth-google` was burned by.
 */
kotlin {
    jvm()
    macosArm64()
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation(project(":crypto"))
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
