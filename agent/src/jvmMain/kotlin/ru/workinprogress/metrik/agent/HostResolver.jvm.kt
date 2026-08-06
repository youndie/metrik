package ru.workinprogress.metrik.agent

/**
 * На JVM резолвингом занимается сам `InetSocketAddress` — вмешиваться незачем: рабочий путь
 * трогать рискованнее, чем оставить.
 */
internal actual fun resolveHost(host: String): String = host
