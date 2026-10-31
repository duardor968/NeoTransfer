package dev.duardo.neotransfer.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsEnabled
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
import dev.duardo.neotransfer.core.ledger.OperationState
import dev.duardo.neotransfer.core.model.Bank as CoreBank
import dev.duardo.neotransfer.core.model.MiTransfer
import dev.duardo.neotransfer.core.protocol.BankRegistration
import dev.duardo.neotransfer.core.protocol.ClassicAssociation
import dev.duardo.neotransfer.core.protocol.ClassicQrPay
import dev.duardo.neotransfer.core.protocol.ClassicTransfer
import dev.duardo.neotransfer.core.protocol.MiTransferRegistration
import dev.duardo.neotransfer.data.ClassicCard
import dev.duardo.neotransfer.data.Settings
import dev.duardo.neotransfer.data.Theme
import dev.duardo.neotransfer.engine.FakeModem
import dev.duardo.neotransfer.engine.Rig
import dev.duardo.neotransfer.platform.BiometricResult
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.crypto.Cipher
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Registering the line and the Clásica end to end on the JVM, like [AppJourneyTest]: the real [AppModel] and
 * controller over the fake modem, inbox and vault, with a fingerprint that always passes. Each step is captured to
 * `build/journey-classic/<mode>/`.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalRoborazziApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xhdpi", application = Application::class)
class ClassicJourneyTest(private val mode: String) {
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

    /** A line without MiTransfer: register it, associate the Clásica, then Home, balance, transfer, QR payment, last operations. */
    @Test
    fun classic() {
        launch()
        shot("onboarding-bank")
        node(hasContentDescription("Clásica de Fincimex")).performClick()
        settle()
        shot("classic-pin")
        text("No tengo PIN: registrar mi línea").performClick()
        settle()

        // MiTransfer's registration: identity card and a new PIN typed twice, then the fingerprint, then the 49.
        field("Carné de identidad").performTextInput(IDENTITY)
        "1234".forEach { key(it).performClick() }
        "1239".forEach { key(it).performClick() }
        settle()
        shot("classic-register-mismatch")
        "1234".forEach { key(it).performClick() }
        "1234".forEach { key(it).performClick() }
        settle()
        shot("classic-register")
        text("Registrar").assertIsEnabled().performClick()
        settle(2_000)
        assertEquals("Registrar en MiTransfer", host.prompts.last())
        assertEquals(listOf(49), rig.modem.services)
        assertIs<MiTransferRegistration>(rig.controller.operations.value.first().operation.request)
        shot("classic-register-waiting")

        // The confirmation: the PIN chosen is saved with the fingerprint by itself.
        deliver("Monedero Mi Transfer: La operacion de registro fue completada. Bienvenido a Transfermovil.")
        assertTrue(MiTransfer in rig.controller.savedKeys.value)
        assertTrue(rig.vault.contains("MITRANSFER=1234"))
        shot("classic-registered")
        text("Continuar").performClick()
        settle()

        // The association, inside MiTransfer's session; its answer comes in MiTransfer's own words.
        field("Número de tarjeta").performTextInput(CLASSIC)
        field("Autorización").performTextInput("482913")
        field("Terminal").performTextInput("40217385")
        settle()
        shot("classic-associate")
        text("Asociar").performClick()
        settle(3_000)
        assertEquals("Asociar la Clásica", host.prompts.last())
        assertEquals(listOf(49, 40, 111), rig.modem.services)
        assertIs<ClassicAssociation>(rig.controller.operations.value.first().operation.request)
        shot("classic-associate-waiting")
        deliver("Monedero Mi Transfer: La tarjeta 9760XXXXXXXX5679 fue asociada a su cuenta.")
        shot("classic-associate-answer")
        text("Añadir la Clásica").performClick()
        settle()
        assertEquals(listOf(ClassicCard(CLASSIC)), rig.controller.classics.value)
        node(hasText("Consultar últimas operaciones")).assertExists()
        shot("home-classic-new")

        // Its balance is MiTransfer's text, shown as it came without the signature.
        text("Consultar saldo").performClick()
        settle(3_000)
        assertEquals(46, rig.modem.services.last())
        deliver("Monedero Mi Transfer: Su saldo es 120.00 USD")
        node(hasText("Su saldo es 120.00 USD")).assertExists()
        shot("home-classic")

        // Transferir from the Clásica: a bank card is refused, another Clásica goes by MiTransfer in USD.
        text("Transferir").performClick()
        settle()
        node(hasSetTextAction()).performTextInput(BANK_CARD)
        settle()
        node(hasText("La Clásica solo transfiere a otra Clásica")).assertExists()
        shot("classic-transfer-bank")
        node(hasSetTextAction()).performTextClearance()
        node(hasSetTextAction()).performTextInput(FRIEND)
        settle()
        node(hasText("Tarjeta nueva", substring = true)).performClick()
        settle()
        "10".forEach { key(it).performClick() }
        settle()
        shot("classic-transfer-amount")
        text("Continuar").performClick()
        settle()
        shot("classic-transfer-review")
        text("Transferir 10.00 USD").performClick()
        settle(2_000)
        assertEquals("Transferir 10.00 USD", host.prompts.last())
        assertEquals(45, rig.modem.services.last())
        val transfer = assertIs<ClassicTransfer>(rig.controller.operations.value.first().operation.request)
        assertEquals(CLASSIC to FRIEND, transfer.card to transfer.destination)
        deliver("Monedero Mi Transfer:  La Transferencia fue completada. Beneficiario: $FRIEND Monto: 10.00 USD Nro. Transaccion: MT000T1")
        assertIs<OperationState.Confirmed>(rig.controller.operations.value.first().state)
        shot("classic-transfer-confirmed")
        text("Volver al inicio").performClick()
        settle()

        // A QR payment in USD: the Clásica pays it with MiTransfer's PIN.
        compose.runOnUiThread { model.open(Intent(Intent.ACTION_VIEW, Uri.parse(PAY_LINK))) }
        settle()
        shot("classic-pay-review")
        text("Pagar 18.00 USD").performClick()
        settle(2_000)
        assertEquals("Pagar 18.00 USD", host.prompts.last())
        assertEquals(30, rig.modem.services.last())
        assertIs<ClassicQrPay>(rig.controller.operations.value.first().operation.request)
        deliver("Monedero Mi Transfer: Pago completado. Id Compra: SLR-20931 Importe: 18.00 USD No. Transaccion: MT000P1")
        assertIs<OperationState.Confirmed>(rig.controller.operations.value.first().state)
        text("Volver al inicio").performClick()
        settle()

        // The Clásica in Activity and Cobrar.
        text("Actividad").performClick()
        settle()
        shot("classic-activity")
        text("Servicentro La Rotonda").performClick()
        settle()
        shot("classic-operation")
        back()

        // Its last operations, asked like a bank's: MiTransfer's answer is text, on a screen of its own with its time.
        node(hasContentDescription("Últimas operaciones")).performClick()
        settle(3_000)
        assertEquals(48, rig.modem.services.last())
        node(hasText("Últimas operaciones de la Clásica")).assertExists()
        val today = DateTimeFormatter.ofPattern("dd/MM/yyyy").format(start.atZone(ZoneId.systemDefault()))
        deliver("Monedero Mi Transfer: Ultimas operaciones: $today Pago SLR-20931 18.00 USD; $today Transferencia 10.00 USD.")
        node(hasText("Pago SLR-20931 18.00 USD", substring = true)).assertExists()
        node(hasText("Respuesta de MiTransfer", substring = true)).assertExists()
        shot("classic-history")
        text("Volver a Actividad").performClick()
        settle()
        node(hasText("Servicentro La Rotonda")).assertExists()
        text("Inicio").performClick()
        settle()
        text("Cobrar").performClick()
        settle()
        shot("classic-receive")
    }

