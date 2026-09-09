package io.github.youndie.metrik.api

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

// Типизированный контракт HTTP-API: пути объявлены один раз здесь и используются обеими
// сторонами — сервером через ktor-server-resources, дашбордом через ktor-client-resources.
//
// Строковый путь в клиенте — это копия контракта, которая протухает молча: переименовали роут на
// сервере, компилятор промолчал, сломалось у пользователя. Здесь это ошибка компиляции.

@Resource("/api")
@Serializable
public class Api {
    /** Список сервисов. Период необязателен: по умолчанию сервер считает последние пять минут. */
    @Resource("services")
    @Serializable
    public class Services(
        public val parent: Api = Api(),
        public val from: Long? = null,
        public val to: Long? = null,
    ) {
        @Resource("{id}")
        @Serializable
        public class ById(
            public val parent: Services = Services(),
            public val id: Long,
        ) {
            @Resource("overview")
            @Serializable
            public class Overview(
                public val parent: ById,
                public val from: Long? = null,
                public val to: Long? = null,
            )

            @Resource("timeseries")
            @Serializable
            public class TimeSeries(
                public val parent: ById,
                public val from: Long? = null,
                public val to: Long? = null,
                public val step: String? = null,
            )

            @Resource("routes")
            @Serializable
            public class Routes(
                public val parent: ById,
                public val from: Long? = null,
                public val to: Long? = null,
            )

            @Resource("system")
            @Serializable
            public class System(
                public val parent: ById,
                public val from: Long? = null,
                public val to: Long? = null,
            )

            @Resource("slow")
            @Serializable
            public class Slow(
                public val parent: ById,
                public val from: Long? = null,
                public val to: Long? = null,
            )

            @Resource("deploys")
            @Serializable
            public class Deploys(
                public val parent: ById,
                public val from: Long? = null,
                public val to: Long? = null,
            )
        }
    }

    @Resource("alerts")
    @Serializable
    public class Alerts(
        public val parent: Api = Api(),
    ) {
        @Resource("history")
        @Serializable
        public class History(
            public val parent: Alerts = Alerts(),
        )
    }

    /** Внутренние счётчики приёма: без них потери и отброшенные пакеты невидимы. */
    @Resource("self")
    @Serializable
    public class Self(
        public val parent: Api = Api(),
    )

    @Resource("admin")
    @Serializable
    public class Admin(
        public val parent: Api = Api(),
    ) {
        @Resource("services/{id}")
        @Serializable
        public class Service(
            public val parent: Admin = Admin(),
            public val id: Long,
        ) {
            @Resource("alerts")
            @Serializable
            public class Alerts(
                public val parent: Service,
            ) {
                /** Заглушение уведомлений по правилу; глушится только доставка, не сам алерт. */
                @Resource("{rule}/mute")
                @Serializable
                public class Mute(
                    public val parent: Alerts,
                    public val rule: String,
                    public val minutes: Long? = null,
                )
            }
        }

        @Resource("alerts/test")
        @Serializable
        public class AlertsTest(
            public val parent: Admin = Admin(),
        )
    }
}
