package com.ghpr.app.ui.openprs

import com.ghpr.app.data.CIWorkflowInfo
import com.ghpr.app.data.OpenPullRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CiStatusTextTest {

    private fun pr(
        ciState: String?,
        ciWorkflows: List<CIWorkflowInfo> = emptyList(),
    ) = OpenPullRequest(
        number = 1,
        title = "Test PR",
        url = "https://github.com/owner/repo/pull/1",
        isDraft = false,
        createdAt = "2024-01-01T00:00:00Z",
        updatedAt = "2024-01-01T00:00:00Z",
        authorLogin = "user",
        authorAvatarUrl = "",
        repoOwner = "owner",
        repoName = "repo",
        ciState = ciState,
        ciWorkflows = ciWorkflows,
    )

    @Test
    fun `returns ci when ciState is null`() {
        assertEquals("ci", ciStatusText(pr(ciState = null)))
    }

    @Test
    fun `returns lowercase ciState when no workflows`() {
        assertEquals("failure", ciStatusText(pr(ciState = "FAILURE")))
        assertEquals("success", ciStatusText(pr(ciState = "SUCCESS")))
        assertEquals("pending", ciStatusText(pr(ciState = "PENDING")))
    }

    @Test
    fun `shows total workflow count for SUCCESS`() {
        val workflows = listOf(
            CIWorkflowInfo("Build", isWorkflow = true, successCount = 3, failureCount = 0, pendingCount = 0),
            CIWorkflowInfo("Test", isWorkflow = true, successCount = 5, failureCount = 0, pendingCount = 0),
        )
        assertEquals("2wf", ciStatusText(pr(ciState = "SUCCESS", ciWorkflows = workflows)))
    }

    @Test
    fun `shows failed workflow count and total failed tasks for FAILURE`() {
        val workflows = listOf(
            CIWorkflowInfo("Build", isWorkflow = true, successCount = 2, failureCount = 1, pendingCount = 0),
            CIWorkflowInfo("Test", isWorkflow = true, successCount = 0, failureCount = 3, pendingCount = 0),
            CIWorkflowInfo("Lint", isWorkflow = true, successCount = 4, failureCount = 0, pendingCount = 0),
        )
        // 2 workflows have failures, 3 total workflows, 4 total failed tasks (1+3)
        assertEquals("2/3wf\u00B74", ciStatusText(pr(ciState = "FAILURE", ciWorkflows = workflows)))
    }

    @Test
    fun `shows failed workflow count for ERROR state`() {
        val workflows = listOf(
            CIWorkflowInfo("Build", isWorkflow = true, successCount = 0, failureCount = 2, pendingCount = 0),
        )
        assertEquals("1/1wf\u00B72", ciStatusText(pr(ciState = "ERROR", ciWorkflows = workflows)))
    }

    @Test
    fun `shows done workflow count for PENDING`() {
        val workflows = listOf(
            CIWorkflowInfo("Build", isWorkflow = true, successCount = 3, failureCount = 0, pendingCount = 0),
            CIWorkflowInfo("Test", isWorkflow = true, successCount = 0, failureCount = 1, pendingCount = 0),
            CIWorkflowInfo("Deploy", isWorkflow = true, successCount = 0, failureCount = 0, pendingCount = 2),
        )
        // Build (SUCCESS) and Test (FAILURE) are done, Deploy (PENDING) is not
        assertEquals("2/3wf", ciStatusText(pr(ciState = "PENDING", ciWorkflows = workflows)))
    }

    @Test
    fun `returns lowercase for unknown state with workflows`() {
        val workflows = listOf(
            CIWorkflowInfo("Build", isWorkflow = true, successCount = 1, failureCount = 0, pendingCount = 0),
        )
        assertEquals("expected", ciStatusText(pr(ciState = "EXPECTED", ciWorkflows = workflows)))
    }

    @Test
    fun `single workflow failure shows 1 of 1`() {
        val workflows = listOf(
            CIWorkflowInfo("CI", isWorkflow = true, successCount = 0, failureCount = 1, pendingCount = 0),
        )
        assertEquals("1/1wf\u00B71", ciStatusText(pr(ciState = "FAILURE", ciWorkflows = workflows)))
    }

    @Test
    fun `all workflows passing shows 0 failed for FAILURE state`() {
        // Edge case: ciState is FAILURE but all workflows show success (stale data)
        val workflows = listOf(
            CIWorkflowInfo("Build", isWorkflow = true, successCount = 3, failureCount = 0, pendingCount = 0),
        )
        assertEquals("0/1wf\u00B70", ciStatusText(pr(ciState = "FAILURE", ciWorkflows = workflows)))
    }
}
