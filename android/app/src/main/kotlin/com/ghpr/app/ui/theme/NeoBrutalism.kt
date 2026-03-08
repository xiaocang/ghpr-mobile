package com.ghpr.app.ui.theme

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val NeoCornerShape = RoundedCornerShape(6.dp)
private val NeoBorderWidth = 2.5.dp

@Composable
fun NeoCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val neo = LocalNeoBrutalColors.current
    Box(
        modifier = modifier
            .padding(bottom = 4.dp, end = 4.dp)
    ) {
        // Shadow
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(x = 4.dp, y = 4.dp)
                .background(neo.shadow, NeoCornerShape)
        )
        // Card
        Box(
            modifier = Modifier
                .background(neo.cardBg, NeoCornerShape)
                .border(NeoBorderWidth, neo.border, NeoCornerShape)
        ) {
            content()
        }
    }
}

@Composable
fun NeoButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    content: @Composable () -> Unit,
) {
    val neo = LocalNeoBrutalColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val shadowOffset by animateDpAsState(
        targetValue = if (isPressed) 1.dp else 4.dp,
        animationSpec = tween(50),
        label = "neoButtonShadow",
    )

    Box(
        modifier = modifier
            .padding(bottom = 4.dp, end = 4.dp)
    ) {
        // Shadow
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(x = shadowOffset, y = shadowOffset)
                .background(neo.shadow, NeoCornerShape)
        )
        // Button surface
        Box(
            modifier = Modifier
                .offset(
                    x = if (isPressed) 3.dp else 0.dp,
                    y = if (isPressed) 3.dp else 0.dp,
                )
                .background(
                    if (enabled) containerColor else containerColor.copy(alpha = 0.4f),
                    NeoCornerShape,
                )
                .border(NeoBorderWidth, neo.border, NeoCornerShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                )
                .padding(horizontal = 20.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.material3.LocalContentColor provides contentColor,
            ) {
                content()
            }
        }
    }
}

@Composable
fun NeoFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    content: @Composable () -> Unit,
) {
    val neo = LocalNeoBrutalColors.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val shadowOffset by animateDpAsState(
        targetValue = if (isPressed) 1.dp else 4.dp,
        animationSpec = tween(50),
        label = "neoFabShadow",
    )

    Box(modifier = modifier.padding(bottom = 4.dp, end = 4.dp)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .offset(x = shadowOffset, y = shadowOffset)
                .background(neo.shadow, NeoCornerShape)
        )
        Box(
            modifier = Modifier
                .offset(
                    x = if (isPressed) 3.dp else 0.dp,
                    y = if (isPressed) 3.dp else 0.dp,
                )
                .background(containerColor, NeoCornerShape)
                .border(NeoBorderWidth, neo.border, NeoCornerShape)
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                )
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@Composable
fun NeoTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "",
    singleLine: Boolean = true,
) {
    val neo = LocalNeoBrutalColors.current
    Column(modifier = modifier) {
        if (label.isNotEmpty()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(neo.cardBg, NeoCornerShape)
                .border(NeoBorderWidth, neo.border, NeoCornerShape)
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun Modifier.neoTopBarBorder(): Modifier {
    val borderColor = LocalNeoBrutalColors.current.border
    return drawBehind {
        val strokeWidth = 2.5.dp.toPx()
        drawLine(
            color = borderColor,
            start = Offset(0f, size.height - strokeWidth / 2),
            end = Offset(size.width, size.height - strokeWidth / 2),
            strokeWidth = strokeWidth,
        )
    }
}
