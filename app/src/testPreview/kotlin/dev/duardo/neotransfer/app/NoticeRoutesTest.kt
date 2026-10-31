package dev.duardo.neotransfer.app

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.duardo.neotransfer.core.ledger.OperationState
import dev.duardo.neotransfer.core.model.Bank
import dev.duardo.neotransfer.core.model.Currency
import dev.duardo.neotransfer.core.model.MiTransfer
import dev.duardo.neotransfer.core.model.Money
import dev.duardo.neotransfer.core.model.OwnCard
import dev.duardo.neotransfer.core.protocol.AccountsQuery
import dev.duardo.neotransfer.core.protocol.BalanceQuery
import dev.duardo.neotransfer.core.protocol.BankRegistration
import dev.duardo.neotransfer.core.protocol.CardAssociation
import dev.duardo.neotransfer.core.protocol.ClassicAssociation
import dev.duardo.neotransfer.core.protocol.ClassicBalanceQuery
import dev.duardo.neotransfer.core.protocol.ClassicHistoryQuery
import dev.duardo.neotransfer.core.protocol.ClassicQrPay
import dev.duardo.neotransfer.core.protocol.ClassicRemoval
import dev.duardo.neotransfer.core.protocol.ClassicTransfer
import dev.duardo.neotransfer.core.protocol.MiTransferRegistration
import dev.duardo.neotransfer.core.protocol.PinChange
import dev.duardo.neotransfer.core.protocol.RegistrationRemoval
import dev.duardo.neotransfer.core.protocol.UssdRequest
import dev.duardo.neotransfer.core.qr.QrCode
import dev.duardo.neotransfer.core.qr.QrContent
import dev.duardo.neotransfer.data.WalletCard
import dev.duardo.neotransfer.designsystem.format.formatCardNumber
import dev.duardo.neotransfer.engine.BankSms
import dev.duardo.neotransfer.engine.FakeModem
import dev.duardo.neotransfer.engine.Rig
import dev.duardo.neotransfer.engine.Routes
import dev.duardo.neotransfer.platform.BiometricResult
import dev.duardo.neotransfer.ui.shell.Tab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import javax.crypto.Cipher
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Where each request of the line and of the Clásica leads, on the JVM: [NeoApp] over the real [AppModel] and controller
 * with the fake modem, inbox and vault. Each one is sent as a screen sends it with [AppModel.submit], from a flow its
 * result replaces; its answer comes by SMS with the app in the background, and its notification is opened from Home.
 * Both screens must name the request. Each step is captured to `build/notice-routes/`.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xhdpi", application = Application::class)
class NoticeRoutesTest {
    @get:Rule
    val compose = createComposeRule()

    private val scheduler = TestCoroutineScheduler()
    private val dispatcher = StandardTestDispatcher(scheduler)
    private val start = Instant.now()
    private val rig = Rig(clock = { start.plusMillis(scheduler.currentTime) }, state = dispatcher, io = dispatcher, modem = FakeModem(latency = 800))
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var model: AppModel
    private var step = 0

