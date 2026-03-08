package com.ghpr.app.data

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
)
