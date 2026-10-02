package io.github.youndie.metrik.server

import kotlin.system.exitProcess

actual fun readEnv(name: String): String? = System.getenv(name)

actual fun endProcess(code: Int): Nothing = exitProcess(code)
