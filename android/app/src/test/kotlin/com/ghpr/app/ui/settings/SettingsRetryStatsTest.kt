package com.ghpr.app.ui.settings

import com.ghpr.app.data.RetryFlakyJob
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Tests for the retry stats computation logic used in SettingsViewModel.
 * This tests the same filtering and counting logic that SettingsViewModel
 * applies in its state combine flow.
 */
class SettingsRetryStatsTest {

    private fun job(
        id: Int,
        repo: String = "owner/repo",
        pr: Int = id,
        status: String,
        updatedAt: String? = null,
    ) = RetryFlakyJob(
        id = id,
        repoFullName = repo,
        prNumber = pr,
        retriesRemaining = if (status == "active") 2 else 0,
        status = status,
        updatedAt = updatedAt,
    )

    /** Mirrors the logic in SettingsViewModel.state combine block */
    private fun computeStats(jobs: List<RetryFlakyJob>): Pair<Int, List<RetryFlakyJob>> {
        val pendingCount = jobs.count { it.status == "active" }
        val recentResults = jobs
            .filter { it.status in listOf("completed", "exhausted", "cancelled") }
            .sortedByDescending { it.updatedAt }
            .take(5)
        return pendingCount to recentResults
    }

    @Test
    fun `empty jobs list gives zero pending and empty results`() {
        val (pending, recent) = computeStats(emptyList())
        assertEquals(0, pending)
        assertEquals(0, recent.size)
    }

    @Test
    fun `counts active jobs as pending`() {
        val jobs = listOf(
            job(1, status = "active"),
            job(2, status = "active"),
            job(3, status = "completed"),
        )
        val (pending, _) = computeStats(jobs)
        assertEquals(2, pending)
    }

    @Test
    fun `active jobs are not in recent results`() {
        val jobs = listOf(
            job(1, status = "active"),
        )
        val (_, recent) = computeStats(jobs)
        assertEquals(0, recent.size)
    }

    @Test
    fun `completed jobs appear in recent results`() {
        val jobs = listOf(
            job(1, status = "completed", updatedAt = "2024-03-20 10:00:00"),
        )
        val (_, recent) = computeStats(jobs)
        assertEquals(1, recent.size)
        assertEquals("completed", recent[0].status)
    }

    @Test
    fun `exhausted jobs appear in recent results`() {
        val jobs = listOf(
            job(1, status = "exhausted", updatedAt = "2024-03-20 10:00:00"),
        )
        val (_, recent) = computeStats(jobs)
        assertEquals(1, recent.size)
        assertEquals("exhausted", recent[0].status)
    }

    @Test
    fun `cancelled jobs appear in recent results`() {
        val jobs = listOf(
            job(1, status = "cancelled", updatedAt = "2024-03-20 10:00:00"),
        )
        val (_, recent) = computeStats(jobs)
        assertEquals(1, recent.size)
        assertEquals("cancelled", recent[0].status)
    }

    @Test
    fun `recent results sorted by updatedAt descending`() {
        val jobs = listOf(
            job(1, status = "completed", updatedAt = "2024-03-18 10:00:00"),
            job(2, status = "exhausted", updatedAt = "2024-03-20 10:00:00"),
            job(3, status = "completed", updatedAt = "2024-03-19 10:00:00"),
        )
        val (_, recent) = computeStats(jobs)
        assertEquals(3, recent.size)
        assertEquals(2, recent[0].id) // most recent
        assertEquals(3, recent[1].id)
        assertEquals(1, recent[2].id) // oldest
    }

    @Test
    fun `recent results capped at 5`() {
        val jobs = (1..10).map { i ->
            job(i, status = "completed", updatedAt = "2024-03-${10 + i} 10:00:00")
        }
        val (_, recent) = computeStats(jobs)
        assertEquals(5, recent.size)
        // Should be the 5 most recent (ids 10, 9, 8, 7, 6)
        assertEquals(10, recent[0].id)
        assertEquals(6, recent[4].id)
    }

    @Test
    fun `mixed statuses correctly categorized`() {
        val jobs = listOf(
            job(1, status = "active"),
            job(2, status = "completed", updatedAt = "2024-03-20 10:00:00"),
            job(3, status = "exhausted", updatedAt = "2024-03-19 10:00:00"),
            job(4, status = "active"),
            job(5, status = "cancelled", updatedAt = "2024-03-18 10:00:00"),
        )
        val (pending, recent) = computeStats(jobs)
        assertEquals(2, pending)
        assertEquals(3, recent.size)
    }

    @Test
    fun `SettingsUiState defaults for retry fields`() {
        val state = SettingsUiState()
        assertEquals(0, state.retryPendingCount)
        assertEquals(emptyList<RetryFlakyJob>(), state.recentRetryResults)
    }

    @Test
    fun `SettingsUiState copy with retry data`() {
        val jobs = listOf(
            job(1, repo = "acme/widgets", pr = 42, status = "completed", updatedAt = "2024-03-20 10:00:00"),
        )
        val state = SettingsUiState().copy(
            retryPendingCount = 2,
            recentRetryResults = jobs,
        )
        assertEquals(2, state.retryPendingCount)
        assertEquals(1, state.recentRetryResults.size)
        assertEquals("acme/widgets", state.recentRetryResults[0].repoFullName)
    }
}
