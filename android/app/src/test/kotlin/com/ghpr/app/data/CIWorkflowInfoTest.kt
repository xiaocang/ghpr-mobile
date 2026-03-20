package com.ghpr.app.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CIWorkflowInfoTest {

    @Test
    fun `totalCount sums all counts`() {
        val wf = CIWorkflowInfo("Build", isWorkflow = true, successCount = 3, failureCount = 1, pendingCount = 2)
        assertEquals(6, wf.totalCount)
    }

    @Test
    fun `totalCount is zero when all counts are zero`() {
        val wf = CIWorkflowInfo("Build", isWorkflow = true, successCount = 0, failureCount = 0, pendingCount = 0)
        assertEquals(0, wf.totalCount)
    }

    @Test
    fun `status is FAILURE when failureCount is positive`() {
        val wf = CIWorkflowInfo("Build", isWorkflow = true, successCount = 5, failureCount = 1, pendingCount = 0)
        assertEquals("FAILURE", wf.status)
    }

    @Test
    fun `status is FAILURE when both failure and pending are positive`() {
        val wf = CIWorkflowInfo("Build", isWorkflow = true, successCount = 0, failureCount = 2, pendingCount = 3)
        assertEquals("FAILURE", wf.status)
    }

    @Test
    fun `status is PENDING when pending positive and no failures`() {
        val wf = CIWorkflowInfo("Build", isWorkflow = true, successCount = 2, failureCount = 0, pendingCount = 1)
        assertEquals("PENDING", wf.status)
    }

    @Test
    fun `status is SUCCESS when only success counts are positive`() {
        val wf = CIWorkflowInfo("Build", isWorkflow = true, successCount = 4, failureCount = 0, pendingCount = 0)
        assertEquals("SUCCESS", wf.status)
    }

    @Test
    fun `status is EXPECTED when all counts are zero`() {
        val wf = CIWorkflowInfo("Build", isWorkflow = true, successCount = 0, failureCount = 0, pendingCount = 0)
        assertEquals("EXPECTED", wf.status)
    }
}
