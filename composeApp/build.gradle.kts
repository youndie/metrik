import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform")
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvmToolchain(21)

    // Desktop-таргет существует ради скорости цикла: wasm собирается заметно дольше, а UI
    // разрабатывается и отлаживается одинаково. Продовая цель — всё равно wasm.
    jvm("desktop")

    wasmJs {
        browser()
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.shared)
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(ktorLibs.client.core)
            implementation(ktorLibs.client.contentNegotiation)
            implementation(ktorLibs.serialization.kotlinx.json)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                // Отладочный таргет ходит в тот же API, что и wasm-сборка, только другим движком.
                implementation(ktorLibs.client.cio)
            }
        }
        wasmJsMain.dependencies {
            implementation(ktorLibs.client.js)
        }
    }
}

compose.desktop {
    application {
        mainClass = "ru.workinprogress.metrik.web.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "metrik"
            packageVersion = "1.0.0"
        }
    }
}
