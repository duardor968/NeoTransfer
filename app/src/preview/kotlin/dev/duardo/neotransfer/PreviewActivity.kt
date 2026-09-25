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
import dev.duardo.neotransfer.data.*
import dev.duardo.neotransfer.platform.BankSmsRecord
import dev.duardo.neotransfer.platform.TelecomSmsController
import dev.duardo.neotransfer.platform.TelecomSmsStore
import dev.duardo.neotransfer.platform.TelecomSmsRuntime
import dev.duardo.neotransfer.platform.TelecomSmsTransport
import dev.duardo.neotransfer.platform.TelecomSmsRecord
import dev.duardo.neotransfer.fuel.FuelController
import java.math.BigDecimal
import java.time.Instant

/** Isolated visual test APK. No telephony or SMS permissions and no BankController instance. */
class PreviewActivity : ComponentActivity() {
    private var previewFuel: FuelController? = null

    override fun onStop() {
        previewFuel?.clearSensitive()
        super.onStop()
    }
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
            val domainFixtures = remember { previewDomainFixtures() }
            val telecom = remember {
                val records = linkedMapOf<String, TelecomSmsRecord>()
                val changes = getSharedPreferences("telecom_info_sms", MODE_PRIVATE)
                lateinit var simulator: TelecomSmsController
                simulator = TelecomSmsController(object : TelecomSmsStore {
                    override fun get(id: String) = records[id]
                    override fun records() = records.values.toList().sortedByDescending { it.draft.createdAtMillis }
                    override fun put(record: TelecomSmsRecord): Boolean {
                        records[record.draft.id] = record
                        changes.edit().putLong("preview_revision", System.nanoTime()).apply()
                        return true
                    }
                }, object : TelecomSmsRuntime {
                    override fun hasSendPermission() = true
                    override fun isActiveSubscription(subscriptionId: Int) = subscriptionId == 1
                }, TelecomSmsTransport { draft ->
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        simulator.onSentCallback(draft.id, draft.callbackToken, true, android.app.Activity.RESULT_OK)
                    }, 500)
                })
                simulator
            }
            var state by remember { mutableStateOf(fixture(intent.getStringExtra("scenario")).let { initial ->
                initial.copy(wallet = initial.wallet.copy(fuelCoupons = domainFixtures.fuelCoupons,
                    operations = initial.wallet.operations + domainFixtures.miTurnoRequests))
            }) }
            val fuel = remember { FuelController(domainFixtures.fuelSecretStore,
                readEnvelope = { id, revision -> domainFixtures.protectedFuelEnvelopes[id]?.takeIf { it.revision == revision } },
                currentCoupon = { id -> state.wallet.fuelCoupons.singleOrNull { it.id == id } }) }
            DisposableEffect(fuel) { previewFuel = fuel; onDispose { previewFuel = null; fuel.close() } }
            fun updateWallet(change: (WalletSnapshot) -> WalletSnapshot) { state = state.copy(wallet = change(state.wallet)) }
            fun selectProduct(id: String) {
                val product = walletProducts(state).firstOrNull { it.id == id } ?: return
                state = state.copy(selectedProductId = id, bank = product.identity.bank ?: state.bank,
                    wallet = state.wallet.copy(settings = state.wallet.settings.copy(selectedRegistrationId = product.registrationId,
                        selectedCardId = product.card?.id, selectedAccountId = product.account?.id)))
            }
            fun simulateService(request: ServiceRequest) {
                val spec = OperationCatalog.find(request.operationId) ?: return
                if (spec.effect == OperationEffect.QUERY) {
                    state = state.copy(serviceResult = "Consulta de demostración. No se ha contactado con el banco.")
                    return
                }
                val product = selectedProduct(state)
                updateWallet { wallet -> wallet.copy(operations = wallet.operations + OperationRecord(
                    java.util.UUID.randomUUID().toString(), spec.title, request.identity.bank?.code, 1,
                    request.values["destination"].orEmpty(), request.values["amount"],
                    request.currency?.name, Instant.now().toEpochMilli(), registrationId = product?.registrationId,
                    source = request.source.wireValue, specId = spec.id, providerId = request.identity.provider.name,
                    profileId = request.identity.profile.name, status = OperationStatus.UNCERTAIN,
                    reviewRequired = true, parameters = request.values.filterKeys { key -> spec.fields.none { it.key == key && it.sensitive } },
                )) }
                state = state.copy(serviceResult = "Solicitud de demostración guardada para revisar. No se ha enviado al banco.")
            }
            NeoTransferApp(state, UiActions(
                unlock = { state = state.copy(unlocked = true) },
                enroll = { bank, pin, done -> pin.fill('\u0000'); state = state.copy(unlocked = true, hasCredentials = true, bank = bank); done() },
                permissions = { state = state.copy(permissions = true) },
                cameraPermission = { requestPermissions(arrayOf(Manifest.permission.CAMERA), 10) },
                selectBank = { state = state.copy(bank = it) }, selectSim = { state = state.copy(subscription = it) },
                balance = { state = state.copy(notice = "Consulta simulada") }, refresh = { state = state.copy(notice = "Actividad de prueba actualizada") },
                pay = { state = state.copy(pending = PendingRecord(it, 1, Instant.now())) },
                saveRecipient = { state = state.copy(recipients = state.recipients + it) },
                resolvePending = { state = state.copy(pending = null) }, dismissNotice = { state = state.copy(notice = null) },
                pickContact = { state = state.copy(contact = PickedContact("Contacto de prueba", "50000000")) },
                clearContact = { state = state.copy(contact = null) }, lock = { state = state.copy(unlocked = false) },
                resolveUncertain = { id, _ -> state = state.copy(uncertain = state.uncertain.filterNot { it.id == id }) },
                resetCredentials = { state = state.copy(unlocked = false, hasCredentials = false) },
                protectScreen = { if (it) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) },
                share = { state = state.copy(notice = "Comprobante de prueba preparado") },
                selectProduct = ::selectProduct,
                selectRegistration = { id ->
                    state.wallet.registrations.singleOrNull { it.id == id }?.let { registration ->
                        state = state.copy(selectedProductId = "registration:$id", subscription = registration.subscriptionId ?: -1,
                            bank = registration.identity()?.bank ?: state.bank,
                            wallet = state.wallet.copy(settings = state.wallet.settings.copy(selectedRegistrationId = id,
                                selectedCardId = null, selectedAccountId = null, activeBankCode = registration.bankCode ?: state.wallet.settings.activeBankCode,
                                subscriptionId = registration.subscriptionId ?: -1)))
                    }
                },
                saveCard = { card -> updateWallet { it.copy(cards = it.cards.replacing(card) { item -> item.id }) }; selectProduct(card.id) },
                deleteCard = { id -> updateWallet { it.copy(cards = it.cards.filterNot { card -> card.id == id }) } },
                saveAccount = { account -> updateWallet { it.copy(accounts = it.accounts.replacing(account) { item -> item.id }) }; selectProduct(account.id) },
                deleteAccount = { id -> updateWallet { it.copy(accounts = it.accounts.filterNot { account -> account.id == id }) } },
                saveContact = { contact -> updateWallet { it.copy(contacts = it.contacts.replacing(contact) { item -> item.id }) } },
                deleteContact = { id -> updateWallet { it.copy(contacts = it.contacts.filterNot { contact -> contact.id == id }) } },
                saveService = { service -> updateWallet { it.copy(services = it.services.replacing(service) { item -> item.id }) } },
                deleteService = { id -> updateWallet { it.copy(services = it.services.filterNot { service -> service.id == id }) } },
                acknowledgeOperation = { id -> updateWallet { it.copy(operations = it.operations.map { row -> if (row.id == id) row.copy(reviewRequired = false) else row }) } },
                removeRegistration = { id ->
                    updateWallet { it.copy(registrations = it.registrations.filterNot { item -> item.id == id },
                        cards = it.cards.filterNot { item -> item.registrationId == id }, accounts = it.accounts.filterNot { item -> item.registrationId == id }) }
                    state = state.copy(configuredRegistrationIds = state.configuredRegistrationIds - id)
                },
                reassociateRegistration = { id, sim ->
                    updateWallet { it.copy(registrations = it.registrations.map { row -> if (row.id == id) row.copy(subscriptionId = sim, enabled = true) else row }) }
                    state = state.copy(configuredRegistrationIds = state.configuredRegistrationIds + id)
                },
                enrollProvider = { identity, pin, done ->
                    pin.fill('\u0000')
                    val id = "preview:${identity.provider}"
                    val row = RegistrationRecord(id, "owner", identity.provider.name, ProfileId.PERSONAL.name,
                        identity.bank?.code, state.subscription, "50000000", identity.title(), credentialAlias = "preview")
                    updateWallet { it.copy(registrations = it.registrations.replacing(row) { item -> item.id }) }
                    state = state.copy(hasCredentials = true, unlocked = true, configuredRegistrationIds = state.configuredRegistrationIds + id,
                        configuredBanks = state.configuredBanks + listOfNotNull(identity.bank))
                    done()
                },
                setNotifications = { state = state.copy(notificationsEnabled = it) },
                runService = ::simulateService, runQrService = { request, _ -> simulateService(request) },
                validateService = OperationCatalog::validate,
                clearServiceResult = { state = state.copy(serviceResult = null) },
                exportBackup = { state = state.copy(notice = "El respaldo se comprueba en la aplicación de pruebas instrumentadas") },
                importBackup = { state = state.copy(notice = "La importación se comprueba en la aplicación de pruebas instrumentadas") },
                importTransfermovil = { state = state.copy(notice = "La importación se comprueba en la aplicación de pruebas instrumentadas") },
                importContacts = { updateWallet { it.copy(contacts = it.contacts + ContactRecord("import-demo", "Contacto importado", listOf(ContactPhone("50000009")))) } },
                renameFuel = { id, label -> updateWallet { wallet -> wallet.copy(fuelCoupons = wallet.fuelCoupons.map { if (it.id == id) it.copy(label = label.trim()) else it }) } },
                archiveFuel = { id, archived -> updateWallet { wallet -> wallet.copy(fuelCoupons = wallet.fuelCoupons.map { if (it.id == id) it.copy(archived = archived) else it }) } },
                authorizeFuel = { _, _, result -> result(true) },
            ), initialQr = if (intent.getStringExtra("scenario") == "qr") QrPayment.parse(mapOf(
                "id_transaccion" to "ESTATICO-123", "importe" to "25.00", "moneda" to "CUP",
                "numero_proveedor" to "123", "descripcion" to "Compra de prueba")) else null,
                telecomController = telecom, smsPermission = true, fuelController = fuel)
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
        val wallet = if (scenario in listOf("empty", "enroll")) WalletSnapshot() else fixtureWallet(now)
        return AppUiState(unlocked = scenario != "locked" && scenario != "enroll", hasCredentials = scenario != "enroll",
            bank = if (scenario == "bpa") Bank.BPA else Bank.BANDEC, subscription = 1, sims = listOf(SimChoice(1, "SIM 1 · CUBACEL")),
            configuredBanks = wallet.registrations.mapNotNull { row -> Bank.entries.firstOrNull { it.code == row.bankCode } }.toSet(), busy = false,
            accounts = if (scenario == "empty") emptyList() else if (scenario == "bpa") listOf(AccountBalance(null, null, money("8240.50")))
                else listOf(AccountBalance("0000XXXXXXXX0001", money("8240.50"), money("8240.50"))),
            balanceAt = if (scenario == "empty") null else now.minusSeconds(120), history = if (scenario == "empty") emptyList() else entries,
            pending = if (scenario == "pending") PendingRecord(MoneyAction(ActionKind.TRANSFER, Bank.BANDEC, "0000000000000002", money("350.00")), 1, now.minusSeconds(90)) else null,
            confirmed = if (scenario == "success") BankMessage.TransferSent(Bank.BANDEC, "0000XXXXXXXX0002", money("350.00"), "DEMO02", money("8240.50")) else null,
            recipients = listOf(Recipient("Alejandro Hernández", "0000000000000002", "50000000")),
            notice = null, permissions = true, cameraPermission = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED, contact = null,
            uncertain = if (scenario == "uncertain") listOf(UncertainTransfer("fixture", TransferRequest(Bank.BANDEC, "0000000000000002", money("350.00")), 1, now.minusSeconds(8000))) else emptyList(),
            wallet = wallet, configuredRegistrationIds = wallet.registrations.map { it.id }.toSet(),
            selectedProductId = if (scenario == "bpa") "bpa-card" else "bandec-card", services = OperationCatalog.all)
    }

    private fun fixtureWallet(now: Instant): WalletSnapshot {
        val registrations = listOf(ProviderId.BANDEC, ProviderId.BPA, ProviderId.BANMET, ProviderId.MITRANSFER).map {
            RegistrationRecord(it.name, "owner", it.name, ProfileId.PERSONAL.name, it.bank?.code, 1, "50000000", it.title(), "preview")
        }
        val cards = listOf(
            CardRecord("bandec-card", "BANDEC", "0000000000000001", "Mi tarjeta CUP", currency = "CUP", favorite = true, holderName = "ALEX RIVERA", expiry = "02/36"),
            CardRecord("bandec-other", "BANDEC", "0000000000000011", "Ahorro", currency = "CUP"),
            CardRecord("bpa-card", "BPA", "0000000000000012", "Mi tarjeta BPA", currency = "CUP", holderName = "ALEX RIVERA", expiry = "01/35"),
            CardRecord("banmet-card", "BANMET", "0000000000000013", "Metropolitano", currency = "CUP"),
            CardRecord("classic-card", "MITRANSFER", "0000000000000015", "Mi Clásica", currency = "USD", profileId = "CLASSIC"),
        )
        val accounts = listOf(AccountRecord("wallet-cup", "MITRANSFER", "", "MiTransfer CUP", currency = "CUP"),
            AccountRecord("wallet-usd", "MITRANSFER", "", "MiTransfer USD", currency = "USD"))
        val balances = cards.mapIndexed { index, card -> BalanceRecord("balance:${card.id}", registrations.single { it.id == card.registrationId }.bankCode ?: "04",
            1, now.minusSeconds(120).toEpochMilli(), card.number, if (index == 0) "8240.50" else "${(index + 1) * 250}.00", card.currency!!,
            registrationId = card.registrationId, cardId = card.id) }
        return WalletSnapshot(identities = listOf(IdentityRecord("owner", "Titular de demostración")), registrations = registrations,
            cards = cards, accounts = accounts, balances = balances,
            contacts = listOf(ContactRecord("alejandro", "Alejandro Hernández", listOf(ContactPhone("50000000", "Móvil")),
                listOf(ContactCard("0000000000000002", "CUP", Bank.BPA.code), ContactCard("0000000000000003", "Ahorro", Bank.BANDEC.code)), true),
                ContactRecord("lucia", "Lucía Rodríguez", listOf(ContactPhone("50000004")), listOf(ContactCard("0000000000000004")))),
            histories = listOf(HistoryEntryRecord("bank-record", Bank.BANDEC.code, 1, now.minusSeconds(172800).atZone(java.time.ZoneId.of("America/Havana")).toLocalDate().toString(),
                "Intereses", true, "12.34", "CUP", "DEMO-HISTORY", registrationId = "BANDEC", cardId = "bandec-card")))
    }
}

private fun <T> List<T>.replacing(value: T, key: (T) -> String): List<T> = filterNot { key(it) == key(value) } + value
