plugins {
    kotlin("multiplatform")
    alias(libs.plugins.pluginSerialization)
}

kotlin {
    jvm()
    jvmToolchain(21)

    macosArm64()
    linuxX64()
    linuxArm64()

    sourceSets {
        commonMain.dependencies {
            api(projects.shared)
            implementation(ktorLibs.server.core)
            implementation(ktorLibs.network)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
