package dev.duardo.neotransfer.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.designsystem.R
import dev.duardo.neotransfer.designsystem.theme.Neo
import dev.duardo.neotransfer.designsystem.theme.NeoMotion
import dev.duardo.neotransfer.designsystem.theme.NeoSpace

/**
 * Screen header. [onBack] adds the back arrow; root screens pass a [title] in large style instead.
 * The bar shares the background, so content and header read as one surface.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeoTopBar(
    title: String? = null,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    large: Boolean = false,
    backIcon: Int = R.drawable.ic_arrow_back,
    backDescription: String = "Atrás",
    actions: @Composable RowScope.() -> Unit = {},
    containerColor: Color = MaterialTheme.colorScheme.background,
) {
    TopAppBar(
        title = {
            if (title != null) FittedText(
                title,
                if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
            )
        },
        modifier = modifier,
        navigationIcon = {
            if (onBack != null) NeoIconButton(backIcon, backDescription, onBack)
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = containerColor, scrolledContainerColor = containerColor),
    )
}

/**
 * Header of a root tab: the title on a fixed 64 dp row under the status bar, so switching tabs never moves it.
 * [actions] sit at the end, aligned like any icon button.
 */
@Composable
fun TabHeader(title: String, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(start = NeoSpace.gutter, end = NeoSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FittedText(title, MaterialTheme.typography.headlineMedium, Modifier.weight(1f).semantics { heading() })
        actions()
    }
}

@Immutable
data class NeoNavItem(val label: String, @param:DrawableRes val icon: Int, @param:DrawableRes val selectedIcon: Int)

/**
 * Bottom navigation without a pill: the current destination takes the filled glyph and the full text color, the
 * rest stay outlined and muted. The glyph settles with a small spring when chosen.
 */
@Composable
fun NeoNavigationBar(items: List<NeoNavItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().background(Neo.colors.group)) {
        HorizontalDivider(thickness = 1.dp, color = Neo.colors.divider)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(64.dp).selectableGroup()) {
            items.forEachIndexed { index, item -> NavItem(item, index == selected, { onSelect(index) }, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun NavItem(item: NeoNavItem, selected: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val color by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        tween(NeoMotion.short), label = "nav",
    )
    val scale = remember { Animatable(1f) }
    var was by remember { mutableStateOf(selected) }
    LaunchedEffect(selected) {
        if (selected && !was) {
            scale.snapTo(0.82f)
            scale.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium))
        }
        was = selected
    }
    Column(
        modifier
            .fillMaxHeight()
            .selectable(selected, interactionSource = null, indication = ripple(bounded = false, radius = 32.dp), role = Role.Tab, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painterResource(if (selected) item.selectedIcon else item.icon), null,
            Modifier.size(24.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value },
            tint = color,
        )
        FittedText(
            item.label,
            MaterialTheme.typography.labelSmall.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
            Modifier.padding(top = NeoSpace.xs),
            color = color,
            textAlign = TextAlign.Center,
        )
    }
}
