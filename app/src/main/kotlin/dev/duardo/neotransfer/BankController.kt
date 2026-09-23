package dev.duardo.neotransfer

import android.Manifest
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.telephony.SubscriptionManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.duardo.neotransfer.core.*
import dev.duardo.neotransfer.platform.*
import org.json.JSONObject
import java.time.Instant
import java.time.LocalTime
import java.util.concurrent.Executors

class NeoTransferApplication : Application() {
    val controller by lazy { BankController(this) }
}

data class SimChoice(val id: Int, val label: String)
class HistoryEntry(val record: BankSmsRecord, val message: BankMessage)

class BankController(
    private val context: Context,
    private val gateway: UssdTransport = UssdGateway(context),
    private val readInbox: () -> List<BankSmsRecord> = { BankSmsInbox(context).read() },
    private val listSims: () -> List<SimChoice> = {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) emptyList()
        else try {
            context.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
                .map { SimChoice(it.subscriptionId, "SIM ${it.simSlotIndex + 1} · ${it.carrierName}") }
        } catch (_: SecurityException) { emptyList() }
    },
    private val now: () -> Instant = Instant::now,
) {
    val store = BankStore(context)
    val vault = BankPinVault(context)
    private val parser = BankSmsParser()
    private val commands = BankCommands()
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val pins = mutableMapOf<Bank, CharArray>()
    private var generation = 0
    private var session: BankSession? = null
    private var latestBankEvidence: BankSession? = null
    private var authWait by mutableStateOf<AuthWait?>(null)
    private var afterAuth by mutableStateOf<AuthWait?>(null)
    private var balanceWait by mutableStateOf<BalanceWait?>(null)
    private var inboxReading = false
    private var transportActive by mutableStateOf(false)

    var unlocked by mutableStateOf(false); private set
    var bank by mutableStateOf(store.bank); private set
    var subscription by mutableStateOf(store.subscription); private set
    var sims by mutableStateOf(emptyList<SimChoice>()); private set
    var configuredBanks by mutableStateOf(emptySet<Bank>()); private set
    val busy: Boolean get() = transportActive || authWait != null || afterAuth != null || balanceWait != null
    var notice by mutableStateOf<String?>(null)
    private val restoredBalance = validStoredBalance()
    var accounts by mutableStateOf(restoredBalance.second); private set
    var balanceAt by mutableStateOf(restoredBalance.first); private set
    var history by mutableStateOf(emptyList<HistoryEntry>()); private set
    var pending by mutableStateOf(store.pending()); private set
    var recipients by mutableStateOf(store.recipients()); private set
    var uncertain by mutableStateOf(store.uncertain()); private set
    var confirmed by mutableStateOf<BankMessage.TransferSent?>(null); private set

    private class AuthWait(
        val bank: Bank, val subscription: Int, val started: Instant,
        val balanceOnly: Boolean, val run: () -> Unit,
    ) {
        var accepted = false
        var confirmedAt: Instant? = null
    }
    private class BalanceWait(val bank: Bank, val subscription: Int, val started: Instant, val auth: AuthWait?) {
        var accepted = false
        var confirmedAt: Instant? = null
    }

    fun readSims() {
        if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return
        sims = listSims()
        if (subscription == -1 && sims.size == 1) selectSim(sims.single().id)
    }

    fun selectSim(id: Int) {
        if (pending != null || busy || !gateway.isIdle()) return
        if (subscription != id) { session = null; latestBankEvidence = null; accounts = emptyList(); balanceAt = null }
        subscription = id; store.subscription = id
        restoreBalance()
    }

    fun selectBank(value: Bank) {
        if (pending != null || busy || !gateway.isIdle()) return
        if (bank != value) { session = null; accounts = emptyList(); balanceAt = null }
        bank = value; store.bank = value
        restoreBalance()
    }

    private fun validStoredBalance(): Pair<Instant?, List<AccountBalance>> =
        store.balances(bank, subscription).takeIf { it.first?.let { at -> at <= now() } == true } ?: (null to emptyList())
    private fun restoreBalance() { validStoredBalance().let { balanceAt = it.first; accounts = it.second } }
    private fun updateBalance(values: List<AccountBalance>, at: Instant) {
        if (at > now()) return
        store.saveBalances(bank, subscription, at, values)
        accounts = values; balanceAt = at
    }

    fun unlock(bytes: ByteArray) {
        try {
            val json = JSONObject(String(bytes, Charsets.UTF_8))
            pins.values.forEach { it.fill('\u0000') }; pins.clear()
            for (item in Bank.entries) if (json.has(item.name)) {
                val pin = json.getString(item.name).toCharArray()
                require(pin.size == item.pinLength && pin.all { it in '0'..'9' })
                pins[item] = pin
            }
            require(pins.isNotEmpty())
            configuredBanks = pins.keys.toSet()
            unlocked = true
            readSims(); reloadInbox()
            tick()
        } finally { bytes.fill(0) }
    }

    fun enrollmentBytes(value: Bank, pin: CharArray): ByteArray {
        require(pin.size == value.pinLength && pin.all { it in '0'..'9' })
        return JSONObject().apply {
            pins.forEach { (b, p) -> put(b.name, String(p)) }
            put(value.name, String(pin))
        }.toString().toByteArray(Charsets.UTF_8)
    }

    fun lock() {
        generation++
        pins.values.forEach { it.fill('\u0000') }; pins.clear()
        unlocked = false; authWait = null; afterAuth = null; balanceWait = null; session = null
        confirmed = null; notice = null
        main.removeCallbacks(ticker)
    }

    fun pinFrom(bytes: ByteArray, selected: Bank): CharArray = try {
        JSONObject(String(bytes, Charsets.UTF_8)).getString(selected.name).toCharArray()
    } finally { bytes.fill(0) }

    fun queryBalance() {
        if (!canOperate() || busy) return
        val pin = pins[bank]?.copyOf() ?: return
        notice = null
        try { withSession(pin, balanceOnly = true) { sendBalance() } }
        finally { pin.fill('\u0000') }
    }

    fun disconnectBank() {
        if (!canOperate() || busy || pending != null) return
        session = null
        send(commands.disconnect()) { result -> notice = failure(result) }
    }

    private fun canOperate(): Boolean {
        if (!unlocked) return false
        readSims()
        if (!sims.any { it.id == subscription }) { notice = "Selecciona una SIM disponible"; return false }
        if (!configuredBanks.contains(bank)) { notice = "Configura la clave de ${bank.name}"; return false }
        if (!gateway.isIdle()) { notice = "Hay una solicitud en curso"; return false }
        if (context.checkSelfPermission(Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            notice = "Permite llamadas y SMS para continuar"; return false
        }
        return true
    }

    private fun withSession(pin: CharArray, balanceOnly: Boolean = false, operation: () -> Unit) {
        if (session?.isValidFor(bank, subscription, now()) == true) return operation()
        val started = now()
        val wait = AuthWait(bank, subscription, started, balanceOnly, operation)
        session = null
        authWait = wait
        send(commands.authenticate(bank, pin)) { result ->
            if (authWait !== wait) return@send
            if (!fresh(wait.started, now())) {
                authWait = null; notice = "No llegó la confirmación de acceso. Consulta los mensajes del banco."
                return@send
            }
            when ((result as? UssdResult.Response)?.let { BankResponse.parse(it.text) }) {
                BankResponse.PROCESSING -> { wait.accepted = true; completeAuthentication(wait) }
                BankResponse.ALREADY_AUTHENTICATED -> {
                    // The platform may still be using a bank selected in another app.
                    authWait = null
                    sendBalance(wait)
                }
                else -> { authWait = null; notice = failure(result) }
            }
        }
        main.postDelayed({
            if (authWait === wait) { authWait = null; notice = "No llegó la confirmación de acceso. Consulta los mensajes del banco." }
        }, 30_000)
    }

    private fun fresh(started: Instant, receivedAt: Instant): Boolean =
        receivedAt >= started && receivedAt <= now() && now() < started.plusSeconds(30)

    private fun completeAuthentication(wait: AuthWait) {
        val at = wait.confirmedAt ?: return
        if (authWait !== wait || !wait.accepted) return
        authWait = null
        if (fresh(wait.started, at)) continueSession(wait, at)
    }

    private fun continueSession(wait: AuthWait, at: Instant, balanceConfirmed: Boolean = false) {
        if (!unlocked || wait.bank != bank || wait.subscription != subscription) return
        val latest = latestBankEvidence?.takeIf { it.authenticatedAt <= now() }
        if (latest != null && latest.subscriptionId == wait.subscription && latest.bank != wait.bank && latest.authenticatedAt >= at) {
            notice = otherBankNotice(latest.bank)
            return
        }
        session = BankSession(wait.bank, wait.subscription, at)
        if (balanceConfirmed && wait.balanceOnly) return
        afterAuth = wait
        fun continueWhenIdle() {
            if (afterAuth !== wait) return
            if (!unlocked || now() >= at.plusSeconds(30)) { afterAuth = null; session = null; return }
            if (gateway.isIdle()) { afterAuth = null; wait.run() } else main.postDelayed({ continueWhenIdle() }, 200)
        }
        continueWhenIdle()
    }

    fun submit(action: MoneyAction, pin: CharArray) {
        try {
            if (!canOperate() || busy || pending != null || action.bank != bank) return
            notice = null
            if (action.kind == ActionKind.TRANSFER && bank == Bank.BANDEC) {
                require(sourceCurrency(accounts, action.source) == action.amount.currency) { "Consulta el saldo para comprobar la moneda de la cuenta de origen" }
            }
            val time = LocalTime.now()
            val wire = when (action.kind) {
                ActionKind.TRANSFER -> listOf(commands.transfer(action.transferRequest()))
                ActionKind.QR -> commands.qrPayment(bank, pin, requireNotNull(action.qr), action.amount,
                    action.description, action.source, action.phone, "${time.minute}${time.second.toString().padStart(2, '0')}")
                ActionKind.RECHARGE -> listOf(commands.recharge(bank, action.destination, action.amount, action.source))
                ActionKind.ELECTRICITY, ActionKind.TELEPHONE -> listOf(commands.bill(bank,
                    action.kind == ActionKind.ELECTRICITY, action.destination, action.amount, action.source))
            }
            val token = generation
            withSession(pin) {
                if (unlocked && generation == token && pending == null) {
                    if (action.kind == ActionKind.TRANSFER && bank == Bank.BANDEC &&
                        sourceCurrency(accounts, action.source) != action.amount.currency) {
                        notice = "El saldo consultado no confirma la moneda de la cuenta de origen"
                        return@withSession
                    }
                    val record = PendingRecord(action, subscription, now())
                    try {
                        store.savePending(record)
                        pending = record; confirmed = null
                        sendParts(wire, 0, record, token)
                    } catch (_: Exception) { notice = "No se pudo guardar la operación. Revisa su estado antes de continuar." }
                }
            }
        } catch (e: IllegalArgumentException) { notice = e.message ?: "Revisa los datos de la operación" }
        finally { pin.fill('\u0000') }
    }

    private fun sendParts(parts: List<UssdCommand>, index: Int, record: PendingRecord, token: Int) {
        if (generation != token || !unlocked) return
        send(parts[index]) { result ->
            if (result is UssdResult.Response && result.text.contains("siendo procesada,")) {
                if (index + 1 < parts.size) sendParts(parts, index + 1, record, token)
                else { notice = "Solicitud procesada. Esperando el comprobante del banco." }
            } else {
                notice = "No se pudo confirmar el resultado. Revisa los mensajes antes de hacer otra operación."
                // Even failure callbacks may occur after the network accepted a debit.
            }
            tick()
        }
    }

    private fun sendBalance(auth: AuthWait? = null) {
        val wait = BalanceWait(bank, subscription, now(), auth)
        balanceWait = wait
        send(commands.defaultBalance()) { result ->
            if (balanceWait !== wait) return@send
            if (!fresh(wait.started, now())) {
                balanceWait = null; session = null; notice = "No llegó el saldo. Puedes volver a consultarlo."
                return@send
            }
            if (result is UssdResult.Response && BankResponse.parse(result.text) == BankResponse.PROCESSING) {
                wait.accepted = true; completeBalance(wait)
            } else {
                balanceWait = null; session = null; notice = failure(result)
            }
        }
        main.postDelayed({
            if (balanceWait === wait) {
                balanceWait = null
                if (wait.auth != null) session = null
                if (unlocked) notice = "No llegó el saldo. Puedes volver a consultarlo."
            }
        }, 30_000)
    }

    private fun completeBalance(wait: BalanceWait) {
        val at = wait.confirmedAt ?: return
        if (balanceWait !== wait || !wait.accepted) return
        balanceWait = null
        if (fresh(wait.started, at)) wait.auth?.let { continueSession(it, at, balanceConfirmed = true) }
    }

    private fun send(command: UssdCommand, callback: (UssdResult) -> Unit) {
        val token = generation
        transportActive = true
        gateway.send(command, subscription) { result ->
            transportActive = false
            if (unlocked && generation == token) callback(result)
        }
    }

    fun receive(record: BankSmsRecord) {
        val message = parser.parse("PAGOxMOVIL", record.body) ?: return
        processEvidence(record, message)
        if (unlocked) reloadInbox()
    }

    private fun otherBankNotice(value: Bank) =
        "La sesión activa es de ${value.name}. Selecciona ese banco o usa Cerrar sesión bancaria en Ajustes."

    private fun observeBank(record: BankSmsRecord, message: BankMessage) {
        if (record.subscriptionId != subscription) return
        val observed = when (message) {
            is BankMessage.Authenticated -> message.bank
            is BankMessage.Balance -> message.bank
            else -> return
        }
        val latest = latestBankEvidence?.takeIf { it.authenticatedAt <= now() }
        if (latest != null && record.receivedAt < latest.authenticatedAt) return
        latestBankEvidence = BankSession(observed, subscription, record.receivedAt)
        if (observed == bank) return
        val changesSession = session?.let { record.receivedAt >= it.authenticatedAt } == true
        val contradictsRequest = authWait?.let { fresh(it.started, record.receivedAt) } == true ||
            balanceWait?.let { fresh(it.started, record.receivedAt) } == true
        if (changesSession || contradictsRequest) {
            session = null; authWait = null; afterAuth = null; balanceWait = null
            if (unlocked) notice = otherBankNotice(observed)
        }
    }

    /** Both delivery paths must apply the same time, bank and SIM checks. */
    private fun processEvidence(record: BankSmsRecord, message: BankMessage) {
        if (record.receivedAt > now()) return
        observeBank(record, message)
        val wait = authWait
        if (message is BankMessage.Authenticated && wait != null && message.bank == wait.bank &&
            record.subscriptionId == wait.subscription && fresh(wait.started, record.receivedAt) && unlocked) {
            wait.confirmedAt = record.receivedAt; completeAuthentication(wait)
        }
        if (message is BankMessage.Balance && message.bank == bank && record.subscriptionId == subscription) {
            if (balanceAt == null || balanceAt!! > now() || record.receivedAt > balanceAt) updateBalance(message.accounts, record.receivedAt)
        }
        val balance = balanceWait
        if (message is BankMessage.Balance && balance != null && message.bank == balance.bank && record.subscriptionId == balance.subscription &&
            fresh(balance.started, record.receivedAt) && unlocked) {
            balance.confirmedAt = record.receivedAt; completeBalance(balance)
        }
        val p = pending
        if (p != null && message is BankMessage.TransferSent && !store.usedReference(message.bank, message.reference) &&
            !store.ambiguousReceipt(message, record.receivedAt, record.subscriptionId) &&
            p.transfer?.accept(message, record.receivedAt, record.subscriptionId) == true) {
            val appliedBalance = applyRemaining(p, message, record.receivedAt)
            store.confirm(message.bank, message.reference, p.subscription, !appliedBalance && !p.refreshTaken, now())
            pending = null; confirmed = message
            tick()
        }
    }

    private fun applyRemaining(pending: PendingRecord, receipt: BankMessage.TransferSent, at: Instant): Boolean {
        if (at > now() || pending.action.bank != bank || pending.subscription != subscription) return false
        val sole = accounts.singleOrNull() ?: return false
        val remaining = receipt.remainingBalance ?: return false
        if (remaining.currency != sole.available.currency) return false
        if (pending.action.source != "0000" && sole.account?.let { matchesAccount(it, pending.action.source) } != true) return false
        if (balanceAt == null || balanceAt!! > now() || at >= balanceAt) updateBalance(listOf(sole.copy(available = remaining)), at)
        return true
    }

    fun reloadInbox() {
        if (!unlocked || inboxReading || context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return
        inboxReading = true
        val token = generation
        io.execute {
            val result = runCatching { readInbox().map { HistoryEntry(it, parser.parse("PAGOxMOVIL", it.body) ?: BankMessage.Unrecognized) } }
            main.post {
                inboxReading = false
                if (!unlocked || generation != token) return@post
                result.onSuccess { rows ->
                    history = rows
                    // Latest first: one cache update, rather than rewriting every historical balance.
                    rows.forEach { row -> processRecovered(row) }
                }.onFailure { notice = "No se pudieron leer los mensajes del banco" }
            }
        }
    }

    private fun processRecovered(row: HistoryEntry) {
        processEvidence(row.record, row.message)
    }

    fun acknowledgePending() {
        if (!unlocked || !gateway.isIdle() || busy) return
        pending?.let(store::closeUncertain); pending = null
        uncertain = store.uncertain()
    }

    fun resolveUncertain(id: String, record: BankSmsRecord) {
        if (!unlocked) return
        if (record.receivedAt > now()) { notice = "El comprobante tiene una fecha futura"; return }
        val item = uncertain.singleOrNull { it.id == id } ?: return
        val receipt = parser.parse("PAGOxMOVIL", record.body) as? BankMessage.TransferSent ?: return
        if (store.usedReference(receipt.bank, receipt.reference) || !item.matches(receipt, record.receivedAt, record.subscriptionId)) {
            notice = "El comprobante no corresponde a esta operación o ya está vinculado"; return
        }
        store.resolveUncertain(id, receipt.bank, receipt.reference)
        uncertain = store.uncertain(); notice = "Comprobante vinculado"
    }

    fun saveRecipient(value: Recipient) { store.saveRecipient(value); recipients = store.recipients() }

    private val ticker = Runnable { tick() }
    private fun tick() {
        main.removeCallbacks(ticker)
        if (!unlocked) return
        val p = pending
        if (!busy && gateway.isIdle() && store.refreshDue(bank, subscription, now()) && canOperate()) {
            // Persist the latch before sending so a restart cannot repeat the refresh.
            if (p != null) { p.refreshTaken = true; store.savePending(p) }
            store.takeRefresh()
            queryBalance()
        }
        main.postDelayed(ticker, 1_000)
    }

    private fun failure(result: UssdResult): String = when (result) {
        UssdResult.PermissionRequired -> "Permite llamadas para continuar"
        UssdResult.InvalidSubscription -> "La SIM seleccionada no está disponible"
        UssdResult.Busy -> "Hay una solicitud en curso"
        is UssdResult.Response -> result.text
        else -> "No se pudo completar la solicitud. Comprueba la cobertura."
    }
}

class BankSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != android.provider.Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val record = BankSmsInbox.fromBroadcast(intent) ?: return
        (context.applicationContext as NeoTransferApplication).controller.receive(record)
    }
}
