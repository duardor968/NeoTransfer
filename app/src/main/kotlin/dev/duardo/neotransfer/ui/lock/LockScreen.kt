package dev.duardo.neotransfer.ui.lock

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.designsystem.R as Ds
import dev.duardo.neotransfer.designsystem.component.NeoButton
import dev.duardo.neotransfer.designsystem.theme.Neo
import dev.duardo.neotransfer.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.ui.common.BrandMark
import dev.duardo.neotransfer.ui.model.LockUi

fun interface LockActions {
    fun unlock()
}

/**
 * What shows behind the system fingerprint prompt: the mark and the locked state, nothing to press.
 * After a cancel, a single action brings the prompt back.
 */
@Composable
fun LockScreen(state: LockUi, actions: LockActions, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize()) {
        BrandBackdrop {
            Spacer(Modifier.weight(0.8f))
            BrandBlock(locked = true)
            Spacer(Modifier.weight(1.2f))
        }
        AnimatedVisibility(
            visible = state !is LockUi.Prompting,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = fadeIn() + slideInVertically { it / 3 },
            exit = fadeOut(),
        ) {
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = NeoSpace.xxl).padding(bottom = NeoSpace.xxl)) {
                when (state) {
                    is LockUi.LockedOut -> NeoButton("Desbloquear en ${state.seconds} s", actions::unlock, icon = Ds.drawable.ic_fingerprint, enabled = false)
                    else -> NeoButton("Desbloquear", actions::unlock, icon = Ds.drawable.ic_fingerprint)
                }
            }
        }
    }
}

@Composable
internal fun BrandBlock(locked: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(NeoSpace.l)) {
        BrandMark(112.dp)
        Text("NeoTransfer", style = MaterialTheme.typography.headlineSmall)
        if (locked) Box(
            Modifier.size(36.dp).background(Neo.colors.neutralContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(Ds.drawable.ic_lock), "Bloqueada", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Full-bleed background with a faint accent glow behind the mark. */
@Composable
internal fun BrandBackdrop(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val glow = Neo.colors.accent.copy(alpha = if (Neo.colors.isDark) 0.10f else 0.12f)
    val background = MaterialTheme.colorScheme.background
    Column(
        modifier
            .fillMaxSize()
            .background(background)
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        listOf(glow, Color.Transparent),
                        center = Offset(size.width / 2, size.height * 0.36f),
                        radius = size.width * 0.75f,
                    ),
                )
            }
            .safeDrawingPadding()
            .heightIn(min = 400.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}
