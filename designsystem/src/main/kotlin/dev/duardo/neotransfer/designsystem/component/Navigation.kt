package dev.duardo.neotransfer.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.designsystem.R
import dev.duardo.neotransfer.designsystem.theme.Neo

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

@Immutable
data class NeoNavItem(val label: String, @param:DrawableRes val icon: Int, @param:DrawableRes val selectedIcon: Int)

/** Bottom navigation: filled glyph and accent pill on the current destination. */
@Composable
fun NeoNavigationBar(items: List<NeoNavItem>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        HorizontalDivider(color = Neo.colors.divider)
        NavigationBar(containerColor = Neo.colors.group, tonalElevation = 0.dp) {
            items.forEachIndexed { index, item ->
                val isSelected = index == selected
                NavigationBarItem(
                    selected = isSelected,
                    onClick = { onSelect(index) },
                    icon = { Icon(painterResource(if (isSelected) item.selectedIcon else item.icon), null) },
                    label = { FittedLabel(item.label, color = LocalContentColor.current) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Neo.colors.onAccentContainer,
                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                        indicatorColor = Neo.colors.accentContainer,
                        unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                )
            }
        }
    }
}
