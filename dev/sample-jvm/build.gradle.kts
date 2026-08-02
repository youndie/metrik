plugins {
    kotlin("jvm")
    application
}

kotlin { jvmToolchain(21) }

application { mainClass.set("ru.workinprogress.metrik.sample.MainKt") }

dependencies {
    implementation(projects.agent)
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.cio)
}
