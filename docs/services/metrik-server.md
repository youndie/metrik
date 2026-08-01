---
id: metrik-server
title: metrik-server (Kotlin/Native + SQLite)
type: service
status: draft
module: :server
tech_stack: [Kotlin/Native, Ktor CIO, sqlx4k SQLite, ktor-network UDP, okio]
targets: [linuxX64, linuxArm64, jvm (только dev/тесты)]
owner: unassigned
depends_on:
  - Telegram Bot API (нотификации)
publishes:
  - ghcr.io/youndie/metrik
---

# metrik-server

## 1. Зона ответственности

Самодостаточный бинарь: слушает UDP, пишет окна в SQLite, отдаёт агрегаты по HTTP, проверяет
правила алертинга и шлёт нотификации. Никакой JVM, никакой внешней БД, один файл данных.

Образец для копирования во всём — katcher (`~/IdeaProjects/katcher`): та же схема
Kotlin/Native + Ktor CIO + sqlx4k + Helm, те же грабли уже пройдены.

**Не отвечает за:** отдачу статики дашборда (см. [metrik-web](metrik-web.md) и research §Р5),
собственную аутентификацию пользователей (заголовки от reverse proxy, как в katcher).

## 2. Контракты

* Приём: [protocol-ingest](../api/protocol-ingest.md), UDP `:9999`
* Чтение: [endpoint-query](../api/endpoint-query.md), HTTP `:8080`

## 2а. Ключевые файлы (якоря кода)

Появятся в M3, планируемая раскладка (по образцу katcher):

| Файл | Что там |
|---|---|
| `server/src/commonMain/.../Application.kt` | сборка модуля, DI (`ktor-server-di`), запуск воркеров |
| `server/src/commonMain/.../db/Migrate.kt` | список миграций + `PRAGMA user_version` |
| `server/src/commonMain/.../ingest/UdpReceiver.kt` | `aSocket(selector).udp().bind()`, разбор, запись |
| `server/src/commonMain/.../query/` | эндпоинты дашборда |
| `server/src/commonMain/.../alert/` | правила, машина состояний, Telegram |
| `server/src/commonMain/.../retention/` | роллапы и удаление старых данных |

## 3. Схема данных

SQLite, sqlx4k. Сырых запросов **нет** — агент присылает уже агрегированные окна (research §Р1).

| Таблица | Содержимое | Retention |
|---|---|---|
| `services` | id, name, created_at | вечно |
| `instances` | id, service_id, instance_key, release, last_seen, flags (`clock_skew`) | 24 часа после last_seen |
| `deploys` | service_id, instance_id, release, first_seen | 90 дней |
| `route_windows` | service_id, window_start, method, route, status, count, sum_ms, max_ms, buckets, partial | **48 часов** |
| `route_rollups` | то же, гранулярность час / день | 90 дней / вечно |
| `system_windows` | instance_id, window_start, heap_used, heap_max, cpu_permille, threads, uptime, gc_count, gc_ms | 48 часов (+ часовые роллапы, 30 дней) |
| `slow_samples` | service_id, route, method, status, duration_ms, ts | 24 часа |
| `alert_rules` | service_id (NULL = дефолт инсталляции), rule_id, threshold, min_count, windows, enabled, telegram_chat_id | вечно |
| `alert_states` | service_id, rule_id, state, since, last_notified_at | вечно |

Индексы под горячие запросы дашборда: `(service_id, window_start)` и
`(service_id, route, window_start)`.

### Инстансы складываются на записи

В `route_windows` **нет `instance_id`**: три инстанса, приславшие окна за одну и ту же минуту,
попадают в одну строку — `count` и `sum_ms` складываются, гистограммы складываются побакетно,
`max_ms` берётся максимумом, `partial` — логическим ИЛИ. Ключ строки: `(service_id, window_start,
method, route, status)`.

