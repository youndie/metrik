import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("multiplatform")
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    // Маршруты Navigation 3 обязаны быть @Serializable: на wasm рефлексии для восстановления
    // стека нет.
    alias(libs.plugins.pluginSerialization)
}

kotlin {
    jvmToolchain(25)

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
            // Шрифты макета (RobotoFlex.ttf, RobotoMono.ttf) как compose-ресурсы — см. Theme.kt.
            implementation(compose.components.resources)
            implementation(ktorLibs.client.core)
            implementation(ktorLibs.client.contentNegotiation)
            // Пути к API не собираются строками: типизированный контракт лежит в :shared.
            implementation(ktorLibs.client.resources)
            implementation(ktorLibs.serialization.kotlinx.json)
            implementation(libs.material.kolor)
            // Абсолютное время в локальной зоне пользователя (M-83) — без неё пришлось бы либо
            // тащить java.time (недоступен на wasmJs), либо считать по UTC и выдавать за локальное.
            implementation(libs.kotlinx.datetime)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.navigation3.ui)
            implementation(project.dependencies.platform(libs.koin.bom))
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.composeViewmodel)
        }
        val desktopMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                // Dispatchers.Main на desktop-JVM: без него viewModelScope падает при первом же
                // обращении к ViewModel.
                implementation(libs.kotlinx.coroutines.swing)
                // Отладочный таргет ходит в тот же API, что и wasm-сборка, только другим движком.
                implementation(ktorLibs.client.cio)
            }
        }
        wasmJsMain.dependencies {
            implementation(ktorLibs.client.js)
            // Кнопки «назад/вперёд» и адресная строка — часть интерфейса дашборда, а не украшение.
            implementation(libs.navigation3.browser)
        }
    }
}

compose.resources {
    packageOfResClass = "ru.workinprogress.metrik.web.generated.resources"
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
