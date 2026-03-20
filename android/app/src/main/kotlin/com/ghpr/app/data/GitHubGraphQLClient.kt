package com.ghpr.app.data

import android.util.Log
import com.ghpr.app.auth.GitHubOAuthManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "GitHubGraphQL"

data class FetchOpenPrsResult(
    val pullRequests: List<OpenPullRequest> = emptyList(),
    val ssoRequired: List<SsoAuthorizationRequired> = emptyList(),
    val missingRepoScope: Boolean = false,
)

class GitHubGraphQLClient(
    private val gitHubOAuthManager: GitHubOAuthManager,
) {
    private val client = OkHttpClient()
    private val jsonMediaType = "application/json".toMediaType()
    private val ssoHeaderRegex =
        Regex("""url=(https://github\.com/orgs/([^/]+)/sso[^,;]*)""")

    suspend fun fetchOpenPrs(repos: List<String>): FetchOpenPrsResult {
        val token = gitHubOAuthManager.getToken()
            ?: return FetchOpenPrsResult()
        val username = gitHubOAuthManager.getLogin()
            ?: return FetchOpenPrsResult()
        if (repos.isEmpty()) return FetchOpenPrsResult()

        return coroutineScope {
            val batchResults = repos.chunked(5).map { batch ->
                async { queryBatch(token, username, batch) }
            }.awaitAll()

            val allPrs = batchResults.flatMap { it.pullRequests }
            val allSso = batchResults.flatMap { it.ssoRequired }.toMutableList()
            val missingRepoScope = batchResults.any { it.missingRepoScope }

            // Repos that returned 0 PRs in search may be SSO-blocked (search silently
            // excludes inaccessible repos). Probe them directly to trigger FORBIDDEN/SSO.
            // Skip probing if we already know the token lacks repo scope.
            if (!missingRepoScope) {
                val reposWithPrs = allPrs.map { "${it.repoOwner}/${it.repoName}" }.toSet()
                val reposToProbe = repos.filter { it !in reposWithPrs }
                if (reposToProbe.isNotEmpty()) {
                    Log.d(TAG, "Probing ${reposToProbe.size} repos with 0 search results: $reposToProbe")
                    val probeResults = reposToProbe.chunked(5).map { batch ->
                        async { probeRepos(token, batch) }
                    }.awaitAll()
                    allSso.addAll(probeResults.flatten())
                }
            }

            FetchOpenPrsResult(
                pullRequests = allPrs,
                ssoRequired = allSso.distinctBy { it.orgName },
                missingRepoScope = missingRepoScope,
            )
        }
    }

    private data class BatchResult(
        val pullRequests: List<OpenPullRequest>,
        val ssoRequired: List<SsoAuthorizationRequired>,
        val missingRepoScope: Boolean = false,
    )

    private val prFragment = """
        ... on PullRequest {
            number
            title
            url
            isDraft
            createdAt
            updatedAt
            author { login avatarUrl }
            repository { owner { login } name }
            reviewThreads(last: 20) {
                nodes {
                    isResolved
                    isOutdated
                }
                pageInfo {
                    hasPreviousPage
                    startCursor
                }
            }
            latestReviews(first: 20) {
                nodes {
                    state
                }
            }
            commits(last: 1) {
                nodes {
                    commit {
                        statusCheckRollup {
                            state
                            contexts(first: 100) {
                                nodes {
                                    ... on CheckRun {
                                        name
                                        conclusion
                                        checkSuite {
                                            workflowRun {
                                                workflow { name }
                                            }
                                        }
                                    }
                                    ... on StatusContext {
                                        context
                                        state
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    """.trimIndent()

    private suspend fun queryBatch(
        token: String,
        username: String,
        repos: List<String>,
    ): BatchResult {
        val repoFilter = repos.joinToString(" ") { "repo:$it" }
        val query = """
            query {
              authored: search(query: "is:pr is:open author:$username $repoFilter", type: ISSUE, first: 100) {
                nodes { $prFragment }
              }
              reviewRequested: search(query: "is:pr is:open review-requested:$username $repoFilter", type: ISSUE, first: 100) {
                nodes { $prFragment }
              }
            }
        """.trimIndent()

        val body = JSONObject().apply {
            put("query", query)
        }.toString().toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url("https://api.github.com/graphql")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/json")
            .post(body)
            .build()

        val (payload, ssoEntries, missingRepo) = executeRequest(request)

        val json = JSONObject(payload)
        if (json.has("errors")) {
            val errors = json.getJSONArray("errors")
            val allForbidden = (0 until errors.length()).all { i ->
                errors.getJSONObject(i).optString("type") == "FORBIDDEN"
            }
            if (!allForbidden) {
                throw Exception(
                    "GitHub GraphQL error: ${errors.getJSONObject(0).optString("message")}",
                )
            }
        }

        val data = json.optJSONObject("data")
        val authoredNodes = data?.optJSONObject("authored")?.optJSONArray("nodes")
        val reviewNodes = data?.optJSONObject("reviewRequested")?.optJSONArray("nodes")

        val results = mutableListOf<OpenPullRequest>()
        parseNodes(token, authoredNodes, PrCategory.AUTHORED, results)
        parseNodes(token, reviewNodes, PrCategory.REVIEW_REQUESTED, results)

        return BatchResult(results, ssoEntries, missingRepo)
    }

    private data class ApiResponse(
        val payload: String,
        val ssoEntries: List<SsoAuthorizationRequired>,
        val missingRepoScope: Boolean,
    )

    private suspend fun executeRequest(request: Request): ApiResponse {
        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                Log.d(TAG, "Response headers: ${response.headers}")
                Log.d(TAG, "Response body: $raw")
                if (!response.isSuccessful) {
                    throw Exception("GitHub GraphQL request failed: HTTP ${response.code}")
                }
                val grantedScopes = response.header("X-OAuth-Scopes").orEmpty()
                val missingRepo = "repo" !in grantedScopes
                if (missingRepo) {
                    Log.d(TAG, "Token is missing 'repo' scope. Granted: $grantedScopes")
                }
                val sso = mutableListOf<SsoAuthorizationRequired>()
                response.header("X-GitHub-SSO")?.let { header ->
                    ssoHeaderRegex.findAll(header).forEach { match ->
                        sso.add(
                            SsoAuthorizationRequired(
                                orgName = match.groupValues[2],
                                authUrl = match.groupValues[1],
                            ),
                        )
                    }
                }
                ApiResponse(raw, sso, missingRepo)
            }
        }
    }

    private suspend fun parseNodes(
        token: String,
        nodes: JSONArray?,
        category: PrCategory,
        out: MutableList<OpenPullRequest>,
    ) {
        if (nodes == null) return
        for (i in 0 until nodes.length()) {
            val node = nodes.getJSONObject(i)
            if (!node.has("number")) continue

            val author = node.optJSONObject("author")
            val repo = node.optJSONObject("repository")
            val commits = node.optJSONObject("commits")
                ?.optJSONArray("nodes")
            val statusCheckRollup = commits
                ?.optJSONObject(0)
                ?.optJSONObject("commit")
                ?.optJSONObject("statusCheckRollup")
            val ciState = statusCheckRollup?.optString("state")
            val ciParsed = parseCIContexts(statusCheckRollup)
            val repoOwner = repo?.optJSONObject("owner")?.optString("login", "").orEmpty()
            val repoName = repo?.optString("name", "").orEmpty()
            val approvalCount = node.optJSONObject("latestReviews")
                ?.optJSONArray("nodes")
                ?.let { reviews ->
                    (0 until reviews.length()).count { idx ->
                        reviews.optJSONObject(idx)?.optString("state") == "APPROVED"
                    }
                } ?: 0
            val reviewThreads = node.optJSONObject("reviewThreads")
            var unresolvedCount = countUnresolvedThreads(reviewThreads?.optJSONArray("nodes"))
            if (category == PrCategory.AUTHORED) {
                val pageInfo = reviewThreads?.optJSONObject("pageInfo")
                val hasPreviousPage = pageInfo?.optBoolean("hasPreviousPage", false) == true
                val startCursor = pageInfo?.optString("startCursor").orEmpty()
                if (hasPreviousPage && startCursor.isNotBlank() && repoOwner.isNotBlank() && repoName.isNotBlank()) {
                    unresolvedCount += fetchAdditionalUnresolvedCount(
                        token = token,
                        repoOwner = repoOwner,
                        repoName = repoName,
                        prNumber = node.getInt("number"),
                        beforeCursor = startCursor,
                    )
                }
            }

            out.add(
                OpenPullRequest(
                    number = node.getInt("number"),
                    title = node.getString("title"),
                    url = node.getString("url"),
                    isDraft = node.optBoolean("isDraft", false),
                    createdAt = node.getString("createdAt"),
                    updatedAt = node.getString("updatedAt"),
                    authorLogin = author?.optString("login", "").orEmpty(),
                    authorAvatarUrl = author?.optString("avatarUrl", "").orEmpty(),
                    repoOwner = repoOwner,
                    repoName = repoName,
                    ciState = ciState,
                    approvalCount = approvalCount,
                    unresolvedCount = unresolvedCount,
                    category = category,
                    checkSuccessCount = ciParsed.successCount,
                    checkFailureCount = ciParsed.failureCount,
                    checkPendingCount = ciParsed.pendingCount,
                    ciWorkflows = ciParsed.workflows,
                    ciIsRunning = ciParsed.isRunning,
                ),
            )
        }
    }

    private data class CIParsed(
        val successCount: Int,
        val failureCount: Int,
        val pendingCount: Int,
        val isRunning: Boolean,
        val workflows: List<CIWorkflowInfo>,
    )

    private fun parseCIContexts(rollup: JSONObject?): CIParsed {
        val empty = CIParsed(0, 0, 0, false, emptyList())
        val contextsNodes = rollup
            ?.optJSONObject("contexts")
            ?.optJSONArray("nodes")
            ?: return empty

        var successCount = 0
        var failureCount = 0
        var pendingCount = 0
        var isRunning = false
        // workflow name -> mutable counts
        val workflowMap = mutableMapOf<String, IntArray>() // [success, failure, pending]
        val workflowIsWf = mutableMapOf<String, Boolean>()

        for (i in 0 until contextsNodes.length()) {
            val ctx = contextsNodes.optJSONObject(i) ?: continue

            if (ctx.has("name")) {
                // CheckRun
                val conclusion = ctx.optString("conclusion", "").ifBlank { null }
                val workflowName = ctx.optJSONObject("checkSuite")
                    ?.optJSONObject("workflowRun")
                    ?.optJSONObject("workflow")
                    ?.optString("name")
                val groupName = workflowName ?: ctx.optString("name", "check")
                val isWf = workflowName != null

                val counts = workflowMap.getOrPut(groupName) { intArrayOf(0, 0, 0) }
                workflowIsWf.putIfAbsent(groupName, isWf)

                when (conclusion?.uppercase()) {
                    "SUCCESS", "NEUTRAL", "SKIPPED" -> {
                        successCount++
                        counts[0]++
                    }
                    "FAILURE", "TIMED_OUT", "CANCELLED", "ACTION_REQUIRED", "STARTUP_FAILURE" -> {
                        failureCount++
                        counts[1]++
                    }
                    else -> {
                        // null conclusion = in progress or queued
                        pendingCount++
                        counts[2]++
                        isRunning = true
                    }
                }
            } else if (ctx.has("context")) {
                // StatusContext
                val state = ctx.optString("state", "").uppercase()
                val contextName = ctx.optString("context", "status")
                val counts = workflowMap.getOrPut(contextName) { intArrayOf(0, 0, 0) }
                workflowIsWf.putIfAbsent(contextName, false)

                when (state) {
                    "SUCCESS" -> { successCount++; counts[0]++ }
                    "FAILURE", "ERROR" -> { failureCount++; counts[1]++ }
                    else -> { pendingCount++; counts[2]++; isRunning = true }
                }
            }
        }

        val workflows = workflowMap.map { (name, counts) ->
            CIWorkflowInfo(
                name = name,
                isWorkflow = workflowIsWf[name] ?: false,
                successCount = counts[0],
                failureCount = counts[1],
                pendingCount = counts[2],
            )
        }.sortedWith(compareBy(
            { if (it.failureCount > 0) 0 else if (it.pendingCount > 0) 1 else 2 },
            { it.name },
        ))

        return CIParsed(successCount, failureCount, pendingCount, isRunning, workflows)
    }

    private fun countUnresolvedThreads(nodes: JSONArray?): Int {
        if (nodes == null) return 0
        var count = 0
        for (i in 0 until nodes.length()) {
            val thread = nodes.optJSONObject(i) ?: continue
            val unresolved = !thread.optBoolean("isResolved", false) && !thread.optBoolean("isOutdated", false)
            if (unresolved) count++
        }
        return count
    }

    private suspend fun fetchAdditionalUnresolvedCount(
        token: String,
        repoOwner: String,
        repoName: String,
        prNumber: Int,
        beforeCursor: String,
    ): Int {
        var unresolvedCount = 0
        var cursor = beforeCursor
        var hasPrevious = true

        while (hasPrevious && cursor.isNotBlank()) {
            val escapedCursor = cursor.replace("\"", "\\\"")
            val query = """
                query {
                  repository(owner: "${repoOwner.replace("\"", "\\\"")}", name: "${repoName.replace("\"", "\\\"")}") {
                    pullRequest(number: $prNumber) {
                      reviewThreads(last: 20, before: "$escapedCursor") {
                        nodes {
                          isResolved
                          isOutdated
                        }
                        pageInfo {
                          hasPreviousPage
                          startCursor
                        }
                      }
                    }
                  }
                }
            """.trimIndent()

            val body = JSONObject().apply { put("query", query) }
                .toString()
                .toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url("https://api.github.com/graphql")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Accept", "application/json")
                .post(body)
                .build()

            val response = executeRequest(request)
            val json = JSONObject(response.payload)
            val reviewThreads = json.optJSONObject("data")
                ?.optJSONObject("repository")
                ?.optJSONObject("pullRequest")
                ?.optJSONObject("reviewThreads")
                ?: break
            unresolvedCount += countUnresolvedThreads(reviewThreads.optJSONArray("nodes"))
            val pageInfo = reviewThreads.optJSONObject("pageInfo")
            hasPrevious = pageInfo?.optBoolean("hasPreviousPage", false) == true
            cursor = pageInfo?.optString("startCursor").orEmpty()
        }

        return unresolvedCount
    }

    /**
     * Probes repos directly via `repository(owner, name)` queries.
     * The search API silently excludes SSO-blocked repos, but direct repo access
     * triggers FORBIDDEN errors and X-GitHub-SSO headers.
     */
    private suspend fun probeRepos(
        token: String,
        repos: List<String>,
    ): List<SsoAuthorizationRequired> {
        val aliases = repos.mapIndexed { i, repo ->
            val (owner, name) = repo.split("/", limit = 2)
            """repo$i: repository(owner: "${owner.replace("\"", "\\\"")}", name: "${name.replace("\"", "\\\"")}") { id }"""
        }
        val query = "query { ${aliases.joinToString("\n")} }"

        val body = JSONObject().apply {
            put("query", query)
        }.toString().toRequestBody(jsonMediaType)

        val request = Request.Builder()
            .url("https://api.github.com/graphql")
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Accept", "application/json")
            .post(body)
            .build()

        return withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                Log.d(TAG, "Probe response headers: ${response.headers}")
                Log.d(TAG, "Probe response body: $raw")

                val sso = mutableListOf<SsoAuthorizationRequired>()

                response.header("X-GitHub-SSO")?.let { header ->
                    ssoHeaderRegex.findAll(header).forEach { match ->
                        sso.add(
                            SsoAuthorizationRequired(
                                orgName = match.groupValues[2],
                                authUrl = match.groupValues[1],
                            ),
                        )
                    }
                }

                if (response.isSuccessful) {
                    val json = JSONObject(raw)
                    if (json.has("errors")) {
                        val errors = json.getJSONArray("errors")
                        for (i in 0 until errors.length()) {
                            val error = errors.getJSONObject(i)
                            if (error.optString("type") == "FORBIDDEN") {
                                Log.d(TAG, "FORBIDDEN error in probe: ${error.optString("message")}")
                            }
                        }
                    }
                }

                sso
            }
        }
    }
}
