package com.ghpr.app.ui.openprs

import com.ghpr.app.data.OpenPullRequest
import com.ghpr.app.data.PrCategory
import com.ghpr.app.data.RetryFlakyJob
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class OpenPrsUiStateTest {

    private fun pr(owner: String = "owner", repo: String = "repo", number: Int = 1) =
        OpenPullRequest(
            number = number,
            title = "PR #$number",
            url = "https://github.com/$owner/$repo/pull/$number",
            isDraft = false,
            createdAt = "2024-01-01T00:00:00Z",
            updatedAt = "2024-01-01T00:00:00Z",
            authorLogin = "user",
            authorAvatarUrl = "",
            repoOwner = owner,
            repoName = repo,
            ciState = "FAILURE",
            category = PrCategory.AUTHORED,
        )

    @Test
    fun `expandedPrKey defaults to null`() {
        val state = OpenPrsUiState()
        assertNull(state.expandedPrKey)
    }

    @Test
    fun `expanding a PR sets expandedPrKey`() {
        val state = OpenPrsUiState()
        val updated = state.copy(expandedPrKey = "owner/repo#1")
        assertEquals("owner/repo#1", updated.expandedPrKey)
    }

    @Test
    fun `collapsing the same PR sets expandedPrKey to null`() {
        val state = OpenPrsUiState(expandedPrKey = "owner/repo#1")
        val key = "owner/repo#1"
        val newKey = if (state.expandedPrKey == key) null else key
        val updated = state.copy(expandedPrKey = newKey)
        assertNull(updated.expandedPrKey)
    }

    @Test
    fun `expanding a different PR replaces the key`() {
        val state = OpenPrsUiState(expandedPrKey = "owner/repo#1")
        val key = "owner/repo#2"
        val newKey = if (state.expandedPrKey == key) null else key
        val updated = state.copy(expandedPrKey = newKey)
        assertEquals("owner/repo#2", updated.expandedPrKey)
    }

    @Test
    fun `prKey generates correct key format`() {
        val testPr = pr(owner = "acme", repo = "widgets", number = 42)
        assertEquals("acme/widgets#42", OpenPrsViewModel.prKey(testPr))
    }

    @Test
    fun `retryFlakyJobs keyed by prKey`() {
        val job = RetryFlakyJob(
            id = 1,
            repoFullName = "owner/repo",
            prNumber = 42,
            retriesRemaining = 2,
            status = "active",
        )
        val jobs = mapOf("owner/repo#42" to job)
        val state = OpenPrsUiState(retryFlakyJobs = jobs)
        assertEquals(job, state.retryFlakyJobs["owner/repo#42"])
        assertNull(state.retryFlakyJobs["other/repo#1"])
    }

    @Test
    fun `swipe should be disabled when PR is expanded`() {
        val testPr = pr()
        val key = OpenPrsViewModel.prKey(testPr)
        val state = OpenPrsUiState(expandedPrKey = key)

        val isExpanded = state.expandedPrKey == key
        val isCiFailure = testPr.ciState?.uppercase() in listOf("FAILURE", "ERROR")
        val swipeEnabled = isCiFailure && !isExpanded

        assertEquals(false, swipeEnabled)
    }

    @Test
    fun `swipe should be enabled when PR is collapsed and CI failed`() {
        val testPr = pr()
        val key = OpenPrsViewModel.prKey(testPr)
        val state = OpenPrsUiState(expandedPrKey = null)

        val isExpanded = state.expandedPrKey == key
        val isCiFailure = testPr.ciState?.uppercase() in listOf("FAILURE", "ERROR")
        val swipeEnabled = isCiFailure && !isExpanded

        assertEquals(true, swipeEnabled)
    }
}
