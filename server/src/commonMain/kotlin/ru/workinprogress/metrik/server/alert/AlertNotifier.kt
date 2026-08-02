package ru.workinprogress.metrik.server.alert

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.forms.submitForm
import io.ktor.http.isSuccess
import io.ktor.http.parameters

/**
 * Куда уходят уведомления. Интерфейс — чтобы тесты не ходили в сеть.
 *
 * Возвращает **была ли доставка**, а не «вызов не упал». Разница принципиальна для кнопки
 * «отправить тестовое»: ненастроенный нотификатор обязан отвечать «не доставлено», иначе проверка
 * настройки Telegram отвечает бодрое «отправлено» ровно в том случае, ради которого её нажимают.
 */
interface AlertNotifier {
    suspend fun notify(
        text: String,
        chatId: String?,
    ): Boolean
}

/** Нотификаций нет — правила всё равно считаются и видны в UI, но доставки не происходит. */
object NoopNotifier : AlertNotifier {
    override suspend fun notify(
        text: String,
        chatId: String?,
    ): Boolean = false
}

/**
 * Telegram Bot API. Смотреть в дашборд никто не будет: ценность мониторинга появляется в момент,
 * когда он сам пишет «orders-api: 12 % пятисоток последние 3 минуты».
 */
class TelegramNotifier(
    private val token: String,
    private val defaultChatId: String,
    private val client: HttpClient = HttpClient(CIO),
) : AlertNotifier {
    override suspend fun notify(
        text: String,
        chatId: String?,
    ): Boolean {
        val target = chatId ?: defaultChatId
        if (token.isBlank() || target.isBlank()) return false

        val response =
            client.submitForm(
                url = "https://api.telegram.org/bot$token/sendMessage",
                formParameters =
                    parameters {
                        append("chat_id", target)
                        append("text", text)
                    },
            )

        return response.status.isSuccess()
    }
}