    @Test
    fun routes() {
        shadowOf(application).grantPermissions(*AppModel.ALL_PERMISSIONS.toTypedArray())
        // Set up already: BPA with its key and a card, and the Clásica with MiTransfer's PIN.
        onController {
            val controller = rig.controller
            controller.unlock(controller.unlockCipher())
            controller.saveKey(controller.keyCipher(), Bank.BPA, "1234".toCharArray())
            controller.saveKey(controller.keyCipher(), MiTransfer, "2468".toCharArray())
            controller.saveCard(WalletCard(OwnCard(OWN_CARD, Bank.BPA)))
            controller.saveClassic(CLASSIC)
        }
        model = AppModel(application, rig.controller, dispatcher).also { it.host = FakeHost() }
        compose.setContent { NeoApp(model) }
        settle()

        // 68 with BPA: its state; a refusal of a removal that asks for nothing has nothing to correct.
        var id = submit(RegistrationRemoval(Bank.BPA))
        text("Eliminando el registro en BPA…")
        shot("removal-sent")
        answer(id, "Banco Popular de Ahorro: Error en la operacion solicitada.")
        assertIs<OperationState.Rejected>(rig.state(id))
        openNotice(id)
        text("BPA no eliminó el registro")
        assertTrue(compose.onAllNodes(hasText("Reintentar")).fetchSemanticsNodes().isEmpty())
        shot("removal-notice")

        // 68 of the Clásica: its state while it is in the app.
        id = submit(ClassicRemoval(CLASSIC, IDENTITY))
        text("Quitando la asociación de la Clásica…")
        answer(id, "Monedero Mi Transfer: Error en la operacion solicitada.")
        openNotice(id)
        text("MiTransfer no quitó la asociación")
        shot("classic-removal-notice")

        // 69: inside a session; its refusal names the bank.
        id = submit(PinChange(Bank.BPA), newKey = "5678")
        text("Cambiando la clave en BPA…")
        deliver(AUTH_BPA)
        answer(id, "Banco Popular de Ahorro: Error en la operacion solicitada.")
        openNotice(id)
        text("BPA no cambió la clave")
        shot("key-change-notice")

        // 60.
        id = submit(CardAssociation(Bank.BPA, ASSOCIATED))
        text("Asociando la tarjeta en BPA…")
        answer(id, "Error en la asociacion de tarjeta. Banco Popular de Ahorro: Esta tarjeta ya esta asociada a un celular")
        openNotice(id)
        text("BPA no asoció la tarjeta")
        shot("association-notice")

        // 58: the list of accounts, not asked again on opening.
        id = submit(AccountsQuery(Bank.BPA))
        text("Esperando la respuesta de BPA…")
        answer(id, "Banco Popular de Ahorro:  La consulta de saldo fue completada. \n Cuenta;Saldo Contable;Saldo Disponible;Moneda \n" +
            "$OWN_CARD; CR 845.10 ; CR 845.10 ;CUP | \n$DOLLARS; CR 120.00 ; CR 120.00 ;USD | \n")
        assertIs<OperationState.Confirmed>(rig.state(id))
        openNotice(id)
        text("Cuentas en el banco")
        text("Añadir")
        assertEquals(1, rig.controller.operations.value.count { it.operation.request is AccountsQuery })
        shot("accounts-notice")

        // A bank's balance: Home on its card.
        id = submit(BalanceQuery(Bank.BPA, rig.controller.sourceFor(OWN_CARD)))
        assertHome(OWN_CARD)
        answer(id, BankSms.balanceBpa("845.10"))
        openNotice(id)
        assertHome(OWN_CARD)
        node(hasText("845", substring = true))
        shot("balance-notice")

        // 49 with a bank: its own screen with the answer, where the key that came is saved on request; then its cards.
        id = submit(BankRegistration(Bank.BANDEC, BANDEC_CARD, "Laura Mendez Soto", expiry = "2812"))
        assertIs<BankRegistrationDestination>(model.navigator.top)
        text("Registrando en BANDEC…")
        answer(id, BankSms.registration("12345"))
        assertIs<OperationState.Confirmed>(rig.state(id))
        openNotice(id)
        assertIs<BankRegistrationDestination>(model.navigator.top)
        text("Línea registrada")
        assertTrue(!rig.vault.contains("BANDEC"))
        shot("bank-registration-notice")
        // Its notification again, with that screen on top, leaves it there.
        val depth = model.navigator.stack.size
        open(notice(id).route)
        assertEquals(depth, model.navigator.stack.size)
        text("Guardar la clave").performClick()
        settle()
        assertTrue(rig.vault.contains("BANDEC=12345"))
        text("Continuar").performClick()
        settle()
        text("Tarjetas de BANDEC")

        // The Clásica's balance: MiTransfer's words, notified and shown without its signature; Home moves to the Clásica.
        val main = model.navigator.stack.first()
        id = submit(ClassicBalanceQuery(CLASSIC))
        assertHome(CLASSIC)
        answer(id, "Monedero Mi Transfer: Su saldo es 86.40 USD")
        assertIs<OperationState.Answered>(rig.state(id))
        assertEquals("Saldo de la Clásica" to "Su saldo es 86.40 USD", notice(id).let { it.title to it.text })
        compose.runOnUiThread { model.homeCard = OWN_CARD }
        settle()
        node(hasText("845", substring = true))
        openNotice(id)
        assertHome(CLASSIC)
        assertTrue(main === model.navigator.stack.single())
        text("Su saldo es 86.40 USD")
        assertTrue(compose.onAllNodes(hasText("845", substring = true)).fetchSemanticsNodes().isEmpty())
        assertTrue(compose.onAllNodes(hasText("Mi Transfer", substring = true)).fetchSemanticsNodes().isEmpty())
        shot("classic-balance-notice")
        compose.runOnUiThread { model.navigator.push(ClassicSettingsDestination(model, CLASSIC)) }
        settle()
        node(hasText("Su saldo es 86.40 USD", substring = true))
        assertTrue(compose.onAllNodes(hasText("Mi Transfer", substring = true)).fetchSemanticsNodes().isEmpty())
        shot("classic-settings-balance")

        // The Clásica's last operations: MiTransfer's text on a screen of its own, over Activity.
        id = submit(ClassicHistoryQuery(CLASSIC))
        assertEquals(Tab.Activity to 2, model.tab to model.navigator.stack.size)
        text("Últimas operaciones de la Clásica")
        deliver(AUTH_WALLET)
        answer(id, "Monedero Mi Transfer: Error en la operacion solicitada.")
        openNotice(id)
        assertEquals(Tab.Activity to 2, model.tab to model.navigator.stack.size)
        assertIs<LineRequestDestination>(model.navigator.top)
        node(hasText("Error en la operacion solicitada.", substring = true))
        shot("classic-history-notice")

        // 111 of a Clásica not in the app: its own screen with MiTransfer's reason; «Revisar los datos» keeps what was sent.
        id = submit(ClassicAssociation(UNKNOWN_CLASSIC, "482913", "40217385"))
        text("Asociando la Clásica…")
        answer(id, "Error en la asociacion de tarjeta Fincimex: No existe registro de la tarjeta.")
        openNotice(id)
        text("MiTransfer no asoció la Clásica")
        node(hasText("No existe registro de la tarjeta", substring = true))
        shot("classic-association-notice")
        text("Revisar los datos").performClick()
        settle()
        text("Asociar la Clásica")
        node(hasText("482913"))
        node(hasText("40217385"))
        node(hasContentDescription("Escanear"))
        shot("classic-association-form")

        // The Clásica's money is activity: its live result, then its receipt.
        id = submit(ClassicTransfer(CLASSIC, FRIEND, Money(BigDecimal.TEN, Currency.USD)))
        assertIs<ResultDestination>(model.navigator.top)
        node(hasText(formatCardNumber(FRIEND), substring = true))
        answer(id, "Monedero Mi Transfer:  La Transferencia fue completada. Beneficiario: $FRIEND Monto: 10.00 USD Nro. Transaccion: MT000T1")
        openNotice(id)
        assertIs<OperationDestination>(model.navigator.top)
        node(hasText(formatCardNumber(FRIEND), substring = true))
        shot("classic-transfer-notice")

        val payment = (QrCode.parse(PAY_LINK, LocalDate.now()) as QrContent.Payment).payment
        id = submit(ClassicQrPay(CLASSIC, payment, payment.amount))
        assertIs<ResultDestination>(model.navigator.top)
        node(hasText("Servicentro La Rotonda", substring = true))
        answer(id, "Monedero Mi Transfer: Pago completado. Id Compra: SLR-20931 Importe: 18.00 USD No. Transaccion: MT000P1")
        openNotice(id)
        assertIs<OperationDestination>(model.navigator.top)
        text("Servicentro La Rotonda")
        shot("classic-payment-notice")

        // MiTransfer's 49: its own screen with the answer, where the new PIN is saved on request.
        id = submit(MiTransferRegistration(IDENTITY), newKey = "1357")
        assertIs<ClassicRegisterDestination>(model.navigator.top)
        text("Registrando en MiTransfer…")
        answer(id, "Monedero Mi Transfer: La operacion de registro fue completada. Bienvenido a Transfermovil.")
        assertIs<OperationState.Confirmed>(rig.state(id))
        openNotice(id)
        text("Línea registrada")
        shot("mitransfer-registration-notice")
        text("Guardar el PIN").performClick()
        settle()
        assertTrue(rig.vault.contains("MITRANSFER=1357"))
        text("Continuar")

        // A receipt or a result opened on what is not money gives way to where it is shown; an unknown one, to a message.
        val keyChange = rig.controller.operations.value.first { it.operation.request is PinChange }.operation.id
        home()
        compose.runOnUiThread { model.navigator.push(OperationDestination(model, keyChange)) }
        settle()
        text("BPA no cambió la clave")
        compose.runOnUiThread { model.navigator.push(ResultDestination(model, keyChange)) }
        settle()
        assertIs<LineRequestDestination>(model.navigator.top)
        text("BPA no cambió la clave")
        home()
        open(Routes.operation("gone"))
        assertEquals(1, model.navigator.stack.size)
        assertEquals("Esa operación ya no está en la app", model.messages.currentSnackbarData?.visuals?.message)

        // A key change answered in words the app does not know: notified without the new key, opened where it is saved.
        id = submit(PinChange(Bank.BPA), newKey = "5678")
        answer(id, "Banco Popular de Ahorro: Su clave fue cambiada. Nueva clave 5678.")
        assertIs<OperationState.Answered>(rig.state(id))
        assertEquals("Cambio de clave en BPA" to "Su clave fue cambiada. Nueva clave ••••.", notice(id).let { it.title to it.text })
        openNotice(id)
        text("Cambio de clave en BPA")
        text("Guardar la clave nueva")
        assertTrue(compose.onAllNodes(hasText("5678", substring = true)).fetchSemanticsNodes().isEmpty())
        shot("key-change-answered-notice")
    }

