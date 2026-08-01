# docs — документация metrik

Документация живёт внутри репозитория продукта и разложена по слоям: от «что и зачем» к «как это
устроено на проводе» и «как это эксплуатируется».

```
[ Feature (что и зачем + BDD) ]
            │
            ▼
[ API / протокол (контракт на проводе) ]
            │
            ▼
[ Service (кто владеет, как деплоится) ]
```

| Слой | Папка | Отвечает на вопрос |
|---|---|---|
| Research | `research/` | почему архитектура именно такая; что проверено, что гипотеза |
| Feature | `features/` | что делает система и зачем, BDD-сценарии = критерии приёмки |
| API | `api/` | контракты: провод агент→сервер и HTTP дашборда |
| Service | `services/` | зона ответственности, конфиг, деплой, грабли |

## Оговорка про статус

Обычный инвариант docs-подхода — «`main` описывает то, что есть». Пока кода нет, документы
описывают **целевую** архитектуру и все помечены `status: draft`. По мере появления кода статус
меняется на `active`, а утверждения перепроверяются по коду — не по этим же документам.

## Документы

**Research**
- [research-architecture](research/research-architecture.md) — проверенные факты, решения, риски.
  **Точка входа для любого, кто берётся за задачу.**

**Features**
- [feature-request-metrics](features/feature-request-metrics.md) — метрики HTTP-запросов
- [feature-system-metrics](features/feature-system-metrics.md) — heap/CPU/треды без JMX
- [feature-alerting](features/feature-alerting.md) — алерты в Telegram

**API**
- [protocol-ingest](api/protocol-ingest.md) — UDP-протокол агент → сервер, v1
- [endpoint-query](api/endpoint-query.md) — HTTP API дашборда

**Services**
- [metrik-agent](services/metrik-agent.md) — KMP Ktor-плагин
- [metrik-server](services/metrik-server.md) — Kotlin/Native + SQLite
- [metrik-web](services/metrik-web.md) — Compose Wasm дашборд

**Бэклог** — [../BACKLOG.md](../BACKLOG.md), задачи `M-NN` по вехам M0…M7.

## Соглашения

- `id` в frontmatter = имя файла.
- **Главный потребитель — Claude Code.** Каждый документ даёт якоря кода: путь к файлу, а не
  пересказ его содержимого. Путь остаётся верным, копия протухает.
- Числа, статусы и тексты ошибок в BDD — только проверенные по коду; пока кода нет, они помечены
  как целевые.
- Гипотезы называются гипотезами (см. раздел про Kotlin/Native в feature-system-metrics) —
  документ, который не отличает проверенное от предполагаемого, вреднее отсутствия документа.
- Язык — русский; идентификаторы, URL, заголовки HTTP — как в коде.
