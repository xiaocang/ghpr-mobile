package com.ghpr.app.data

enum class PrCategory {
    AUTHORED,
    REVIEW_REQUESTED,
}

data class CIWorkflowInfo(
    val name: String,
    val isWorkflow: Boolean,
    val successCount: Int,
    val failureCount: Int,
    val pendingCount: Int,
) {
    val totalCount: Int get() = successCount + failureCount + pendingCount

    val status: String
        get() = when {
            failureCount > 0 -> "FAILURE"
            pendingCount > 0 -> "PENDING"
            successCount > 0 -> "SUCCESS"
            else -> "EXPECTED"
        }
}

data class OpenPullRequest(
    val number: Int,
    val title: String,
    val url: String,
    val isDraft: Boolean,
    val createdAt: String,
    val updatedAt: String,
    val authorLogin: String,
    val authorAvatarUrl: String,
    val repoOwner: String,
    val repoName: String,
    val ciState: String?,
    val approvalCount: Int = 0,
    val unresolvedCount: Int = 0,
    val category: PrCategory = PrCategory.AUTHORED,
    val checkSuccessCount: Int = 0,
    val checkFailureCount: Int = 0,
    val checkPendingCount: Int = 0,
    val ciWorkflows: List<CIWorkflowInfo> = emptyList(),
    val ciIsRunning: Boolean = false,
    val ciTruncated: Boolean = false,
)
