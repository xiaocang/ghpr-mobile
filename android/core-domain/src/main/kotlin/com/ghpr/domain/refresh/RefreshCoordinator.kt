package com.ghpr.domain.refresh

class RefreshCoordinator(
    private val minIntervalMillis: Long,
    private val pushRefreshState: PushRefreshState,
    private val lastRefreshStore: LastRefreshStore = InMemoryLastRefreshStore(),
    private val refreshSettingsStore: RefreshSettingsStore? = null,
) {
    fun evaluateOnOpen(nowMillis: Long): RefreshDecision {
        if (pushRefreshState.consumePending()) {
            return RefreshDecision.Run(RefreshReason.PUSH_EVENT)
        }

        val last = lastRefreshStore.readLastRefreshAtMillis()
        if (last == null) {
            return RefreshDecision.Run(RefreshReason.APP_OPEN)
        }

        val elapsed = nowMillis - last
        val effectiveMinInterval = effectiveMinIntervalMillis()
        if (elapsed < effectiveMinInterval) {
            return RefreshDecision.Skip(
                reason = SkipReason.WITHIN_MIN_INTERVAL,
                retryAfterMillis = effectiveMinInterval - elapsed,
            )
        }

        return RefreshDecision.Run(RefreshReason.APP_OPEN)
    }

    fun evaluateManualRefresh(): RefreshDecision = RefreshDecision.Run(RefreshReason.MANUAL)

    fun markPushEventReceived() {
        pushRefreshState.markPending()
    }

    fun onRefreshCompleted(nowMillis: Long) {
        lastRefreshStore.writeLastRefreshAtMillis(nowMillis)
    }

    fun lastRefreshAtMillis(): Long? = lastRefreshStore.readLastRefreshAtMillis()

    private fun effectiveMinIntervalMillis(): Long {
        return refreshSettingsStore?.read()?.minIntervalMillis ?: minIntervalMillis
    }
}
