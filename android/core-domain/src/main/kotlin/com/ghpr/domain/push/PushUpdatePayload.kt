package com.ghpr.domain.push

data class PushUpdatePayload(
    val repo: String,
    val prNumber: Int,
    val action: String,
    val deliveryId: String,
    val sentAtMillis: Long,
    val prTitle: String? = null,
    val prUrl: String? = null,
)
