package ru.workinprogress.metrik.server.query

import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asBoolean
import io.github.smyrgeorge.sqlx4k.impl.extensions.asDouble
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import ru.workinprogress.metrik.api.AlertRuleView
import ru.workinprogress.metrik.server.alert.AlertDefaults
import ru.workinprogress.metrik.server.alert.AlertRuleIds
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Админский контур: пороги алертов на сервис и удаление сервиса.
 *
 * Регистрации сервисов здесь нет — ключ один на инсталляцию, сервис заводится сам при первом
 * пакете. Удаление нужно, чтобы убрать фантом, заведённый опечаткой в имени.
 */
@OptIn(ExperimentalTime::class)
class AdminService(
    private val db: ISQLite,
    private val defaults: AlertDefaults = AlertDefaults(),
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    /**
     * До какого момента правило заглушено. Истёкшие заглушения не считаются активными.
     */
    suspend fun mutes(serviceId: Long): Map<String, Long> =
        db
            .fetchAll(
                Statement
                    .create("SELECT rule_id, until FROM alert_mutes WHERE service_id = :id")
                    .bind("id", serviceId),
            ).getOrThrow()
            .rows
            .associate { row -> row.get("rule_id").asString() to row.get("until").asLong() }
            .filterValues { until -> until > nowMs() }

    /**
     * Заглушает уведомления по правилу до [untilMs].
     *
     * Заглушается **только доставка**: правило продолжает считаться, гореть в UI и писаться в
     * историю. Иначе кнопка «заглушить» означала бы «перестань замечать проблему».
     */
    suspend fun mute(
        serviceId: Long,
        ruleId: String,
        untilMs: Long,
    ) {
        require(ruleId in AlertRuleIds.all) { "unknown rule: $ruleId" }

        db
            .execute(
                Statement
                    .create(
                        """
                        INSERT INTO alert_mutes (service_id, rule_id, until) VALUES (:id, :rule, :until)
                        ON CONFLICT(service_id, rule_id) DO UPDATE SET until = excluded.until
                        """.trimIndent(),
                    ).bind("id", serviceId)
                    .bind("rule", ruleId)
                    .bind("until", untilMs),
            ).getOrThrow()
    }

    suspend fun unmute(
        serviceId: Long,
        ruleId: String,
    ) {
        db
            .execute(
                Statement
                    .create("DELETE FROM alert_mutes WHERE service_id = :id AND rule_id = :rule")
                    .bind("id", serviceId)
                    .bind("rule", ruleId),
            ).getOrThrow()
    }

    /** Правила сервиса: дефолты инсталляции, перекрытые переопределениями. */
    suspend fun rules(serviceId: Long): List<AlertRuleView> {
        val muted = mutes(serviceId)
        val overrides =
            db
                .fetchAll(
                    Statement
                        .create(
                            """
                            SELECT rule_id, threshold, min_count, windows, enabled, telegram_chat_id
                            FROM alert_rules WHERE service_id = :id
                            """.trimIndent(),
                        ).bind("id", serviceId),
                ).getOrThrow()
                .rows
                .associate { row ->
                    val ruleId = row.get("rule_id").asString()
                    ruleId to
                        AlertRuleView(
                            ruleId = ruleId,
                            threshold = row.get("threshold").asDouble(),
                            minCount = row.get("min_count").asInt(),
                            windows = row.get("windows").asInt(),
                            enabled = row.get("enabled").asBoolean(),
                            telegramChatId = row.get("telegram_chat_id").asStringOrNull(),
                            inherited = false,
                        )
                }

        return AlertRuleIds.all.map { ruleId ->
            (overrides[ruleId] ?: defaults.view(ruleId)).copy(mutedUntil = muted[ruleId])
        }
    }

    /** Переопределяет правило для сервиса. Единственная мутирующая операция в UI. */
    suspend fun updateRule(
        serviceId: Long,
        rule: AlertRuleView,
    ) {
        require(rule.ruleId in AlertRuleIds.all) { "unknown rule: ${rule.ruleId}" }

        db
            .execute(
                Statement
                    .create(
                        """
                        INSERT INTO alert_rules (service_id, rule_id, threshold, min_count, windows, enabled, telegram_chat_id)
                        VALUES (:id, :rule, :threshold, :minCount, :windows, :enabled, :chat)
                        ON CONFLICT(IFNULL(service_id, -1), rule_id) DO UPDATE SET
                            threshold = excluded.threshold,
                            min_count = excluded.min_count,
                            windows = excluded.windows,
                            enabled = excluded.enabled,
                            telegram_chat_id = excluded.telegram_chat_id
                        """.trimIndent(),
                    ).bind("id", serviceId)
                    .bind("rule", rule.ruleId)
                    .bind("threshold", rule.threshold)
                    .bind("minCount", rule.minCount)
                    .bind("windows", rule.windows)
                    .bind("enabled", if (rule.enabled) 1 else 0)
                    .bind("chat", rule.telegramChatId),
            ).getOrThrow()
    }

    /** Удаляет сервис со всеми его данными: каскад описан внешними ключами схемы. */
    suspend fun deleteService(serviceId: Long) {
        db.execute("PRAGMA foreign_keys = ON;").getOrThrow()
        db
            .execute(
                Statement.create("DELETE FROM window_receipts WHERE service_id = :id").bind("id", serviceId),
            ).getOrThrow()
        db.execute(Statement.create("DELETE FROM services WHERE id = :id").bind("id", serviceId)).getOrThrow()
    }
}