    // ---- Helpers ----

    /** Sends [request] with [AppModel.submit] from a flow that its result replaces; the new operation's id. */
    private fun submit(request: UssdRequest, newKey: String? = null): String {
        home()
        val before = rig.controller.operations.value.size
        compose.runOnUiThread {
            val flow = SettingsDestination(model)
            model.navigator.push(flow)
            model.launch { model.submit(request, flow, newKey = newKey?.toCharArray()) }
        }
        settle(3_000)
        assertEquals(before + 1, rig.controller.operations.value.size)
        assertTrue(model.navigator.stack.none { it is SettingsDestination })
        return rig.controller.operations.value.first().operation.id.also { assertEquals(request, rig.status(it).operation.request) }
    }

    /** With the app in the background, the SMS that settles operation [id] notifies it. */
    private fun answer(id: String, body: String) {
        home()
        deliver(body)
        assertTrue(rig.notifier.posted.any { it.key == id }, "Sin notificación para ${rig.status(id).operation.request}: ${rig.state(id)}")
    }

    /** Taps the notification of operation [id] from Home. */
    private fun openNotice(id: String) {
        home()
        open(notice(id).route)
    }

    private fun notice(id: String) = rig.notifier.posted.last { it.key == id }

    private fun open(route: String) {
        compose.runOnUiThread { model.open(Intent().putExtra(Routes.EXTRA, route)) }
        settle()
    }

