plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

/**
 * The password sign-in is a **module of its own**, and that is a security measure rather than a
 * matter of taste.
 *
 * An installation may decide to have no passwords at all. Were the method to live in shared code,
 * "this build has no passwords" would be a promise held up by configuration. As a separate module it
 * becomes a fact of the build: the code is simply not in the image.
 */
kotlin {
    jvm()
    macosArm64()
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core"))
            implementation(project(":crypto"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
