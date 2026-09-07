package io.github.youndie.metrik.server

actual fun readEnv(name: String): String? = System.getenv(name)
