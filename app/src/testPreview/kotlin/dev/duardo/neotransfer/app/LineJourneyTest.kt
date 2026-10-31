package dev.duardo.neotransfer.app

import android.app.Application
import android.net.Uri
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.duardo.neotransfer.core.ledger.OperationState
import dev.duardo.neotransfer.core.model.Bank
import dev.duardo.neotransfer.core.model.MiTransfer
import dev.duardo.neotransfer.core.model.OwnCard
import dev.duardo.neotransfer.core.protocol.AccountsQuery
import dev.duardo.neotransfer.core.protocol.CardAssociation
import dev.duardo.neotransfer.core.protocol.ClassicRemoval
import dev.duardo.neotransfer.core.protocol.PinChange
import dev.duardo.neotransfer.core.protocol.RegistrationRemoval
import dev.duardo.neotransfer.data.Settings
import dev.duardo.neotransfer.data.Theme
import dev.duardo.neotransfer.data.WalletCard
import dev.duardo.neotransfer.designsystem.format.NoBreak
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
 * Ajustes › BPA and Ajustes › Clásica end to end on the JVM: [NeoApp] over the real [AppModel] and controller with the
 * fake modem, inbox and vault. The line's services go out, their answers come by SMS, and each state shows where it is
 * looked for. Each step is captured to `build/line-journey/<mode>/`.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalRoborazziApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xhdpi", application = Application::class)
class LineJourneyTest(private val mode: String) {
    @get:Rule
    val compose = createComposeRule()

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val start = Instant.now()
    private val rig = Rig(clock = { start.plusMillis(scheduler.currentTime) }, state = dispatcher, io = dispatcher, modem = FakeModem(latency = 800))
    private val host = FakeHost()
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var model: AppModel
    private var step = 0

