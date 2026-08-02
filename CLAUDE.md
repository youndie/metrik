# CLAUDE.md

Guidance for working in this repository.

## Что это

**metrik** — мониторинг Ktor-сервисов: KMP-плагин-агент, нативный сервер с SQLite, дашборд на
Compose. Подробности — [README.md](README.md).

M0–M7 написаны: провод и кодек гистограммы, агент, приём и хранение, API чтения, дашборд,
алертинг, роллапы и деплой. Открыт только выбор лицензии (M-74). Подробности и то, что сознательно
отложено, — [BACKLOG.md](BACKLOG.md).

## С чего начинать сессию

1. [docs/research/research-architecture.md](docs/research/research-architecture.md) — решения и их
   обоснования. Без этого файла задачи выглядят как «сделай очевидное», а очевидное здесь часто
   неверно: метка серии — шаблон маршрута, а не путь; агрегирует агент, а не сервер; инстансы
   складываются на записи и разреза по ним у маршрутов нет.
2. [BACKLOG.md](BACKLOG.md) — что делать.
3. Документ слоя, к которому относится задача: `docs/features/`, `docs/api/`, `docs/services/`.

## Модули

| Модуль | Что это | Таргеты |
|---|---|---|
| `:shared` | модель провода и кодек гистограммы, общий код агента и сервера | jvm, macosArm64, linuxX64, linuxArm64, wasmJs |
| `:agent` | Ktor-плагин, встраивается в целевой сервис | jvm, macosArm64, linuxX64, linuxArm64 |
| `:server` | приём, хранение, чтение, алерты; нативный бинарь | jvm (dev), macosArm64, linuxX64, linuxArm64 |
| `:composeApp` | дашборд | wasmJs (прод), desktop-jvm (отладка) |

`jvm`-таргет сервера существует для локальной разработки и тестов; деплоится нативный бинарь.
`desktop` в дашборде — только чтобы не ждать wasm-сборку на каждой правке UI.

Дашборд разложен по слоям `feature/<name>/{domain,data,ui}` (скилл `client-feature-impl`),
навигация — Navigation 3 со связкой с историей браузера, пути к API берутся из `@Resource`-классов
в `:shared` и никогда не собираются строками. Подробности —
[docs/services/metrik-web.md](docs/services/metrik-web.md), раздел «Устройство модуля».

## Команды

```bash
./gradlew build                        # всё: компиляция всех таргетов, ktlintCheck, тесты
./gradlew :server:macosArm64Test       # тесты сервера (native — единственный настоящий таргет)
./gradlew :shared:jvmTest              # быстрый прогон общего кода
./gradlew :composeApp:run              # дашборд на desktop — быстрый цикл по UI
./gradlew :composeApp:wasmJsBrowserDistribution   # прод-бандл дашборда
```

Если wasm-сборка падает с «Lock file was changed» — `./gradlew kotlinWasmUpgradeYarnLock`
и закоммитить `kotlin-js-store/`.

Запуск нативного сервера локально (`METRIK_INGEST_KEY` обязателен, без него процесс падает
намеренно):

```bash
METRIK_INGEST_KEY=dev-key METRIK_DB_PATH=/tmp/metrik.db METRIK_SELF_SERVICE=metrik-server ./server/build/bin/macosArm64/releaseExecutable/server.kexe
```

`METRIK_SELF_SERVICE` включает самонаблюдение — удобно для ручной проверки: через минуту сервер
появится в собственном `/api/services`.

Полная сборка с нуля идёт минуты: `linuxX64`/`linuxArm64` на macOS кросс-компилируются, а wasm
собирает webpack. В обычном цикле собирай конкретный модуль и таргет, а не `build`.

## Стиль кода

ktlint **1.8.0** — версия зафиксирована в `gradle/libs.versions.toml` (`ktlint`) и оттуда же
подставляется в Gradle-плагин. CLI той же версии стоит в `~/.local/bin/ktlint`; форматируй им, а не
гоняй сборку ради тайпо:

```bash
~/.local/bin/ktlint -F "**/*.kt" "**/*.kts"
```

Версии CLI и плагина обязаны совпадать — иначе локальный формат и CI расходятся. Пара правил
ktlint не чинится автоматически (`standard:kdoc` на висячем KDoc в начале файла,
`standard:no-consecutive-comments`) — их правят руками.

## Референсы

| Репо | Зачем смотреть |
|---|---|
| [katcher](https://github.com/youndie/katcher) | тот же стек (Kotlin/Native + Ktor CIO + sqlx4k + Helm). Образец для `:server`: `db/Migrate.kt` (миграции), `Application.kt` (DI и старт), `charts/katcher` |

## Правило, которое стоит соблюдать

> Проверяй по коду, не пиши по памяти.

Каждый факт в `docs/research/` помечен тем, где он проверен: класс в jar, файл в klib, листинг
Maven Central, файл в katcher. Гипотезы названы гипотезами. Меняешь утверждение — перепроверь
источник, а не соседний документ.

## Конвенции

* Язык документации — русский; идентификаторы, URL и заголовки HTTP — как в коде.
* Бэклог плоский, файлом `BACKLOG.md` с задачами `M-NN`. Отдельный файл на задачу оправдан, когда
  на неё ссылаются десятки документов и её нельзя перемещать; здесь такой связности нет.
* Документы, описывающие ещё не написанный код, живут со `status: draft`.
