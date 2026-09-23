package dev.duardo.neotransfer

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import dev.duardo.neotransfer.core.*
import dev.duardo.neotransfer.platform.BankSmsRecord
import java.math.BigDecimal
import java.time.Instant

/** Isolated visual test APK. No telephony or SMS permissions and no BankController instance. */
class PreviewActivity : ComponentActivity() {
    override fun getResources(): Resources {
        val original = super.getResources()
        val scale = intent?.getFloatExtra("fontScale", original.configuration.fontScale)?.coerceIn(1f, 2f)
            ?: return original
        if (scale == original.configuration.fontScale) return original
        return baseContext.createConfigurationContext(Configuration(original.configuration).apply { fontScale = scale }).resources
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            var state by remember { mutableStateOf(fixture(intent.getStringExtra("scenario"))) }
            NeoTransferApp(state, UiActions(
                unlock = { state = state.copy(unlocked = true) },
                enroll = { bank, pin, done -> pin.fill('\u0000'); state = state.copy(unlocked = true, hasCredentials = true, bank = bank); done() },
                permissions = { state = state.copy(permissions = true) },
                cameraPermission = { requestPermissions(arrayOf(Manifest.permission.CAMERA), 10) },
                selectBank = { state = state.copy(bank = it) }, selectSim = { state = state.copy(subscription = it) },
                balance = { state = state.copy(notice = "Consulta simulada") }, refresh = { state = state.copy(notice = "Mensajes de prueba actualizados") },
                pay = { state = state.copy(pending = PendingRecord(it, 1, Instant.now())) },
                saveRecipient = { state = state.copy(recipients = state.recipients + it) },
                resolvePending = { state = state.copy(pending = null) }, dismissNotice = { state = state.copy(notice = null) },
                pickContact = { state = state.copy(contact = PickedContact("Contacto de prueba", "50000000")) },
                clearContact = { state = state.copy(contact = null) }, lock = { state = state.copy(unlocked = false) },
                resolveUncertain = { id, _ -> state = state.copy(uncertain = state.uncertain.filterNot { it.id == id }) },
                resetCredentials = { state = state.copy(unlocked = false, hasCredentials = false) },
                protectScreen = { if (it) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) },
                share = { state = state.copy(notice = "Comprobante de prueba preparado") },
            ), initialQr = if (intent.getStringExtra("scenario") == "qr") QrPayment.parse(mapOf(
                "id_transaccion" to "ESTATICO-123", "importe" to "25.00", "moneda" to "CUP",
                "numero_proveedor" to "123", "descripcion" to "Compra de prueba")) else null)
        }
    }

    private fun fixture(scenario: String?): AppUiState {
        val now = Instant.now()
        fun money(value: String) = Money(BigDecimal(value), Currency.CUP)
        val entries = if (scenario == "services") listOf(
            HistoryEntry(BankSmsRecord(8, "Recibo sintético Nauta Hogar con importe nominal e importe pagado separados.", now, 1),
                BankMessage.ServicePaymentCompleted(Bank.BPA, "Nauta Hogar", "fixture@example.invalid", money("300.00"), money("270.00"), "DEMO08")),
            HistoryEntry(BankSmsRecord(9, "Recibo sintético de recarga Nauta que no indica importe pagado.", now.minusSeconds(1800), 1),
                BankMessage.ServicePaymentCompleted(Bank.BPA, "Recarga Nauta", "fixture@example.invalid", money("100.00"), null, "DEMO09")),
        ) else listOf(
            HistoryEntry(BankSmsRecord(1, "Comprobante sintético de transferencia recibida. No corresponde a una operación real.", now.minusSeconds(1800), 1),
                BankMessage.TransferReceived("0000XXXXXXXX0001", "50000000", money("1500.00"), "DEMO01")),
            HistoryEntry(BankSmsRecord(2, "Comprobante sintético de transferencia enviada. No corresponde a una operación real.", now.minusSeconds(7200), 1),
                BankMessage.TransferSent(Bank.BANDEC, "0000000000000002", money("350.00"), "DEMO02", money("8240.50"))),
            HistoryEntry(BankSmsRecord(3, "Banco Bandec: Usted se encuentra autenticado en el sistema.", now.minusSeconds(86400), 1), BankMessage.Authenticated(Bank.BANDEC, "0000XXXXXXXX0001")),
            HistoryEntry(BankSmsRecord(4, "Comprobante sintético de pago. Importe pagado: 120.00 CUP. No. Transaccion: DEMO04", now.minusSeconds(86400), 1),
                BankMessage.PaymentCompleted(Bank.BPA, "Mercado del Parque", money("120.00"), "DEMO04", "COMPRA004", "21/09/2026 14:20")),
            HistoryEntry(BankSmsRecord(5, "Comprobante sintético de recarga. Monto Pagado: 250.00 CUP.", now.minusSeconds(90000), 1),
                BankMessage.RechargeCompleted(null, "50000000", money("250.00"), "DEMO05")),
            HistoryEntry(BankSmsRecord(6, "Código de verificación: 000000. Mensaje sintético.", now.minusSeconds(172800), 1), BankMessage.Unrecognized),
            HistoryEntry(BankSmsRecord(7, "Comprobante sintético de transferencia enviada.", now.minusSeconds(259200), 1),
                BankMessage.TransferSent(Bank.BPA, "0000XXXXXXXX0003", money("1234567.89"), "DEMO07", null)),
        )
        return AppUiState(unlocked = scenario != "locked" && scenario != "enroll", hasCredentials = scenario != "enroll",
            bank = if (scenario == "bpa") Bank.BPA else Bank.BANDEC, subscription = 1, sims = listOf(SimChoice(1, "SIM 1 · CUBACEL")),
            configuredBanks = Bank.entries.toSet(), busy = false,
            accounts = if (scenario == "empty") emptyList() else if (scenario == "bpa") listOf(AccountBalance(null, null, money("8240.50")))
                else listOf(AccountBalance("0000XXXXXXXX0001", money("8240.50"), money("8240.50"))),
            balanceAt = if (scenario == "empty") null else now.minusSeconds(120), history = if (scenario == "empty") emptyList() else entries,
            pending = if (scenario == "pending") PendingRecord(MoneyAction(ActionKind.TRANSFER, Bank.BANDEC, "0000000000000002", money("350.00")), 1, now.minusSeconds(90)) else null,
            confirmed = if (scenario == "success") BankMessage.TransferSent(Bank.BANDEC, "0000XXXXXXXX0002", money("350.00"), "DEMO02", money("8240.50")) else null,
            recipients = listOf(Recipient("Alejandro Hernández", "0000000000000002", "50000000")),
            notice = null, permissions = true, cameraPermission = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED, contact = null,
            uncertain = if (scenario == "uncertain") listOf(UncertainTransfer("fixture", TransferRequest(Bank.BANDEC, "0000000000000002", money("350.00")), 1, now.minusSeconds(8000))) else emptyList())
    }
}
