package com.ghpr.domain.push

import com.ghpr.domain.refresh.RefreshCoordinator

/**
 * Parses incoming FCM data payload and marks refresh dirty state when valid.
 */
class HandlePushDataUseCase(
    private val refreshCoordinator: RefreshCoordinator,
    private val deliveryTracker: PushDeliveryTracker = PushDeliveryTracker(),
) {
    fun handle(data: Map<String, String>): PushUpdatePayload? {
        val payload = PushPayloadParser.parse(data) ?: return null
        if (!deliveryTracker.markIfNew(payload.deliveryId)) return null

        refreshCoordinator.markPushEventReceived()
        return payload
    }
}