    /** A bank without a key: register the line; the key of the SMS is saved with the fingerprint, its card PIN never. */
    @Test
    fun bankRegistration() {
        launch()
        node(hasContentDescription("Banco Popular de Ahorro")).performClick()
        settle()
        shot("bank-key")
        text("No tengo clave: registrar mi línea").performClick()
        settle()
        field("Número de tarjeta").performTextInput(OWN_CARD)
        field("Nombre").performTextInput("Laura")
        field("Primer apellido").performTextInput("Méndez")
        field("Segundo apellido").performTextInput("Soto")
        field("Carné de identidad").performTextInput(IDENTITY)
        settle()
        // The protocol has no accents: what the field shows is what the bank gets.
        node(hasSetTextAction() and hasText("Mendez")).assertExists()
        shot("bank-register")
        text("Registrar").performClick()
        settle(2_000)
        assertEquals("Registrar la línea en BPA", host.prompts.last())
        assertEquals(listOf(49), rig.modem.services)
        val request = assertIs<BankRegistration>(rig.controller.operations.value.first().operation.request)
        assertEquals("Mendez", request.firstSurname)
        shot("bank-register-waiting")

        deliver(
            "Banco Popular de Ahorro: La operacion de registro fue completada. Bienvenido a Transfermovil. " +
                "Clave de Autenticacion: 4821 El PIN asociado a su tarjeta 921212XXXXXX6783 es 1357.",
        )
        assertTrue(CoreBank.BPA in rig.controller.savedKeys.value)
        assertTrue(rig.vault.contains("BPA=4821") && !rig.vault.contains("1357"))
        assertTrue(rig.controller.keysToSave.value.isEmpty())
        shot("bank-registered")
        text("Continuar").performClick()
        settle()
        node(hasText("Tarjetas de BPA")).assertExists()
        shot("bank-cards")
    }

    // ---- Helpers ----

    private fun launch() {
        shadowOf(application).grantPermissions(*AppModel.ALL_PERMISSIONS.toTypedArray())
        if (mode == "font150") RuntimeEnvironment.setFontScale(1.5f)
        if (mode == "dark") onController { rig.controller.updateSettings(Settings(theme = Theme.DARK)) }
        model = AppModel(application, rig.controller, dispatcher).also { it.host = host }
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
        kotlinx.coroutines.CoroutineScope(dispatcher).launch { block() }
        settle()
    }

    private fun deliver(body: String) {
        rig.inbox.deliver(body, start.plusMillis(scheduler.currentTime - 2_000), start.plusMillis(scheduler.currentTime))
        rig.controller.onBankSms()
        settle(7_000)
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        captureScreenRoboImage("build/journey-classic/$mode/${"%02d".format(++step)}-$name.png")
    }

    private fun node(matcher: androidx.compose.ui.test.SemanticsMatcher): SemanticsNodeInteraction =
        compose.onAllNodes(matcher, useUnmergedTree = false).onFirst()

    private fun text(value: String) = node(hasText(value)).also { runCatching { it.performScrollTo() } }
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
        private const val IDENTITY = "58072304511"
        private const val OWN_CARD = "9212120123456783"
        private const val CLASSIC = "9760030312345679"
        private const val FRIEND = "9760030155200783"
        private const val BANK_CARD = "9205880012345673"
        private const val PAY_LINK = "https://transfermovil.app/action?id_transaccion=SLR-20931&importe=18&moneda=USD&numero_proveedor=0001234&titulo=Servicentro%20La%20Rotonda"

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun modes(): List<Array<String>> = listOf("light", "dark", "font150").map { arrayOf(it) }
    }
}
