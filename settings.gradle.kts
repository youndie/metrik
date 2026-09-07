pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
        // Written out by hand, and it has to be: `pluginManagement` is evaluated before any settings
        // plugin is applied — including the sborka one, which is fetched through it.
        maven("https://reposilite.kotlin.website/snapshots") {
            name = "wip-snapshots"
            content {
                // Both groups on purpose. The portfolio is moving to `io.github.youndie` and sborka
                // is already there — the plugin marker and the jar behind it are under the new one.
                // The old one is held by the library versions published before the move: they are
                // still on the server and resolve as before.
                includeGroupByRegex("io\\.github\\.youndie.*")
                includeGroupByRegex("ru\\.workinprogress.*")
            }
        }
    }
}

// Lets Gradle fetch the JDK the toolchain asks for instead of demanding it be installed first.
// Without this, `jvmToolchain(25)` builds only on a machine where someone already put a JDK 25.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    // mavenCentral() and google() with their content filters — Compose Multiplatform pulls
    // androidx.lifecycle and androidx.savedstate, which are not in Central — plus the shared `wip`
    // catalog and the check that this repository's `.editorconfig` is the one the rest of them use.
    id("io.github.youndie.sborka.settings") version "0.3.0.41"
}

dependencyResolutionManagement {
    repositories {
        // WHERE THE WASM AND JS TOOLCHAINS COME FROM, declared here because they cannot be declared
        // where the Kotlin plugin wants to declare them. A `wasmJs` target needs Node, Yarn and
        // Binaryen, and the plugin adds ivy repositories for them TO THE PROJECT — which the shared
        // conventions' default mode refuses, and which `PREFER_SETTINGS` ignores. Either way they
        // have to be here, or `com.github.webassembly:binaryen` is looked for in Maven Central.
        //
        // Filtered, and that is not decoration: an unfiltered repository takes part in resolving
        // EVERY dependency, and when it is unreachable Gradle disables it and fails everything that
        // had not resolved earlier in the list.
        ivy("https://nodejs.org/dist/") {
            name = "Node distributions"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("org.nodejs", "node") }
        }

        ivy("https://github.com/yarnpkg/yarn/releases/download") {
            name = "Yarn distributions"
            patternLayout { artifact("v[revision]/[artifact](-v[revision]).[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.yarnpkg", "yarn") }
        }

        ivy("https://github.com/WebAssembly/binaryen/releases/download") {
            name = "Binaryen distributions"
            patternLayout { artifact("version_[revision]/[module]-version_[revision]-[classifier].[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.github.webassembly", "binaryen") }
        }
    }

    versionCatalogs {
        create("ktorLibs") {
            from("io.ktor:ktor-version-catalog:3.5.2")
        }
    }
}

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

rootProject.name = "metrik"

include(":shared")
include(":agent")
include(":server")
include(":composeApp")
include(":cli")
include(":dev:sample-jvm")
