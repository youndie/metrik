plugins {
    kotlin("multiplatform")
    alias(libs.plugins.pluginSerialization)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    // Только нативные таргеты: смысл клиента в том, что он один самодостаточный бинарь,
    // а не в том, что он ещё где-то запускается.
    listOf(
        macosArm64(),
        linuxX64(),
        linuxArm64(),
    ).forEach { target ->
        target.binaries.executable {
            entryPoint = "ru.workinprogress.metrik.cli.main"
            baseName = "metrik"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.shared)
            implementation(libs.mosaic.runtime)
            implementation(libs.mcp.client)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(ktorLibs.client.core)
            // На Kotlin/Native у CIO нет TLS («TLS sessions are not supported»), а metrik живёт
            // за https. Тот же движок, что у сервера для Telegram — см. research §1.7.
            implementation(ktorLibs.client.curl)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
