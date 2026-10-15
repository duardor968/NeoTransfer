package dev.duardo.neotransfer.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.atomic.AtomicInteger

/** One screen on the back stack. It keeps its own state, so going back finds it as it was left. */
abstract class Destination {
    /** Identity for saved state and transitions. */
    val key: String = "screen-${counter.incrementAndGet()}"

    @Composable
    abstract fun Content(app: AppModel, book: Book)

    private companion object {
        val counter = AtomicInteger()
    }
}

/** The back stack. Its first entry is never popped: back from there leaves the app. */
class Navigator(root: Destination) {
    val stack = mutableStateListOf(root)
    val top: Destination get() = stack.last()

    /** Whether the last change went deeper (push) or back, for the transition. */
    var forward by mutableStateOf(true)
        private set

    fun push(destination: Destination) {
        forward = true
        stack += destination
    }

    fun pop(): Boolean {
        if (stack.size == 1) return false
        forward = false
        stack.removeAt(stack.lastIndex)
        return true
    }

    /** Drops [destination] (and whatever is above it), wherever it is. */
    fun remove(destination: Destination) {
        val index = stack.indexOf(destination)
        if (index <= 0) return
        forward = false
        while (stack.size > index) stack.removeAt(stack.lastIndex)
    }

    /** Ends a flow that started at [first]: everything from it up is replaced by [next]. */
    fun finish(first: Destination, next: Destination) {
        val index = stack.indexOf(first).takeIf { it > 0 } ?: stack.size
        forward = true
        while (stack.size > index) stack.removeAt(stack.lastIndex)
        stack += next
    }

    fun reset(root: Destination) {
        forward = true
        stack.clear()
        stack += root
    }

    fun popToRoot() {
        forward = false
        while (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
}
