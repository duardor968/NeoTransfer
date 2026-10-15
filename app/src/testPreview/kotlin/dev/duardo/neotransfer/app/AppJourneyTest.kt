package dev.duardo.neotransfer.app

import android.app.Application
import android.content.Intent
import android.net.Uri
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
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.duardo.neotransfer.core.ledger.OperationState
import dev.duardo.neotransfer.core.protocol.CubacelQuery
import dev.duardo.neotransfer.data.Settings
import dev.duardo.neotransfer.data.Theme
import dev.duardo.neotransfer.engine.BankSms
import dev.duardo.neotransfer.engine.FakeModem
import dev.duardo.neotransfer.engine.NoticeKind
import dev.duardo.neotransfer.engine.Rig
import dev.duardo.neotransfer.engine.UssdReply
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
import javax.crypto.Cipher
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The integrated app end to end on the JVM: [NeoApp] over the real [AppModel] and controller, with the fake modem,
 * inbox and vault, and a host whose fingerprint always passes. Each step is captured to `build/journey/<mode>/`.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalRoborazziApi::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xhdpi", application = Application::class)
class AppJourneyTest(private val mode: String) {
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
    fun journey() {
        shadowOf(application).grantPermissions(*AppModel.ALL_PERMISSIONS.toTypedArray())
        if (mode == "font150") RuntimeEnvironment.setFontScale(1.5f)
        if (mode == "dark") onController { rig.controller.updateSettings(Settings(theme = Theme.DARK)) }
        model = AppModel(application, rig.controller, dispatcher).also { it.host = host }
        compose.setContent { NeoApp(model) }
        settle()

        // First start: nothing set up, no lock; bank, key (fingerprint), card.
        shot("onboarding-bank")
        node(hasContentDescription("Banco Popular de Ahorro")).performClick()
        settle()
        shot("onboarding-key")
        "1234".forEach { key(it).performClick(); settle() }
        assertEquals("Guardar clave de BPA", host.prompts.last())
        shot("onboarding-card")
        field("Número de tarjeta").performTextInput(OWN_CARD)
        field("Titular (opcional)").performTextInput("Laura Méndez Soto")
        field("Vence (opcional)").performTextInput("0329")
        settle()
        shot("onboarding-card-filled")
        text("Guardar tarjeta").performClick()
        settle()
        shot("onboarding-cards")
        text("Continuar").performClick()
        settle()
        shot("home-new")

        // Balance: the 40 goes first, then the 46; the SMS fills the balance in whenever it comes.
        text("Consultar saldo").performClick()
        settle(3_000)
        assertEquals(listOf(40, 46), rig.modem.services)
        shot("home-refreshing")
        deliver(BankSms.balanceBpa("12480.50"))
        shot("home-balance")

        // Transfer to a typed card: review, fingerprint, sent, confirmed by the SMS while the result is open.
        text("Transferir").performClick()
        settle()
        shot("transfer-recipient")
        node(hasSetTextAction()).performTextInput(FRIEND)
        settle()
        shot("transfer-recipient-typed")
        text("Tarjeta nueva").performClick()
        settle()
        "300".forEach { key(it).performClick() }
        settle()
        shot("transfer-amount")
        text("Continuar").performClick()
        settle()
        shot("transfer-review")
        text("Transferir 300.00 CUP").performClick()
        settle(2_000)
        assertEquals("Transferir 300.00 CUP", host.prompts.last())
        shot("transfer-sent")
        deliver(transferSent("300.00", "BR00JOURNEY1", "12180.50"))
        shot("transfer-confirmed")
        text("Ver operación").performClick()
        settle()
        shot("operation-confirmed")
        text("Guardar contacto").performClick()
        settle()
        field("Nombre").performTextInput("Juan Prueba")
        settle()
        shot("contact-new-from-operation")
        text("Guardar").performClick()
        settle()
        back()
        text("Volver al inicio").performClick()
        settle()

        // Same money still pending: asked, never blocked. Then the bank is busy: "No se ejecutó" with Reintentar.
        repeat(2) { attempt ->
            text("Transferir").performClick()
            settle()
            text("Juan Prueba").performClick()
            settle()
            "50".forEach { key(it).performClick() }
            text("Continuar").performClick()
            settle()
            text("Transferir 50.00 CUP").performClick()
            settle(2_000)
            if (attempt == 1) {
                shot("transfer-duplicate")
                text("Cancelar").performClick()
                settle()
                back()
                back()
                back()
            } else {
                text("Volver al inicio").performClick()
                settle()
            }
        }
        rig.controller.onBackground()
        deliver(BankSms.busy())
        val notice = rig.notifier.posted.last()
        assertEquals(NoticeKind.RESULT, notice.kind)
        assertEquals("No se ejecutó: el banco estaba ocupado", notice.text)
        rig.controller.onForeground()
        settle()
        shot("home-not-executed")
        assertIs<OperationState.NotExecuted>(rig.controller.operations.value.first().state)

        // A credit from a known phone: activity and notification name the contact.
        rig.controller.onBackground()
        deliver(BankSms.received(OWN_CARD, "750.00", "IN00JOURNEY", from = "5350000009"))
        rig.controller.onForeground()
        settle()
        assertTrue(rig.notifier.posted.any { it.kind == NoticeKind.CREDIT && it.title.endsWith("te envió 750 CUP") })

        // Tabs.
        text("Actividad").performClick()
        settle()
        shot("activity")
        text("Contactos").performClick()
        settle()
        shot("contacts")
        text("Servicios").performClick()
        settle()
        shot("services")
        text("Electricidad").performClick()
        settle()
        field("Código de cliente").performTextInput("01234567891")
        settle()
        shot("service-electricity")
        back()
        text("Agua").performClick()
        settle()
        shot("service-water")
        back()
        rig.modem.answer = { command -> if (command.service == null) UssdReply.Text("Saldo: 125.40 CUP. Linea activa hasta 03-12-2026.") else UssdReply.Text(ACK) }
        text("Saldo Cubacel").performClick()
        settle()
        node(hasContentDescription("Consultar saldo")).performClick()
        settle(2_000)
        assertIs<CubacelQuery>(rig.controller.operations.value.first().operation.request)
        shot("cubacel")
        back()

        // A payment link opens the review straight away (no camera); the key travels with it, no session needed.
        text("Inicio").performClick()
        settle()
        compose.runOnUiThread { model.open(Intent(Intent.ACTION_VIEW, Uri.parse(PAY_LINK))) }
        settle()
        shot("pay-review")
        text("Pagar 480.00 CUP").performClick()
        settle(2_000)
        assertEquals("Pagar 480.00 CUP", host.prompts.last())
        assertEquals(30, rig.modem.services.last())
        shot("pay-sent")
        text("Volver al inicio").performClick()
        settle()

        // A transfer the bank refuses: its reason, in its words.
        text("Transferir").performClick()
        settle()
        text("Juan Prueba").performClick()
        settle()
        "75".forEach { key(it).performClick() }
        text("Continuar").performClick()
        settle()
        text("Transferir 75.00 CUP").performClick()
        settle(2_000)
        deliver("Fallo la transferencia. Banco Popular de Ahorro: SALDO INSUFICIENTE")
        shot("transfer-rejected")
        text("Volver al inicio").performClick()
        settle()

        // Receive and the recharge shortcut.
        text("Cobrar").performClick()
        settle()
        shot("receive")
        back()
        text("Recargar").performClick()
        settle()
        shot("home-recharge")
        text("Móvil").performClick()
        settle()
        shot("service-mobile")
        back()

        // Settings and theme.
        node(hasContentDescription("Ajustes")).performClick()
        settle()
        shot("settings")
        text("BPA").performClick()
        settle()
        shot("settings-bank")
        back()
        text("Respaldo").performClick()
        settle()
        shot("settings-backup")
        back()
        back()

        // Lock after a minute in the background; the fingerprint goes up by itself (cancelled here: no loop).
        host.next = BiometricResult.Cancelled
        rig.controller.onBackground()
        settle(61_000)
        shot("lock-cancelled")
        text("Desbloquear").performClick()
        settle()
        shot("unlocked")
        assertEquals("Desbloquear NeoTransfer", host.prompts.last())
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
        captureScreenRoboImage("build/journey/$mode/${"%02d".format(++step)}-$name.png")
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

    private fun transferSent(amount: String, ref: String, left: String) =
        "Banco Popular de Ahorro:  La Transferencia fue completada. \n Fecha: 15/10/2026 \n Beneficiario: 9205XXXXXXXX5673 \n " +
            "Ordenante: CUP \n Monto: $amount CUP \n Nro. Transaccion: $ref \n Saldo restante: CR $left CUP "

    private class FakeHost : Host {
        val prompts = mutableListOf<String>()
        var next: BiometricResult? = null
        override suspend fun biometric(title: String, subtitle: String?, cipher: Cipher?): BiometricResult {
            prompts += title
            return next?.also { next = null } ?: BiometricResult.Passed(cipher)
        }
        override suspend fun createDocument(name: String): Uri? = null
        override suspend fun openDocument(): Uri? = null
        override suspend fun pickContact(): Uri? = null
        override fun displayName(uri: Uri): String? = null
        override fun requestPermissions(permissions: List<String>) = Unit
        override fun shouldExplain(permission: String) = false
        override suspend fun requestPermission(permission: String) = true
        override fun share(text: String) = Unit
        override fun copy(text: String) = Unit
        override fun open(url: String) = Unit
        override fun openAppSettings() = Unit
        override fun openNotificationSettings() = Unit
    }

    companion object {
        private const val OWN_CARD = "9212060123456781"
        private const val FRIEND = "9205880012345673"
        private const val ACK = "Su solicitud esta siendo procesada, espere un SMS"
        private const val PAY_LINK = "https://transfermovil.app/action?id_transaccion=CLE-00417&importe=480&moneda=CUP&numero_proveedor=0001234&titulo=Cafeteria%20La%20Esquina"

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun modes(): List<Array<String>> = listOf("light", "dark", "font150").map { arrayOf(it) }
    }
}
