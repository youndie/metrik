---
id: metrik-agent
title: metrik-agent (Ktor-плагин)
type: service
status: draft
module: :agent
tech_stack: [Kotlin Multiplatform, Ktor server plugin, ktor-network, kotlinx.serialization]
targets: [jvm, linuxX64, linuxArm64, macosArm64]
owner: unassigned
depends_on:
  - metrik-server
publishes:
  - ru.workinprogress.metrik:agent
---

# metrik-agent

## 1. Зона ответственности

Библиотека, которую встраивают в целевой Ktor-сервис одной строкой:

```kotlin
install(Metrik) {
    service = "orders-api"
    apiKey = System.getenv("METRIK_KEY")     // один ключ на всю инсталляцию metrik
    endpoint = "metrik-server:9999"
    release = System.getenv("APP_VERSION")   // необязательно, но именно оно объясняет «после чего сломалось»
}
```

Отвечает за: замер длительности и статуса каждого запроса, агрегацию в минутное окно, снятие
системного среза, отправку окна по UDP.

**Не отвечает за:** доставку (fire-and-forget, никаких ретраев и очереди на диске), хранение,
алерты, любую сетевую активность на горячем пути запроса.

Главный инвариант: **отказ metrik не должен влиять на целевой сервис.** Нет сервера, не резолвится
DNS, переполнен буфер — плагин молча считает потерю в свой счётчик и продолжает работать. Ни одно
исключение агента не всплывает в pipeline запроса.

## 2. Контракт

Провод — [protocol-ingest](../api/protocol-ingest.md). Общий код с сервером (модель `Frame`,
кодек гистограммы) — модуль `:shared`, пакет `ru.workinprogress.metrik.wire`.

## 2а. Ключевые файлы (якоря кода)

Появятся в M2, планируемая раскладка:

| Файл | Что там |
|---|---|
| `agent/src/commonMain/.../Metrik.kt` | `createApplicationPlugin`, конфиг, установка хуков |
| `agent/src/commonMain/.../WindowAggregator.kt` | окно, `HashMap<SeriesKey, Bucket>`, ротация, лимит кардинальности |
| `agent/src/commonMain/.../UdpSender.kt` | нарезка окна на пакеты ≤ 1200 байт, отправка |
| `agent/src/commonMain/.../SystemSnapshot.kt` | `expect fun readSystemSnapshot()` |
| `agent/src/jvmMain/.../SystemSnapshot.jvm.kt` | `Runtime` + `ProcessHandle`, опциональный JMX |
| `agent/src/nativeMain/.../SystemSnapshot.native.kt` | `/proc/self/statm`, `/proc/self/stat` |

## 3. Как устроен замер

1. Хук `Metrics` (`io.ktor.server.application.hooks.Metrics`) — снимаем
   `TimeSource.Monotonic.markNow()` и кладём в атрибуты вызова.
2. Хук `ResponseSent` — считаем дельту, берём статус из `call.response.status()`, шаблон маршрута из
   `call.attributes.getOrNull(RoutingRoot.routingCallKey)?.route` (см. research §1.1). Статус
   кодируется классом для 1xx–3xx и точным кодом для 4xx/5xx
   ([protocol-ingest](../api/protocol-ingest.md), «Кодирование статуса»).
3. Хук `CallFailed` — та же серия со статусом `0`.
4. Инкремент в текущем окне: `count++`, `sum += ms`, `max = maxOf(...)`, `buckets[idx]++`.
5. Отдельная корутина по таймеру закрывает окно, атомарно подменяет его новым, сериализует
   закрытое и отправляет.

На горячем пути: одно чтение монотонных часов, один lookup в map, четыре арифметические операции.
Никаких аллокаций строк (ключ серии кэшируется на маршрут), никакого IO, никаких suspend-вызовов
в сеть. Бюджет — сотня наносекунд на запрос, проверяется бенчмарком (M-23).

## 4. Конфигурация

| Параметр | Дефолт | Смысл |
|---|---|---|
| `service` | — | обязателен, логическое имя сервиса. Опечатка заведёт на сервере фантомный сервис — регистрации нет, имя и есть идентификатор |
| `apiKey` | — | обязателен, ingest-key инсталляции (не сервиса) |
| `endpoint` | — | `host:port` metrik-server |
| `instanceId` | hostname + суффикс запуска | идентификатор инстанса |
| `release` | `null` | версия релиза; смена значения рисует отметку деплоя на графиках |
| `windowMs` | `60_000` | длительность окна |
| `maxSeries` | `200` | лимит кардинальности, лишнее → серия `<other>` |
| `slowSamples` | `5` | сколько медленных запросов слать за окно |
| `systemMetrics` | `true` | снимать ли системный срез |
| `enabled` | `true` | глобальный выключатель без снятия плагина |

## 5. Сознательные ограничения

* **Нет буфера на диске.** Агент — не очередь доставки. Пропал сервер — потеряли окно.
* **Нет трейсинга.** Ни span'ов, ни correlation id: metrik отвечает на «сервису плохо?»,
  а не «почему именно этот запрос был медленным».
* **Нет пользовательских метрик** (`counter("orders")`) в v1 — оценить после первых
  реальных инсталляций, чтобы не тащить в «глупый» агент реестр метрик.
