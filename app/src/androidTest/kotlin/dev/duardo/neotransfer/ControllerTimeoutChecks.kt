package dev.duardo.neotransfer

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import dev.duardo.neotransfer.core.*
import dev.duardo.neotransfer.data.*
import dev.duardo.neotransfer.platform.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Controller presentation state against Room and a modem that never dials. */
object ControllerTimeoutChecks {
    fun run(context: Context, onPassed: (String) -> Unit = {}): Int {
        fun case(name: String, body: (Harness) -> Unit) {
            val fixture = Harness(context)
            try { body(fixture); onPassed(name) }
            finally { fixture.close() }
        }

        case("A timed-out transfer releases pending, allows a new query and keeps late proof in Room") { h ->
            h.startTransfer()
            val id = h.main { checkNotNull(h.bank.pending).id }
            check(h.main { h.modem.services.count { it == 45 } == 1 })
            h.clock = h.clock.plusSeconds(31)
            h.main { h.modem.reply(UssdResult.TimedOut) }
            h.await { h.wallet.snapshot.operations.any { it.id == id && it.timeoutAt != null } && !h.bank.busy }
            val expired = h.repository.snapshot().operations.single { it.id == id }
            check(expired.status == OperationStatus.UNCERTAIN && !expired.reviewRequired && expired.timeoutAt != null)
            h.main {
                check(h.bank.pending == null && h.bank.confirmed == null)
                check(h.bank.serviceResult == null && h.bank.notice == "Tiempo de espera agotado")
                h.bank.queryBalance()
            }
            h.await { h.modem.services.size > 2 }
            h.main { h.modem.reply(UssdResult.NetworkFailure(-1)) }
            h.await { !h.bank.busy }
            h.main { h.bank.clearServiceResult() }
            val calls = h.main { h.modem.services.toList() }
            check(calls.count { it == 45 } == 1)

            h.deliverReceipt("LATE001")
            h.await { h.wallet.snapshot.operations.single { it.id == id }.status == OperationStatus.CONFIRMED }
            val confirmed = h.repository.snapshot()
            check(confirmed.operations.single { it.id == id }.timeoutAt == expired.timeoutAt)
            check(confirmed.receipts.single { it.reference == "LATE001" }.operationId == id)
            h.main {
                check(h.bank.pending == null && h.bank.confirmed == null && h.bank.serviceResult == null)
                check(h.modem.services == calls)
            }
        }

        case("A timely transfer receipt retains the normal confirmation presentation") { h ->
            h.startTransfer()
            val id = h.main { checkNotNull(h.bank.pending).id }
            h.main { h.modem.reply(UssdResult.Response("Su solicitud esta siendo procesada, espere un SMS")) }
            h.await { !h.bank.busy && h.wallet.snapshot.operations.single { it.id == id }.status == OperationStatus.AWAITING_CONFIRMATION }
            h.deliverReceipt("ONTIME01")
            h.await { h.wallet.snapshot.operations.single { it.id == id }.status == OperationStatus.CONFIRMED }
            check(h.repository.snapshot().operations.single { it.id == id }.timeoutAt == null)
            h.main {
                check(h.bank.pending == null && h.bank.confirmed?.reference == "ONTIME01")
                check(h.bank.serviceResult == "Operación confirmada por el comprobante del banco.")
                check(h.modem.services.count { it == 45 } == 1)
            }
        }
        return 2
    }

    private class Harness(private val base: Context) {
        private val key = "controller_timeout_${UUID.randomUUID()}"
        private val database = "$key.db"
        private val handler = Handler(Looper.getMainLooper())
        private val preferences = mutableSetOf<String>()
        private val context = object : ContextWrapper(base) {
            override fun checkSelfPermission(permission: String) = PackageManager.PERMISSION_GRANTED
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val scoped = "${key}_$name"
                preferences += scoped
                return super.getSharedPreferences(scoped, mode)
            }
        }
        val repository = RoomWalletRepository.open(base, database)
        var clock: Instant = Instant.parse("2026-09-23T12:00:00Z")
        lateinit var wallet: WalletCoordinator
        lateinit var bank: BankController
        lateinit var modem: FakeModem

