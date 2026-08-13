# CLAUDE.md

Guidance for working in this repository.

## Что это

**metrik** — мониторинг Ktor-сервисов: KMP-плагин-агент, нативный сервер с SQLite и два клиента —
дашборд на Compose Wasm и бинарь для терминала. Подробности — [README.md](README.md).

M0–M10 написаны: провод и кодек гистограммы, агент, приём и хранение, API чтения, дашборд,
алертинг, роллапы и деплой, рефакторинг дашборда, доступ агентов по MCP и клиент для терминала.
Открыты **M-96** (техдолг по навигации) и **M-99** (память на параллельных соединениях).
Подробности и то, что сознательно отложено, — [BACKLOG.md](BACKLOG.md).

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
| `:server` | приём, хранение, чтение, алерты, MCP; нативный бинарь | jvm (dev), macosArm64, linuxX64, linuxArm64 |
| `:composeApp` | дашборд в браузере | wasmJs (прод), desktop-jvm (отладка) |
| `:cli` | клиент для терминала на Mosaic | macosArm64, linuxX64, linuxArm64 |

`jvm`-таргет сервера существует для локальной разработки и тестов; деплоится нативный бинарь.
`desktop` в дашборде — только чтобы не ждать wasm-сборку на каждой правке UI. У `:cli` jvm-таргета
нет намеренно: весь смысл в одном самодостаточном бинаре.

**Клиентов два, и они ходят разными путями.** Дашборд стучится в `/api` и авторизуется заголовками
reverse proxy; `:cli` — в `/mcp` по токену, потому что форму входа oauth2-proxy терминал пройти не
может, а второй способ входа пришлось бы охранять вечно. Оба разбирают ответы **в одни и те же DTO
из `:shared`** — второй копии контракта в репозитории нет.

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
./gradlew :cli:linkDebugExecutableMacosArm64      # бинарь клиента для терминала
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

Клиент для терминала против этого же сервера (`METRIK_MCP_TOKEN` на сервере и `METRIK_TOKEN` у
клиента — одно и то же значение):

```bash
METRIK_URL=http://127.0.0.1:8080 METRIK_TOKEN=dev-token ./cli/build/bin/macosArm64/debugExecutable/metrik.kexe
```

**Проверять его надо под настоящим терминалом.** Без TTY Mosaic не рисует вовсе и говорит «Unable
to run in non-interactive mode», так что запуск из скрипта или через пайп покажет пустоту и создаст
впечатление, что клиент сломан. В неинтерактивной среде — `script -qec '<команда>' /dev/null`.

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
| [tracy](https://github.com/youndie/tracy) | MCP из того же стека и `commonMain`. Оттуда взяты и приёмы, и грабли: авторизация перехватчиком вместо `authenticate { }`, `401` кодом вместо страницы входа, «нет токена — нет эндпоинта» |

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
