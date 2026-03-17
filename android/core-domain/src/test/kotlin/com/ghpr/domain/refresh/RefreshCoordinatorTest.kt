package com.ghpr.domain.refresh

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RefreshCoordinatorTest {

    @Test
    fun `first open should run refresh`() {
        val coordinator = RefreshCoordinator(
            minIntervalMillis = 60_000,
            pushRefreshState = PushRefreshState(),
        )

        val decision = coordinator.evaluateOnOpen(nowMillis = 1_000)

        assertEquals(RefreshDecision.Run(RefreshReason.APP_OPEN), decision)
    }

    @Test
    fun `open within interval should skip`() {
        val coordinator = RefreshCoordinator(
            minIntervalMillis = 60_000,
            pushRefreshState = PushRefreshState(),
        )
        coordinator.onRefreshCompleted(nowMillis = 10_000)

        val decision = coordinator.evaluateOnOpen(nowMillis = 20_000)

        assertEquals(
            RefreshDecision.Skip(
                reason = SkipReason.WITHIN_MIN_INTERVAL,
                retryAfterMillis = 50_000,
            ),
            decision,
        )
    }

    @Test
    fun `push event should force run even within interval`() {
        val coordinator = RefreshCoordinator(
            minIntervalMillis = 60_000,
            pushRefreshState = PushRefreshState(),
        )
        coordinator.onRefreshCompleted(nowMillis = 10_000)
        coordinator.markPushEventReceived()

        val decision = coordinator.evaluateOnOpen(nowMillis = 20_000)

        assertEquals(RefreshDecision.Run(RefreshReason.PUSH_EVENT), decision)
    }

    @Test
    fun `manual refresh should always run`() {
        val coordinator = RefreshCoordinator(
            minIntervalMillis = 60_000,
            pushRefreshState = PushRefreshState(),
        )

        val decision = coordinator.evaluateManualRefresh()

        assertEquals(RefreshDecision.Run(RefreshReason.MANUAL), decision)
    }

    @Test
    fun `pending push event should be consumed once`() {
        val state = PushRefreshState()
        assertTrue(!state.consumePending())

        state.markPending()
        assertTrue(state.consumePending())
        assertTrue(!state.consumePending())
    }
}
