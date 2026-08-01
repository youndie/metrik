package ru.workinprogress.metrik.server.alert

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.forms.submitForm
import io.ktor.http.parameters

/** Куда уходят уведомления. Интерфейс — чтобы тесты не ходили в сеть. */
interface AlertNotifier {
    suspend fun notify(
        text: String,
        chatId: String?,
    )
}

/** Нотификаций нет — правила всё равно считаются и видны в UI. */
object NoopNotifier : AlertNotifier {
    override suspend fun notify(
        text: String,
        chatId: String?,
    ) = Unit
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
    ) {
        val target = chatId ?: defaultChatId
        if (token.isBlank() || target.isBlank()) return

        client.submitForm(
            url = "https://api.telegram.org/bot$token/sendMessage",
            formParameters =
                parameters {
                    append("chat_id", target)
                    append("text", text)
                },
        )
    }
}
