plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("ru.workinprogress.sborka.kmp")
    id("ru.workinprogress.sborka.lint")
    id("ru.workinprogress.sborka.publish")
}

// :agent exposes this module through `api`, so it has to be resolvable for anyone
// consuming the agent from a Maven repository.

kotlin {
    withSourcesJar()

    jvm()

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
