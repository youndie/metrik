plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("io.github.youndie.sborka.kmp")
    id("io.github.youndie.sborka.lint")
    id("io.github.youndie.sborka.publish")
}

// Published so a Ktor service can depend on the agent without vendoring its source.

kotlin {
    // OPTED IN OUT LOUD. The conventions compile with `allWarningsAsErrors`, and this API was being
    // used with the compiler asking to be told so on every build — a warning nobody read because
    // nothing failed on it. Saying it here is the same statement the annotation would make at each
    // use site, made once and visible.
    compilerOptions {
        optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
        // `newSingleThreadContext`, in the UDP test. Delicate because the thread it creates has to
        // be closed by hand; the test does close it, and saying so here is the acknowledgement the
        // compiler is asking for.
        optIn.add("kotlinx.coroutines.DelicateCoroutinesApi")
    }

    withSourcesJar()

    jvm()

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
            implementation(ktorLibs.server.testHost)
        }
    }
}
