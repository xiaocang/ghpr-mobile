package com.ghpr.domain.push

/**
 * In-memory deduplication for push deliveries.
 *
 * Keeps a bounded set of recent delivery IDs to avoid processing duplicated
 * push notifications more than once.
 */
class PushDeliveryTracker(
    private val maxEntries: Int = 256,
) {
    private val order = ArrayDeque<String>()
    private val seen = HashSet<String>()

    fun markIfNew(deliveryId: String): Boolean {
        if (deliveryId.isBlank()) return false
        if (seen.contains(deliveryId)) return false

        seen.add(deliveryId)
        order.addLast(deliveryId)

        while (order.size > maxEntries) {
            val evicted = order.removeFirst()
            seen.remove(evicted)
        }

        return true
    }
}
