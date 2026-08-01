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

    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
