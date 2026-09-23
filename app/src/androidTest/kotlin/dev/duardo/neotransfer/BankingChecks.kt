package dev.duardo.neotransfer

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import dev.duardo.neotransfer.core.*
import dev.duardo.neotransfer.platform.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Device tests with real Android storage/Looper and an injected modem that cannot make calls. */
class BankingChecks : Instrumentation() {
    private val report = StringBuilder()
    private var passed = 0
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        try {
            // Remove only this harness's old fixtures, never the app's real preferences.
            targetContext.dataDir.resolve("shared_prefs").listFiles().orEmpty()
                .filter { it.name.matches(Regex("banking_test_[0-9a-f-]{36}_(bank_state|bank_vault)\\.xml")) }
                .forEach { targetContext.deleteSharedPreferences(it.name.removeSuffix(".xml")) }
            case("future broadcast cannot confirm money, consume its reference or apply its balance") { h ->
                h.authenticate()
                main {
                    h.bank.submit(h.action(), "12345".toCharArray()); h.modem.complete()
                    h.bank.receive(BankSmsRecord(null, receipt("FUTURELIVE", true), h.clock.plusSeconds(60), 1))
                    check(h.bank.pending != null && !h.bank.store.usedReference(Bank.BANDEC, "FUTURELIVE"))
                    check(h.bank.accounts.single().available.amount == BigDecimal("1000.00"))
                    h.bank.receive(h.record(receipt("FUTURELIVE", true)))
                    check(h.bank.pending == null && h.bank.store.usedReference(Bank.BANDEC, "FUTURELIVE"))
                    check(h.modem.calls.count { it.service == 45 } == 1)
                }
            }
            case("future recovered receipt stays pending and its current counterpart can still confirm") { h ->
                h.authenticate()
                main { h.bank.submit(h.action(), "12345".toCharArray()); h.modem.complete() }
                h.recover(BankSmsRecord(101, receipt("FUTUREINBOX", true), h.clock.plusSeconds(60), 1))
                main {
                    check(h.bank.pending != null && !h.bank.store.usedReference(Bank.BANDEC, "FUTUREINBOX"))
                    check(h.bank.accounts.single().available.amount == BigDecimal("1000.00"))
                }
                h.recover(h.record(receipt("FUTUREINBOX", true)))
                main {
                    check(h.bank.pending == null && h.bank.store.usedReference(Bank.BANDEC, "FUTUREINBOX"))
                    check(h.bank.accounts.single().available.amount == BigDecimal("990.00"))
                    check(h.modem.calls.count { it.service == 45 } == 1)
                }
            }
            case("future manual receipt cannot resolve uncertainty or consume a reference") { h ->
                h.authenticate()
                main {
                    h.bank.submit(h.action(), "12345".toCharArray()); h.modem.complete(); h.bank.acknowledgePending()
                    val id = h.bank.uncertain.single().id
                    h.bank.resolveUncertain(id, BankSmsRecord(null, receipt("FUTUREMANUAL"), h.clock.plusSeconds(60), 1))
                    check(h.bank.uncertain.size == 1 && !h.bank.store.usedReference(Bank.BANDEC, "FUTUREMANUAL"))
                    h.bank.resolveUncertain(id, h.record(receipt("FUTUREMANUAL")))
                    check(h.bank.uncertain.isEmpty() && h.bank.store.usedReference(Bank.BANDEC, "FUTUREMANUAL"))
                    check(h.modem.calls.count { it.service == 45 } == 1)
                }
            }
            case("future stored and recovered balances cannot hide the current balance") { h ->
                main {
                    val values = (BankSmsParser().parse("PAGOxMOVIL", balance()) as BankMessage.Balance).accounts
                    h.bank.store.saveBalances(Bank.BANDEC, 1, h.clock.plusSeconds(60), values)
                    h.recreate(); check(h.bank.accounts.isEmpty() && h.bank.balanceAt == null)
                    h.unlock(); h.bank.selectBank(Bank.BPA); h.bank.selectBank(Bank.BANDEC)
                    check(h.bank.accounts.isEmpty() && h.bank.balanceAt == null)
                }
                val current = h.record(balance().replace("1000.00", "700.00"))
                h.recover(BankSmsRecord(102, balance(), h.clock.plusSeconds(60), 1), current)
                main {
                    check(h.bank.accounts.single().available.amount == BigDecimal("700.00"))
                    check(h.bank.balanceAt == current.receivedAt)
                    h.bank.selectBank(Bank.BPA); h.bank.selectBank(Bank.BANDEC)
                    check(h.bank.accounts.single().available.amount == BigDecimal("700.00"))
                    check(h.bank.balanceAt == java.time.Instant.ofEpochMilli(current.receivedAt.toEpochMilli()))
                }
            }
            case("history excludes administrative messages and collapses only matching duplicate receipts") { h ->
                val paid = Money(BigDecimal("90.00"), Currency.CUP)
                val receipt = BankMessage.ServicePaymentCompleted(Bank.BPA, "Nauta Hogar", "fixture@example.invalid",
                    Money(BigDecimal("100.00"), Currency.CUP), paid, "HISTORY01")
                val rows = listOf(
                    HistoryEntry(BankSmsRecord(1, "fixture", h.clock, 1), receipt),
                    HistoryEntry(BankSmsRecord(2, "fixture", h.clock.minusSeconds(10), 1), receipt),
                    HistoryEntry(BankSmsRecord(3, "fixture", h.clock, 2), receipt),
                    HistoryEntry(BankSmsRecord(4, "fixture", h.clock, 1), receipt.copy(paid = Money(BigDecimal("80.00"), Currency.CUP))),
                    HistoryEntry(BankSmsRecord(5, "fixture", h.clock.minusSeconds(600), 1), receipt),
                    HistoryEntry(BankSmsRecord(6, "fixture", h.clock, 1), BankMessage.Authenticated(Bank.BPA, null)),
                )
                check(financialHistory(rows).map { it.entry.record.id }.toSet() == setOf(1L, 3L, 4L, 5L))
                check(rows.size == 6)
            }
            case("balance storage preserves missing account and ledger without inventing values") { h ->
                main {
                    val available = Money(BigDecimal("420.00"), Currency.CUP)
                    h.bank.store.saveBalances(Bank.BPA, 1, h.clock,
                        listOf(AccountBalance(null, null, available, "Cuenta de ahorro")))
                    h.bank.selectBank(Bank.BPA)
                    check(h.bank.accounts.single() == AccountBalance(null, null, available, "Cuenta de ahorro"))
                    h.recreate()
                    check(h.bank.accounts.single().account == null && h.bank.accounts.single().ledger == null)
                    check(h.bank.accounts.single().label == "Cuenta de ahorro")
                    h.bank.store.saveBalances(Bank.BPA, 1, h.clock, listOf(AccountBalance(null, null, available)))
                    h.bank.selectBank(Bank.BANDEC); h.bank.selectBank(Bank.BPA)
                    check(h.bank.accounts.single().label == null)
                }
            }
            case("contrary bank evidence at the same timestamp invalidates a probed session") { h ->
                main {
                    h.unlock(); h.bank.queryBalance()
                    h.modem.complete(UssdResult.Response("Usted ya se encuentra autenticado en el sistema"))
                    h.modem.complete()
                    val at = h.clock
                    h.bank.receive(BankSmsRecord(null, balance(), at, 1))
                    check(!h.bank.busy)
                    h.bank.receive(BankSmsRecord(null, auth().replace("Bandec", "BPA"), at, 1))
                    h.bank.submit(h.action(), "12345".toCharArray())
                    check(h.modem.calls.last().service == 40 && h.modem.calls.none { it.service == 45 })
                    h.modem.complete(UssdResult.NetworkFailure(-1))
                }
            }
            case("fresh external bank authentication revokes the cached session, old future and wrong SIM do not") { h ->
                h.authenticate()
                main {
                    val other = auth().replace("Bandec", "BPA")
                    h.bank.receive(BankSmsRecord(null, other, h.clock.minusSeconds(60), 1))
                    h.bank.receive(BankSmsRecord(null, other, h.clock.plusSeconds(60), 1))
                    h.bank.receive(BankSmsRecord(null, other, h.clock, 2))
                    h.bank.queryBalance(); check(h.modem.calls.last().service == 46)
                    check(h.modem.calls.count { it.service == 40 } == 1)
                    h.modem.complete(); h.bank.receive(h.record(balance()))
                    h.bank.receive(h.record(other)); check(h.bank.bank == Bank.BANDEC)
                    h.bank.submit(h.action(), "12345".toCharArray())
                    check(h.modem.calls.last().service == 40 && h.modem.calls.none { it.service == 45 })
                    h.modem.complete(UssdResult.Response("Usted ya se encuentra autenticado en el sistema"))
                    check(h.modem.calls.last().service == 46)
                    h.modem.complete(); h.bank.receive(h.record(balance().replace("Bandec", "BPA")))
                    check(h.bank.pending == null && h.modem.calls.none { it.service == 45 || it.service == 70 })
                }
            }
            case("a recovered external bank balance revokes the cached session without changing the selected bank") { h ->
                h.authenticate()
                h.recover(h.record(balance().replace("Bandec", "BPA")))
                main {
                    h.bank.submit(h.action(), "12345".toCharArray())
                    check(h.bank.bank == Bank.BANDEC && h.modem.calls.last().service == 40)
                    check(h.modem.calls.none { it.service == 45 || it.service == 70 })
                    h.modem.complete(UssdResult.Response("Usted ya se encuentra autenticado en el sistema"))
                    check(h.modem.calls.last().service == 46); h.modem.complete()
                }
                h.recover(h.record(balance().replace("Bandec", "BPA")))
                main { check(h.bank.pending == null && !h.bank.busy && h.modem.calls.none { it.service == 45 }) }
            }
            case("newest recovered bank evidence prevents older authentication from releasing queued money") { h ->
                main {
                    h.unlock(); h.bank.receive(h.record(balance())); h.bank.submit(h.action(), "12345".toCharArray())
                    h.modem.complete()
                }
                val olderAuth = h.record(auth())
                val newerOtherBank = h.record(balance().replace("Bandec", "BPA"))
                h.recover(olderAuth, newerOtherBank)
                main {
                    check(!h.bank.busy && h.bank.pending == null && h.modem.calls.map { it.service } == listOf(40))
                    h.bank.submit(h.action(), "12345".toCharArray()); check(h.modem.calls.last().service == 40)
                    h.modem.complete(UssdResult.NetworkFailure(-1))
                }
            }
            case("fresh authentication and balance recovered after missed broadcasts complete a query once") { h ->
                main { h.unlock(); h.bank.queryBalance(); h.modem.complete() }
                val authentication = h.record(auth())
                h.recover(authentication)
                main { check(h.modem.calls.map { it.service } == listOf(40, 46)); h.modem.complete() }
                h.recover(h.record(balance()), authentication)
                main { check(!h.bank.busy && h.modem.calls.map { it.service } == listOf(40, 46)) }
            }
            case("recovered session probe waits for its callback and releases money only once") { h ->
                main {
                    h.unlock(); h.bank.receive(h.record(balance())); h.bank.submit(h.action(), "12345".toCharArray())
                    h.modem.complete(UssdResult.Response("Usted ya se encuentra autenticado en el sistema"))
                }
                val proof = h.record(balance())
                h.recover(proof)
                main {
                    check(h.bank.pending == null && h.modem.calls.map { it.service } == listOf(40, 46))
                    h.modem.complete()
                    check(h.bank.pending != null && h.modem.calls.map { it.service } == listOf(40, 46, 45))
                    h.modem.complete()
                }
                h.recover(BankSmsRecord(103, proof.body, proof.receivedAt, proof.subscriptionId))
                main { check(h.modem.calls.count { it.service == 45 } == 1) }
            }
            case("already authenticated refreshes from a fresh balance without a second query") { h ->
                main {
                    h.unlock(); h.bank.receive(h.record(balance().replace("1000.00", "800.00")))
                    h.bank.queryBalance()
                    h.modem.complete(UssdResult.Response("usted ya se encuentra autenticado en el sistema"))
                    check(h.modem.calls.map { it.service } == listOf(40, 46))
                    h.bank.queryBalance(); h.bank.selectBank(Bank.BPA); h.bank.selectSim(2)
                    check(h.modem.calls.size == 2 && h.bank.bank == Bank.BANDEC && h.bank.subscription == 1)
                    h.modem.complete(); h.bank.receive(h.record(balance()))
                    check(!h.bank.busy && h.bank.accounts.single().available.amount == BigDecimal("1000.00"))
                    check(h.modem.calls.map { it.service } == listOf(40, 46))
                }
            }
            case("already authenticated money waits for the matching fresh balance and its callback") { h ->
                main {
                    h.unlock(); h.bank.receive(h.record(balance())); h.bank.submit(h.action(), "12345".toCharArray())
                    h.modem.complete(UssdResult.Response("Usted ya se encuentra autenticado en el sistema."))
                    check(h.modem.calls.map { it.service } == listOf(40, 46))
                    h.bank.receive(BankSmsRecord(null, balance(), h.clock.minusSeconds(1), 1))
                    h.bank.receive(BankSmsRecord(null, balance(), h.clock.plusSeconds(1), 1))
                    h.bank.receive(BankSmsRecord(null, balance(), h.clock, 2))
                    check(h.bank.pending == null && h.modem.calls.size == 2)
                    h.bank.receive(h.record(balance()))
                    check(h.bank.pending == null && h.modem.calls.size == 2)
                    h.modem.complete()
                    check(h.modem.calls.map { it.service } == listOf(40, 46, 45))
                    check(h.bank.pending != null)
                    h.modem.complete()
                }
            }
            case("balance from another bank cannot authorize money or be reused as the selected session") { h ->
                main {
                    h.unlock(); h.bank.receive(h.record(balance())); h.bank.submit(h.action(), "12345".toCharArray())
                    h.modem.complete(UssdResult.Response("Usted ya se encuentra autenticado en el sistema"))
                    h.modem.complete(); h.bank.receive(h.record(balance().replace("Bandec", "BPA")))
                    check(!h.bank.busy && h.bank.pending == null && h.bank.notice.orEmpty().contains("BPA"))
                    check(h.modem.calls.none { it.service == 45 })
                    h.bank.submit(h.action(), "12345".toCharArray())
                    check(h.modem.calls.last().service == 40)
                    h.modem.complete(UssdResult.Response("PIN incorrecto"))
                }
            }
            case("authentication SMS before a rejected callback cannot release queued money") { h ->
                main {
                    h.unlock(); h.bank.receive(h.record(balance())); h.bank.submit(h.action(), "12345".toCharArray())
                    h.bank.receive(h.record(auth()))
                    h.modem.complete(UssdResult.Response("PIN incorrecto"))
                    check(h.modem.calls.map { it.service } == listOf(40))
                    check(h.bank.pending == null && !h.bank.busy && h.bank.notice == "PIN incorrecto")
                }
            }
            case("authentication callback before SMS continues once and ignores the wrong SIM") { h ->
                main {
                    h.unlock(); h.bank.queryBalance(); h.modem.complete()
                    h.bank.receive(BankSmsRecord(null, auth(), h.clock, 2))
                    check(h.modem.calls.size == 1)
                    h.bank.receive(h.record(auth())); h.bank.receive(h.record(auth()))
                    check(h.modem.calls.map { it.service } == listOf(40, 46))
                    h.modem.complete(); h.bank.receive(h.record(balance())); check(!h.bank.busy)
                }
            }
            case("another bank authentication SMS cannot create the selected bank session") { h ->
                main {
                    h.unlock(); h.bank.queryBalance(); h.modem.complete()
                    h.bank.receive(h.record(auth().replace("Bandec", "BPA")))
                    check(!h.bank.busy && h.modem.calls.map { it.service } == listOf(40))
                    check(h.bank.notice.orEmpty().contains("BPA"))
                }
            }
            case("a probe balance before its rejected callback cannot authorize money") { h ->
                main {
                    h.unlock(); h.bank.receive(h.record(balance())); h.bank.submit(h.action(), "12345".toCharArray())
                    h.modem.complete(UssdResult.Response("Usted ya se encuentra autenticado en el sistema"))
                    h.bank.receive(h.record(balance())); h.modem.complete(UssdResult.Response("Sesion expirada"))
                    check(!h.bank.busy && h.bank.pending == null && h.modem.calls.none { it.service == 45 })
                    h.bank.queryBalance(); check(h.modem.calls.last().service == 40)
                    h.modem.complete(UssdResult.NetworkFailure(-1))
                }
            }
            case("locking during session verification cancels queued money and ignores the old callback") { h ->
                main {
                    h.unlock(); h.bank.receive(h.record(balance())); h.bank.submit(h.action(), "12345".toCharArray())
                    h.modem.complete(UssdResult.Response("Usted ya se encuentra autenticado en el sistema"))
                    h.bank.lock(); h.bank.receive(h.record(balance())); h.modem.complete()
                    check(h.bank.pending == null && h.bank.notice == null && h.modem.calls.none { it.service == 45 })
                    h.unlock(); h.bank.queryBalance(); check(h.modem.calls.last().service == 40)
                    h.modem.complete(UssdResult.NetworkFailure(-1))
                }
            }
            case("expired authentication and probe responses cannot send money or retry it") { h ->
                main {
                    h.unlock(); h.bank.receive(h.record(balance())); h.bank.submit(h.action(), "12345".toCharArray())
                    h.clock = h.clock.plusSeconds(31)
                    h.modem.complete(UssdResult.Response("Usted ya se encuentra autenticado en el sistema"))
                    check(!h.bank.busy && h.modem.calls.size == 1)
                    h.bank.submit(h.action(), "12345".toCharArray())
                    h.modem.complete(UssdResult.Response("Usted ya se encuentra autenticado en el sistema"))
                    h.bank.receive(h.record(balance())); h.clock = h.clock.plusSeconds(31); h.modem.complete()
                    check(!h.bank.busy && h.bank.pending == null && h.modem.calls.none { it.service == 45 })
                }
            }
            case("new balance currency is checked again before a queued BANDEC transfer") { h ->
                main {
                    h.unlock(); h.bank.receive(h.record(balance())); h.bank.submit(h.action(), "12345".toCharArray())
                    h.modem.complete(UssdResult.Response("Usted ya se encuentra autenticado en el sistema"))
                    h.modem.complete(); h.bank.receive(h.record(balance().replace("CUP", "USD")))
                    check(!h.bank.busy && h.bank.pending == null && h.modem.calls.none { it.service == 45 })
                    check(h.bank.notice.orEmpty().contains("moneda"))
                }
            }
            case("receipt before USSD callback retains the refresh") { h ->
                h.authenticate()
                main { h.bank.submit(h.action(), "12345".toCharArray()) }
                check(h.modem.calls.last().service == 45)
                main {
                    check(h.bank.store.pending() != null)
                    h.bank.receive(h.record(receipt("TEST001")))
                    check(h.bank.pending == null)
                    check(h.modem.calls.count { it.service == 46 } == 1)
                    h.modem.complete()
                }
                waitFor { h.modem.calls.count { it.service == 46 } == 2 }
                main { h.modem.complete(); h.bank.receive(h.record(balance())) }
                Thread.sleep(1_200)
                main { check(h.modem.calls.count { it.service == 46 } == 2) }
            }
            case("uncertain operation and refresh survive process recreation without resending money") { h ->
                h.authenticate()
                main { h.bank.submit(h.action(), "12345".toCharArray()); h.modem.complete(); h.bank.lock() }
                val previous = h.bank
                main { h.clock = h.clock.plusSeconds(11); h.recreate(); h.unlock() }
                waitFor { h.modem.calls.isNotEmpty() }
                main {
                    check(h.bank.pending != null)
                    check(h.modem.calls.none { it.service == 45 })
                    check(h.modem.calls.single().service == 40)
                    previous.lock()
                }
            }
            case("late receipt cannot confirm a manually closed identical operation") { h ->
                h.authenticate()
                main {
                    h.bank.submit(h.action(), "12345".toCharArray()); h.modem.complete()
                    h.bank.acknowledgePending(); check(h.bank.pending == null)
                    h.clock = h.clock.plusSeconds(1)
                    h.bank.submit(h.action(), "12345".toCharArray()); h.modem.complete()
                    h.bank.receive(h.record(receipt("OLD001")))
                    check(h.bank.pending != null); check(h.bank.confirmed == null)
                }
            }
            case("locking before authentication receipt cancels queued money operation") { h ->
                main {
                    h.unlock(); h.bank.receive(h.record(balance()))
                    h.bank.submit(h.action(), "12345".toCharArray())
                    check(h.modem.calls.single().service == 40)
                    h.bank.lock(); h.bank.receive(h.record(auth())); h.modem.complete()
                }
                Thread.sleep(500)
                main { check(h.modem.calls.none { it.service == 45 }); check(h.bank.store.pending() == null) }
            }
            case("duplicate receipt and wrong SIM do not unlock a second transfer") { h ->
                h.authenticate()
                main {
                    h.bank.submit(h.action(), "12345".toCharArray()); h.modem.complete()
                    h.bank.receive(BankSmsRecord(null, receipt("NEW001"), h.clock, 2))
                    check(h.bank.pending != null)
                    h.bank.receive(h.record(receipt("NEW001", true)))
                    check(h.bank.pending == null)
                    h.clock = h.clock.plusSeconds(1)
                    h.bank.submit(h.action(), "12345".toCharArray()); h.modem.complete()
                    h.bank.receive(h.record(receipt("NEW001", true)))
                    check(h.bank.pending != null)
                }
            }
            case("confirmation refresh remains due after recreation") { h ->
                h.authenticate()
                main {
                    h.bank.submit(h.action(), "12345".toCharArray())
                    h.bank.receive(h.record(receipt("PERSIST001")))
                    check(h.bank.pending == null)
                    h.bank.lock()
                    h.recreate(); h.unlock()
                }
                waitFor { h.modem.calls.isNotEmpty() }
                main { check(h.modem.calls.single().service == 40); check(h.bank.pending == null) }
            }
            case("linking a late receipt removes ambiguity and prevents receipt reuse") { h ->
                h.authenticate()
                main {
                    h.bank.submit(h.action(), "12345".toCharArray()); h.modem.complete(); h.bank.acknowledgePending()
                    val old = h.bank.uncertain.single()
                    h.bank.resolveUncertain(old.id, h.record(receipt("LINK001", true)))
                    check(h.bank.uncertain.isEmpty())
                    h.bank.submit(h.action(), "12345".toCharArray()); h.modem.complete()
                    h.bank.receive(h.record(receipt("LINK001", true))); check(h.bank.pending != null)
                    h.bank.receive(h.record(receipt("LINK002", true))); check(h.bank.pending == null)
                    h.bank.lock(); h.recreate()
                    check(h.bank.accounts.single().available.amount.compareTo(BigDecimal("990.00")) == 0)
                }
            }
            case("Android QR parser accepts supported inputs and rejects ambiguous fields") { _ ->
                val json = "{\"id_transaccion\":\"ESTATICO-123\",\"numero_proveedor\":\"123\",\"importe\":25.00,\"moneda\":\"CUP\",\"extra\":null}"
                val qr = QrInput.parse(json) as QrInput.Payment
                check(qr.value.service == 31 && qr.value.amount.amount.compareTo(BigDecimal("25.00")) == 0)
                val link = QrInput.parse("transfermovil://tm_compra_en_linea/action?id_transaccion=TEST123&numero_proveedor=123&importe=12%2C50&moneda=CUP") as QrInput.Payment
                check(link.value.service == 30 && link.value.amount.amount.compareTo(BigDecimal("12.50")) == 0)
                val card = QrInput.parse("TRANSFERMOVIL_ETECSA,TRANSFERENCIA,0000000000000002,5350000000,") as QrInput.Card
                check(card.phone == "50000000" && card.card == "0000000000000002")
                check(runCatching { QrInput.parse("https://transfermovil.app/action?id_transaccion=ONE&id_transaccion=TWO&importe=1&moneda=CUP&numero_proveedor=123") }.isFailure)
                check(runCatching { QrInput.parse(json.replace("null", "\"invalid!\"")) }.isFailure)
                val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(json, com.google.zxing.BarcodeFormat.QR_CODE, 360, 360)
                val luminance = ByteArray(360 * 360) { i -> if (matrix[i % 360, i / 360]) 0 else 255.toByte() }
                val source = com.google.zxing.PlanarYUVLuminanceSource(luminance, 360, 360, 0, 0, 360, 360, false)
                val decoded = com.google.zxing.MultiFormatReader().decode(com.google.zxing.BinaryBitmap(com.google.zxing.common.HybridBinarizer(source)))
                check((QrInput.parse(decoded.text) as QrInput.Payment).value == qr.value)
            }
            finish(-1, Bundle().apply { putString("stream", "\n$report\nPASS $passed Android checks; fake modem only.\n") })
        } catch (error: Throwable) {
            finish(0, Bundle().apply { putString("stream", "\n$report\nFAIL ${error.stackTraceToString()}\n") })
        }
    }

    private fun main(action: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync { try { action() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
    private fun waitFor(condition: () -> Boolean) {
        repeat(30) {
            var result = false
            main { result = condition() }
            if (result) return
            Thread.sleep(100)
        }
        error("Timed out waiting for test state")
    }
    private fun case(name: String, body: (Harness) -> Unit) {
        sendStatus(0, Bundle().apply { putString("stream", "BEGIN $name\n") })
        lateinit var h: Harness
        main { h = Harness() }
        try { body(h); passed++; report.appendLine("PASS $name"); sendStatus(0, Bundle().apply { putString("stream", "PASS $name\n") }) }
        finally { main { h.bank.lock(); h.context.clearOwnedPreferences() } }
    }

    private inner class Harness {
        val context = TestContext(targetContext)
        var clock = Instant.now()
        @Volatile var inbox = emptyList<BankSmsRecord>()
        lateinit var bank: BankController
        lateinit var modem: FakeModem
        init { BankStore(context).apply { subscription = 1; bank = Bank.BANDEC }; recreate() }
        fun recreate() {
            modem = FakeModem()
            bank = BankController(context, modem, { inbox }, { listOf(SimChoice(1, "SIM de prueba")) }, { clock })
            modem.beforeSend = { command -> if (command.service == 45) check(bank.store.pending() != null) }
        }
        fun unlock() = bank.unlock("{\"BANDEC\":\"12345\"}".toByteArray())
        fun record(body: String): BankSmsRecord {
            clock = clock.plusMillis(1)
            return BankSmsRecord(null, body, clock, 1)
        }
        fun action() = MoneyAction(ActionKind.TRANSFER, Bank.BANDEC, "0000000000000002", Money(BigDecimal("10.00"), Currency.CUP))
        fun recover(vararg records: BankSmsRecord) {
            main { inbox = records.sortedByDescending { it.receivedAt } }
            waitFor {
                val loaded = bank.history.map { it.record } == inbox
                if (!loaded) bank.reloadInbox()
                loaded
            }
        }
        fun authenticate() {
            main { unlock(); bank.queryBalance(); bank.receive(record(auth())); modem.complete() }
            waitFor { modem.calls.any { it.service == 46 } }
            main { modem.complete(); bank.receive(record(balance())); check(!bank.busy) }
        }
    }

    private class FakeModem : UssdTransport {
        val calls = mutableListOf<UssdCommand>()
        var beforeSend: (UssdCommand) -> Unit = {}
        private var callback: ((UssdResult) -> Unit)? = null
        override fun isIdle() = callback == null
        override fun send(command: UssdCommand, subscriptionId: Int, result: (UssdResult) -> Unit) {
            check(isIdle()); beforeSend(command); calls += command; callback = result
        }
        fun complete(response: UssdResult = UssdResult.Response("Su solicitud está siendo procesada, espere un SMS")) {
            val result = checkNotNull(callback); callback = null
            result(response)
        }
    }

    private class TestContext(base: Context) : ContextWrapper(base) {
        private val prefix = "banking_test_${UUID.randomUUID()}_"
        private val owned = mutableSetOf<String>()
        override fun checkSelfPermission(permission: String) = PackageManager.PERMISSION_GRANTED
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val scoped = prefix + name; owned += scoped
            return super.getSharedPreferences(scoped, mode)
        }
        fun clearOwnedPreferences() { owned.forEach { super.deleteSharedPreferences(it) } }
    }

    private fun auth() = "Usted se ha autenticado en la plataforma de pagos moviles, en el Banco Bandec con la cuenta 0000XXXXXXXX0001, puede comenzar a utilizar nuestros servicios de pagos a traves del movil"
    private fun balance() = "Banco Bandec La consulta de saldo fue completada.\nCuenta;Saldo Contable;Saldo Disponible;Moneda\n0000XXXXXXXX0001; CR 1000.00; CR 1000.00;CUP |"
    private fun receipt(reference: String, remaining: Boolean = false) = "Banco Bandec: La Transferencia fue completada.\nBeneficiario: 0000XXXXXXXX0002\nMonto: 10.00 CUP\nNro. Transaccion: $reference" + if (remaining) "\nSaldo restante: CR 990.00 CUP" else ""
}