    private fun home() {
        compose.runOnUiThread {
            model.navigator.popToRoot()
            model.tab = Tab.Home
            model.messages.currentSnackbarData?.dismiss()
        }
        settle()
    }

    private fun assertHome(card: String) {
        assertEquals(Triple(Tab.Home, card, 1), Triple(model.tab, model.homeCard, model.navigator.stack.size))
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
        captureScreenRoboImage("build/notice-routes/${"%02d".format(++step)}-$name.png")
    }

    private fun node(matcher: SemanticsMatcher): SemanticsNodeInteraction =
        compose.onAllNodes(matcher, useUnmergedTree = false).onFirst().also { it.fetchSemanticsNode() }

    /** The first node with exactly [value], scrolled into view: fails when there is none. */
    private fun text(value: String) = node(hasText(value)).also { runCatching { it.performScrollTo() } }

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

    private companion object {
        const val OWN_CARD = "9212129900000029"
        const val DOLLARS = "9225129900435519"
        const val ASSOCIATED = "9212129900000037"
        const val BANDEC_CARD = "9206069900000041"
        const val CLASSIC = "9760030000007813"
        const val FRIEND = "9760030155200783"
        /** A Clásica neither in the app nor among the contacts. */
        const val UNKNOWN_CLASSIC = "9760030000009918"
        const val IDENTITY = "58072304511"
        const val AUTH_BPA = "Usted se ha autenticado en la plataforma de pagos moviles, en el Banco Popular de Ahorro, puede comenzar a utilizar " +
            "nuestros servicios de pagos a traves del movil"
        const val AUTH_WALLET = "Monedero Mi Transfer: Usted se ha autenticado en la plataforma de pagos moviles."
        const val PAY_LINK = "https://transfermovil.app/action?id_transaccion=SLR-20931&importe=18&moneda=USD&numero_proveedor=0001234&titulo=Servicentro%20La%20Rotonda"
    }
}
