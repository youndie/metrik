pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        // Compose Multiplatform тянет androidx.lifecycle / androidx.savedstate — их нет в Central.
        google()
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
include(":dev:sample-jvm")
