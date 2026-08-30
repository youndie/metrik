plugins {
    id("org.jetbrains.kotlin.jvm")
    id("ru.workinprogress.sborka.jvm")
    id("ru.workinprogress.sborka.lint")
    application
}

// A SAMPLE, not a library.
kotlin {
    explicitApi = null
}

application { mainClass.set("ru.workinprogress.metrik.sample.MainKt") }

dependencies {
    implementation(projects.agent)
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.cio)
}
