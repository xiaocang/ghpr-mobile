package com.ghpr.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.ghpr.app.ui.theme.LocalNeoBrutalColors

private val AvatarShape = RoundedCornerShape(6.dp)

@Composable
fun AvatarCircle(
    imageUrl: String?,
    fallbackLetter: Char,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val neo = LocalNeoBrutalColors.current
    if (!imageUrl.isNullOrEmpty()) {
        AsyncImage(
            model = imageUrl,
            contentDescription = "Avatar",
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(AvatarShape)
                .border(2.5.dp, neo.border, AvatarShape),
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(AvatarShape)
                .border(2.5.dp, neo.border, AvatarShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = fallbackLetter.uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}
