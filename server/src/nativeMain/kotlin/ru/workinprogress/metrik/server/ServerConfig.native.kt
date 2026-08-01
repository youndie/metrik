package ru.workinprogress.metrik.server

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
actual fun readEnv(name: String): String? = getenv(name)?.toKString()
