package com.ghpr.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ghpr.app.data.NotificationEventMapper
import com.ghpr.app.ui.theme.LocalGhprStatusColors
import com.ghpr.app.ui.theme.MonoStyle

private val BadgeShape = RoundedCornerShape(4.dp)

@Composable
fun StatusBadge(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MonoStyle.codeBold,
        color = color,
        modifier = modifier
            .clip(BadgeShape)
            .border(2.dp, color, BadgeShape)
            .background(color.copy(alpha = 0.25f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun StatusBadge(
    text: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val displayColor = if (enabled) color else color.copy(alpha = 0.3f)
    Text(
        text = text,
        style = MonoStyle.codeBold,
        color = displayColor,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(BadgeShape)
            .border(2.dp, displayColor, BadgeShape)
            .background(displayColor.copy(alpha = 0.25f))
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun actionStatusColor(action: String): Color {
    val statusColors = LocalGhprStatusColors.current
    return when (NotificationEventMapper.normalizeAction(action)) {
        "opened" -> statusColors.opened
        "closed" -> statusColors.closed
        "merged" -> statusColors.merged
        "review_requested" -> statusColors.pending
        "commented" -> statusColors.commented
        "mentioned" -> statusColors.mentioned
        "assigned" -> statusColors.assigned
        "updated" -> statusColors.updated
        "state_changed" -> statusColors.stateChanged
        else -> statusColors.link
    }
}
