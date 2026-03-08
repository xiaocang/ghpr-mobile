package com.ghpr.app.data

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
import org.json.JSONObject

class GitHubGraphQLClient(
    private val gitHubOAuthManager: GitHubOAuthManager,
) {
    private val client = OkHttpClient()
    private val jsonMediaType = "application/json".toMediaType()

    suspend fun fetchOpenPrs(repos: List<String>): List<OpenPullRequest> {
        val token = gitHubOAuthManager.getToken() ?: return emptyList()
        if (repos.isEmpty()) return emptyList()

        return coroutineScope {
            repos.chunked(5).map { batch ->
                async { queryBatch(token, batch) }
            }.awaitAll().flatten()
        }
    }

    private suspend fun queryBatch(token: String, repos: List<String>): List<OpenPullRequest> {
        val repoFilter = repos.joinToString(" ") { "repo:$it" }
        val query = """
            query {
              search(query: "is:pr is:open $repoFilter", type: ISSUE, first: 100) {
                nodes {
                  ... on PullRequest {
                    number
                    title
                    url
                    isDraft
                    createdAt
                    updatedAt
                    author { login avatarUrl }
                    repository { owner { login } name }
                    commits(last: 1) {
                      nodes {
                        commit {
                          statusCheckRollup { state }
                        }
                      }
                    }
                  }
                }
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

        val payload = withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw Exception("GitHub GraphQL request failed: HTTP ${response.code}")
                }
                raw
            }
        }

        val json = JSONObject(payload)
        if (json.has("errors")) {
            val errors = json.getJSONArray("errors")
            throw Exception("GitHub GraphQL error: ${errors.getJSONObject(0).optString("message")}")
        }

        val nodes = json.getJSONObject("data")
            .getJSONObject("search")
            .getJSONArray("nodes")

        val results = mutableListOf<OpenPullRequest>()
        for (i in 0 until nodes.length()) {
            val node = nodes.getJSONObject(i)
            if (!node.has("number")) continue

            val author = node.optJSONObject("author")
            val repo = node.optJSONObject("repository")
            val commits = node.optJSONObject("commits")
                ?.optJSONArray("nodes")
            val ciState = commits
                ?.optJSONObject(0)
                ?.optJSONObject("commit")
                ?.optJSONObject("statusCheckRollup")
                ?.optString("state")

            results.add(
                OpenPullRequest(
                    number = node.getInt("number"),
                    title = node.getString("title"),
                    url = node.getString("url"),
                    isDraft = node.optBoolean("isDraft", false),
                    createdAt = node.getString("createdAt"),
                    updatedAt = node.getString("updatedAt"),
                    authorLogin = author?.optString("login", "").orEmpty(),
                    authorAvatarUrl = author?.optString("avatarUrl", "").orEmpty(),
                    repoOwner = repo?.optJSONObject("owner")?.optString("login", "").orEmpty(),
                    repoName = repo?.optString("name", "").orEmpty(),
                    ciState = ciState,
                ),
            )
        }
        return results
    }
}
