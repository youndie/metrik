package io.github.youndie.metrik.server

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import kotlin.system.exitProcess

@OptIn(ExperimentalForeignApi::class)
actual fun readEnv(name: String): String? = getenv(name)?.toKString()

actual fun endProcess(code: Int): Nothing = exitProcess(code)
