package com.ghpr.domain.push

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PushPayloadParserTest {

    @Test
    fun `parse should return payload for valid data`() {
        val payload = PushPayloadParser.parse(
            mapOf(
                "type" to "pr_update",
                "repo" to "owner/repo",
                "prNumber" to "42",
                "action" to "synchronize",
                "deliveryId" to "abc-delivery",
                "sentAt" to "1730000100000",
            ),
        )

        assertEquals(
            PushUpdatePayload(
                repo = "owner/repo",
                prNumber = 42,
                action = "synchronize",
                deliveryId = "abc-delivery",
                sentAtMillis = 1730000100000,
            ),
            payload,
        )
    }

    @Test
    fun `parse should return null for unsupported type`() {
        val payload = PushPayloadParser.parse(mapOf("type" to "other"))
        assertNull(payload)
    }

    @Test
    fun `parse should return null when required fields are missing`() {
        val payload = PushPayloadParser.parse(
            mapOf(
                "type" to "pr_update",
                "repo" to "owner/repo",
                "prNumber" to "42",
            ),
        )
        assertNull(payload)
    }

    @Test
    fun `parse should return null for invalid numbers`() {
        val payload = PushPayloadParser.parse(
            mapOf(
                "type" to "pr_update",
                "repo" to "owner/repo",
                "prNumber" to "not-int",
                "action" to "opened",
                "deliveryId" to "abc",
                "sentAt" to "bad",
            ),
        )
        assertNull(payload)
    }
}
