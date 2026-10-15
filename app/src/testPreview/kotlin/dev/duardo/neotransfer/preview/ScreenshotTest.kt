package dev.duardo.neotransfer.preview

import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.duardo.neotransfer.ui.model.Appearance
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders every catalog screen in light, dark and light at 1.5× font into `build/screenshots/`.
 * Run: `gradlew :app:testPreviewUnitTest --tests '*ScreenshotTest*'`.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xhdpi")
class ScreenshotTest(private val screen: String, private val mode: String) {

    @get:Rule
    val compose = createComposeRule()

    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun capture() {
        val appearance = if (mode == "dark") Appearance.Dark else Appearance.Light
        if (mode == "font150") RuntimeEnvironment.setFontScale(1.5f)
        compose.setContent { PreviewHost(appearance) { PreviewCatalog.content(screen) } }
        compose.waitForIdle()
        captureScreenRoboImage("build/screenshots/$screen--$mode.png")
    }

    companion object {
        private val only: String? = System.getProperty("screens")?.takeIf { it.isNotBlank() }

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}--{1}")
        fun parameters(): List<Array<String>> = PreviewCatalog.ids
            .filter { id -> only == null || only.split(',').any { id.startsWith(it) } }
            .flatMap { id -> listOf("light", "dark", "font150").map { arrayOf(id, it) } }
    }
}
