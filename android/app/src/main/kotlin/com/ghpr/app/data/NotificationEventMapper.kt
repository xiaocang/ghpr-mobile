package com.ghpr.app.data

object NotificationEventMapper {
    private val normalizedValues = setOf(
        "opened",
        "updated",
        "review_requested",
        "commented",
        "mentioned",
        "assigned",
        "merged",
        "closed",
        "state_changed",
    )

    fun normalizeAction(raw: String): String {
        val action = raw.trim().lowercase()
        if (action in normalizedValues) return action
        return when (action) {
            "reopened", "ready_for_review" -> "opened"
            "synchronize", "edited" -> "updated"
            "comment" -> "commented"
            "mention" -> "mentioned"
            "assign" -> "assigned"
            "state_change" -> "state_changed"
            else -> "updated"
        }
    }

    fun normalizeReason(rawReason: String): String = when (rawReason.trim().lowercase()) {
        "review_requested" -> "review_requested"
        "author" -> "updated"
        "comment" -> "commented"
        "mention" -> "mentioned"
        "assign" -> "assigned"
        "state_change" -> "state_changed"
        else -> "updated"
    }

    fun labelFor(action: String): String = when (normalizeAction(action)) {
        "opened" -> "Opened"
        "updated" -> "Updated"
        "review_requested" -> "Review requested"
        "commented" -> "Commented"
        "mentioned" -> "Mentioned"
        "assigned" -> "Assigned"
        "merged" -> "Merged"
        "closed" -> "Closed"
        "state_changed" -> "State changed"
        else -> "Updated"
    }
}

