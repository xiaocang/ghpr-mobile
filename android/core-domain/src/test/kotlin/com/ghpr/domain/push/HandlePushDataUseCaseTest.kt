package com.ghpr.domain.push

import com.ghpr.domain.refresh.PushRefreshState
import com.ghpr.domain.refresh.RefreshCoordinator
import com.ghpr.domain.refresh.RefreshDecision
import com.ghpr.domain.refresh.RefreshReason
import com.ghpr.domain.refresh.SkipReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HandlePushDataUseCaseTest {

    @Test
    fun `handle should mark pending refresh and return parsed payload when valid`() {
        val coordinator = RefreshCoordinator(
            minIntervalMillis = 60_000,
            pushRefreshState = PushRefreshState(),
        )
        coordinator.onRefreshCompleted(nowMillis = 10_000)

        val useCase = HandlePushDataUseCase(coordinator)

        val payload = useCase.handle(
            mapOf(
                "type" to "pr_update",
                "repo" to "owner/repo",
                "prNumber" to "88",
                "action" to "opened",
                "deliveryId" to "delivery-1",
                "sentAt" to "1730000100000",
            ),
        )

        assertEquals(88, payload?.prNumber)
        assertEquals(
            RefreshDecision.Run(RefreshReason.PUSH_EVENT),
            coordinator.evaluateOnOpen(nowMillis = 20_000),
        )
    }

    @Test
    fun `handle should ignore duplicated delivery id`() {
        val coordinator = RefreshCoordinator(
            minIntervalMillis = 60_000,
            pushRefreshState = PushRefreshState(),
        )
        coordinator.onRefreshCompleted(nowMillis = 10_000)

        val useCase = HandlePushDataUseCase(coordinator)
        val data = mapOf(
            "type" to "pr_update",
            "repo" to "owner/repo",
            "prNumber" to "88",
            "action" to "opened",
            "deliveryId" to "delivery-dup",
            "sentAt" to "1730000100000",
        )

        val first = useCase.handle(data)
        val second = useCase.handle(data)

        assertEquals("delivery-dup", first?.deliveryId)
        assertNull(second)
    }

    @Test
    fun `handle should ignore invalid payload and not mark refresh pending`() {
        val coordinator = RefreshCoordinator(
            minIntervalMillis = 60_000,
            pushRefreshState = PushRefreshState(),
        )
        coordinator.onRefreshCompleted(nowMillis = 10_000)

        val useCase = HandlePushDataUseCase(coordinator)
        val payload = useCase.handle(mapOf("type" to "other"))

        assertNull(payload)
        assertEquals(
            RefreshDecision.Skip(
                reason = SkipReason.WITHIN_MIN_INTERVAL,
                retryAfterMillis = 50_000,
            ),
            coordinator.evaluateOnOpen(nowMillis = 20_000),
        )
    }
}
