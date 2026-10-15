package dev.duardo.neotransfer.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.data.Theme
import dev.duardo.neotransfer.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.ui.common.NeoTransferTheme
import dev.duardo.neotransfer.ui.lock.LockActions
import dev.duardo.neotransfer.ui.lock.LockScreen
import dev.duardo.neotransfer.ui.model.Appearance
import dev.duardo.neotransfer.ui.onboarding.PermissionsActions
import dev.duardo.neotransfer.ui.onboarding.PermissionsScreen
import dev.duardo.neotransfer.ui.shell.Tab
import kotlinx.coroutines.delay
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * The whole app: nothing until the stored state is in memory (the system splash stays up meanwhile), then the lock,
 * the permission request, and the screens of the back stack.
 */
@Composable
fun NeoApp(model: AppModel) {
    val book by model.book.collectAsState()
    val domain = book.domain
    val appearance = when (domain.settings.theme) {
        Theme.SYSTEM -> Appearance.System
        Theme.LIGHT -> Appearance.Light
        Theme.DARK -> Appearance.Dark
    }
    NeoTransferTheme(appearance, minuteClock()) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            val gate = model.permissionGate()
            when {
                !domain.loaded -> Unit
                domain.locked && domain.configured -> {
                    LockScreen(model.lock, LockActions(model::unlock))
                    LaunchedEffect(Unit) { model.unlock() }
                }
                gate != null -> {
                    PermissionsScreen(gate, object : PermissionsActions {
                        override fun grant() = model.grantPermissions()
                        override fun skip() = model.skipPermissions()
                    })
                    LaunchedEffect(Unit) { model.checkPermissions() }
                }
                !model.entered -> LaunchedEffect(Unit) { model.enter() }
                else -> Screens(model, book)
            }
            val tabbed = model.navigator.top is Main
            SnackbarHost(
                model.messages,
                Modifier.align(Alignment.BottomCenter).imePadding().navigationBarsPadding().padding(bottom = if (tabbed) 80.dp else NeoSpace.l),
            ) { Snackbar(it, containerColor = MaterialTheme.colorScheme.inverseSurface) }
        }
    }
}

@Composable
private fun Screens(model: AppModel, book: Book) {
    val navigator = model.navigator
    val saved = rememberSaveableStateHolder()
    BackHandler(enabled = navigator.stack.size > 1 || model.tab != Tab.Home) {
        if (!navigator.pop()) model.tab = Tab.Home
    }
    AnimatedContent(
        navigator.top,
        transitionSpec = {
            val forward = navigator.forward
            (slideInHorizontally(tween(260)) { if (forward) it / 6 else -it / 6 } + fadeIn(tween(220))) togetherWith
                (slideOutHorizontally(tween(260)) { if (forward) -it / 10 else it / 10 } + fadeOut(tween(160)))
        },
        contentKey = { it.key },
        label = "screens",
    ) { destination ->
        saved.SaveableStateProvider(destination.key) { destination.Content(model, book) }
    }
}

/** A clock that changes once a minute, so "hace 5 min" labels move on their own. */
@Composable
private fun minuteClock(): Clock {
    var minute by remember { mutableLongStateOf(System.currentTimeMillis() / 60_000) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000 - System.currentTimeMillis() % 60_000)
            minute = System.currentTimeMillis() / 60_000
        }
    }
    return remember(minute) { MinuteClock(minute) }
}

private class MinuteClock(private val minute: Long, private val zone: ZoneId = ZoneId.systemDefault()) : Clock() {
    override fun getZone(): ZoneId = zone
    override fun withZone(zone: ZoneId): Clock = MinuteClock(minute, zone)
    override fun instant(): Instant = Instant.now()
    override fun equals(other: Any?) = other is MinuteClock && other.minute == minute && other.zone == zone
    override fun hashCode() = minute.hashCode()
}
