package ru.workinprogress.metrik.server

actual fun readEnv(name: String): String? = System.getenv(name)
