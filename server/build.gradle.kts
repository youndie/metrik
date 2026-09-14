plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("io.github.youndie.sborka.kmp")
    id("io.github.youndie.sborka.lint")
    id("io.github.youndie.sborka.parity")
    // Генерирует `KoreBuildIdentity` — версию, коммит и время сборки исходником, который
    // компилируется внутрь бинаря: у Kotlin/Native нет ни ресурсов, ни манифеста, откуда `/version`
    // мог бы их прочитать.
    alias(libs.plugins.koreBuild)
}

// NOT PUBLISHED: the server ships as a container image, not as an artefact. Explicit API is off for
// the same reason — nothing resolves this module as a library.
kotlin {
    explicitApi = null

    // OPTED IN OUT LOUD, for the same reason as in `:agent`: the conventions compile with
    // `allWarningsAsErrors`, and the UDP receiver was using this API with the compiler asking to be
    // told so on every build.
    compilerOptions {
        optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}

// WHERE THE PLATFORM PROBE LOOKS. `localhost` is the name it resolves; the port is bound by the test
// itself, so the suite needs nothing running beside it. `PlatformTest` says what that covers.
parityProbe {
    host = "localhost"
    port = 0
}

kotlin {
    jvm()

    listOf(
        macosArm64(),
        linuxX64(),
        linuxArm64(),
    ).forEach { target ->
        target.binaries.executable {
            entryPoint = "io.github.youndie.metrik.server.main"

            // 16 КиБ вместо 256 по умолчанию. Аллокатор Kotlin/Native держит страницу на класс
            // размера **на поток**, поток удерживает её сколько живёт, а `Dispatchers.IO`
            // наращивает потоки под конкуренцией — значит резидентная память идёт за числом
            // потоков, а не за живой кучей, и настройками GC не ограничивается: это страницы, а не
            // объекты.
            //
            // Замер на katcher того же стека, `--memory=192m --cpus=1`, пятьдесят одновременных
            // запросов: дефолтная сборка убита `exit=137` в восьми прогонах из восьми, пик
            // 252–329 МБ там, где лимит позволял; с этой опцией — 22–26 МБ в покое и 47–62 МБ под
            // той же нагрузкой. `-Xallocator=std` не берём: у сервиса с базой на пути запроса его
            // пик оказался **выше**, обратное тому, что он делает на сервисе без базы.
            binaryOption("fixedBlockPageSize", "16")
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.shared)
            implementation(projects.agent)

            // Участок от SIGTERM до выхода, три пробы и /version. `kore-ktor` тянет `kore-core`;
            // названы оба, потому что оба импортируются: `Application.kt` берёт и жизненный цикл,
            // и маршруты.
            implementation(libs.kore.core)
            implementation(libs.kore.ktor)
            implementation(ktorLibs.server.core)
            implementation(ktorLibs.network)
            implementation(ktorLibs.server.cio)
            implementation(ktorLibs.server.di)
            implementation(ktorLibs.server.contentNegotiation)
            implementation(ktorLibs.server.resources)
            implementation(ktorLibs.serialization.kotlinx.json)
            // Сам клиент общий, движок — платформенный (см. NotifierHttpClient).
            implementation(ktorLibs.client.core)
            implementation(libs.sqlx4k.sqlite)
            implementation(libs.okio)
            implementation(libs.mcp.server)
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
