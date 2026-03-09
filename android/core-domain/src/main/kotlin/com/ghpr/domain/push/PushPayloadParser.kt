package com.ghpr.domain.push

object PushPayloadParser {
    private const val KEY_TYPE = "type"
    private const val KEY_REPO = "repo"
    private const val KEY_PR_NUMBER = "prNumber"
    private const val KEY_ACTION = "action"
    private const val KEY_DELIVERY_ID = "deliveryId"
    private const val KEY_SENT_AT = "sentAt"
    private const val KEY_PR_TITLE = "prTitle"
    private const val KEY_PR_URL = "prUrl"
    private const val EXPECTED_TYPE = "pr_update"

    fun parse(data: Map<String, String>): PushUpdatePayload? {
        if (data[KEY_TYPE] != EXPECTED_TYPE) return null

        val repo = data[KEY_REPO]?.trim().orEmpty()
        if (repo.isBlank()) return null

        val prNumber = data[KEY_PR_NUMBER]?.toIntOrNull() ?: return null
        if (prNumber <= 0) return null

        val action = data[KEY_ACTION]?.trim().orEmpty()
        if (action.isBlank()) return null

        val deliveryId = data[KEY_DELIVERY_ID]?.trim().orEmpty()
        if (deliveryId.isBlank()) return null

        val sentAtMillis = data[KEY_SENT_AT]?.toLongOrNull() ?: return null
        if (sentAtMillis <= 0L) return null

        return PushUpdatePayload(
            repo = repo,
            prNumber = prNumber,
            action = action,
            deliveryId = deliveryId,
            sentAtMillis = sentAtMillis,
            prTitle = data[KEY_PR_TITLE]?.trim()?.takeIf { it.isNotEmpty() },
            prUrl = data[KEY_PR_URL]?.trim()?.takeIf { it.isNotEmpty() },
        )
    }
}
