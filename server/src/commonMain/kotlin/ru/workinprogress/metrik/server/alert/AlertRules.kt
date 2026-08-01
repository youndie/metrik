package ru.workinprogress.metrik.server.alert

import ru.workinprogress.metrik.api.AlertRuleView

/** Идентификаторы правил. Строки, а не enum: они уезжают в базу и в API. */
object AlertRuleIds {
    const val ERROR_RATE = "error_rate"
    const val LATENCY = "latency"
    const val MEMORY = "memory"
    const val ABSENT = "absent"

    val all = listOf(ERROR_RATE, LATENCY, MEMORY, ABSENT)
}

/**
 * Дефолтные пороги инсталляции.
 *
 * `minCount` и `windows` — не украшение, а защита от ложных срабатываний: без порога по количеству
 * один ошибочный запрос из трёх даёт 33 % и будит дежурного ночью, без гистерезиса шумный порог
 * даёт флаппинг, после которого алерты перестают читать.
 */
class AlertDefaults(
    val errorRate: Double = 0.05,
    val errorRateMinCount: Int = 20,
    val latencyMs: Double = 2_000.0,
    val latencyWindows: Int = 3,
    val memoryRatio: Double = 0.9,
    val memoryWindows: Int = 3,
    val absentMinutes: Int = 3,
) {
    fun view(ruleId: String): AlertRuleView =
        when (ruleId) {
            AlertRuleIds.ERROR_RATE -> {
                AlertRuleView(ruleId, errorRate, errorRateMinCount, 1, enabled = true)
            }

            AlertRuleIds.LATENCY -> {
                AlertRuleView(ruleId, latencyMs, 0, latencyWindows, enabled = true)
            }

            AlertRuleIds.MEMORY -> {
                AlertRuleView(ruleId, memoryRatio, 0, memoryWindows, enabled = true)
            }

            AlertRuleIds.ABSENT -> {
                AlertRuleView(ruleId, absentMinutes.toDouble(), 0, 1, enabled = true)
            }

            else -> {
                error("unknown rule: $ruleId")
            }
        }
}
