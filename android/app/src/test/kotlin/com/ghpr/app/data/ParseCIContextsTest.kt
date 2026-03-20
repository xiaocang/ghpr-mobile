package com.ghpr.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ParseCIContextsTest {

    @Test
    fun `returns empty result for null rollup`() {
        val result = parseCIContexts(null)
        assertEquals(0, result.successCount)
        assertEquals(0, result.failureCount)
        assertEquals(0, result.pendingCount)
        assertFalse(result.isRunning)
        assertTrue(result.workflows.isEmpty())
    }

    @Test
    fun `returns empty result for rollup without contexts`() {
        val rollup = JSONObject().put("state", "SUCCESS")
        val result = parseCIContexts(rollup)
        assertEquals(0, result.successCount)
        assertEquals(0, result.failureCount)
        assertEquals(0, result.pendingCount)
        assertTrue(result.workflows.isEmpty())
    }

    @Test
    fun `counts CheckRun successes`() {
        val rollup = buildRollup(
            checkRun("lint", "SUCCESS", "Build"),
            checkRun("test", "SUCCESS", "Build"),
        )
        val result = parseCIContexts(rollup)
        assertEquals(2, result.successCount)
        assertEquals(0, result.failureCount)
        assertEquals(0, result.pendingCount)
        assertFalse(result.isRunning)
        assertEquals(1, result.workflows.size)
        assertEquals("Build", result.workflows[0].name)
        assertEquals(2, result.workflows[0].successCount)
        assertTrue(result.workflows[0].isWorkflow)
    }

    @Test
    fun `counts CheckRun failures`() {
        val rollup = buildRollup(
            checkRun("lint", "SUCCESS", "Build"),
            checkRun("test", "FAILURE", "Build"),
            checkRun("e2e", "TIMED_OUT", "Test"),
        )
        val result = parseCIContexts(rollup)
        assertEquals(1, result.successCount)
        assertEquals(2, result.failureCount)
        assertEquals(0, result.pendingCount)
        val buildWf = result.workflows.find { it.name == "Build" }!!
        assertEquals(1, buildWf.successCount)
        assertEquals(1, buildWf.failureCount)
        val testWf = result.workflows.find { it.name == "Test" }!!
        assertEquals(0, testWf.successCount)
        assertEquals(1, testWf.failureCount)
    }

    @Test
    fun `null conclusion treated as pending and sets isRunning`() {
        val rollup = buildRollup(
            checkRun("lint", null, "Build"),
        )
        val result = parseCIContexts(rollup)
        assertEquals(0, result.successCount)
        assertEquals(0, result.failureCount)
        assertEquals(1, result.pendingCount)
        assertTrue(result.isRunning)
        assertEquals("PENDING", result.workflows[0].status)
    }

    @Test
    fun `blank conclusion treated as pending`() {
        val rollup = buildRollup(
            checkRun("lint", "", "Build"),
        )
        val result = parseCIContexts(rollup)
        assertEquals(1, result.pendingCount)
        assertTrue(result.isRunning)
    }

    @Test
    fun `NEUTRAL and SKIPPED are treated as success`() {
        val rollup = buildRollup(
            checkRun("skip-check", "NEUTRAL", "CI"),
            checkRun("skipped-job", "SKIPPED", "CI"),
        )
        val result = parseCIContexts(rollup)
        assertEquals(2, result.successCount)
        assertEquals(0, result.failureCount)
    }

    @Test
    fun `CANCELLED and ACTION_REQUIRED and STARTUP_FAILURE are treated as failure`() {
        val rollup = buildRollup(
            checkRun("a", "CANCELLED", "CI"),
            checkRun("b", "ACTION_REQUIRED", "CI"),
            checkRun("c", "STARTUP_FAILURE", "CI"),
        )
        val result = parseCIContexts(rollup)
        assertEquals(0, result.successCount)
        assertEquals(3, result.failureCount)
    }

    @Test
    fun `CheckRun without workflow uses check name as group`() {
        val rollup = buildRollup(
            checkRunNoWorkflow("codecov", "SUCCESS"),
        )
        val result = parseCIContexts(rollup)
        assertEquals(1, result.workflows.size)
        assertEquals("codecov", result.workflows[0].name)
        assertFalse(result.workflows[0].isWorkflow)
    }

    @Test
    fun `StatusContext SUCCESS is counted`() {
        val rollup = buildRollup(
            statusContext("ci/circleci", "SUCCESS"),
        )
        val result = parseCIContexts(rollup)
        assertEquals(1, result.successCount)
        assertEquals(0, result.failureCount)
        assertEquals(1, result.workflows.size)
        assertEquals("ci/circleci", result.workflows[0].name)
        assertFalse(result.workflows[0].isWorkflow)
    }

    @Test
    fun `StatusContext FAILURE and ERROR are counted as failure`() {
        val rollup = buildRollup(
            statusContext("ci/travis", "FAILURE"),
            statusContext("ci/jenkins", "ERROR"),
        )
        val result = parseCIContexts(rollup)
        assertEquals(0, result.successCount)
        assertEquals(2, result.failureCount)
    }

    @Test
    fun `StatusContext PENDING is counted as pending`() {
        val rollup = buildRollup(
            statusContext("ci/deploy", "PENDING"),
        )
        val result = parseCIContexts(rollup)
        assertEquals(1, result.pendingCount)
        assertTrue(result.isRunning)
    }

    @Test
    fun `mixed CheckRun and StatusContext are all counted`() {
        val rollup = buildRollup(
            checkRun("lint", "SUCCESS", "Build"),
            checkRun("test", "FAILURE", "Build"),
            statusContext("ci/external", "SUCCESS"),
            checkRun("e2e", null, "E2E"),
        )
        val result = parseCIContexts(rollup)
        assertEquals(2, result.successCount)  // lint + ci/external
        assertEquals(1, result.failureCount)  // test
        assertEquals(1, result.pendingCount)  // e2e (null conclusion)
        assertTrue(result.isRunning)
        assertEquals(3, result.workflows.size) // Build, ci/external, E2E
    }

    @Test
    fun `workflows sorted by failure first then pending then success then by name`() {
        val rollup = buildRollup(
            checkRun("a", "SUCCESS", "Zebra"),
            checkRun("b", null, "Middle"),
            checkRun("c", "FAILURE", "Alpha"),
        )
        val result = parseCIContexts(rollup)
        assertEquals("Alpha", result.workflows[0].name)   // failure first
        assertEquals("Middle", result.workflows[1].name)   // pending second
        assertEquals("Zebra", result.workflows[2].name)    // success third
    }

    @Test
    fun `multiple check runs grouped under same workflow`() {
        val rollup = buildRollup(
            checkRun("lint", "SUCCESS", "CI"),
            checkRun("test", "SUCCESS", "CI"),
            checkRun("build", "FAILURE", "CI"),
        )
        val result = parseCIContexts(rollup)
        assertEquals(1, result.workflows.size)
        val ci = result.workflows[0]
        assertEquals("CI", ci.name)
        assertEquals(2, ci.successCount)
        assertEquals(1, ci.failureCount)
        assertEquals(3, ci.totalCount)
    }

    @Test
    fun `empty contexts nodes array returns empty result`() {
        val rollup = JSONObject().apply {
            put("contexts", JSONObject().apply {
                put("nodes", JSONArray())
            })
        }
        val result = parseCIContexts(rollup)
        assertEquals(0, result.successCount)
        assertEquals(0, result.failureCount)
        assertEquals(0, result.pendingCount)
        assertFalse(result.isRunning)
        assertTrue(result.workflows.isEmpty())
    }

    // --- Helper builders ---

    private fun checkRun(name: String, conclusion: String?, workflowName: String): JSONObject {
        return JSONObject().apply {
            put("name", name)
            if (conclusion != null) put("conclusion", conclusion) else put("conclusion", JSONObject.NULL)
            put("checkSuite", JSONObject().apply {
                put("workflowRun", JSONObject().apply {
                    put("workflow", JSONObject().apply {
                        put("name", workflowName)
                    })
                })
            })
        }
    }

    private fun checkRunNoWorkflow(name: String, conclusion: String?): JSONObject {
        return JSONObject().apply {
            put("name", name)
            if (conclusion != null) put("conclusion", conclusion) else put("conclusion", JSONObject.NULL)
            put("checkSuite", JSONObject().apply {
                put("workflowRun", JSONObject.NULL)
            })
        }
    }

    private fun statusContext(context: String, state: String): JSONObject {
        return JSONObject().apply {
            put("context", context)
            put("state", state)
        }
    }

    private fun buildRollup(vararg nodes: JSONObject): JSONObject {
        val nodesArray = JSONArray()
        nodes.forEach { nodesArray.put(it) }
        return JSONObject().apply {
            put("contexts", JSONObject().apply {
                put("nodes", nodesArray)
            })
        }
    }
}
