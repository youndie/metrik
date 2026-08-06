package ru.workinprogress.metrik.agent

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.NI_NUMERICHOST
import platform.posix.addrinfo
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.getnameinfo

private const val ADDRESS_BUFFER = 46

/**
 * `getaddrinfo` и никак иначе: собственного резолвера у Kotlin/Native нет.
 *
 * Числовой адрес достаётся через `getnameinfo` с `NI_NUMERICHOST`, а не разбором `sockaddr` по
 * семействам: так одинаково работает и для IPv4, и для IPv6, и не зависит от того, какие поля
 * структур platform.posix выставляет на конкретной платформе.
 *
 * Предпочитается IPv4 — ingest-сокет metrik слушает IPv4, и взять первый попавшийся ответ значило
 * бы иногда отправлять на IPv6-адрес, которого там никто не слушает. IPv6 остаётся запасным
 * вариантом, а не выбором по умолчанию.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun resolveHost(host: String): String =
    memScoped {
        val result = allocPointerTo<addrinfo>()
        if (getaddrinfo(host, null, null, result.ptr) != 0) {
            error("metrik: не удалось разрешить имя $host")
        }

        try {
            val buffer = allocArray<ByteVar>(ADDRESS_BUFFER)
            var fallback: String? = null

            var node = result.value
            while (node != null) {
                val info = node.pointed
                val address = info.ai_addr
                if (address != null &&
                    getnameinfo(
                        address,
                        info.ai_addrlen.convert(),
                        buffer,
                        ADDRESS_BUFFER.convert(),
                        null,
                        0.convert(),
                        NI_NUMERICHOST,
                    ) == 0
                ) {
                    val numeric = buffer.toKString()
                    if (info.ai_family == AF_INET) return@memScoped numeric
                    if (fallback == null) fallback = numeric
                }
                node = info.ai_next
            }

            fallback ?: error("metrik: имя $host не дало ни одного адреса")
        } finally {
            freeaddrinfo(result.value)
        }
    }
