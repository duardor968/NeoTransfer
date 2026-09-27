package dev.duardo.neotransfer

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import dev.duardo.neotransfer.core.*
import dev.duardo.neotransfer.core.fuel.FuelEnvelopeProtector
import dev.duardo.neotransfer.data.*
import dev.duardo.neotransfer.fuel.FuelSecretStore
import dev.duardo.neotransfer.platform.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Isolated UUID databases and a fake modem: no real USSD, user database, vault or SIM writes. */
object OperationExecutorChecks {
    fun run(context: Context, onPassed: (String) -> Unit = {}): Int {
        var passed = 0
        fun case(name: String, provider: ProviderId = ProviderId.BPA, body: (Harness) -> Unit) {
            val key = "executor_check_${UUID.randomUUID()}"
            val file = "$key.db"
            val isolated = object : ContextWrapper(context) {
                override fun checkSelfPermission(permission: String) = android.content.pm.PackageManager.PERMISSION_GRANTED
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                    super.getSharedPreferences("${key}_$name", mode)
            }
            val repository = RoomWalletRepository.open(context, file)
            repository.putIdentity(IdentityRecord("owner", "Fixture"))
            val registration = RegistrationRecord("registration", "owner", provider.name, "PERSONAL", provider.bank?.code, 7, null,
                "Fixture ${provider.name}", "access:registration")
            repository.putRegistration(registration)
            val harness = Harness(isolated, repository, registration)
            try { body(harness); passed++; onPassed(name) }
            finally {
                harness.close()
                check(context.deleteDatabase(file)) { "La base aislada no se pudo eliminar" }
                check(context.deleteSharedPreferences("${key}_bank_state")) { "Las preferencias aisladas no se pudieron eliminar" }
            }
        }

        case("SMS evidence uses valid service-centre time with second precision") { h ->
            val started = h.clock.plusMillis(700)
            val received = h.clock.plusSeconds(163)
            check(smsEvidenceAfter(received, h.clock, started, received))
            check(!smsEvidenceAfter(received, h.clock.minusSeconds(1), started, received))
            check(!smsEvidenceAfter(received, null, started, received))
            check(smsEvidenceTime(received, received.plusSeconds(1), received.plusSeconds(2)) == null)
            check(smsEvidenceTime(received, received, received.minusSeconds(1)) == null)
            check(smsEvidenceTime(received, Instant.EPOCH, received) == null)
        }
        for (money in listOf(false, true)) case("A 163 second authentication delay cannot resume ${if (money) "money" else "a query"}", ProviderId.BANDEC) { h ->
            val sent = h.clock.plusSeconds(1)
            h.main {
                if (money) h.executor.execute(h.bankTransfer(), h.target(), "12345".toCharArray(), approved = true)
                else h.executor.queryBandecCard(h.target(), SourceSelector.Explicit("0000000000000001"), "12345".toCharArray())
            }
            h.await { h.modem.services == listOf(40) }
            h.main { h.modem.reply(Harness.processing) }
            h.clock = h.clock.plusSeconds(163)
            h.observe(h.authRecord(sentAt = sent))
            h.await { !h.executor.busy && "Tiempo de espera agotado" in h.results }
            check(h.main { h.modem.services == listOf(40) })
            check(h.repository.snapshot().operations.none { it.specId == "bandec.transfer" || it.specId == "bandec.card-select" })
        }
        case("A valid acknowledged late access is reusable only by a new explicit request", ProviderId.BANDEC) { h ->
            val sent = h.clock.plusSeconds(1)
            h.main { h.executor.execute(h.bankTransfer(), h.target(), "12345".toCharArray(), approved = true) }
            h.await { h.modem.services == listOf(40) }
            h.main { h.modem.reply(Harness.processing) }
            h.clock = h.clock.plusSeconds(163)
            h.observe(h.authRecord(sentAt = sent))
            h.await { !h.executor.busy && h.wallet.snapshot.operations.single().status == OperationStatus.CONFIRMED }
            check(h.main { h.modem.services == listOf(40) })
            h.main { h.executor.queryBandecCard(h.target(), SourceSelector.Explicit("0000000000000001"), "12345".toCharArray()) }
            h.await { h.modem.services == listOf(40, 60) }
            check(h.repository.snapshot().operations.none { it.specId == "bandec.transfer" })
        }
        case("Receiving authentication late does not restart its one hour session age", ProviderId.BANDEC) { h ->
            val started = h.clock
            h.main { h.executor.execute(h.bankTransfer(), h.target(), "12345".toCharArray(), approved = true) }
            h.await { h.modem.services == listOf(40) }
            h.main { h.modem.reply(Harness.processing) }
            h.clock = started.plusSeconds(163)
            h.observe(h.authRecord(sentAt = started.plusSeconds(1)))
            h.await { h.wallet.snapshot.operations.single().status == OperationStatus.CONFIRMED }
            h.clock = started.plusSeconds(3601)
            h.main { h.executor.queryBandecCard(h.target(), SourceSelector.Explicit("0000000000000001"), "12345".toCharArray()) }
            h.await { h.modem.services == listOf(40, 40) }
        }
        case("A missing callback times out at thirty seconds and a later callback cannot continue", ProviderId.BANDEC) { h ->
            h.main { h.executor.execute(h.bankTransfer(), h.target(), "12345".toCharArray(), approved = true) }
            h.await { h.modem.services == listOf(40) }
            h.elapseTimeout()
            h.await { !h.executor.busy && "Tiempo de espera agotado" in h.results }
            check(h.repository.snapshot().operations.single().let { it.status == OperationStatus.UNCERTAIN && !it.reviewRequired })
            h.main { check(h.modem.isIdle()); h.modem.replyAbandoned(Harness.processing) }
            check(h.main { h.modem.services == listOf(40) && !h.executor.busy })
        }
        case("The original deadline abandons a second USSD step without releasing a newer request", ProviderId.BANDEC) { h ->
            h.main { h.executor.execute(h.bankTransfer(), h.target(), "12345".toCharArray(), approved = true) }
            h.await { h.modem.services == listOf(40) }
            h.main { h.modem.reply(Harness.processing) }
            h.clock = h.clock.plusSeconds(29)
            h.observe(h.authRecord())
            h.await { h.modem.services == listOf(40, 60) }
            h.elapseTimeout(1)
            h.await { !h.executor.busy }
            check(h.main { h.modem.isIdle() })
            h.main { h.executor.execute(ServiceRequest("bandec.balance", h.target().identity), h.target(), "12345".toCharArray(), approved = false) }
            h.await { h.modem.services == listOf(40, 60, 46) }
            h.main {
                h.modem.replyAbandoned(Harness.processing)
                h.modem.replyAbandoned(UssdResult.NetworkFailure(-1))
                check(h.executor.busy && !h.modem.isIdle())
                check(h.modem.services == listOf(40, 60, 46))
                h.modem.reply(Harness.processing)
            }
            h.balance("0000XXXXXXXX0001", "CUP")
            h.await { h.wallet.snapshot.operations.single { it.specId == "bandec.balance" }.status == OperationStatus.CONFIRMED }
            check(h.repository.snapshot().operations.none { it.specId == "bandec.transfer" })
        }
        case("An older acknowledged query timer cannot abandon or present over a newer execution", ProviderId.BANDEC) { h ->
            h.main { h.executor.execute(ServiceRequest("bandec.balance", h.target().identity), h.target(), "12345".toCharArray(), approved = false) }
            h.authenticate(); h.await { h.modem.services == listOf(40, 46) }
            h.main { h.modem.reply(Harness.processing) }
            h.await { h.wallet.snapshot.operations.single { it.specId == "bandec.balance" }.status == OperationStatus.AWAITING_CONFIRMATION }
            h.clock = h.clock.plusSeconds(4)
            h.main { h.executor.queryBandecCard(h.target(), SourceSelector.Explicit("0000000000000001"), "12345".toCharArray()) }
            h.await { h.modem.services == listOf(40, 46, 60) }
            val before = h.main { h.results.toList() }
            h.elapseTimeout(25)
            h.await { h.wallet.snapshot.operations.single { it.specId == "bandec.balance" }.timeoutAt != null }
            h.main {
                check(h.executor.busy && !h.modem.isIdle())
                check(h.results == before)
                h.modem.reply(Harness.processing)
            }
            h.await { h.modem.services == listOf(40, 46, 60, 46) }
            check(h.main { h.executor.busy && !h.modem.isIdle() })
        }
        case("A modem timeout uses the timeout transition even before the executor timer callback", ProviderId.BANDEC) { h ->
            h.main { h.executor.execute(h.bankTransfer(), h.target(), "12345".toCharArray(), approved = true) }
            h.await { h.modem.services == listOf(40) }
            h.main { h.modem.reply(UssdResult.TimedOut) }
            h.await { !h.executor.busy && "Tiempo de espera agotado" in h.results }
            check(h.repository.snapshot().operations.single().let { it.status == OperationStatus.UNCERTAIN && !it.reviewRequired })
            check(h.main { h.modem.services == listOf(40) })
        }
        case("Timeout publishes its persisted state before allowing the next money request", ProviderId.BANDEC) { h ->
            h.main { h.executor.execute(h.bankTransfer(), h.target(), "12345".toCharArray(), approved = true) }
            h.await { h.modem.services == listOf(40) }
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            h.main { h.wallet.transact(block = {
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS)) { "No se liberó la barrera de persistencia" }
            }) }
            try {
                check(entered.await(10, TimeUnit.SECONDS))
                h.main {
                    h.modem.reply(UssdResult.TimedOut)
                    check(h.executor.busy)
                    check(h.wallet.snapshot.operations.single().status == OperationStatus.SUBMITTING)
                }
            } finally { release.countDown() }
            h.await { !h.executor.busy }
            check(h.main { h.wallet.snapshot.operations.single().let {
                it.status == OperationStatus.UNCERTAIN && !it.reviewRequired && it.timeoutAt != null
            } })
            h.main { h.executor.execute(h.bankTransfer(), h.target(), "12345".toCharArray(), approved = true) }
            h.await { h.modem.services == listOf(40, 40) }
        }
        case("An old SMS received during a new request cannot authenticate the new request", ProviderId.BANDEC) { h ->
            val firstSent = h.clock.plusSeconds(1)
            h.main { h.executor.execute(h.bankTransfer(), h.target(), "12345".toCharArray(), approved = true) }
            h.await { h.modem.services == listOf(40) }
            h.clock = h.clock.plusSeconds(31)
            h.main { h.modem.reply(Harness.processing) }
            h.await { !h.executor.busy }
            check(h.main { h.wallet.snapshot.operations.single().status == OperationStatus.UNCERTAIN })
            h.main { h.executor.execute(h.bankTransfer(), h.target(), "12345".toCharArray(), approved = true) }
            h.await { h.modem.services == listOf(40, 40) }
            h.main { h.modem.reply(Harness.processing) }
            h.clock = h.clock.plusSeconds(1)
            h.observe(h.authRecord(sentAt = firstSent))
            check(h.main { h.modem.services == listOf(40, 40) && h.executor.busy })
            check(h.repository.snapshot().operations.none { it.specId == "bandec.transfer" })
        }
        for (kind in listOf("missing", "future", "another SIM", "cancelled"))
            case("Authentication cannot proceed with $kind evidence", ProviderId.BANDEC) { h ->
                h.main { h.executor.execute(h.bankTransfer(), h.target(), "12345".toCharArray(), approved = true) }
                h.await { h.modem.services == listOf(40) }
                h.main { h.modem.reply(Harness.processing); if (kind == "cancelled") h.executor.invalidate() }
                h.clock = h.clock.plusSeconds(1)
                val sent = when (kind) { "missing" -> null; "future" -> h.clock.plusSeconds(1); else -> h.clock }
                h.observe(h.authRecord(sentAt = sent, sim = if (kind == "another SIM") 8 else 7))
                check(h.main { h.modem.services == listOf(40) })
                check(h.repository.snapshot().operations.none { it.specId == "bandec.transfer" })
            }
        case("A callback after expiry cannot restart a payment even with already authenticated response", ProviderId.BANDEC) { h ->
            h.main { h.executor.execute(h.bankTransfer(), h.target(), "12345".toCharArray(), approved = true) }
            h.await { h.modem.services == listOf(40) }
            h.clock = h.clock.plusSeconds(30)
            h.main { h.modem.reply(UssdResult.Response("Usted ya se encuentra autenticado en el sistema")) }
            h.await { !h.executor.busy && "Tiempo de espera agotado" in h.results }
            check(h.main { h.modem.services == listOf(40) })
        }
        case("Authentication and selection share the original thirty second deadline", ProviderId.BANDEC) { h ->
            h.main { h.executor.execute(h.bankTransfer(), h.target(), "12345".toCharArray(), approved = true) }
            h.await { h.modem.services == listOf(40) }
            h.main { h.modem.reply(Harness.processing) }
            h.clock = h.clock.plusSeconds(29)
            h.observe(h.authRecord())
            h.await { h.modem.services == listOf(40, 60) }
            h.clock = h.clock.plusSeconds(2)
            h.main { h.modem.reply(Harness.processing) }
            h.await { !h.executor.busy }
            check(h.main { h.modem.services == listOf(40, 60) })
        }
        case("An equivalent balance probe satisfies the requested query without a second balance command", ProviderId.BANDEC) { h ->
            h.main { h.executor.execute(ServiceRequest("bandec.balance", h.target().identity), h.target(), "12345".toCharArray(), approved = false) }
            h.await { h.modem.services == listOf(40) }
            h.main { h.modem.reply(UssdResult.Response("Usted ya se encuentra autenticado en el sistema")) }
            h.await { h.modem.services == listOf(40, 46) }
            h.main { h.modem.reply(Harness.processing) }
            h.balance("0000XXXXXXXX0001", "CUP")
            h.await { !h.executor.busy && h.wallet.snapshot.operations.single { it.specId == "bandec.balance" }.status == OperationStatus.CONFIRMED }
            check(h.main { h.modem.services == listOf(40, 46) })
        }
        case("A default probe cannot replace an explicitly selected card query", ProviderId.BANDEC) { h ->
            h.main { h.executor.queryBandecCard(h.target(), SourceSelector.Explicit("0000000000000001"), "12345".toCharArray()) }
            h.await { h.modem.services == listOf(40) }
            h.main { h.modem.reply(UssdResult.Response("Usted ya se encuentra autenticado en el sistema")) }
            h.await { h.modem.services == listOf(40, 46) }
            h.main { h.modem.reply(Harness.processing) }
            h.balance("0000XXXXXXXX0009", "CUP")
            h.await { h.modem.services == listOf(40, 46, 60) }
            check(h.main { h.executor.busy })
        }
        case("Explicit authentication completes once and does not repeat its own command", ProviderId.BANDEC) { h ->
            val request = ServiceRequest("bandec.authenticate", h.target().identity, values = mapOf("pin" to "12345"))
            h.main { h.executor.execute(request, h.target(), "12345".toCharArray(), approved = true) }
            h.authenticate()
            h.await { !h.executor.busy && h.wallet.snapshot.operations.single().status == OperationStatus.CONFIRMED }
            check(h.main { h.modem.services == listOf(40) })
        }
        case("Two unresolved authentication attempts cannot claim a later ambiguous SMS", ProviderId.BANDEC) { h ->
            h.main { h.executor.execute(h.bankTransfer(), h.target(), "12345".toCharArray(), approved = true) }
            h.await { h.modem.services == listOf(40) }
            h.main { h.modem.reply(Harness.processing) }
            h.elapseTimeout(); h.await { !h.executor.busy }
            h.main { h.executor.execute(h.bankTransfer(), h.target(), "12345".toCharArray(), approved = true) }
            h.await { h.modem.services == listOf(40, 40) }
            h.main { h.modem.reply(Harness.processing) }
            h.clock = h.clock.plusSeconds(1)
            h.observe(h.authRecord())
            check(h.main { h.modem.services == listOf(40, 40) })
            check(h.repository.snapshot().operations.none { it.status == OperationStatus.CONFIRMED || it.specId == "bandec.transfer" })
        }
        case("A late matching origin balance updates its query without sending the expired transfer", ProviderId.BANDEC) { h ->
            h.main { h.executor.execute(h.bankTransfer(), h.target(), "12345".toCharArray(), approved = true) }
            h.authenticate(); h.await { h.modem.services == listOf(40, 60) }; h.acknowledgeSelectionAndRead()
            val sent = h.clock.plusSeconds(1)
            h.elapseTimeout()
            h.await { !h.executor.busy }
            val before = h.main { h.results.toList() }
            h.clock = h.clock.plusSeconds(132)
            val record = BankSmsRecord(null, "Banco Bandec La consulta de saldo fue completada.\nCuenta;Saldo Contable;Saldo Disponible;Moneda\n0000XXXXXXXX0001; CR 1000.00; CR 900.00;CUP |", h.clock, 7, sentAt = sent)
            h.observe(record)
            h.await { h.wallet.snapshot.operations.single { it.specId == "bandec.card-balance" }.status == OperationStatus.CONFIRMED }
            check(h.main { h.modem.services == listOf(40, 60, 46) && !h.executor.busy })
            check(h.repository.snapshot().operations.none { it.specId == "bandec.transfer" })
            check(h.main { h.results == before })
        }
        for (late in listOf(false, true)) case("A ${if (late) "late" else "timely"} balance persists with presentation limited to its wait", ProviderId.BANDEC) { h ->
            h.main { h.executor.execute(ServiceRequest("bandec.balance", h.target().identity), h.target(), "12345".toCharArray(), approved = false) }
            h.authenticate(); h.await { h.modem.services == listOf(40, 46) }
            h.main { h.modem.reply(Harness.processing) }
            h.await { h.wallet.snapshot.operations.single { it.specId == "bandec.balance" }.status == OperationStatus.AWAITING_CONFIRMATION }
            if (late) {
                h.elapseTimeout()
                h.await { h.wallet.snapshot.operations.single { it.specId == "bandec.balance" }.timeoutAt != null }
            }
            val before = h.main { h.results.toList() }
            val (record, result) = h.balanceEvidence("0000XXXXXXXX0001", "CUP")
            h.observe(record, result)
            h.await { h.wallet.snapshot.operations.single { it.specId == "bandec.balance" }.status == OperationStatus.CONFIRMED }
            check(h.main { if (late) h.results == before else h.results.size == before.size + 1 && "900.00 CUP" in h.results.last() })
            check(h.main { h.modem.services == listOf(40, 46) })
        }
        case("A changed access context cannot reuse its acknowledged late authentication", ProviderId.BANDEC) { h ->
            h.main { h.executor.execute(h.bankTransfer(), h.target(), "12345".toCharArray(), approved = true) }
            h.await { h.modem.services == listOf(40) }
            h.main { h.modem.reply(Harness.processing) }
            h.main { h.wallet.transact(block = { putRegistration(h.registration.copy(subscriptionId = 8)) }) }
            h.await { h.wallet.snapshot.registrations.single().subscriptionId == 8 }
            h.clock = h.clock.plusSeconds(1)
            h.observe(h.authRecord())
            check(h.main { h.modem.services == listOf(40) })
        }
        case("Changing credential generation invalidates a cached authenticated session", ProviderId.BANDEC) { h ->
            val request = ServiceRequest("bandec.authenticate", h.target().identity, values = mapOf("pin" to "12345"))
            h.main { h.executor.execute(request, h.target(), "12345".toCharArray(), approved = true) }
            h.authenticate(); h.await { !h.executor.busy }
            h.generation++
            h.main { h.executor.queryBandecCard(h.target(), SourceSelector.Explicit("0000000000000001"), "12345".toCharArray()) }
            h.await { h.modem.services == listOf(40, 40) }
        }
        case("An unrecognized authentication callback stops without success or a fabricated rejection", ProviderId.BANDEC) { h ->
            h.main { h.executor.execute(h.bankTransfer(), h.target(), "12345".toCharArray(), approved = true) }
            h.await { h.modem.services == listOf(40) }
            h.main { h.modem.reply(UssdResult.Response("Solicitud rechazada")) }
            h.await { !h.executor.busy && h.wallet.snapshot.operations.single().status == OperationStatus.UNCERTAIN }
            check(h.main { h.results.last() == "No se pudo confirmar el acceso con la respuesta del proveedor." && h.modem.services == listOf(40) })
        }
        case("Executor denies unapproved money and keeps serial requests durable before the fake modem") { h ->
            val request = h.telecomTransfer()
            val target = ServiceExecutionContext(null, request.identity, 7, null, null)
            h.main { h.executor.execute(request, target, null, approved = false) }
            check(h.main { h.modem.services.isEmpty() })
            h.main { h.executor.execute(request, target, null, approved = true) }
            h.await { h.modem.services.size == 1 }
            check(h.repository.snapshot().operations.single().parameters.keys.none { it.contains("pin", true) })
            h.main { h.executor.execute(request, target, null, approved = true) }
            check(h.main { h.modem.services.size == 1 && h.executor.busy })
            h.main { h.modem.reply(UssdResult.NetworkFailure(-1)) }
            h.await { h.wallet.snapshot.operations.single().status == OperationStatus.UNCERTAIN }
            check(h.main { h.modem.services.size == 1 })
        }

        case("Executor rejects a registration whose line changed after approval") { h ->
            val request = h.bankTransfer()
            val captured = h.target()
            h.main { h.wallet.transact(block = { putRegistration(h.registration.copy(subscriptionId = 8)) }) }
            h.await { h.wallet.snapshot.registrations.single().subscriptionId == 8 }
            h.main { h.executor.execute(request, captured, "1234".toCharArray(), approved = true) }
            check(h.main { h.modem.services.isEmpty() })
            check(h.repository.snapshot().operations.isEmpty())
        }

        case("Executor requires auth acknowledgement and provider evidence then sends money once") { h ->
            h.main { h.executor.execute(h.bankTransfer(), h.target(), "1234".toCharArray(), approved = true) }
            h.await { h.modem.services == listOf(40) }
            h.clock = h.clock.plusSeconds(1)
            val body = "Usted se ha autenticado en la plataforma de pagos moviles, en el Banco Popular de Ahorro, puede comenzar a utilizar nuestros servicios de pagos a traves del movil"
            val record = BankSmsRecord(null, body, h.clock, 7, sentAt = h.clock)
            val result = SmsIngestor(h.repository, now = { h.clock }).ingest(record)
            h.main { h.executor.observed(record, result) }
            check(h.main { h.modem.services == listOf(40) })
            h.main { h.modem.reply(UssdResult.Response("Su solicitud esta siendo procesada, espere un SMS")) }
            h.await { h.modem.services == listOf(40, 45) }
            val duplicate = BankSmsRecord(1, body, h.clock, 7, sentAt = h.clock)
            val duplicateResult = SmsIngestor(h.repository, now = { h.clock }).ingest(duplicate)
            h.main { h.executor.observed(duplicate, duplicateResult); h.modem.reply(UssdResult.NetworkFailure(-1)) }
            h.await { h.wallet.snapshot.operations.single { it.specId == "bpa.transfer" }.status == OperationStatus.UNCERTAIN }
            check(h.main { h.modem.services == listOf(40, 45) })
        }

        case("Executor foreground invalidation prevents a queued payment after authentication") { h ->
            h.main { h.executor.execute(h.bankTransfer(), h.target(), "1234".toCharArray(), approved = true) }
            h.await { h.modem.services == listOf(40) }
            h.main { h.executor.invalidate(); h.modem.reply(UssdResult.Response("Su solicitud esta siendo procesada, espere un SMS")) }
            h.await { h.wallet.snapshot.operations.single().status == OperationStatus.UNCERTAIN }
            check(h.main { h.modem.services == listOf(40) })
        }
        case("BANDEC rejected selection never falls through to a default balance", ProviderId.BANDEC) { h ->
            h.beginBandecQuery()
            h.main { h.modem.reply(UssdResult.Response("Solicitud rechazada")) }
            h.await { !h.executor.busy }
            check(h.main { h.modem.services == listOf(40, 60) })
            check(h.repository.snapshot().operations.none { it.specId == "bandec.card-balance" })
        }
        case("BANDEC selection acknowledgement alone never confirms an account", ProviderId.BANDEC) { h ->
            h.beginBandecQuery(); h.acknowledgeSelectionAndRead()
            check(h.main { h.executor.busy })
            check(h.repository.snapshot().operations.filter { it.specId.startsWith("bandec.card-") }.none { it.status == OperationStatus.CONFIRMED })
            h.main { h.executor.invalidate() }
        }
        case("BANDEC balance for a different card cannot populate the selected product", ProviderId.BANDEC) { h ->
            h.addCard(); h.beginBandecQuery(); h.acknowledgeSelectionAndRead()
            h.balance("0000XXXXXXXX0009", "CUP")
            h.await { !h.executor.busy }
            check(h.main { h.wallet.snapshot.balances.none { it.cardId == "card" } })
            check(h.main { h.modem.services == listOf(40, 60, 46) })
        }
        case("BANDEC matching balance confirms both persisted query steps", ProviderId.BANDEC) { h ->
            h.addCard(); h.beginBandecQuery(); h.acknowledgeSelectionAndRead()
            h.balance("0000XXXXXXXX0001", "CUP")
            h.await { !h.executor.busy && h.wallet.snapshot.operations.filter { it.specId.startsWith("bandec.card-") }.all { it.status == OperationStatus.CONFIRMED } }
            h.await { h.wallet.snapshot.balances.singleOrNull()?.cardId == "card" }
            check(h.main { h.modem.services == listOf(40, 60, 46) })
        }
        case("BANDEC ignores another SIM and rejects another bank as source proof", ProviderId.BANDEC) { h ->
            h.addCard(); h.beginBandecQuery(); h.acknowledgeSelectionAndRead()
            h.balance("0000XXXXXXXX0001", "CUP", sim = 8)
            check(h.main { h.executor.busy })
            check(h.repository.snapshot().operations.filter { it.specId.startsWith("bandec.card-") }.none { it.status == OperationStatus.CONFIRMED })
            h.balance("0000XXXXXXXX0001", "CUP", bank = "BPA")
            h.await { !h.executor.busy }
            check(h.main { h.wallet.snapshot.balances.none { it.cardId == "card" } })
        }
        case("BANDEC cancellation after selection submission never starts the balance step", ProviderId.BANDEC) { h ->
            h.beginBandecQuery()
            h.main { h.executor.invalidate(); h.modem.reply(Harness.processing) }
            h.await { h.wallet.snapshot.operations.single { it.specId == "bandec.card-select" }.status == OperationStatus.UNCERTAIN }
            check(h.main { h.modem.services == listOf(40, 60) })
        }
        case("BANDEC USD card labelled CUP cannot send a CUP amount as USD", ProviderId.BANDEC) { h ->
            h.addCard()
            h.main { h.executor.execute(h.bankTransfer(amount = "250.00"), h.target(), "12345".toCharArray(), approved = true) }
            h.authenticate(); h.await { h.modem.services == listOf(40, 60) }; h.acknowledgeSelectionAndRead()
            h.balance("0000XXXXXXXX0001", "USD")
            h.await { !h.executor.busy }
            check(h.main { 45 !in h.modem.services })
            check(h.repository.snapshot().operations.none { it.specId == "bandec.transfer" })
        }
        case("BANDEC exact fresh currency permits only one transfer", ProviderId.BANDEC) { h ->
            h.main { h.executor.execute(h.bankTransfer(), h.target(), "12345".toCharArray(), approved = true) }
            h.authenticate(); h.await { h.modem.services == listOf(40, 60) }; h.acknowledgeSelectionAndRead()
            h.balance("0000XXXXXXXX0001", "CUP")
            h.await { h.modem.services == listOf(40, 60, 46, 45) }
            h.main { h.modem.reply(UssdResult.NetworkFailure(-1)) }
            h.await { !h.executor.busy }
            check(h.main { h.modem.services.count { it == 45 } == 1 })
        }
        case("BANDEC another card with the same currency never enables the transfer", ProviderId.BANDEC) { h ->
            h.main { h.executor.execute(h.bankTransfer(), h.target(), "12345".toCharArray(), approved = true) }
            h.authenticate(); h.await { h.modem.services == listOf(40, 60) }; h.acknowledgeSelectionAndRead()
            h.balance("0000XXXXXXXX0009", "CUP")
            h.await { !h.executor.busy }
            check(h.main { 45 !in h.modem.services })
        }
        case("BANDEC changed default in the same bank invalidates the currency proof", ProviderId.BANDEC) { h ->
            val request = h.bankTransfer(source = SourceSelector.Default)
            h.main { h.executor.execute(request, h.target(), "12345".toCharArray(), approved = true) }
            h.authenticate(); h.await { h.modem.services == listOf(40, 46) }
            h.main { h.modem.reply(Harness.processing) }
            val cup = h.balanceEvidence("0000XXXXXXXX0001", "CUP")
            val changed = h.balanceEvidence("0000XXXXXXXX0009", "CUP")
            h.main { h.executor.observed(cup.first, cup.second); h.executor.observed(changed.first, changed.second) }
            h.await { !h.executor.busy }
            check(h.main { 45 !in h.modem.services })
        }
        case("BANDEC default cannot replace the full account shown in the approved context", ProviderId.BANDEC) { h ->
            h.addCard()
            val target = h.target().copy(productId = "card", productNumber = "0000000000000001", productCurrency = "CUP")
            h.main { h.executor.execute(h.bankTransfer(source = SourceSelector.Default), target, "12345".toCharArray(), approved = true) }
            h.authenticate(); h.await { h.modem.services == listOf(40, 46) }; h.main { h.modem.reply(Harness.processing) }
            h.balance("0000XXXXXXXX0009", "CUP")
            h.await { !h.executor.busy }
            check(h.main { 45 !in h.modem.services })
        }
        case("BANDEC default transfer uses a fresh matching currency without selecting an invented PAN", ProviderId.BANDEC) { h ->
            h.main { h.executor.execute(h.bankTransfer(source = SourceSelector.Default), h.target(), "12345".toCharArray(), approved = true) }
            h.authenticate(); h.await { h.modem.services == listOf(40, 46) }; h.main { h.modem.reply(Harness.processing) }
            h.balance("0000XXXXXXXX0001", "CUP")
            h.await { h.modem.services == listOf(40, 46, 45) }
            h.main { h.modem.reply(UssdResult.NetworkFailure(-1)) }
        }
        case("Linked Clasica uses its personal parent session and preserves the product identity for encoding", ProviderId.MITRANSFER) { h ->
            h.repository.putCard(CardRecord("classic", h.registration.id, "0000000000000001", "Clásica", currency = "USD", profileId = "CLASSIC"))
            h.await { h.wallet.snapshot.cards.any { it.id == "classic" } }
            val identity = ProviderIdentity(ProviderId.MITRANSFER, ProfileId.CLASSIC)
            val target = h.target().copy(identity = identity, authenticationIdentity = ProviderIdentity(ProviderId.MITRANSFER),
                productId = "classic", productNumber = "0000000000000001", productCurrency = "USD")
            val request = ServiceRequest("wallet.classic.transfer", identity, SourceSelector.Explicit("0000000000000001"),
                values = mapOf("destination" to "0000000000000002", "amount" to "1"))
            h.main { h.executor.execute(request, target.copy(productId = null, productNumber = null), "1234".toCharArray(), approved = true) }
            check(h.main { h.modem.services.isEmpty() })
            h.main { h.executor.execute(request, target, "1234".toCharArray(), approved = true) }
            h.authenticate(); h.await { h.modem.services == listOf(40, 45) }
            val operations = h.repository.snapshot().operations
            check(operations.single { it.specId == "wallet.authenticate" }.profileId == "PERSONAL")
            check(operations.single { it.specId == "wallet.classic.transfer" }.let { it.profileId == "CLASSIC" && it.registrationId == h.registration.id })
            h.main { h.modem.reply(UssdResult.NetworkFailure(-1)) }
        }
        case("MiTransfer context selects the explicit bag and rejects a mismatched selected currency", ProviderId.MITRANSFER) { h ->
            h.repository.putAccount(AccountRecord("cup", h.registration.id, "", "CUP", currency = "CUP"))
            h.repository.putAccount(AccountRecord("usd", h.registration.id, "", "USD", currency = "USD"))
            h.repository.setSettings(h.repository.snapshot().settings.copy(selectedRegistrationId = h.registration.id,
                selectedAccountId = "cup", selectedCardId = null, subscriptionId = 7))
            h.await { h.wallet.snapshot.settings.selectedAccountId == "cup" && h.wallet.snapshot.accounts.size == 2 }
            val controller = h.controller()
            val request = ServiceRequest("wallet.transfer", h.target().identity, currency = Currency.USD,
                values = mapOf("mobile" to "50000001", "amount" to "1"))
            h.main {
                check(runCatching { controller.serviceContext(request) }.isFailure)
                val chosen = controller.serviceContext(request, "usd")
                check(chosen.productId == "usd" && chosen.productNumber == "" && chosen.productCurrency == "USD")
                controller.lock()
            }
        }
        case("QR execution keeps the original merchant and fixed amount immutable", ProviderId.MITRANSFER) { h ->
            val qr = QrPayment("FIXTURE", "123", Money("10.00".toBigDecimal(), Currency.CUP), "1", "", "", null, null, null)
            val request = WalletQrOperations.fromQr(h.target().identity, SourceSelector.Default, Currency.CUP, qr, qr.amount)
            val target = h.target().copy(originalQr = qr)
            for (changes in listOf(mapOf("amount" to "250.00"), mapOf("provider" to "999"))) {
                val altered = ServiceRequest(request.operationId, request.identity, request.source, request.currency, request.values + changes)
                h.main { h.executor.execute(altered, target, "1234".toCharArray(), approved = true) }
                check(h.main { h.modem.services.isEmpty() })
            }
            h.main { h.executor.execute(request, target.copy(originalQr = null), "1234".toCharArray(), approved = true) }
            check(h.main { h.modem.services.isEmpty() })
            h.main { h.executor.execute(request, target, "1234".toCharArray(), approved = true) }
            h.await { h.modem.services == listOf(30) }
            check(h.repository.snapshot().operations.single().qr?.transactionId == qr.transactionId)
            h.main { h.modem.reply(UssdResult.NetworkFailure(-1)) }
        }
        case("Interactive USSD opens the native transport without using the callback modem API") { h ->
            val request = ServiceRequest("cubacel.plans", ProviderIdentity(ProviderId.CUBACEL))
            h.main { h.executor.execute(request, ServiceExecutionContext(null, request.identity, 7, null, null), null, approved = true) }
            h.await { h.nativeRequests.size == 1 && h.wallet.snapshot.operations.single().status == OperationStatus.UNCERTAIN }
            check(h.main { h.modem.services.isEmpty() && h.nativeRequests.single().transport == OperationTransport.INTERACTIVE_USSD })
        }
        case("Fresh history waits for the query acknowledgement and never confirms money", ProviderId.BANDEC) { h ->
            val request = ServiceRequest("bandec.recent-operations", h.target().identity, SourceSelector.Explicit("0000000000000001"))
            h.main { h.executor.execute(request, h.target(), "12345".toCharArray(), approved = false) }
            h.authenticate(); h.await { h.modem.services == listOf(40, 48) }
            h.clock = h.clock.plusSeconds(1)
            val record = BankSmsRecord(null, "Banco Bandec Ultimas operaciones.\nFecha;Servicio;Operacion;Monto;Moneda;NoTransaccion\n23/09/2026;Intereses Ref: HST01;Cr;1.00;CUP; |", h.clock, 7, sentAt = h.clock)
            val result = SmsIngestor(h.repository, now = { h.clock }).ingest(record)
            h.main { h.executor.observed(record, result) }
            check(h.repository.snapshot().histories.single().account == null)
            h.main { h.modem.reply(Harness.processing) }
            h.await { h.wallet.snapshot.histories.single().account == "0000000000000001" }
            check(h.repository.snapshot().operations.single { it.specId == request.operationId }.status == OperationStatus.CONFIRMED)
            check(h.main { 45 !in h.modem.services })
            check(h.main { "Últimas operaciones recibidas de BANDEC." in h.results })
        }
        case("A history response received after timeout retains its original query context", ProviderId.BANDEC) { h ->
            val request = ServiceRequest("bandec.recent-operations", h.target().identity, SourceSelector.Explicit("0000000000000001"))
            h.main { h.executor.execute(request, h.target(), "12345".toCharArray(), approved = false) }
            h.authenticate(); h.await { h.modem.services == listOf(40, 48) }
            h.main { h.modem.reply(Harness.processing) }
            val sent = h.clock.plusSeconds(1)
            h.elapseTimeout()
            h.await { h.wallet.snapshot.operations.single { it.specId == request.operationId }.timeoutAt != null }
            val before = h.main { h.results.toList() }
            h.clock = h.clock.plusSeconds(132)
            h.observe(BankSmsRecord(null, "Banco Bandec Ultimas operaciones.\nFecha;Servicio;Operacion;Monto;Moneda;NoTransaccion\n23/09/2026;Intereses Ref: HSTLATE;Cr;1.00;CUP; |", h.clock, 7, sentAt = sent))
            h.await { h.wallet.snapshot.histories.singleOrNull()?.account == "0000000000000001" }
            check(h.repository.snapshot().operations.single { it.specId == request.operationId }.status == OperationStatus.CONFIRMED)
            check(h.main { h.modem.services == listOf(40, 48) && !h.executor.busy })
            check(h.main { h.results == before })
        }
        for (source in listOf(SourceSelector.Default, SourceSelector.Explicit("0000000000000001")))
            case("Recreated executor completes a late $source origin query without restoring a session or transfer", ProviderId.BANDEC) { h ->
                h.main { h.executor.execute(h.bankTransfer(source), h.target(), "12345".toCharArray(), approved = true) }
                h.authenticate()
                if (source is SourceSelector.Explicit) {
                    h.await { h.modem.services == listOf(40, 60) }; h.acknowledgeSelectionAndRead()
                } else {
                    h.await { h.modem.services == listOf(40, 46) }; h.main { h.modem.reply(Harness.processing) }
                }
                val spec = if (source is SourceSelector.Explicit) "bandec.card-balance" else "bandec.origin-balance"
                h.await { h.wallet.snapshot.operations.single { it.specId == spec }.status == OperationStatus.AWAITING_CONFIRMATION }
                val sent = h.clock.plusSeconds(1)
                h.elapseTimeout(); h.await { !h.executor.busy }
                h.recreateExecutor(); h.recreateExecutor()
                val before = h.main { h.results.toList() }
                h.clock = h.clock.plusSeconds(132)
                val body = "Banco Bandec La consulta de saldo fue completada.\nCuenta;Saldo Contable;Saldo Disponible;Moneda\n0000XXXXXXXX0001; CR 1000.00; CR 900.00;CUP |"
                h.observe(BankSmsRecord(null, body, h.clock, 8, sentAt = sent))
                check(h.repository.snapshot().operations.single { it.specId == spec }.status != OperationStatus.CONFIRMED)
                h.observe(BankSmsRecord(null, body, h.clock, 7, sentAt = sent))
                val operations = h.repository.snapshot().operations
                check(operations.single { it.specId == spec }.status == OperationStatus.CONFIRMED)
                if (source is SourceSelector.Explicit) check(operations.single { it.specId == "bandec.card-select" }.status == OperationStatus.CONFIRMED)
                check(operations.none { it.specId == "bandec.transfer" })
                check(h.main { h.modem.services.isEmpty() && h.results == before && h.confirmations.isEmpty() })
                h.main { h.executor.execute(ServiceRequest("bandec.balance", h.target().identity), h.target(), "12345".toCharArray(), approved = false) }
                h.await { h.modem.services == listOf(40) }
            }
        for (invalid in listOf("restored", "ambiguous", "ambiguous mask", "wrong source", "old SMSC", "missing SMSC", "wrong bank"))
            case("Recreated card query rejects $invalid evidence", ProviderId.BANDEC) { h ->
                h.beginBandecQuery(); h.await { h.modem.services == listOf(40, 60) }; h.acknowledgeSelectionAndRead()
                h.await { h.wallet.snapshot.operations.single { it.specId == "bandec.card-balance" }.status == OperationStatus.AWAITING_CONFIRMATION }
                val read = h.repository.snapshot().operations.single { it.specId == "bandec.card-balance" }
                h.elapseTimeout(); h.await { !h.executor.busy }
                val expired = h.repository.snapshot().operations.single { it.id == read.id }
                if (invalid == "restored") h.repository.putOperation(expired.copy(restored = true))
                if (invalid == "ambiguous") h.repository.putOperation(expired.copy(id = "another-read"))
                if (invalid == "ambiguous mask") h.repository.putCard(CardRecord("other-card", h.registration.id,
                    "0000111100000001", "Otra tarjeta", currency = "CUP"))
                h.recreateExecutor()
                h.clock = h.clock.plusSeconds(132)
                val bank = if (invalid == "wrong bank") "Popular de Ahorro" else "Bandec"
                val account = if (invalid == "wrong source") "0000XXXXXXXX0009" else "0000XXXXXXXX0001"
                val sent = when (invalid) {
                    "missing SMSC" -> null
                    "old SMSC" -> Instant.ofEpochMilli(read.startedAt).minusSeconds(1)
                    else -> Instant.ofEpochMilli(read.startedAt).plusSeconds(1)
                }
                val before = h.main { h.results.toList() }
                h.observe(BankSmsRecord(null, "Banco $bank La consulta de saldo fue completada.\nCuenta;Saldo Contable;Saldo Disponible;Moneda\n$account; CR 1000.00; CR 900.00;CUP |", h.clock, 7, sentAt = sent))
                check(h.repository.snapshot().operations.single { it.id == read.id }.status != OperationStatus.CONFIRMED)
                check(h.main { h.modem.services.isEmpty() && h.results == before })
            }
        for (kind in listOf("unique", "ambiguous", "restored", "wrong SIM", "old SMSC", "missing SMSC"))
            case("Recreated history with $kind evidence preserves journal guards without resending", ProviderId.BANDEC) { h ->
            val request = ServiceRequest("bandec.recent-operations", h.target().identity, SourceSelector.Explicit("0000000000000001"))
            h.main { h.executor.execute(request, h.target(), "12345".toCharArray(), approved = false) }
            h.authenticate(); h.await { h.modem.services == listOf(40, 48) }
            h.main { h.modem.reply(Harness.processing) }
            h.await { h.wallet.snapshot.operations.single { it.specId == request.operationId }.status == OperationStatus.AWAITING_CONFIRMATION }
            val query = h.repository.snapshot().operations.single { it.specId == request.operationId }
            h.recreateExecutor(); h.recreateExecutor()
            if (kind == "ambiguous") h.repository.putOperation(query.copy(id = "another-history", source = "0000000000000002", status = OperationStatus.UNCERTAIN))
            if (kind == "restored") h.repository.putOperation(h.repository.snapshot().operations.single { it.id == query.id }.copy(restored = true))
            h.clock = h.clock.plusSeconds(163)
            val before = h.main { h.results.toList() }
            val body = "Banco Bandec Ultimas operaciones.\nFecha;Servicio;Operacion;Monto;Moneda;NoTransaccion\n23/09/2026;Intereses Ref: HSTREC;Cr;1.00;CUP; |"
            val sent = when (kind) {
                "old SMSC" -> Instant.ofEpochMilli(query.startedAt).minusSeconds(1)
                "missing SMSC" -> null
                else -> Instant.ofEpochMilli(query.startedAt).plusSeconds(1)
            }
            h.observe(BankSmsRecord(null, body, h.clock, if (kind == "wrong SIM") 8 else 7, sentAt = sent))
            val snapshot = h.repository.snapshot()
            check((snapshot.operations.single { it.id == query.id }.status == OperationStatus.CONFIRMED) == (kind == "unique"))
            check(snapshot.histories.single().account == if (kind == "unique") query.source else null)
            check(h.main { h.modem.services.isEmpty() && h.results == before && h.confirmations.isEmpty() })
        }
        case("Access reservation reuses its durable ID and never disables an existing access") { h ->
            var reserved: RegistrationRecord? = null
            h.main { h.wallet.reserveRegistration(ProviderIdentity(ProviderId.MITRANSFER), 7) { reserved = it } }
            h.await { reserved != null }
            val first = requireNotNull(reserved)
            check(!first.enabled && first.credentialAlias == null)
            reserved = null
            h.main { h.wallet.reserveRegistration(ProviderIdentity(ProviderId.MITRANSFER), 7) { reserved = it } }
            h.await { reserved != null }; check(reserved == first)
            h.main { h.wallet.reserveRegistration(h.target().identity, 7) { check(it == h.registration); reserved = it } }
            h.await { reserved == h.registration }
            check(h.repository.snapshot().registrations.single { it.id == h.registration.id }.enabled)
        }
        case("Wallet close waits for a cancelled blocking IO read before closing its repository") { h ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val ended = CountDownLatch(1)
            val blocking = java.util.concurrent.atomic.AtomicBoolean(false)
            val repositoryClosed = java.util.concurrent.atomic.AtomicBoolean(false)
            val wrapper = object : WalletRepository by h.repository {
                override fun snapshot(): WalletSnapshot {
                    if (blocking.compareAndSet(true, false)) {
                        entered.countDown(); check(release.await(10, TimeUnit.SECONDS))
                        check(!repositoryClosed.get()) { "El repositorio se cerró mientras el lector seguía activo" }
                    }
                    return h.repository.snapshot()
                }
                override fun close() { repositoryClosed.set(true) }
            }
            val coordinator = h.main { WalletCoordinator(h.testContext, wrapper) }
            h.await { coordinator.ready }
            blocking.set(true)
            h.main { coordinator.transact(block = { snapshot() }) }
            check(entered.await(10, TimeUnit.SECONDS))
            try {
                h.main { coordinator.close { ended.countDown() } }
                check(!repositoryClosed.get() && ended.count == 1L)
            } finally { release.countDown() }
            check(ended.await(10, TimeUnit.SECONDS) && repositoryClosed.get())
        }
        case("Caja Extra rejects absent, changed and dynamic original QR before modem access") { h ->
            val qr = QrPayment("ESTATICO", "123", Money("10.00".toBigDecimal(), Currency.CUP), "1", "", "", null, null, null)
            val request = CashExtraOperations.fromQr(h.target().identity, SourceSelector.Default, Currency.CUP, qr, qr.amount)
            val altered = ServiceRequest(request.operationId, request.identity, request.source, request.currency, request.values + ("provider" to "999"))
            for ((value, original) in listOf(request to null, altered to qr, request to qr.copy(transactionId = "DINAMICO"))) {
                h.main { h.executor.execute(value, h.target().copy(originalQr = original), "1234".toCharArray(), approved = true) }
                check(h.main { h.modem.services.isEmpty() })
            }
            h.main { h.executor.execute(request, h.target().copy(originalQr = qr), "1234".toCharArray(), approved = true) }
            h.await { h.modem.services == listOf(40) }
            h.main { h.modem.reply(Harness.processing) }
            h.clock = h.clock.plusSeconds(1)
            val access = BankSmsRecord(null, "Usted se ha autenticado en la plataforma de pagos moviles, en el Banco Popular de Ahorro, puede comenzar a utilizar nuestros servicios de pagos a traves del movil", h.clock, 7, sentAt = h.clock)
            val result = SmsIngestor(h.repository, now = { h.clock }).ingest(access)
            h.main { h.executor.observed(access, result) }
            h.await { h.modem.services == listOf(40, 32) }
            h.main { h.modem.reply(UssdResult.NetworkFailure(-1)) }
        }
        case("Retired providers and profiles remain identifiable but cannot execute", ProviderId.MITRANSFER) { h ->
            val historical = RegistrationRecord("bfi-history", "owner", "BFI", "PERSONAL", "05", 7, null, "Histórico BFI")
            h.repository.putRegistration(historical)
            h.await { h.wallet.snapshot.registrations.any { it.id == historical.id } }
            val retired = listOf(
                "bfi.balance" to ProviderIdentity(ProviderId.BFI),
                "bpa.balance" to ProviderIdentity(ProviderId.BFI),
                "wallet.business.transfer" to ProviderIdentity(ProviderId.MITRANSFER, ProfileId.CLASSIC_BUSINESS),
                "wallet.agent.mobile" to ProviderIdentity(ProviderId.MITRANSFER, ProfileId.AGENT),
            )
            for ((id, identity) in retired) {
                val request = ServiceRequest(id, identity, currency = Currency.CUP)
                check(OperationCatalog.validate(request).isNotEmpty())
                check(runCatching { OperationCatalog.encode(request) }.isFailure)
                val target = if (identity.provider == ProviderId.BFI)
                    ServiceExecutionContext(historical.id, identity, 7, null, historical)
                else h.target().copy(identity = identity, authenticationIdentity = h.target().identity)
                h.main { h.executor.execute(request, target, "1234".toCharArray(), approved = true) }
                check(h.main { h.modem.services.isEmpty() })
            }
            for (id in listOf("miboleto.offers", "miboleto.redeem", "miboleto.seller.request-code", "wallet.ticket.buy", "wallet.ticket.list")) {
                check(OperationCatalog.find(id) == null)
                val request = ServiceRequest(id, h.target().identity)
                h.main { h.executor.execute(request, h.target(), "1234".toCharArray(), approved = true) }
                check(h.main { h.modem.services.isEmpty() })
            }
            check(OperationCatalog.all.all { it.identities.all(OperationCatalog::supports) })
            for (provider in listOf("bpa", "bandec", "banmet")) check(OperationCatalog.find("$provider.transfer") != null)
            check(h.repository.snapshot().registrations.single { it.id == historical.id } == historical)
            check(h.repository.snapshot().operations.isEmpty())
        }
        case("Nauta receipt settles the actual debit and cannot be reused for a second request", ProviderId.BANDEC) { h ->
            val request = h.nautaRequest()
            h.main { h.executor.execute(request, h.target(), "12345".toCharArray(), approved = true) }
            h.authenticate(); h.await { h.modem.services == listOf(40, 84) }
            h.main { h.modem.reply(Harness.processing) }
            h.clock = h.clock.plusSeconds(1)
            h.observe(BankSmsRecord(null, h.nautaReceipt("NAUTA01"), h.clock, 7, sentAt = h.clock))
            val first = h.repository.snapshot().operations.single { it.specId == request.operationId }
            check(first.status == OperationStatus.CONFIRMED && first.amount?.toBigDecimal()?.compareTo("270".toBigDecimal()) == 0)
            check(first.parameters["amount"] == "300")
            val receipt = h.repository.snapshot().receipts.single()
            check(receipt.amount.toBigDecimal() == "270".toBigDecimal() && receipt.nominalAmount?.toBigDecimal() == "300".toBigDecimal())
            h.clock = h.clock.plusSeconds(1)
            h.main { h.executor.execute(request, h.target(), "12345".toCharArray(), approved = true) }
            h.await { h.modem.services == listOf(40, 84, 84) }
            h.main { h.modem.reply(Harness.processing) }
            h.clock = h.clock.plusSeconds(1)
            h.observe(BankSmsRecord(null, h.nautaReceipt("NAUTA01"), h.clock, 7, sentAt = h.clock))
            val requests = h.repository.snapshot().operations.filter { it.specId == request.operationId }
            check(requests.count { it.status == OperationStatus.CONFIRMED } == 1)
            check(requests.single { it.id != first.id }.status != OperationStatus.CONFIRMED)
            check(h.repository.snapshot().receipts.size == 1 && h.repository.snapshot().movements.size == 1)
        }
        for (late in listOf(false, true)) case("A ${if (late) "late" else "timely"} payment notifies confirmed data without presenting an expired result", ProviderId.BANDEC) { h ->
            val request = h.nautaRequest()
            h.main { h.executor.execute(request, h.target(), "12345".toCharArray(), approved = true) }
            h.authenticate(); h.await { h.modem.services == listOf(40, 84) }
            h.main { h.modem.reply(Harness.processing) }
            h.await { h.wallet.snapshot.operations.single { it.specId == request.operationId }.status == OperationStatus.AWAITING_CONFIRMATION }
            if (late) {
                h.elapseTimeout()
                h.await { h.wallet.snapshot.operations.single { it.specId == request.operationId }.timeoutAt != null }
            }
            val before = h.main { h.results.toList() }
            h.clock = h.clock.plusSeconds(1)
            h.observe(BankSmsRecord(null, h.nautaReceipt("PRES01"), h.clock, 7, sentAt = h.clock))
            val confirmed = h.repository.snapshot().operations.single { it.specId == request.operationId }
            check(confirmed.status == OperationStatus.CONFIRMED)
            check(h.main { h.confirmations.single().id == confirmed.id })
            check(h.main { if (late) h.results == before else h.results == before + "Operación confirmada por el comprobante del banco." })
            check(h.main { h.modem.services == listOf(40, 84) })
        }
        case("Nauta ignores wrong family account SIM bank date nominal-only and excess debits", ProviderId.BANDEC) { h ->
            val request = h.nautaRequest()
            h.main { h.executor.execute(request, h.target(), "12345".toCharArray(), approved = true) }
            h.authenticate(); h.await { h.modem.services == listOf(40, 84) }
            h.main { h.modem.reply(Harness.processing) }
            val start = h.repository.snapshot().operations.single { it.specId == request.operationId }.startedAt
            h.clock = h.clock.plusSeconds(1)
            val cases = listOf(
                BankSmsRecord(null, h.nautaReceipt("WRONG1").replace("fixture@", "another@"), h.clock, 7, sentAt = h.clock),
                BankSmsRecord(null, h.nautaReceipt("WRONG2").replace("nauta.com.cu", "nauta.co.cu"), h.clock, 7, sentAt = h.clock),
                BankSmsRecord(null, h.nautaReceipt("WRONG3"), h.clock, 8, sentAt = h.clock),
                BankSmsRecord(null, h.nautaReceipt("WRONG4").replace("Banco Bandec", "Banco Popular de Ahorro"), h.clock, 7, sentAt = h.clock),
                BankSmsRecord(null, h.nautaReceipt("WRONG5"), h.clock.plusSeconds(60), 7, sentAt = h.clock.plusSeconds(60)),
                BankSmsRecord(null, h.nautaReceipt("WRONG6"), Instant.ofEpochMilli(start - 1), 7, sentAt = Instant.ofEpochMilli(start - 1)),
                BankSmsRecord(null, h.nautaReceipt("WRONG7").replace("Monto Pagado: 270 CUP. ", ""), h.clock, 7, sentAt = h.clock),
                BankSmsRecord(null, h.nautaReceipt("WRONG8").replace("270 CUP", "301 CUP"), h.clock, 7, sentAt = h.clock),
                BankSmsRecord(null, h.nautaReceipt("WRONG9").replace("Nauta Hogar:", ":").replace("pagada", "recargada"), h.clock, 7, sentAt = h.clock),
            )
            for (record in cases) {
                h.observe(record)
                check(h.repository.snapshot().operations.single { it.specId == request.operationId }.status != OperationStatus.CONFIRMED)
            }
            h.observe(BankSmsRecord(null, h.nautaReceipt("NAUTAOK"), h.clock, 7, sentAt = h.clock))
            check(h.repository.snapshot().operations.single { it.specId == request.operationId }.status == OperationStatus.CONFIRMED)
        }
        case("A delayed Nauta receipt does not choose between two identical submitted requests", ProviderId.BANDEC) { h ->
            val request = h.nautaRequest()
            h.main { h.executor.execute(request, h.target(), "12345".toCharArray(), approved = true) }
            h.authenticate(); h.await { h.modem.services == listOf(40, 84) }
            h.main { h.modem.reply(UssdResult.NetworkFailure(-1)) }
            h.await { h.wallet.snapshot.operations.any { it.specId == request.operationId && it.status == OperationStatus.UNCERTAIN } }
            h.repository.snapshot().operations.single { it.specId == request.operationId }.let { h.repository.putOperation(it.copy(reviewRequired = false)) }
            h.await { h.wallet.snapshot.operations.single { it.specId == request.operationId }.reviewRequired.not() }
            h.clock = h.clock.plusSeconds(1)
            h.main { h.executor.execute(request, h.target(), "12345".toCharArray(), approved = true) }
            h.await { h.modem.services == listOf(40, 84, 84) }
            h.main { h.modem.reply(Harness.processing) }
            h.clock = h.clock.plusSeconds(1)
            h.observe(BankSmsRecord(null, h.nautaReceipt("DELAYED1"), h.clock, 7, sentAt = h.clock))
            check(h.repository.snapshot().operations.filter { it.specId == request.operationId }.let { it.size == 2 && it.none { it.status == OperationStatus.CONFIRMED } })
            check(h.repository.snapshot().receipts.single().operationId == null)
        }
        case("Restored service evidence cannot confirm a live Nauta request", ProviderId.BANDEC) { h ->
            val request = h.nautaRequest()
            h.main { h.executor.execute(request, h.target(), "12345".toCharArray(), approved = true) }
            h.authenticate(); h.await { h.modem.services == listOf(40, 84) }
            h.main { h.modem.reply(Harness.processing) }
            h.clock = h.clock.plusSeconds(1)
            val body = h.nautaReceipt("RESTORED1")
            h.repository.importSnapshot(WalletSnapshot(events = listOf(EventRecord("old-event", EventSource.BROADCAST, null, "PAGOxMOVIL", body,
                h.clock.toEpochMilli(), 7, "old-event")), receipts = listOf(ReceiptRecord("old-receipt", "old-event", "02", 7,
                "PAYMENT", "RESTORED1", "270", "CUP", "Nauta Hogar · fixture@nauta.com.cu", nominalAmount = "300"))))
            val message = checkNotNull(BankSmsParser().parse("PAGOxMOVIL", body))
            val forgedEligibility = SmsIngestResult(IngestResult("old-event", "old-event", "old-receipt", false), message,
                FinancialMovement.from(message), evidenceEligible = true)
            h.observe(BankSmsRecord(null, body, h.clock, 7, sentAt = h.clock), forgedEligibility)
            check(h.repository.snapshot().operations.single { it.specId == request.operationId }.status != OperationStatus.CONFIRMED)
        }
        case("Hogar wording cannot choose a recharge over an unresolved debt payment", ProviderId.BANDEC) { h ->
            val recharge = h.nautaRequest()
            val debt = ServiceRequest("service.nauta.debt", recharge.identity, recharge.source, recharge.currency, recharge.values)
            h.main { h.executor.execute(debt, h.target(), "12345".toCharArray(), approved = true) }
            h.authenticate(); h.await { h.modem.services == listOf(40, 86) }
            h.main { h.modem.reply(UssdResult.NetworkFailure(-1)) }
            h.await { h.wallet.snapshot.operations.any { it.specId == debt.operationId && it.status == OperationStatus.UNCERTAIN } }
            h.repository.snapshot().operations.single { it.specId == debt.operationId }.let { h.repository.putOperation(it.copy(reviewRequired = false)) }
            h.await { !h.wallet.snapshot.operations.single { it.specId == debt.operationId }.reviewRequired }
            h.clock = h.clock.plusSeconds(1)
            h.observe(BankSmsRecord(null, h.nautaReceipt("DEBT1"), h.clock, 7, sentAt = h.clock))
            check(h.repository.snapshot().operations.single { it.specId == debt.operationId }.status == OperationStatus.UNCERTAIN)
            h.main { h.executor.execute(recharge, h.target(), "12345".toCharArray(), approved = true) }
            h.await { h.modem.services == listOf(40, 86, 84) }
            h.main { h.modem.reply(Harness.processing) }
            h.clock = h.clock.plusSeconds(1)
            h.observe(BankSmsRecord(null, h.nautaReceipt("AMBIGUOUS1"), h.clock, 7, sentAt = h.clock))
            check(h.repository.snapshot().operations.filter { it.specId in setOf(debt.operationId, recharge.operationId) }.none { it.status == OperationStatus.CONFIRMED })
            check(h.repository.snapshot().receipts.all { it.operationId == null })
        }
        case("Stamp receipt identifies payer and entity before completing the bank request", ProviderId.BANDEC) { h ->
            val request = ServiceRequest("service.stamp", h.target().identity, currency = Currency.CUP, values = mapOf(
                "municipality" to "2101", "taxpayer" to "00000000000", "taxCode" to "1", "period" to "9/2026", "amount" to "50", "entity" to "95016"))
            h.main { h.executor.execute(request, h.target(), "12345".toCharArray(), approved = true) }
            h.authenticate(); h.await { h.modem.services == listOf(40, 43) }
            h.main { h.modem.reply(Harness.processing) }
            h.clock = h.clock.plusSeconds(1)
            val body = "Banco Bandec El pago del impuesto sobre el documento (sello del timbre) fue completado.\nCI: 00000000000\nEntidad: Oficinas Tramites MININT\nValor del sello: 50 CUP\nImporte Pagado: 50 CUP\nNro. Transaccion Banco: STAMP01\nIdSello: 000000000001."
            for (bad in listOf(body.replace("CI: 00000000000", "CI: 00000000001"),
                body.replace("Oficinas Tramites MININT", "Otros tramites"), body.replace("IdSello: 000000000001.", ""),
                body.replace("CI: 00000000000", ""), body.replace("Nro. Transaccion Banco: STAMP01", ""))) {
                h.observe(BankSmsRecord(null, bad.replace("STAMP01", "BAD" + bad.hashCode().toUInt()), h.clock, 7, sentAt = h.clock))
                check(h.repository.snapshot().operations.single { it.specId == request.operationId }.status != OperationStatus.CONFIRMED)
            }
            h.observe(BankSmsRecord(null, body, h.clock, 7, sentAt = h.clock))
            check(h.repository.snapshot().operations.single { it.specId == request.operationId }.status == OperationStatus.CONFIRMED)
        }
        case("MiTurno rechecks the date after authentication crosses midnight", ProviderId.BANDEC) { h ->
            val day = java.time.LocalDate.of(2026, 9, 23)
            h.clock = day.atTime(23, 59, 59).atZone(java.time.ZoneId.systemDefault()).toInstant()
            fun request(date: String) = ServiceRequest("service.miturno.change", h.target().identity,
                values = mapOf("identity" to "00000000001", "serviceType" to "1", "date" to date))
            h.main { h.executor.execute(request("22/09/2026"), h.target(), "12345".toCharArray(), approved = true) }
            check(h.main { h.modem.services.isEmpty() })
            h.main { h.executor.execute(request("23/09/2026"), h.target(), "12345".toCharArray(), approved = true) }
            h.authenticate(); h.await { !h.executor.busy }
            check(h.main { h.modem.services == listOf(40) })
            check(h.repository.snapshot().operations.none { it.specId == "service.miturno.change" })
            h.main { h.executor.execute(request("24/09/2026"), h.target(), "12345".toCharArray(), approved = true) }
            h.await { h.modem.services == listOf(40, 105) }
            h.main { h.modem.reply(UssdResult.NetworkFailure(-1)) }
        }
        case("Conflicting stamp identities under one bank reference are not collapsed as duplicate facts", ProviderId.BANDEC) { h ->
            val body = "Banco Bandec El pago del impuesto sobre el documento (sello del timbre) fue completado.\nCI: 00000000000\nEntidad: Oficinas Tramites MININT\nValor del sello: 50 CUP\nImporte Pagado: 50 CUP\nNro. Transaccion Banco: SAMESTAMP\nIdSello: 000000000001."
            h.observe(BankSmsRecord(null, body, h.clock, 7, sentAt = h.clock))
            h.clock = h.clock.plusSeconds(1)
            h.observe(BankSmsRecord(null, body.replace("CI: 00000000000", "CI: 00000000001").replace("IdSello: 000000000001", "IdSello: 000000000002"), h.clock, 7, sentAt = h.clock))
            val receipts = h.repository.snapshot().receipts
            check(receipts.size == 2 && receipts.all { it.referenceConflict && it.operationId == null })
            check(receipts.map { it.purchaseId }.toSet() == setOf("000000000001", "000000000002"))
            check(receipts.all { it.account == null })
        }
        case("A locked controller protects a received coupon without a bank PIN or a purchase in progress", ProviderId.BANDEC) { h ->
            val controller = h.controller(h.fuelProtector)
            h.await { !controller.busy }
            var finished = false
            h.main { controller.receive(BankSmsRecord(null, h.fuelBody(), h.clock, 7, sentAt = h.clock)) { finished = true } }
            h.await { finished }
            check(h.main { !controller.unlocked && controller.history.isEmpty() && h.modem.services.isEmpty() })
            val stored = h.repository.snapshot()
            val coupon = stored.fuelCoupons.single()
            check(stored.events.single().let { it.body == null && it.bodyWithheld })
            val protected = h.repository.protectedFuelEnvelopes(listOf(coupon)).single()
            check(protected.blob.startsWith("1:") && !protected.blob.contains(Harness.fuelEnvelope))
            check(stored.receipts.single().operationId == null && stored.operations.isEmpty())
        }
        case("Fuel purchase evidence arriving before the callback waits for its valid acknowledgement", ProviderId.BANDEC) { h ->
            h.beginFuel()
            h.clock = h.clock.plusSeconds(1)
            h.observeFuel(BankSmsRecord(null, h.fuelBody(), h.clock, 7, sentAt = h.clock))
            check(h.repository.snapshot().operations.single { it.specId == "service.fuel" }.status != OperationStatus.CONFIRMED)
            check(h.repository.snapshot().fuelObservations.single().purchaseEvidence != null)
            h.main { h.modem.reply(Harness.processing) }
            h.await { h.wallet.snapshot.operations.single { it.specId == "service.fuel" }.status == OperationStatus.CONFIRMED }
            val operation = h.repository.snapshot().operations.single { it.specId == "service.fuel" }
            check(operation.currency == "CUP" && operation.parameters["amountCurrency"] == "1")
            check(h.repository.snapshot().receipts.single().operationId == operation.id)
            h.clock = h.clock.plusSeconds(1)
            h.observeFuel(BankSmsRecord(null, h.fuelBody(), h.clock, 7, sentAt = h.clock))
            check(h.repository.snapshot().receipts.size == 1 && h.repository.snapshot().movements.size == 1)
        }
        for (late in listOf(false, true)) case("A ${if (late) "late" else "timely"} acknowledged fuel receipt persists with presentation limited to its wait", ProviderId.BANDEC) { h ->
            h.beginFuel(); h.main { h.modem.reply(Harness.processing) }
            h.await { h.wallet.snapshot.operations.single { it.specId == "service.fuel" }.status == OperationStatus.AWAITING_CONFIRMATION }
            val sent = h.clock.plusSeconds(1)
            if (late) {
                h.elapseTimeout()
                h.await { h.wallet.snapshot.operations.single { it.specId == "service.fuel" }.timeoutAt != null }
            }
            val before = h.main { h.results.toList() }
            h.clock = h.clock.plusSeconds(2)
            h.observeFuel(BankSmsRecord(null, h.fuelBody(), h.clock, 7, sentAt = sent))
            h.await { h.wallet.snapshot.operations.single { it.specId == "service.fuel" }.status == OperationStatus.CONFIRMED }
            check(h.repository.snapshot().receipts.single().operationId != null)
            check(h.main { if (late) h.results == before else h.results == before + "Compra del cupón confirmada por el comprobante del banco." })
            check(h.main { h.modem.services == listOf(40, 35) })
        }
        case("A received coupon cannot override a rejected purchase callback", ProviderId.BANDEC) { h ->
            h.beginFuel()
            h.clock = h.clock.plusSeconds(1)
            h.observeFuel(BankSmsRecord(null, h.fuelBody(), h.clock, 7, sentAt = h.clock))
            h.main { h.modem.reply(UssdResult.Response("Solicitud rechazada")) }
            h.await { !h.executor.busy }
            h.clock = h.clock.plusSeconds(1)
            h.observeFuel(BankSmsRecord(null, h.fuelBody(), h.clock, 7, sentAt = h.clock))
            check(h.repository.snapshot().operations.single { it.specId == "service.fuel" }.status != OperationStatus.CONFIRMED)
            check(h.repository.snapshot().fuelCoupons.size == 1 && h.repository.snapshot().receipts.single().operationId == null)
        }
        case("Fuel confirmation expires when its callback is missing or arrives outside the local evidence window", ProviderId.BANDEC) { h ->
            h.beginFuel()
            h.clock = h.clock.plusSeconds(1)
            h.observeFuel(BankSmsRecord(null, h.fuelBody(), h.clock, 7, sentAt = h.clock))
            check(h.repository.snapshot().receipts.single().operationId == null)
            h.clock = h.clock.plusSeconds(31)
            h.main { h.modem.reply(Harness.processing) }
            h.await { !h.executor.busy }
            h.observeFuel(BankSmsRecord(null, h.fuelBody(), h.clock, 7, sentAt = h.clock))
            check(h.repository.snapshot().operations.single { it.specId == "service.fuel" }.status != OperationStatus.CONFIRMED)
            check(h.main { h.modem.services == listOf(40, 35) })
        }
        case("Fuel status renewal list and coupon credit are observations rather than bank purchase receipts", ProviderId.BANDEC) { h ->
            h.beginFuel(); h.main { h.modem.reply(Harness.processing) }
            for ((header, label) in listOf("Estado del cupon de combustible con" to "Saldo actual",
                "Actualizado el token del cupon de combustible con" to "Saldo actual",
                "Se ha realizado una devolucion al cupon de combustible con" to "Importe acreditado")) {
                h.clock = h.clock.plusSeconds(1)
                h.observeFuel(BankSmsRecord(null, h.fuelBody(header = header, amountLabel = label), h.clock, 7, sentAt = h.clock))
                check(h.repository.snapshot().operations.single { it.specId == "service.fuel" }.status != OperationStatus.CONFIRMED)
            }
            h.clock = h.clock.plusSeconds(1)
            val body = "Mis cupones de combustible.\nNo.Serie;Saldo actual;Importe pagado;Moneda;ID Banco;ID TM;Fecha;Banco;DatosCupon\n" +
                "0000000000002;10.00;10.00;CUP;BANK2;TM2;23/09/2026;BANDEC;${Harness.fuelEnvelope}"
            h.observeFuel(BankSmsRecord(null, body, h.clock, 7, sentAt = h.clock))
            check(h.repository.snapshot().operations.single { it.specId == "service.fuel" }.status != OperationStatus.CONFIRMED)
            check(h.repository.snapshot().receipts.none { it.operationId != null })
            h.clock = h.clock.plusSeconds(1)
            h.observeFuel(BankSmsRecord(null, h.fuelBody(serial = "0000000000003", reference = "BANK3"), h.clock, 7, sentAt = h.clock))
            check(h.repository.snapshot().operations.single { it.specId == "service.fuel" }.status == OperationStatus.CONFIRMED)
        }
        case("Fuel rejects mismatched bank SIM amount currency and nonfresh evidence without deriving a discount", ProviderId.BANDEC) { h ->
            h.beginFuel(); h.main { h.modem.reply(Harness.processing) }
            val started = h.repository.snapshot().operations.single { it.specId == "service.fuel" }.startedAt
            h.clock = h.clock.plusSeconds(1)
            val messages = listOf(
                BankSmsRecord(null, h.fuelBody(serial = "0000000000001", reference = "WRONG1", bank = "BPA"), h.clock, 7, sentAt = h.clock),
                BankSmsRecord(null, h.fuelBody(serial = "0000000000002", reference = "WRONG2"), h.clock, 8, sentAt = h.clock),
                BankSmsRecord(null, h.fuelBody(serial = "0000000000003", reference = "WRONG3", amount = "9.00"), h.clock, 7, sentAt = h.clock),
                BankSmsRecord(null, h.fuelBody(serial = "0000000000004", reference = "WRONG4", currency = "USD"), h.clock, 7, sentAt = h.clock),
                BankSmsRecord(null, h.fuelBody(serial = "0000000000005", reference = "WRONG5"), h.clock.plusSeconds(60), 7, sentAt = h.clock.plusSeconds(60)),
                BankSmsRecord(null, h.fuelBody(serial = "0000000000006", reference = "WRONG6"), Instant.ofEpochMilli(started - 1), 7, sentAt = Instant.ofEpochMilli(started - 1)),
            )
            for (record in messages) {
                h.observeFuel(record)
                check(h.repository.snapshot().operations.single { it.specId == "service.fuel" }.status != OperationStatus.CONFIRMED)
            }
        }
        case("A late fuel purchase cannot choose the newer of two identical requests after uncertainty was reviewed", ProviderId.BANDEC) { h ->
            h.beginFuel(); h.main { h.modem.reply(Harness.processing) }
            h.await { h.wallet.snapshot.operations.single { it.specId == "service.fuel" }.status == OperationStatus.AWAITING_CONFIRMATION }
            val first = h.repository.snapshot().operations.single { it.specId == "service.fuel" }
            check(h.repository.updateOperationStatus(first.id, first.status, OperationStatus.UNCERTAIN, h.clock.toEpochMilli()))
            h.repository.putOperation(first.copy(status = OperationStatus.UNCERTAIN, reviewRequired = false))
            h.await { h.wallet.snapshot.operations.single { it.specId == "service.fuel" }.status == OperationStatus.UNCERTAIN }
            h.clock = h.clock.plusSeconds(31)
            h.main { h.executor.execute(h.fuelRequest(), h.target(), "12345".toCharArray(), approved = true) }
            h.await { h.modem.services == listOf(40, 35, 35) }
            h.main { h.modem.reply(Harness.processing) }
            h.clock = h.clock.plusSeconds(1)
            h.observeFuel(BankSmsRecord(null, h.fuelBody(), h.clock, 7, sentAt = h.clock))
            check(h.repository.snapshot().operations.filter { it.specId == "service.fuel" }.let { it.size == 2 && it.none { op -> op.status == OperationStatus.CONFIRMED } })
            check(h.repository.snapshot().receipts.single().operationId == null)
        }
        case("An automatic balance shares the original deadline and cannot extend a timeout") { h ->
            val started = h.clock
            val operation = h.acknowledgedTransfer()
            h.elapseRefresh()
            h.await { h.modem.services == listOf(40, 45, 46) }
            check(h.main { h.latestTimeoutDeadline() == started.plusSeconds(30) })
            h.elapseTimeout(19)
            h.await { !h.executor.busy && h.modem.isIdle() }
            val operations = h.repository.snapshot().operations
            check(operations.single { it.id == operation.id }.timeoutAt != null)
            check(operations.single { it.parameters["refreshOf"] == operation.id }.timeoutAt != null)
            check(h.main { h.results.count { it == "Tiempo de espera agotado" } == 1 })
            h.elapseRefresh(60)
            check(h.main { h.modem.services == listOf(40, 45, 46) })
        }
        case("Post-operation balance runs once at ten seconds for the captured source and stays silent") { h ->
            val operation = h.acknowledgedTransfer()
            val before = h.main { h.results.toList() }
            val oldTimer = h.main { h.refreshCallbacks.single().second }
            h.elapseRefresh(9)
            check(h.main { h.modem.services == listOf(40, 45) })
            check(!h.repository.snapshot().operations.single { it.id == operation.id }.refreshTaken)
            h.elapseRefresh(1)
            h.await { h.modem.services == listOf(40, 45, 46) }
            val query = h.repository.snapshot().operations.single { it.parameters["refreshOf"] == operation.id }
            check(query.source == operation.source && query.subscriptionId == operation.subscriptionId && query.providerId == operation.providerId)
            check(h.repository.snapshot().operations.single { it.id == operation.id }.refreshTaken)
            h.main { h.modem.reply(Harness.processing) }
            val (record, result) = h.balanceEvidence("0000XXXXXXXX0001", "CUP", bank = "Popular de Ahorro")
            h.observe(record, result)
            check(h.repository.snapshot().operations.single { it.id == query.id }.status == OperationStatus.CONFIRMED)
            check(h.repository.snapshot().balances.single().available == "900.00")
            h.elapseRefresh(90)
            h.main { oldTimer(); check(h.results == before && h.modem.services == listOf(40, 45, 46)) }
        }
        case("A busy explicit query defers the one-shot refresh without redirecting its source") { h ->
            val operation = h.acknowledgedTransfer()
            h.main { h.executor.execute(ServiceRequest("bpa.balance", h.target().identity,
                SourceSelector.Explicit("0000000000000003")), h.target(), null, approved = false) }
            h.await { h.modem.services == listOf(40, 45, 46) }
            h.elapseRefresh()
            check(h.main { h.executor.busy && h.modem.services == listOf(40, 45, 46) })
            check(!h.repository.snapshot().operations.single { it.id == operation.id }.refreshTaken)
            h.main { h.modem.reply(UssdResult.NetworkFailure(-1)) }
            h.await { h.modem.services == listOf(40, 45, 46, 46) }
            check(h.repository.snapshot().operations.single { it.parameters["refreshOf"] == operation.id }.source == "0000000000000001")
        }
        for (reason in listOf("latched", "changed access", "restored", "invalidated"))
            case("Post-operation refresh is discarded when $reason") { h ->
                val operation = h.acknowledgedTransfer()
                when (reason) {
                    "latched" -> h.repository.putOperation(operation.copy(refreshTaken = true))
                    "changed access" -> h.main { h.generation++ }
                    "restored" -> h.repository.putOperation(operation.copy(restored = true, status = OperationStatus.UNCERTAIN))
                    else -> h.main { h.executor.invalidate() }
                }
                h.elapseRefresh()
                var drained = false
                h.main { h.wallet.transact(block = { Unit }, onSuccess = { drained = true }) }
                h.await { drained }
                check(h.main { h.modem.services == listOf(40, 45) })
                check(h.repository.snapshot().operations.none { it.parameters["refreshOf"] == operation.id })
            }
        case("Recreation never rebuilds refreshes and an old timer cannot activate a new queue") { h ->
            val old = h.acknowledgedTransfer()
            val oldTimer = h.main { h.refreshCallbacks.single().second }
            h.recreateExecutor()
            h.elapseRefresh(60)
            check(h.main { h.modem.services.isEmpty() })
            check(!h.repository.snapshot().operations.single { it.id == old.id }.refreshTaken)
            val current = h.acknowledgedTransfer()
            h.clock = h.clock.plusSeconds(10)
            h.main { oldTimer(); check(h.modem.services == listOf(40, 45)) }
            h.elapseRefresh(0)
            h.await { h.modem.services == listOf(40, 45, 46) }
            check(h.repository.snapshot().operations.single { it.parameters["refreshOf"] == current.id }.source == current.source)
        }
        case("An intervening provider login cancels the former provider's queued refresh") { h ->
            val operation = h.acknowledgedTransfer()
            val other = h.registration.copy(id = "other-bank", providerId = "BANDEC", bankCode = "02", credentialAlias = "access:other-bank")
            h.repository.putRegistration(other)
            h.await { h.wallet.snapshot.registrations.size == 2 }
            val target = ServiceExecutionContext(other.id, ProviderIdentity(ProviderId.BANDEC), 7, null, other)
            h.main { h.executor.execute(ServiceRequest("bandec.balance", target.identity), target, "12345".toCharArray(), approved = false) }
            h.await { h.modem.services == listOf(40, 45, 40) }
            h.elapseRefresh()
            h.main { h.modem.reply(UssdResult.NetworkFailure(-1)) }
            h.await { !h.executor.busy }
            check(h.main { h.modem.services == listOf(40, 45, 40) })
            check(!h.repository.snapshot().operations.single { it.id == operation.id }.refreshTaken)
        }
        case("Invalidating while the refresh latch is queued cannot dispatch a read afterwards") { h ->
            val operation = h.acknowledgedTransfer()
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            h.main { h.wallet.transact(block = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)) }) }
            try {
                check(entered.await(10, TimeUnit.SECONDS))
                h.elapseRefresh()
                h.main { check(h.executor.busy); h.executor.invalidate() }
            } finally { release.countDown() }
            h.await { h.wallet.snapshot.operations.single { it.id == operation.id }.refreshTaken }
            check(h.main { !h.executor.busy && h.modem.services == listOf(40, 45) })
            check(h.repository.snapshot().operations.none { it.parameters["refreshOf"] == operation.id })
        }
        for (known in listOf(false, true)) case("Monetary service refresh requires a ${if (known) "known" else "missing"} explicit origin") { h ->
            val request = ServiceRequest("service.nauta.home", h.target().identity,
                if (known) SourceSelector.Explicit("0000000000000001") else SourceSelector.Default,
                Currency.CUP, mapOf("username" to "fixture", "accountType" to "1", "amount" to "300"))
            h.main { h.executor.execute(request, h.target(), "1234".toCharArray(), approved = true) }
            h.authenticate(); h.await { h.modem.services == listOf(40, 84) }
            h.main { h.modem.reply(Harness.processing) }
            h.await { !h.executor.busy }
            h.elapseRefresh()
            if (known) h.await { h.modem.services == listOf(40, 84, 46) }
            else check(h.main { h.modem.services == listOf(40, 84) && h.refreshCallbacks.isEmpty() })
        }
        case("BANDEC refresh reads the proven origin without selecting it again", ProviderId.BANDEC) { h ->
            h.main { h.executor.execute(h.bankTransfer(), h.target(), "12345".toCharArray(), approved = true) }
            h.authenticate(); h.await { h.modem.services == listOf(40, 60) }; h.acknowledgeSelectionAndRead()
            h.balance("0000XXXXXXXX0001", "CUP")
            h.await { h.modem.services == listOf(40, 60, 46, 45) }
            h.main { h.modem.reply(Harness.processing) }
            h.await { !h.executor.busy }
            h.elapseRefresh()
            h.await { h.modem.services == listOf(40, 60, 46, 45, 46) }
            check(h.repository.snapshot().operations.single { it.parameters["refreshOf"] != null }.source == "0000000000000001")
        }
        return passed
    }

    private class Harness(private val context: Context, val repository: WalletRepository, val registration: RegistrationRecord) {
        val testContext: Context get() = context
        private val handler = Handler(Looper.getMainLooper())
        var clock: Instant = Instant.parse("2026-09-23T12:00:00Z")
        var generation = 0L
        lateinit var wallet: WalletCoordinator
        lateinit var executor: OperationExecutor
        lateinit var modem: FakeModem
        val nativeRequests = mutableListOf<SystemDialRequest>()
        val results = mutableListOf<String>()
        val confirmations = mutableListOf<OperationRecord>()
        private val timeoutCallbacks = mutableListOf<Pair<Instant, () -> Unit>>()
        val refreshCallbacks = mutableListOf<Pair<Instant, () -> Unit>>()
        private val controllers = mutableListOf<BankController>()
        val fuelProtector = FuelSecretStore { javax.crypto.spec.SecretKeySpec(ByteArray(32) { it.toByte() }, "AES") }
        init {
            main {
                wallet = WalletCoordinator(context, repository)
                createExecutor()
            }
            await { wallet.ready && !executor.busy }
        }
        private fun createExecutor() {
            modem = FakeModem { check(wallet.snapshot.operations.any { it.status == OperationStatus.SUBMITTING }) {
                "El módem recibió una orden sin persistencia previa"
            }
                wallet.snapshot.operations.filter { it.status == OperationStatus.SUBMITTING && it.parameters["refreshOf"] != null }.forEach { query ->
                    check(wallet.snapshot.operations.single { it.id == query.parameters["refreshOf"] }.refreshTaken) {
                        "La consulta automática llegó al módem antes de persistir su latch"
                    }
                }
            }
            executor = OperationExecutor(wallet, modem, { clock }, { true }, { results += it }, { nativeRequests += it; it.complete(false) }, { operation, _ -> confirmations += operation },
                accessGeneration = { generation },
                scheduleTimeout = { milliseconds, callback ->
                    check(milliseconds in 1..30_000L)
                    timeoutCallbacks += clock.plusMillis(milliseconds) to callback
                }, scheduleBalanceRefresh = { milliseconds, callback ->
                    check(milliseconds == 10_000L)
                    refreshCallbacks += clock.plusMillis(milliseconds) to callback
                })
        }
        fun recreateExecutor() {
            main { executor.close(); timeoutCallbacks.clear(); refreshCallbacks.clear(); createExecutor() }
            await { !executor.busy }
        }
        fun elapseRefresh(seconds: Long = 10) {
            clock = clock.plusSeconds(seconds)
            main {
                val due = refreshCallbacks.filter { it.first <= clock }
                refreshCallbacks.removeAll(due.toSet())
                due.forEach { it.second() }
            }
        }
        fun target() = ServiceExecutionContext(registration.id, ProviderIdentity(ProviderId.valueOf(registration.providerId)), 7, null, registration, accessGeneration = generation)
        fun controller(protector: FuelEnvelopeProtector? = null) = main {
            BankController(context, modem, { emptyList() }, { listOf(SimChoice(7, "Fixture")) }, { clock }, wallet, protector).also(controllers::add)
        }
        fun unlock(controller: BankController) {
            val credentials = AccessCredentials.empty().use { it.scoped["access:registration"] = "1234".toCharArray(); it.encode() }
            main { controller.unlock(credentials) }; await { !controller.busy }
        }
        fun bankTransfer(source: SourceSelector = SourceSelector.Explicit("0000000000000001"), amount: String = "1.00") =
            ServiceRequest("${registration.providerId.lowercase()}.transfer", target().identity, source, Currency.CUP,
                mapOf("destination" to "0000000000000002", "amount" to amount))
        fun acknowledgedTransfer(): OperationRecord {
            main { executor.execute(bankTransfer(), target(), "1234".toCharArray(), approved = true) }
            authenticate(); await { modem.services == listOf(40, 45) }
            main { modem.reply(processing) }
            await { !executor.busy && wallet.snapshot.operations.any { it.specId == "bpa.transfer" && it.status == OperationStatus.AWAITING_CONFIRMATION } }
            return repository.snapshot().operations.single { it.specId == "bpa.transfer" && it.status == OperationStatus.AWAITING_CONFIRMATION }
        }
        fun nautaRequest() = ServiceRequest("service.nauta.home", target().identity, currency = Currency.CUP,
            values = mapOf("username" to "fixture", "accountType" to "1", "amount" to "300"))
        fun nautaReceipt(reference: String) = "Banco Bandec: La cuenta Nauta Hogar: fixture@nauta.com.cu ha sido pagada con 300 CUP. Monto Pagado: 270 CUP. Id Transaccion: $reference."
        fun observe(record: BankSmsRecord, result: SmsIngestResult = SmsIngestor(repository, now = { clock }).ingest(record)) {
            var finished = false
            main { executor.observed(record, result); wallet.transact(block = { Unit }, onSuccess = { finished = true }) }
            await { finished }
        }
        fun fuelRequest() = ServiceRequest("service.fuel", target().identity, currency = Currency.CUP, values = mapOf("amount" to "10"))
        fun beginFuel() {
            main { executor.execute(fuelRequest(), target(), "12345".toCharArray(), approved = true) }
            authenticate(); await { modem.services == listOf(40, 35) }
        }
        fun fuelBody(serial: String = "0000000000001", reference: String = "BANK1", amount: String = "10.00", currency: String = "CUP",
                     bank: String = "Bandec", header: String = "El pago del cupon de combustible fue completado.", amountLabel: String = "Importe pagado") =
            "Banco $bank: $header\nID TM: TM1.\nNo. Transaccion : $reference.\nNo. Serie: $serial.\n$amountLabel: $amount $currency\nDatos del cupon: $fuelEnvelope"
        fun observeFuel(record: BankSmsRecord) = observe(record, SmsIngestor(repository, now = { clock }, fuelProtector = fuelProtector).ingest(record))
        fun addCard() {
            repository.putCard(CardRecord("card", registration.id, "0000000000000001", "Fixture CUP", currency = "CUP"))
            await { wallet.snapshot.cards.any { it.id == "card" } }
        }
        fun authenticate() {
            await { modem.services == listOf(40) }
            main { modem.reply(processing) }
            clock = clock.plusSeconds(1)
            val record = authRecord()
            val result = SmsIngestor(repository, now = { clock }).ingest(record)
            main { executor.observed(record, result) }
        }
        fun authRecord(sentAt: Instant? = clock, sim: Int = 7): BankSmsRecord {
            val place = when (registration.providerId) {
                "MITRANSFER" -> "el monedero MiTransfer"
                "BPA" -> "el Banco Popular de Ahorro"
                else -> "el Banco Bandec con la cuenta 0000XXXXXXXX0001"
            }
            return BankSmsRecord(null, "Usted se ha autenticado en la plataforma de pagos moviles, en $place, puede comenzar a utilizar nuestros servicios de pagos a traves del movil", clock, sim, sentAt = sentAt)
        }
        fun elapseTimeout(seconds: Long = 30) {
            clock = clock.plusSeconds(seconds)
            main {
                val due = timeoutCallbacks.filter { it.first <= clock }
                timeoutCallbacks.removeAll(due.toSet())
                due.forEach { it.second() }
            }
        }
        fun latestTimeoutDeadline(): Instant = timeoutCallbacks.maxOf { it.first }
        fun beginBandecQuery() {
            main { executor.queryBandecCard(target(), SourceSelector.Explicit("0000000000000001"), "12345".toCharArray()) }
            authenticate(); await { modem.services == listOf(40, 60) }
        }
        fun acknowledgeSelectionAndRead() {
            main { modem.reply(processing) }; await { modem.services == listOf(40, 60, 46) }
            main { modem.reply(processing) }
        }
        fun balanceEvidence(account: String, currency: String, sim: Int = 7, bank: String = "Bandec"): Pair<BankSmsRecord, SmsIngestResult> {
            clock = clock.plusSeconds(1)
            val record = BankSmsRecord(null, "Banco $bank La consulta de saldo fue completada.\nCuenta;Saldo Contable;Saldo Disponible;Moneda\n$account; CR 1000.00; CR 900.00;$currency |", clock, sim, sentAt = clock)
            return record to SmsIngestor(repository, now = { clock }).ingest(record)
        }
        fun balance(account: String, currency: String, sim: Int = 7, bank: String = "Bandec") {
            val (record, result) = balanceEvidence(account, currency, sim, bank)
            main { executor.observed(record, result) }
        }
        fun telecomTransfer() = ServiceRequest("cubacel.transfer", ProviderIdentity(ProviderId.CUBACEL),
            values = mapOf("mobile" to "50000001", "pin" to "1234", "amount" to "1.00"))
        fun <T> main(action: () -> T): T {
            if (Looper.myLooper() == Looper.getMainLooper()) return action()
            val latch = CountDownLatch(1)
            var result: Result<T>? = null
            handler.post { try { result = runCatching(action) } finally { latch.countDown() } }
            check(latch.await(10, TimeUnit.SECONDS)) { "La tarea del hilo principal no terminó" }
            return requireNotNull(result).getOrThrow()
        }
        fun await(condition: () -> Boolean) {
            check(Looper.myLooper() != Looper.getMainLooper())
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!main(condition)) {
                check(System.nanoTime() < deadline) { "No llegó el estado esperado del ejecutor" }
                Thread.sleep(10)
            }
        }
        fun close() {
            val complete = CountDownLatch(1)
            main { controllers.forEach { it.close() }; executor.close(); wallet.close { complete.countDown() } }
            check(complete.await(10, TimeUnit.SECONDS)) { "La cartera no terminó de cerrar su IO" }
        }
        companion object {
            val processing = UssdResult.Response("Su solicitud esta siendo procesada, espere un SMS")
            const val fuelEnvelope = "W3ePK80M6VKxq9C60HXAHg==|+yRrfQTQ/zLIJShacXR3ow=="
        }
    }

    private class FakeModem(val beforeSend: () -> Unit) : UssdTransport {
        val services = mutableListOf<Int>()
        private var callback: ((UssdResult) -> Unit)? = null
        private val abandoned = mutableListOf<(UssdResult) -> Unit>()
        override fun isIdle() = callback == null
        override fun send(command: UssdCommand, subscriptionId: Int, result: (UssdResult) -> Unit) {
            check(isIdle()); check(subscriptionId == 7); beforeSend()
            services += command.service; callback = result
        }
        override fun abandon(result: (UssdResult) -> Unit): Boolean {
            if (callback !== result) return false
            abandoned += result
            callback = null
            return true
        }
        fun replyAbandoned(result: UssdResult) { abandoned.last()(result) }
        fun reply(result: UssdResult) { val previous = checkNotNull(callback); callback = null; previous(result) }
    }
}
