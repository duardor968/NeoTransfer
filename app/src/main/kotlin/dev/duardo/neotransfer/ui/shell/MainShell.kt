package dev.duardo.neotransfer.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.duardo.neotransfer.designsystem.R as Ds
import dev.duardo.neotransfer.designsystem.component.NeoNavItem
import dev.duardo.neotransfer.designsystem.component.NeoNavigationBar

enum class Tab(val item: NeoNavItem) {
    Home(NeoNavItem("Inicio", Ds.drawable.ic_home, Ds.drawable.ic_home_fill1)),
    Activity(NeoNavItem("Actividad", Ds.drawable.ic_receipt_long, Ds.drawable.ic_receipt_long_fill1)),
    Contacts(NeoNavItem("Contactos", Ds.drawable.ic_group, Ds.drawable.ic_group_fill1)),
    Services(NeoNavItem("Servicios", Ds.drawable.ic_grid_view, Ds.drawable.ic_grid_view_fill1)),
}

/** Root frame of the four tabs. Each tab screen draws its own header; the shell only adds the bottom bar. */
@Composable
fun MainShell(tab: Tab, onTab: (Tab) -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0),
        bottomBar = { NeoNavigationBar(Tab.entries.map { it.item }, tab.ordinal, { onTab(Tab.entries[it]) }) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) { content() }
    }
}
