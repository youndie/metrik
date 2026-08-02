package ru.workinprogress.metrik.web.core.domain

import ru.workinprogress.metrik.api.Step

/**
 * Диапазон периода, общий для «Обзора» и экрана сервиса.
 *
 * Для «1 ч»/«24 ч» просим минутный шаг: контракт гарантирует минутные окна только за последние
 * 48 часов, оба диапазона укладываются. Для «7 д» просим часовой — иначе сервер всё равно молча
 * отдаст часовой и пометит это в ответе (docs/api/endpoint-query.md, «Правила ответов»), а честно
 * заявленное намерение лучше отражает то, что реально произойдёт.
 */
enum class Range(
    val label: String,
    val ms: Long,
    val step: Step,
) {
    HOUR("1 ч", 60 * 60 * 1000L, Step.MINUTE),
    DAY("24 ч", 24 * 60 * 60 * 1000L, Step.MINUTE),
    WEEK("7 д", 7 * 24 * 60 * 60 * 1000L, Step.HOUR),
}
