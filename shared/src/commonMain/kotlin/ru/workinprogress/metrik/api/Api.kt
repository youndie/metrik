package ru.workinprogress.metrik.api

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

// Типизированный контракт HTTP-API: пути объявлены один раз здесь и используются обеими
// сторонами — сервером через ktor-server-resources, дашбордом через ktor-client-resources.
//
// Строковый путь в клиенте — это копия контракта, которая протухает молча: переименовали роут на
// сервере, компилятор промолчал, сломалось у пользователя. Здесь это ошибка компиляции.

@Resource("/api")
@Serializable
class Api {
    /** Список сервисов. Период необязателен: по умолчанию сервер считает последние пять минут. */
    @Resource("services")
    @Serializable
    class Services(
        val parent: Api = Api(),
        val from: Long? = null,
        val to: Long? = null,
    ) {
        @Resource("{id}")
        @Serializable
        class ById(
            val parent: Services = Services(),
            val id: Long,
        ) {
            @Resource("overview")
            @Serializable
            class Overview(
                val parent: ById,
                val from: Long? = null,
                val to: Long? = null,
            )

            @Resource("timeseries")
            @Serializable
            class TimeSeries(
                val parent: ById,
                val from: Long? = null,
                val to: Long? = null,
                val step: String? = null,
            )

            @Resource("routes")
            @Serializable
            class Routes(
                val parent: ById,
                val from: Long? = null,
                val to: Long? = null,
            )

            @Resource("system")
            @Serializable
            class System(
                val parent: ById,
                val from: Long? = null,
                val to: Long? = null,
            )

            @Resource("slow")
            @Serializable
            class Slow(
                val parent: ById,
                val from: Long? = null,
                val to: Long? = null,
            )

            @Resource("deploys")
            @Serializable
            class Deploys(
                val parent: ById,
                val from: Long? = null,
                val to: Long? = null,
            )
        }
    }

    @Resource("alerts")
    @Serializable
    class Alerts(
        val parent: Api = Api(),
    ) {
        @Resource("history")
        @Serializable
        class History(
            val parent: Alerts = Alerts(),
        )
    }

    /** Внутренние счётчики приёма: без них потери и отброшенные пакеты невидимы. */
    @Resource("self")
    @Serializable
    class Self(
        val parent: Api = Api(),
    )

    @Resource("admin")
    @Serializable
    class Admin(
        val parent: Api = Api(),
    ) {
        @Resource("services/{id}")
        @Serializable
        class Service(
            val parent: Admin = Admin(),
            val id: Long,
        ) {
            @Resource("alerts")
            @Serializable
            class Alerts(
                val parent: Service,
            ) {
                /** Заглушение уведомлений по правилу; глушится только доставка, не сам алерт. */
                @Resource("{rule}/mute")
                @Serializable
                class Mute(
                    val parent: Alerts,
                    val rule: String,
                    val minutes: Long? = null,
                )
            }
        }

        @Resource("alerts/test")
        @Serializable
        class AlertsTest(
            val parent: Admin = Admin(),
        )
    }
}
