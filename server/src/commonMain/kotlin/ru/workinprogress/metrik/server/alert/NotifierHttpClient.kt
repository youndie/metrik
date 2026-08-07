package ru.workinprogress.metrik.server.alert

import io.ktor.client.HttpClient

/**
 * Клиент для исходящих уведомлений.
 *
 * Движок разный по платформам не из вкусовщины: **CIO не умеет TLS на Kotlin/Native** и падает с
 * «TLS sessions are not supported on Native platform». Telegram живёт только по https, поэтому в
 * нативном сервере — а он и есть продовый — доставка не работала вовсе. Наружу это выглядело как
 * «Telegram не настроен», потому что уведомления по контракту глотают свои ошибки.
 */
internal expect fun notifierHttpClient(): HttpClient