    @Test
    fun line() {
        shadowOf(application).grantPermissions(*AppModel.ALL_PERMISSIONS.toTypedArray())
        if (mode == "font150") RuntimeEnvironment.setFontScale(1.5f)
        // Set up already: BPA with its key and a card, and the Clásica with MiTransfer's PIN.
        onController {
            val controller = rig.controller
            controller.unlock(controller.unlockCipher())
            controller.saveKey(controller.keyCipher(), Bank.BPA, "1234".toCharArray())
            controller.saveKey(controller.keyCipher(), MiTransfer, "2468".toCharArray())
            controller.saveCard(WalletCard(OwnCard(OWN_CARD, Bank.BPA)))
            controller.saveClassic(CLASSIC)
            if (mode == "dark") controller.updateSettings(Settings(theme = Theme.DARK))
        }
        model = AppModel(application, rig.controller, dispatcher).also { it.host = host }
        compose.setContent { NeoApp(model) }
        settle()

        node(hasContentDescription("Ajustes")).performClick()
        settle()
        shot("settings")
        text("BPA").performClick()
        settle()
        shot("settings-bank")

        // Cambiar clave: typed twice (a mismatch starts over), sent inside a session opened with the current key.
        text("Cambiar clave").performClick()
        settle()
        "5678".forEach { key(it).performClick() }
        settle()
        shot("key-repeat")
        "5679".forEach { key(it).performClick() }
        settle()
        shot("key-mismatch")
        text("Las claves no coinciden")
        "56785678".forEach { key(it).performClick() }
        settle(3_000)
        assertEquals(listOf(40, 69), rig.modem.services)
        assertIs<PinChange>(lastRequest())
        text("Cambiando la clave en BPA…")
        shot("key-sent")

        // The bank answers in words the app does not know: shown without the key, and the person saves it.
        deliver("Banco Popular de Ahorro: Su clave fue cambiada. Nueva clave 5678.")
        text("Cambio de clave en BPA")
        node(hasText("Respuesta de BPA, ", substring = true)).assertExists()
        assertTrue(compose.onAllNodes(hasText("5678", substring = true)).fetchSemanticsNodes().isEmpty())
        shot("key-answered")
        text("Guardar la clave nueva").performClick()
        settle()
        assertEquals("Guardar la clave de BPA", host.prompts.last())
        assertTrue(rig.vault.contains("BPA=5678"))
        assertTrue(rig.controller.keysToSave.value.isEmpty())
        assertEquals("Clave nueva guardada", model.messages.currentSnackbarData?.visuals?.message)
        text("En el banco; la nueva queda guardada")
        shot("key-saved")
        dismissMessage()

        // Cuentas en el banco: asked as soon as it opens; the missing one is added from the answer.
        text("Cuentas en el banco").performClick()
        settle(3_000)
        assertIs<AccountsQuery>(lastRequest())
        text("Esperando la respuesta de BPA…")
        shot("accounts-sending")
        deliver(
            "Banco Popular de Ahorro:  La consulta de saldo fue completada. \n Cuenta;Saldo Contable;Saldo Disponible;Moneda \n" +
                "$OWN_CARD; CR 845.10 ; CR 845.10 ;CUP | \n$DOLLARS; CR 120.00 ; CR 120.00 ;USD | \n",
        )
        node(hasText("Saldo 845.10${NoBreak}CUP · En${NoBreak}la${NoBreak}app")).assertExists()
        shot("accounts")
        text("Añadir").performClick()
        settle()
        assertTrue(rig.controller.cards.value.any { it.card.card.number == DOLLARS })
        assertTrue(compose.onAllNodes(hasText("Añadir")).fetchSemanticsNodes().isEmpty())
        shot("accounts-added")
        back()

        // Asociar una tarjeta: another bank's card is caught before it goes; after the bank's refusal the form comes back filled in.
        text("Asociar una tarjeta").performClick()
        settle()
        field("Número de tarjeta").performTextInput(BANDEC_CARD)
        settle()
        text("No es una tarjeta de BPA")
        shot("association-other-bank")
        field("Número de tarjeta").performTextReplacement(ASSOCIATED)
        settle()
        text("Asociar").performClick()
        settle(3_000)
        assertEquals(CardAssociation(Bank.BPA, ASSOCIATED), lastRequest())
        deliver("Error en la asociacion de tarjeta. Banco Popular de Ahorro: Esta tarjeta ya esta asociada a un celular")
        text("BPA no asoció la tarjeta")
        shot("association-rejected")
        text("Revisar los datos").performClick()
        settle()
        node(hasSetTextAction() and hasText(ASSOCIATED)).assertExists()
        shot("association-review")
        node(hasContentDescription("Cerrar")).performClick()
        settle()
        assertTrue(compose.onAllNodes(hasText("Rechazada", substring = true)).fetchSemanticsNodes().isEmpty())
        shot("settings-bank-rejected")

        // Eliminar el registro: confirmed in a sheet; not executed, Reintentar sends it again as it was.
        text("Eliminar el registro de la línea").performClick()
        settle()
        shot("unregister")
        text("Eliminar registro").performClick()
        settle(3_000)
        assertEquals(RegistrationRemoval(Bank.BPA), lastRequest())
        text("Eliminando el registro en BPA…")
        deliver(BankSms.busy())
        assertIs<OperationState.NotExecuted>(rig.controller.operations.value.first().state)
        text("No se eliminó el registro")
        shot("unregister-not-executed")
        text("Reintentar").performClick()
        settle(3_000)
        assertEquals(2, rig.controller.operations.value.count { it.operation.request == RegistrationRemoval(Bank.BPA) })
        text("Eliminando el registro en BPA…")
        node(hasContentDescription("Cerrar")).performClick()
        settle()
        text("Esperando respuesta")
        shot("settings-bank-unregistering")
        back()

        // Ajustes › Clásica: its association with MiTransfer goes with the identity card; MiTransfer answers in its words.
        text("Clásica").performClick()
        settle()
        shot("settings-classic")
        text("Quitar la asociación").performClick()
        settle()
        field("Carné de identidad").performTextInput("58072304511")
        settle()
        shot("classic-unassociate")
        text("Quitar asociación").performClick()
        settle(3_000)
        assertEquals(ClassicRemoval(CLASSIC, "58072304511"), lastRequest())
        deliver("Monedero Mi Transfer: La tarjeta fue desasociada de su cuenta.")
        text("Quitar la asociación de la Clásica")
        shot("classic-answered")
        text("Volver a la Clásica").performClick()
        settle()
        assertIs<ClassicSettingsDestination>(model.navigator.top)
        assertTrue(compose.onAllNodes(hasText("Respondida")).fetchSemanticsNodes().isEmpty())

        // Quitar la Clásica de la app: the last one takes MiTransfer's PIN with it, with the fingerprint.
        text("Quitar la Clásica de la app").performClick()
        settle()
        shot("classic-remove")
        text("Quitar").performClick()
        settle()
        assertEquals("Quitar la Clásica", host.prompts.last())
        assertTrue(rig.controller.classics.value.isEmpty())
        assertTrue(MiTransfer !in rig.controller.savedKeys.value)
        assertTrue(compose.onAllNodes(hasText("Clásica")).fetchSemanticsNodes().isEmpty())
        shot("settings-without-classic")
    }

    // ---- Helpers ----

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

    /** The newest request handed to the controller. */
    private fun lastRequest() = rig.controller.operations.value.first().operation.request

    private fun shot(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage("build/line-journey/$mode/${"%02d".format(++step)}-$name.png")
    }

    private fun dismissMessage() {
        compose.runOnUiThread { model.messages.currentSnackbarData?.dismiss() }
        settle()
    }

    private fun node(matcher: SemanticsMatcher): SemanticsNodeInteraction = compose.onAllNodes(matcher, useUnmergedTree = false).onFirst()

    /** The first node with exactly [value], scrolled into view: fails when there is none. */
    private fun text(value: String) = node(hasText(value)).also { runCatching { it.performScrollTo() } }.also { it.fetchSemanticsNode() }
    private fun key(digit: Char) = node(hasText(digit.toString()) and hasClickAction())
    private fun field(label: String) = node(hasSetTextAction() and hasText(label))

    private fun back() {
        compose.runOnUiThread { model.navigator.pop() }
        settle()
    }

    private class FakeHost : Host {
        val prompts = mutableListOf<String>()
        var next: BiometricResult? = null
        override suspend fun biometric(title: String, subtitle: String?, cipher: Cipher?): BiometricResult {
            prompts += title
            return next?.also { next = null } ?: BiometricResult.Passed(cipher)
        }
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
        private const val OWN_CARD = "9212129900000029"
        private const val DOLLARS = "9225129900435519"
        private const val ASSOCIATED = "9212129900000037"
        private const val BANDEC_CARD = "9206069900000041"
        private const val CLASSIC = "9760030000007813"

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun modes(): List<Array<String>> = listOf("light", "dark", "font150").map { arrayOf(it) }
    }
}
