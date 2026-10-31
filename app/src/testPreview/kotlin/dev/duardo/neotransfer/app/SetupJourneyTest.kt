package dev.duardo.neotransfer.app

import android.app.Application
import android.net.Uri
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.duardo.neotransfer.core.model.Bank as CoreBank
import dev.duardo.neotransfer.core.protocol.BankRegistration
import dev.duardo.neotransfer.data.Settings
import dev.duardo.neotransfer.data.Theme
import dev.duardo.neotransfer.engine.BankSms
import dev.duardo.neotransfer.engine.FakeModem
import dev.duardo.neotransfer.engine.Rig
import dev.duardo.neotransfer.platform.BiometricResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import javax.crypto.Cipher
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Setting up a bank's line from the first start, on the JVM: the registration's form takes only values it can send,
 * and the card it was registered with comes filled in afterwards. Each step is captured to `build/journey-setup/<mode>/`.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalRoborazziApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xhdpi", application = Application::class)
class SetupJourneyTest(private val mode: String) {
    @get:Rule
    val compose = createComposeRule()

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val start = Instant.now()
    private val rig = Rig(clock = { start.plusMillis(scheduler.currentTime) }, state = dispatcher, io = dispatcher, modem = FakeModem(latency = 800))
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var model: AppModel
    private var step = 0

    /** BANDEC: an impossible month is never typed; after the registration its card only needs confirming. */
    @Test
    fun bandec() {
        launch()
        node(hasContentDescription("Banco de Crédito y Comercio")).performClick()
        settle()
        text("No tengo clave: registrar mi línea").performClick()
        settle()
        // The card's field is called as everywhere else and reads the card with the camera.
        node(hasContentDescription("Escanear"))
        field("Número de tarjeta").performTextInput(CARD)
        field("Nombre y apellidos").performTextInput("Laura Méndez Soto")
        // The expiry as printed, MM/AA: «3» cannot start a month nor «13» be one, so neither is taken; BANDEC gets AAMM.
        "3132".forEach { field("Vencimiento").performTextInput(it.toString()) }
        settle()
        "30".forEach { field("Vencimiento").performTextInput(it.toString()) }
        settle()
        node(hasText("12/30"))
        shot("register")
        text("Registrar").performClick()
        settle(2_000)
        val request = assertIs<BankRegistration>(rig.controller.operations.value.first().operation.request)
        assertEquals(Triple(CARD, "Laura Mendez Soto", "3012"), Triple(request.card, request.name, request.expiry))

        deliver(BankSms.registration("12345"))
        assertTrue(rig.vault.contains("BANDEC=12345"))
        text("Continuar").performClick()
        settle()
        // What was registered fills the card in: the person only confirms it.
        text("Tarjetas de BANDEC")
        node(hasText("Laura Mendez Soto"))
        node(hasText("12/30"))
        shot("cards-filled-in")
        // «Vence» takes the same rule: typed again, «13» is never a month.
        field("Vencimiento (opcional)").performTextClearance()
        "132".forEach { field("Vencimiento (opcional)").performTextInput(it.toString()) }
        "30".forEach { field("Vencimiento (opcional)").performTextInput(it.toString()) }
        settle()
        node(hasText("12/30"))
        text("Guardar tarjeta").performClick()
        settle()
        val saved = rig.controller.cards.value.single().card
        assertEquals(listOf(CARD, "Laura Mendez Soto", "12/30"), listOf(saved.card.number, saved.holder, saved.expiry))
        shot("cards-saved")
    }

    /** Metropolitano: the Telebanca card's «95» is printed in the field and only the other 8 digits are typed. */
    @Test
    fun telebanca() {
        launch()
        node(hasContentDescription("Banco Metropolitano")).performClick()
        settle()
        text("No tengo clave: registrar mi línea").performClick()
        settle()
        field("Tarjeta Telebanca").performTextInput("123456789")
        field("Nombre").performTextInput("Laura")
        field("Primer apellido").performTextInput("Mendez")
        field("Segundo apellido").performTextInput("Soto")
        field("Carné de identidad").performTextInput("85031204567")
        settle()
        shot("register-telebanca")
        text("Registrar").performClick()
        settle(2_000)
        assertEquals("9512345678", assertIs<BankRegistration>(rig.controller.operations.value.first().operation.request).card)
        assertEquals(CoreBank.BANMET, (rig.controller.operations.value.first().operation.request as BankRegistration).bank)
    }

    /** MiTransfer's registration: one field marked at a time, and no cursor in the identity card while the keypad types. */
    @Test
    fun classicPin() {
        launch()
        node(hasContentDescription("Clásica de Fincimex")).performClick()
        settle()
        text("No tengo PIN: registrar mi línea").performClick()
        settle()
        val identity = field("Carné de identidad")
        identity.performTextInput("58072304511")
        settle()
        identity.assertIsFocused()
        "12".forEach { key(it).performClick() }
        settle()
        identity.assertIsNotFocused()
        shot("classic-register")
    }

    // ---- Helpers ----

    private fun launch() {
        shadowOf(application).grantPermissions(*AppModel.ALL_PERMISSIONS.toTypedArray())
        if (mode == "font150") RuntimeEnvironment.setFontScale(1.5f)
        if (mode == "dark") onController { rig.controller.updateSettings(Settings(theme = Theme.DARK)) }
        model = AppModel(application, rig.controller, dispatcher).also { it.host = FakeHost() }
        compose.setContent { NeoApp(model) }
        settle()
    }

    private fun settle(advance: Long = 0) {
        scheduler.advanceTimeBy(advance)
        repeat(8) {
            scheduler.runCurrent()
            if (::model.isInitialized) compose.waitForIdle()
        }
    }

    private fun onController(block: suspend () -> Unit) {
        CoroutineScope(dispatcher).launch { block() }
        settle()
    }

    private fun deliver(body: String) {
        rig.inbox.deliver(body, start.plusMillis(scheduler.currentTime - 2_000), start.plusMillis(scheduler.currentTime))
        rig.controller.onBankSms()
        settle(7_000)
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage("build/journey-setup/$mode/${"%02d".format(++step)}-$name.png")
    }

    private fun node(matcher: SemanticsMatcher): SemanticsNodeInteraction =
        compose.onAllNodes(matcher, useUnmergedTree = false).onFirst().also { it.fetchSemanticsNode() }

    private fun text(value: String) = node(hasText(value)).also { runCatching { it.performScrollTo() } }
    private fun key(digit: Char) = node(hasText(digit.toString()) and hasClickAction())
    private fun field(label: String) = node(hasSetTextAction() and hasText(label))

    private class FakeHost : Host {
        override suspend fun biometric(title: String, subtitle: String?, cipher: Cipher?): BiometricResult = BiometricResult.Passed(cipher)
        override suspend fun createDocument(name: String): Uri? = null
        override suspend fun openDocument(): Uri? = null
        override fun displayName(uri: Uri): String? = null
        override fun requestPermissions(permissions: List<String>) = Unit
        override fun shouldExplain(permission: String) = false
        override suspend fun requestPermission(permission: String) = true
        override fun share(text: String) = Unit
        override fun copy(text: String) = Unit
        override fun open(url: String) = Unit
        override fun openContact(key: String) = Unit
        override fun openAppSettings() = Unit
        override fun openNotificationSettings() = Unit
    }

    companion object {
        private const val CARD = "9206069900000041"

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun modes(): List<Array<String>> = listOf("light", "dark", "font150").map { arrayOf(it) }
    }
}