        init {
            val registration = RegistrationRecord("registration", "owner", ProviderId.BPA.name, ProfileId.PERSONAL.name,
                Bank.BPA.code, 7, null, "BPA", "access:registration")
            repository.migrateLegacy(emptyMap<String, Any>())
            repository.putIdentity(IdentityRecord("owner", "Fixture"))
            repository.putRegistration(registration)
            repository.setSettings(WalletSettings(activeBankCode = Bank.BPA.code, subscriptionId = 7,
                selectedRegistrationId = registration.id))
            BankStore(context).apply { bank = Bank.BPA; subscription = 7 }
            main {
                wallet = WalletCoordinator(context, repository)
                modem = FakeModem()
                bank = BankController(context, modem, { emptyList() }, { listOf(SimChoice(7, "Fixture")) }, { clock }, wallet)
            }
            await { wallet.ready }
            val credentials = AccessCredentials.empty().use {
                it.scoped["access:registration"] = "1234".toCharArray()
                it.encode()
            }
            main { bank.unlock(credentials) }
            await { !bank.busy && bank.configuredRegistrationIds == setOf(registration.id) }
            check(wallet.snapshot.registrations.size == 1)
        }

        fun startTransfer() {
            main {
                bank.submit(MoneyAction(ActionKind.TRANSFER, Bank.BPA, "0000000000000002",
                    Money(BigDecimal("10.00"), Currency.CUP), "0000000000000001"), "1234".toCharArray())
            }
            await { modem.services == listOf(40) }
            main { modem.reply(UssdResult.Response("Su solicitud esta siendo procesada, espere un SMS")) }
            clock = clock.plusSeconds(1)
            val authenticated = BankSmsRecord(null, "Usted se ha autenticado en la plataforma de pagos moviles, en el Banco Popular de Ahorro, puede comenzar a utilizar nuestros servicios de pagos a traves del movil",
                clock, 7, sentAt = clock)
            val finished = CountDownLatch(1)
            main { bank.receive(authenticated) { finished.countDown() } }
            check(finished.await(10, TimeUnit.SECONDS)) { "No se procesó la autenticación" }
            await { modem.services == listOf(40, 45) && bank.pending != null }
        }

        fun deliverReceipt(reference: String) {
            clock = clock.plusSeconds(1)
            val record = BankSmsRecord(null, "Banco BPA: La Transferencia fue completada.\nBeneficiario: 0000XXXXXXXX0002\nMonto: 10.00 CUP\nNro. Transaccion: $reference",
                clock, 7, sentAt = clock)
            val finished = CountDownLatch(1)
            main { bank.receive(record) { finished.countDown() } }
            check(finished.await(10, TimeUnit.SECONDS)) { "No se procesó el comprobante" }
        }

        fun <T> main(action: () -> T): T {
            val done = CountDownLatch(1)
            var result: Result<T>? = null
            handler.post { try { result = runCatching(action) } finally { done.countDown() } }
            check(done.await(10, TimeUnit.SECONDS)) { "No respondió el hilo principal" }
            return requireNotNull(result).getOrThrow()
        }

        fun await(condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!main(condition)) {
                check(System.nanoTime() < deadline) { "No llegó el estado esperado" }
                Thread.sleep(10)
            }
        }

        fun close() {
            if (::wallet.isInitialized) {
                val done = CountDownLatch(1)
                main { if (::bank.isInitialized) bank.close(); wallet.close { done.countDown() } }
                check(done.await(10, TimeUnit.SECONDS)) { "La cartera no terminó de cerrar Room" }
            } else repository.close()
            check(base.deleteDatabase(database))
            preferences.forEach { base.deleteSharedPreferences(it) }
        }
    }

    private class FakeModem : UssdTransport {
        val services = mutableListOf<Int>()
        private var callback: ((UssdResult) -> Unit)? = null
        override fun isIdle() = callback == null
        override fun send(command: UssdCommand, subscriptionId: Int, result: (UssdResult) -> Unit) {
            check(callback == null && subscriptionId == 7)
            services += command.service
            callback = result
        }
        fun reply(value: UssdResult) {
            val current = checkNotNull(callback)
            callback = null
            current(value)
        }
    }
}
