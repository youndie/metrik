plugins {
    kotlin("multiplatform")
    alias(libs.plugins.pluginSerialization)
    `maven-publish`
}

// :agent exposes this module through `api`, so it has to be resolvable for anyone
// consuming the agent from a Maven repository.
publishing {
    repositories {
        maven {
            name = "wip"
            url = uri("https://reposilite.kotlin.website/snapshots")
            credentials {
                username = findProperty("REPOSILITE_USER")?.toString()
                password = findProperty("REPOSILITE_SECRET")?.toString()
            }
        }
    }
}

kotlin {
    withSourcesJar()

    jvm()
    jvmToolchain(25)

    macosArm64()
    linuxX64()
    linuxArm64()

    wasmJs {
        browser()
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.serialization.json)
            // Пути API объявлены типизированно и живут здесь — обе стороны берут один контракт.
            api(ktorLibs.resources)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
