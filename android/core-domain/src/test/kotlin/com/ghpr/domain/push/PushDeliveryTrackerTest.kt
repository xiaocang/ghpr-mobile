package com.ghpr.domain.push

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PushDeliveryTrackerTest {

    @Test
    fun `markIfNew should reject blank and duplicates`() {
        val tracker = PushDeliveryTracker()

        assertFalse(tracker.markIfNew(""))
        assertTrue(tracker.markIfNew("d1"))
        assertFalse(tracker.markIfNew("d1"))
    }

    @Test
    fun `markIfNew should evict oldest when max reached`() {
        val tracker = PushDeliveryTracker(maxEntries = 2)

        assertTrue(tracker.markIfNew("d1"))
        assertTrue(tracker.markIfNew("d2"))
        assertTrue(tracker.markIfNew("d3"))

        assertTrue(tracker.markIfNew("d1"))
    }
}
