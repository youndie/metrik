plugins {
    id("org.jetbrains.kotlin.jvm")
    id("io.github.youndie.sborka.jvm")
    id("io.github.youndie.sborka.lint")
    application
}

// A SAMPLE, not a library.
kotlin {
    explicitApi = null
}

application { mainClass.set("io.github.youndie.metrik.sample.MainKt") }

dependencies {
    implementation(projects.agent)
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.cio)
}
