package ru.workinprogress.metrik.agent

/** В k8s `HOSTNAME` это имя пода — самый дешёвый и точный идентификатор инстанса. */
internal actual fun defaultInstanceId(): String =
    System.getenv("HOSTNAME")
        ?: System.getenv("COMPUTERNAME")
        ?: "unknown"
