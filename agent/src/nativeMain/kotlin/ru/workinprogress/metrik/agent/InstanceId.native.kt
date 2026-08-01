package ru.workinprogress.metrik.agent

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

/** В k8s `HOSTNAME` это имя пода — самый дешёвый и точный идентификатор инстанса. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun defaultInstanceId(): String = getenv("HOSTNAME")?.toKString() ?: "unknown"
