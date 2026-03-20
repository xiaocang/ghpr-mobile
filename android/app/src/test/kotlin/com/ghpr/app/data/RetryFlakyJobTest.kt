package com.ghpr.app.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RetryFlakyJobTest {

    @Test
    fun `totalRetries is always 3`() {
        val job = RetryFlakyJob(
            id = 1,
            repoFullName = "owner/repo",
            prNumber = 42,
            retriesRemaining = 2,
            status = "active",
        )
        assertEquals(3, job.totalRetries)
    }

    @Test
    fun `totalRetries is 3 even when retriesRemaining is 0`() {
        val job = RetryFlakyJob(
            id = 1,
            repoFullName = "owner/repo",
            prNumber = 42,
            retriesRemaining = 0,
            status = "exhausted",
        )
        assertEquals(3, job.totalRetries)
    }

    @Test
    fun `workflowAttempts defaults to empty map`() {
        val job = RetryFlakyJob(
            id = 1,
            repoFullName = "owner/repo",
            prNumber = 42,
            retriesRemaining = 3,
            status = "active",
        )
        assertEquals(emptyMap<String, Int>(), job.workflowAttempts)
    }

    @Test
    fun `workflowAttempts preserves values when provided`() {
        val attempts = mapOf("Build" to 2, "Test" to 1)
        val job = RetryFlakyJob(
            id = 1,
            repoFullName = "owner/repo",
            prNumber = 42,
            retriesRemaining = 1,
            workflowAttempts = attempts,
            status = "active",
        )
        assertEquals(2, job.workflowAttempts["Build"])
        assertEquals(1, job.workflowAttempts["Test"])
    }

    @Test
    fun `createdAt and updatedAt default to null`() {
        val job = RetryFlakyJob(
            id = 1,
            repoFullName = "owner/repo",
            prNumber = 42,
            retriesRemaining = 3,
            status = "active",
        )
        assertEquals(null, job.createdAt)
        assertEquals(null, job.updatedAt)
    }
}
