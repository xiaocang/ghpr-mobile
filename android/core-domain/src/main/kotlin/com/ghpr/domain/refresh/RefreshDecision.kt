package com.ghpr.domain.refresh

sealed interface RefreshDecision {
    data class Run(val reason: RefreshReason) : RefreshDecision
    data class Skip(val reason: SkipReason, val retryAfterMillis: Long) : RefreshDecision
}

enum class RefreshReason {
    APP_OPEN,
    MANUAL,
    PUSH_EVENT
}

enum class SkipReason {
    WITHIN_MIN_INTERVAL
}
