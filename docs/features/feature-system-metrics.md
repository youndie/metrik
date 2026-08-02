---
id: feature-system-metrics
title: Системные метрики процесса
type: feature
status: active
owner: unassigned
involved_services: [metrik-agent, metrik-server, metrik-web]
api: [protocol-ingest]
tags: [core]
---

# Системные метрики процесса

## 1. Суть

Раз в окно агент снимает срез состояния процесса — heap, CPU, треды, uptime — чтобы «сервис
тормозит» можно было отличить от «сервис упирается в память» без похода в кластер.

Это тот самый пункт, который в изначальном драфте назван главным челленджем: снять системные
метрики, **не подтаскивая JMX**.

## 2. Бизнес-правила

* Базовый набор (heap used/max, CPU, треды, uptime) обязан работать без модуля `java.management`
  и на Kotlin/Native.
* Расширенный набор (GC) — опциональный: если источника нет, поле просто отсутствует в пакете.
* Отсутствие данных отображается как «нет данных», **никогда** как ноль.
* Снятие среза — раз в окно (60 с), не на каждый запрос.

## 3. Откуда берутся числа

### JVM — только `java.base`

| Метрика | Источник |
|---|---|
| heap used | `Runtime.getRuntime().totalMemory() - freeMemory()` |
| heap max | `Runtime.getRuntime().maxMemory()` |
| CPU процесса | дельта `ProcessHandle.current().info().totalCpuDuration()` за окно ÷ (wall × `availableProcessors()`) |
| треды | `Thread.activeCount()` (приблизительно — это счёт в текущей группе, задокументировать) |
| uptime | дельта от момента старта плагина |

`ProcessHandle` живёт в `java.base` с JDK 9 — это ключ ко всему решению: CPU-время процесса
достаётся без `ManagementFactory`.

### JVM — опциональное обогащение

GC-счётчики требуют `java.management`. Доступность проверяется **лениво и один раз**, рефлексией,
ровно как это делает сам Ktor в `MicrometerMetrics` (`isManagementFactoryAvailable`). Нет модуля —
нет поля `gc`, агент работает дальше.

### Kotlin/Native (Linux) — гипотеза, проверить в M2

| Метрика | Предполагаемый источник |
|---|---|
| RSS | `/proc/self/statm` (поле 2 × page size) |
| CPU | `/proc/self/stat` (utime + stime, тики ÷ `sysconf(_SC_CLK_TCK)`) |
| треды | `/proc/self/stat` (поле 20) |
| GC | `kotlin.native.runtime.GC` — API экспериментальное, может не подойти |

У нативного процесса нет «heap max» в смысле JVM: вместо `hm` подставляется лимит cgroup, если он
читается (`/sys/fs/cgroup/memory.max`), иначе поле пустое. Это разница в семантике между
платформами, и в UI её надо называть честно: «RSS», а не «heap».

## 4. Якоря кода

| Сервис | Код |
|---|---|
| metrik-agent | `agent/src/commonMain/.../SystemSnapshot.kt` (expect), `jvmMain/.../SystemSnapshot.jvm.kt`, `nativeMain/.../SystemSnapshot.native.kt` |

## 5. Сценарии (BDD)

### Сценарий: работает без java.management
* **Дано:** сервис запущен на JRE, собранном jlink без модуля `java.management`
* **Когда:** агент снимает срез
* **Тогда:** heap/CPU/треды/uptime приходят, поле `gc` отсутствует, исключений нет

### Сценарий: нативный сервис
* **Дано:** агент установлен в Kotlin/Native Ktor-сервис под Linux
* **Когда:** агент снимает срез
* **Тогда:** RSS и CPU приходят, `hm` пустое или равно лимиту cgroup

### Сценарий: снятие среза не стоит заметного времени
* **Дано:** сервис под нагрузкой
* **Когда:** наступает момент снятия среза
* **Тогда:** латентность запросов в этот момент не отличима от соседних окон
