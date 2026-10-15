package dev.duardo.neotransfer.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.designsystem.theme.Neo

enum class NeoButtonStyle { Primary, Tonal, Danger }

/** Full-width pill. One [NeoButtonStyle.Primary] per screen. */
@Composable
fun NeoButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: NeoButtonStyle = NeoButtonStyle.Primary,
    @DrawableRes icon: Int? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    fill: Boolean = true,
) {
    val colors = Neo.colors
    val scheme = MaterialTheme.colorScheme
    val (container, content) = when (style) {
        NeoButtonStyle.Primary -> colors.accent to colors.onAccent
        NeoButtonStyle.Tonal -> scheme.surfaceContainerHigh to scheme.onSurface
        NeoButtonStyle.Danger -> colors.dangerContainer to colors.danger
    }
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick = { if (!loading) onClick() },
        modifier = modifier.pressScale(interaction, 0.97f).heightIn(min = 56.dp).then(if (fill) Modifier.fillMaxWidth() else Modifier),
        enabled = enabled,
        interactionSource = interaction,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = scheme.onSurface.copy(alpha = 0.08f),
            disabledContentColor = scheme.onSurface.copy(alpha = 0.38f),
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp),
    ) {
        when {
            loading -> CircularProgressIndicator(Modifier.size(20.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
            icon != null -> Icon(painterResource(icon), null, Modifier.size(20.dp))
        }
        if (loading || icon != null) Spacer(Modifier.width(10.dp))
        FittedText(text, MaterialTheme.typography.labelLarge)
    }
}

/** Inline action in accent ink: "Ver todo", "Añadir teléfono"; [icon] goes before the words. */
@Composable
fun NeoTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = Neo.colors.accentInk,
    enabled: Boolean = true,
    @DrawableRes icon: Int? = null,
) {
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp), enabled = enabled, colors = ButtonDefaults.textButtonColors(contentColor = color)) {
        if (icon != null) {
            Icon(painterResource(icon), null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        FittedText(text, MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun NeoIconButton(@DrawableRes icon: Int, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current, enabled: Boolean = true) {
    IconButton(onClick = onClick, modifier = modifier, enabled = enabled) {
        Icon(painterResource(icon), contentDescription, tint = tint)
    }
}
