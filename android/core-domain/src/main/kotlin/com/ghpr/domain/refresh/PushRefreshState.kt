package com.ghpr.domain.refresh

/**
 * Tracks pending remote events that should trigger lightweight refresh.
 */
class PushRefreshState {
    private var hasPendingEvent: Boolean = false

    fun markPending() {
        hasPendingEvent = true
    }

    fun consumePending(): Boolean {
        val pending = hasPendingEvent
        hasPendingEvent = false
        return pending
    }
}
