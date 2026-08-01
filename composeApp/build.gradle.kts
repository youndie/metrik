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
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
            }
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
