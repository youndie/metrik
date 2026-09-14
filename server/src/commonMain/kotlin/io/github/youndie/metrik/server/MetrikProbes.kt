package io.github.youndie.metrik.server

import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.youndie.kore.health.HealthRegistry
import io.github.youndie.kore.health.LivenessGate
import io.github.youndie.kore.health.ReadinessGate
import io.github.youndie.kore.health.StartupGate
import io.github.youndie.kore.health.storeCheck
import kotlinx.coroutines.CoroutineScope

/**
 * Три вопроса, которые оркестратор задаёт процессу, и одна зависимость за ними.
 *
 * Их три, потому что они падают по разным причинам и читаются разной машинерией. До этого чарт
 * направлял **обе** пробы на `/health`, а тот пинговал базу — то есть проба живости падала от
 * недоступной базы и перезапускала под. Хранилище недоступно всем подам сразу, так что это ровно
 * тот случай, когда перезапуск растягивает восстановление вместо того, чтобы его ускорить; и
 * одновременно readiness не могла упасть ни по какой другой причине, включая остановку.
 *
 * * **startup** — защёлка. Миграции накатываются до того, как движок начнёт отвечать, так что
 *   `503` здесь бывает только пока они идут, и больше никогда: Kubernetes спрашивает стартовую
 *   пробу только на старте, а поздний отказ перезапустил бы под, который пытается остановиться.
 * * **readiness** — проверка базы ниже плюс защёлка остановки. Единственная, которая **должна**
 *   падать: она уходит в `false` на стадии announce, и трафик перестаёт приходить раньше, чем
 *   что-нибудь закроется.
 * * **liveness** — базу не трогает вовсе, и в этом весь смысл разделения (см. выше).
 *
 * Проверка — `SELECT 1`, а не `pool.acquire()`: пул отдаёт соединение из простаивающих и тогда,
 * когда хранилище за ним исчезло, и только запрос, дошедший до SQLite, говорит, что база ответила.
 */
class MetrikProbes(
    db: ISQLite,
) {
    private val checks =
        HealthRegistry(
            listOf(
                storeCheck("sqlite") { db.fetchAll("SELECT 1;").getOrThrow() },
            ),
        )

    val startup: StartupGate = StartupGate()
    val readiness: ReadinessGate = ReadinessGate(checks)
    val liveness: LivenessGate = LivenessGate()

    /**
     * Запускает цикл опроса. **Больше это не делает никто**: реестр кэширует результаты и обновляет
     * их своим циклом, поэтому без этого вызова `/health/ready` отвечает по проверкам, которые ни
     * разу не выполнялись — `UNKNOWN` навсегда, а читается это как сломанная зависимость.
     */
    fun start(scope: CoroutineScope) {
        checks.start(scope)
    }

    fun stop() {
        checks.stop()
    }
}
