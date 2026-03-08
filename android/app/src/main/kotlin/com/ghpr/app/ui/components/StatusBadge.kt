package com.ghpr.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ghpr.app.ui.theme.LocalGhprStatusColors

private val BadgeShape = RoundedCornerShape(4.dp)

@Composable
fun StatusBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier
            .clip(BadgeShape)
            .border(2.dp, color, BadgeShape)
            .background(color.copy(alpha = 0.25f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun actionStatusColor(action: String): Color {
    val statusColors = LocalGhprStatusColors.current
    return when (action.lowercase()) {
        "opened", "reopened" -> statusColors.opened
        "closed" -> statusColors.closed
        "merged" -> statusColors.merged
        "synchronize", "edited", "review_requested" -> statusColors.pending
        else -> statusColors.link
    }
}
