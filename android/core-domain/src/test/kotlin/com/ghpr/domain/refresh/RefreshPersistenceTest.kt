package com.ghpr.domain.refresh

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RefreshPersistenceTest {

    @Test
    fun `coordinator should persist and read last refresh from store`() {
        val store = InMemoryLastRefreshStore(initialValue = 7_000)
        val coordinator = RefreshCoordinator(
            minIntervalMillis = 60_000,
            pushRefreshState = PushRefreshState(),
            lastRefreshStore = store,
        )

        assertEquals(7_000, coordinator.lastRefreshAtMillis())

        coordinator.onRefreshCompleted(nowMillis = 15_000)

        assertEquals(15_000, store.readLastRefreshAtMillis())
    }

    @Test
    fun `coordinator should use settings store min interval when provided`() {
        val settingsStore = InMemoryRefreshSettingsStore(
            initialSettings = RefreshSettings(minIntervalMillis = 10_000),
        )
        val coordinator = RefreshCoordinator(
            minIntervalMillis = 60_000,
            pushRefreshState = PushRefreshState(),
            refreshSettingsStore = settingsStore,
        )
        coordinator.onRefreshCompleted(nowMillis = 1_000)

        val decision = coordinator.evaluateOnOpen(nowMillis = 5_000)

        assertEquals(
            RefreshDecision.Skip(
                reason = SkipReason.WITHIN_MIN_INTERVAL,
                retryAfterMillis = 6_000,
            ),
            decision,
        )
    }
}
