package ru.workinprogress.metrik.server.alert

import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asInt
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLongOrNull
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.workinprogress.metrik.api.AlertView
import ru.workinprogress.metrik.server.query.AdminService
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

const val ALERT_STATE_OK = "OK"
const val ALERT_STATE_FIRING = "FIRING"

private const val MINUTE_MS = 60_000L

/**
 * Машина состояний алертов.
 *
 * Нотификация уходит **на переходе**, а не на каждой проверке, и на выход из FIRING нужен
 * гистерезис: без него шумный порог даёт флаппинг, после которого алерты перестают читать.
 * Пока инцидент длится, напоминание не чаще cooldown.
 */
@OptIn(ExperimentalTime::class)
class AlertWorker(
    private val db: ISQLite,
    private val admin: AdminService,
    private val notifier: AlertNotifier,
    private val evaluator: AlertEvaluator = AlertEvaluator(db, admin),
    private val cooldownMs: Long = 30 * MINUTE_MS,
    private val intervalMs: Long = MINUTE_MS,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope) {
        job =
            scope.launch(Dispatchers.Default) {
                while (currentlyActive()) {
                    try {
                        tick()
                    } catch (cause: CancellationException) {
                        throw cause
                    } catch (_: Throwable) {
                        // Воркер алертов, падающий от одной ошибки, хуже отсутствующего.
                    }
                    delay(intervalMs)
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    suspend fun tick() {
        val now = nowMs()

        db
            .fetchAll("SELECT id, name FROM services")
            .getOrThrow()
            .rows
            .forEach { row ->
                val serviceId = row.get("id").asLong()
                val serviceName = row.get("name").asString()
                val rules = admin.rules(serviceId).associateBy { it.ruleId }

                evaluator.evaluate(serviceId, now).forEach { verdict ->
                    val windows = rules[verdict.ruleId]?.windows ?: 1
                    applyVerdict(serviceId, serviceName, verdict, windows, now)
                }
            }
    }

    private suspend fun applyVerdict(
        serviceId: Long,
        serviceName: String,
        verdict: RuleVerdict,
        windows: Int,
        now: Long,
    ) {
        val state = loadState(serviceId, verdict.ruleId, now)

        val breaches = if (verdict.breached) state.breaches + 1 else 0
        val recoveries = if (verdict.breached) 0 else state.recoveries + 1

        var newState = state.state
        var since = state.since
        var lastNotified = state.lastNotifiedAt

        if (state.state == ALERT_STATE_OK && verdict.breached && breaches >= windows) {
            newState = ALERT_STATE_FIRING
            since = now
            lastNotified = now
            notify(serviceId, serviceName, verdict, firing = true, since = now)
        } else if (state.state == ALERT_STATE_FIRING && !verdict.breached && recoveries >= windows) {
            newState = ALERT_STATE_OK
            since = now
            lastNotified = now
            notify(serviceId, serviceName, verdict, firing = false, since = state.since)
        } else if (state.state == ALERT_STATE_FIRING && verdict.breached) {
            val previous = state.lastNotifiedAt
            if (previous == null || now - previous >= cooldownMs) {
                lastNotified = now
                notify(serviceId, serviceName, verdict, firing = true, since = state.since)
            }
        }

        saveState(serviceId, verdict.ruleId, newState, since, lastNotified, breaches, recoveries)
    }

    private suspend fun notify(
        serviceId: Long,
        serviceName: String,
        verdict: RuleVerdict,
        firing: Boolean,
        since: Long,
    ) {
        val text =
            if (firing) {
                "🔴 $serviceName — ${verdict.ruleId}: ${verdict.detail}"
            } else {
                val minutes = (nowMs() - since) / MINUTE_MS
                "🟢 $serviceName — ${verdict.ruleId} recovered (lasted $minutes min)"
            }

        db
            .execute(
                Statement
                    .create(
                        """
                        INSERT INTO alert_history (service_id, rule_id, state, at, detail)
                        VALUES (:id, :rule, :state, :at, :detail)
                        """.trimIndent(),
                    ).bind("id", serviceId)
                    .bind("rule", verdict.ruleId)
                    .bind("state", if (firing) ALERT_STATE_FIRING else ALERT_STATE_OK)
                    .bind("at", nowMs())
                    .bind("detail", verdict.detail),
            ).getOrThrow()

        val chat = admin.rules(serviceId).firstOrNull { it.ruleId == verdict.ruleId }?.telegramChatId

        // Ошибка доставки не должна ни ронять воркер, ни терять состояние: следующая проверка
        // попробует снова.
        runCatching { notifier.notify(text, chat) }
    }

    private suspend fun loadState(
        serviceId: Long,
        ruleId: String,
        now: Long,
    ): StoredState {
        val row =
            db
                .fetchAll(
                    Statement
                        .create(
                            """
                            SELECT state, since, last_notified_at, breaches, recoveries
                            FROM alert_states WHERE service_id = :id AND rule_id = :rule
                            """.trimIndent(),
                        ).bind("id", serviceId)
                        .bind("rule", ruleId),
                ).getOrThrow()
                .rows
                .firstOrNull()
                ?: return StoredState(ALERT_STATE_OK, now, null, 0, 0)

        return StoredState(
            state = row.get("state").asString(),
            since = row.get("since").asLong(),
            lastNotifiedAt = row.get("last_notified_at").asLongOrNull(),
            breaches = row.get("breaches").asInt(),
            recoveries = row.get("recoveries").asInt(),
        )
    }

    private suspend fun saveState(
        serviceId: Long,
        ruleId: String,
        state: String,
        since: Long,
        lastNotifiedAt: Long?,
        breaches: Int,
        recoveries: Int,
    ) {
        db
            .execute(
                Statement
                    .create(
                        """
                        INSERT INTO alert_states (service_id, rule_id, state, since, last_notified_at, breaches, recoveries)
                        VALUES (:id, :rule, :state, :since, :notified, :breaches, :recoveries)
                        ON CONFLICT(service_id, rule_id) DO UPDATE SET
                            state = excluded.state,
                            since = excluded.since,
                            last_notified_at = excluded.last_notified_at,
                            breaches = excluded.breaches,
                            recoveries = excluded.recoveries
                        """.trimIndent(),
                    ).bind("id", serviceId)
                    .bind("rule", ruleId)
                    .bind("state", state)
                    .bind("since", since)
                    .bind("notified", lastNotifiedAt)
                    .bind("breaches", breaches)
                    .bind("recoveries", recoveries),
            ).getOrThrow()
    }

    suspend fun active(): List<AlertView> =
        db
            .fetchAll(
                """
                SELECT s.name AS service, a.rule_id, a.state, a.since
                FROM alert_states a JOIN services s ON s.id = a.service_id
                WHERE a.state = '$ALERT_STATE_FIRING'
                ORDER BY a.since DESC
                """.trimIndent(),
            ).getOrThrow()
            .rows
            .map { row ->
                AlertView(
                    service = row.get("service").asString(),
                    ruleId = row.get("rule_id").asString(),
                    state = row.get("state").asString(),
                    since = row.get("since").asLong(),
                )
            }

    suspend fun history(limit: Int = 100): List<AlertView> =
        db
            .fetchAll(
                """
                SELECT s.name AS service, h.rule_id, h.state, h.at, h.detail
                FROM alert_history h JOIN services s ON s.id = h.service_id
                ORDER BY h.at DESC LIMIT ${limit.coerceIn(1, 500)}
                """.trimIndent(),
            ).getOrThrow()
            .rows
            .map { row ->
                AlertView(
                    service = row.get("service").asString(),
                    ruleId = row.get("rule_id").asString(),
                    state = row.get("state").asString(),
                    since = row.get("at").asLong(),
                    detail = row.get("detail").asStringOrNull(),
                )
            }

    private suspend fun currentlyActive(): Boolean = kotlin.coroutines.coroutineContext.isActive

    private class StoredState(
        val state: String,
        val since: Long,
        val lastNotifiedAt: Long?,
        val breaches: Int,
        val recoveries: Int,
    )
}