Причина — арифметика: с разрезом по инстансам получалось ~400 МБ на один сервис за неделю, что
несовместимо со словом «лёгкий» в описании продукта. Слияние на записи плюс 48-часовая ретенция
минуток дают примерно тридцатикратную экономию.

Цена, принятая сознательно: **посмотреть маршруты одного конкретного пода нельзя.** Разрез по
инстансам остаётся там, где он действительно нужен, — у системных метрик (эта нода жрёт память) и
у `absent` (этот под замолчал).

Слияние делается read-modify-write внутри той же транзакции, что и батч приёма, поэтому повторный
пакет обязан отбрасываться по `(s, i, t, q)` — иначе окно молча удвоится.

### Отметки деплоя

Смена `rel` у инстанса пишет строку в `deploys`. На графиках сервиса это вертикальная линия:
«здесь выкатили 1.4.212». Ради этого `rel` и ездит по проводу — «стало хуже» почти всегда значит
«стало хуже после чего-то».

## 4. Воркеры

| Воркер | Период | Что делает |
|---|---|---|
| UDP receiver | постоянно | принимает датаграммы, валидирует, сливает и пишет окна батчем в одной транзакции |
| Rollup | раз в час | сворачивает минутные окна в часовые, часовые — в дневные. Трогает только окна старше 5 минут, чтобы не свернуть минуту, в которую ещё долетают пакеты отстающих инстансов |
| Retention | раз в час | удаляет то, что вышло за срок, чистит инстансы без данных дольше суток, `PRAGMA incremental_vacuum` |
| Alerting | раз в минуту | прогоняет правила по последним окнам, ведёт машину состояний, шлёт нотификации |

## 5. Конфигурация (env)

| Переменная | Дефолт | Смысл |
|---|---|---|
| `METRIK_DB_PATH` | `/data/metrik.db` | файл SQLite (нужен персистентный volume) |
| `METRIK_HTTP_PORT` | `8080` | HTTP API |
| `METRIK_UDP_PORT` | `9999` | ingest |
| `METRIK_INGEST_KEY` | — | **обязателен**, один на инсталляцию; сервер не стартует без него |
| `METRIK_TELEGRAM_TOKEN` | — | без него алерты только в UI |
| `METRIK_TELEGRAM_CHAT_ID` | — | чат по умолчанию; на сервис можно переопределить |
| `METRIK_RETENTION_HOURS` | `48` | минутные окна |
| `METRIK_ALERT_*` | см. [feature-alerting](../features/feature-alerting.md) | дефолтные пороги правил |

### Как заводится сервис

Никак — в этом и смысл. Ingest-key один на инсталляцию (команда одна, сеть доверенная), и сервис
создаётся автоматически при первом пакете с новым `s`. Подключение нового сервиса — это две строки
в его коде и переменная окружения, без похода в UI за ключом.

Обратная сторона: **опечатка в имени сервиса заводит фантомный сервис.** Лечится кнопкой «удалить»
в админском контуре; ротация самого ключа — смена переменной окружения и рестарт.

## 6. Известные грабли (из katcher)

* **Статика.** `staticFiles`/`staticResources` в `nativeMain` недоступны (завязаны на
  `java.io.File`/classloader). Katcher обошёл это, вкомпилировав Tailwind строковой константой
  (`katcher/server/src/commonMain/.../static/CSS.kt`). Для metrik решение другое — дашборд едет
  отдельным контейнером.
* **Миграции руками.** Ни Flyway, ни аналога: список SQL + `PRAGMA user_version`, как в
  `katcher/server/src/commonMain/.../db/Migrate.kt`. Порядок и идемпотентность — на нас.
* **`runBlocking` на старте.** Katcher так делает миграции до старта движка — рабочий паттерн,
  повторяем.
* **UDP-порт в k8s** требует отдельного `Service` с `protocol: UDP`; ingress его не проксирует и
  не должен — порт остаётся внутрикластерным (research §Р7).
