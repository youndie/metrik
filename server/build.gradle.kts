plugins {
    kotlin("multiplatform")
    alias(libs.plugins.pluginSerialization)
}

kotlin {
    jvm()
    jvmToolchain(21)

    listOf(
        macosArm64(),
        linuxX64(),
        linuxArm64(),
    ).forEach { target ->
        target.binaries.executable {
            entryPoint = "ru.workinprogress.metrik.server.main"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.shared)
            implementation(projects.agent)
            implementation(ktorLibs.server.core)
            implementation(ktorLibs.network)
            implementation(ktorLibs.server.cio)
            implementation(ktorLibs.server.di)
            implementation(ktorLibs.server.contentNegotiation)
            implementation(ktorLibs.server.resources)
            implementation(ktorLibs.serialization.kotlinx.json)
            implementation(libs.sqlx4k.sqlite)
            implementation(libs.okio)
        }
        jvmMain.dependencies {
            // На JVM у CIO с TLS всё в порядке.
            implementation(ktorLibs.client.cio)
        }
        nativeMain.dependencies {
            // А на Kotlin/Native CIO падает с «TLS sessions are not supported on Native platform»,
            // и уведомления в Telegram (только https) не уходили вовсе — см. M-97 и research §1.7.
            implementation(ktorLibs.client.curl)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(ktorLibs.server.testHost)
            implementation(ktorLibs.client.contentNegotiation)
        }
    }
}
