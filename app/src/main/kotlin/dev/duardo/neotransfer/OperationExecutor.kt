package dev.duardo.neotransfer

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.duardo.neotransfer.core.*
import dev.duardo.neotransfer.core.miturno.MiTurnoContracts
import dev.duardo.neotransfer.data.*
import dev.duardo.neotransfer.platform.*
import java.time.Instant
import java.time.LocalTime
import java.util.UUID

/** Captured before the biometric prompt; never resolve a different registration after approval. */
data class ServiceExecutionContext(
    val registrationId: String?, val identity: ProviderIdentity, val subscriptionId: Int,
    val productId: String?, val registration: RegistrationRecord?,
    val authenticationIdentity: ProviderIdentity = identity,
    val productNumber: String? = null,
    val accessGeneration: Long = 0,
    val productCurrency: String? = null,
    val originalQr: QrPayment? = null,
)

class SystemDialRequest internal constructor(
    val executionContext: ServiceExecutionContext, val command: UssdCommand,
    val complete: (Boolean) -> Unit,
    val transport: OperationTransport = OperationTransport.SYSTEM_DIAL,
) { override fun toString() = "SystemDialRequest" }

internal class OperationExecutor(
    private val wallet: WalletCoordinator,
    private val gateway: UssdTransport,
    private val now: () -> Instant,
    private val canContinue: () -> Boolean,
    private val onResult: (String) -> Unit,
    private val dial: (SystemDialRequest) -> Unit,
    private val onConfirmed: (OperationRecord, BankMessage) -> Unit,
    private val accessGeneration: (String) -> Long = { 0 },
) {
    var busy by mutableStateOf(true); private set
    private val main = Handler(Looper.getMainLooper())
    private var epoch = 0
    private var session: ProviderSession? = null
    private var active: Execution? = null
    private var authentication: Authentication? = null
    private var cardQuery: CardQuery? = null
    private val evidenceRevision = mutableMapOf<Int, Long>()
    private val historyQueries = mutableListOf<HistoryQuery>()
    private val fuelPurchases = mutableListOf<FuelPurchaseWait>()
    private data class SessionEvidence(val identity: ProviderIdentity?, val at: Instant, val eventId: String)
    private val latestEvidence = mutableMapOf<Int, SessionEvidence>()

    init {
        wallet.transact("No se pudo recuperar el estado de las solicitudes", block = {
            snapshot().operations.forEach { operation -> when (operation.status) {
                OperationStatus.PREPARED -> updateOperationStatus(operation.id, operation.status, OperationStatus.CANCELLED, now().toEpochMilli())
                OperationStatus.SUBMITTING, OperationStatus.AWAITING_CONFIRMATION -> updateOperationStatus(operation.id, operation.status, OperationStatus.UNCERTAIN, now().toEpochMilli())
                else -> Unit
            } }
        }, onSuccess = { busy = false }, onFailure = { busy = false })
    }

    private class Execution(
        val request: ServiceRequest, val context: ServiceExecutionContext, val pin: CharArray?,
        val token: Int, val legacy: MoneyAction?, val prepared: ((OperationRecord) -> Unit)?,
        val wire: List<UssdCommand>?,
    ) { var originProof: OriginProof? = null }
    private data class OriginProof(val source: SourceSelector, val currency: Currency, val revision: Long)
    private class CardQuery(val execution: Execution, val forMoney: Boolean) {
        val readId = UUID.randomUUID().toString()
        val selectId = if (execution.request.source is SourceSelector.Explicit) UUID.randomUUID().toString() else null
        var startedReading: Instant? = null
        var accepted = false
        var completing = false
        var evidence: Pair<BankSmsRecord, SmsIngestResult>? = null
    }
    private class Authentication(val operationId: String, val execution: Execution, val started: Instant,
                                 val balanceChallenge: Boolean = false) {
        var accepted = false
        var completing = false
        var evidence: Pair<BankSmsRecord, SmsIngestResult>? = null
    }
    private class HistoryQuery(val operationId: String, val context: ServiceExecutionContext, val sentAt: Instant) {
        var accepted = false
        var evidence: Pair<BankSmsRecord, SmsIngestResult>? = null
    }
    private class FuelPurchaseWait(val operationId: String, val subscriptionId: Int, val sentAt: Instant) {
        var accepted = false
        var settling = false
        val receiptIds = mutableSetOf<String>()
    }

    fun invalidate() {
        cardQuery?.let { flow -> flow.selectId?.let(::markUncertain); markUncertain(flow.readId) }; cardQuery = null
        epoch++; session = null; authentication = null
        active?.pin?.fill('\u0000'); active = null; busy = false
    }

    fun close() { invalidate(); fuelPurchases.clear(); main.removeCallbacksAndMessages(null) }

    fun queryBandecCard(context: ServiceExecutionContext, source: SourceSelector.Explicit, pin: CharArray) =
        execute(ServiceRequest("bandec.card-balance", ProviderIdentity(ProviderId.BANDEC), source), context, pin, approved = false)

    fun contextValid(value: ServiceExecutionContext): Boolean {
        if (value.subscriptionId < 0 || !OperationCatalog.supports(value.identity) || !OperationCatalog.supports(value.authenticationIdentity)) return false
        val registration = value.registrationId?.let { id -> wallet.snapshot.registrations.singleOrNull { it.id == id } }
        if (value.registrationId != null && (registration == null || registration != value.registration || !registration.enabled ||
            registration.subscriptionId != value.subscriptionId || registration.identity() != value.authenticationIdentity ||
            value.accessGeneration != accessGeneration(value.registrationId))) return false
        if (value.productNumber != null) {
            val card = wallet.snapshot.cards.singleOrNull { it.id == value.productId }
            val account = wallet.snapshot.accounts.singleOrNull { it.id == value.productId }
            if (card == null && account == null || (card?.number ?: account?.number) != value.productNumber ||
                (card?.currency ?: account?.currency) != value.productCurrency ||
                (card?.registrationId ?: account?.registrationId) != value.registrationId ||
                registration?.productIdentity(card?.profileId ?: account?.profileId) != value.identity) return false
        }
        return true
    }

    fun execute(request: ServiceRequest, executionContext: ServiceExecutionContext, pin: CharArray?, approved: Boolean,
                legacy: MoneyAction? = null, wire: List<UssdCommand>? = null,
                onPrepared: ((OperationRecord) -> Unit)? = null) {
        var created: Execution? = null
        try {
            val spec = requireNotNull(OperationCatalog.find(request.operationId)) { "Operación no disponible" }
            require(spec.transport in setOf(OperationTransport.ENCODED_USSD, OperationTransport.DIRECT_USSD, OperationTransport.SYSTEM_DIAL,
                OperationTransport.INTERACTIVE_USSD)) {
                "Esta operación utiliza otro canal de atención"
            }
            require(request.identity == executionContext.identity && contextValid(executionContext)) { "El origen ha cambiado. Revisa de nuevo la operación." }
            if (request.identity == ProviderIdentity(ProviderId.MITRANSFER) && executionContext.productCurrency != null &&
                request.currency != null) require(executionContext.productCurrency == request.currency?.name) {
                "La moneda de la bolsa no coincide con la operación revisada"
            }
            executionContext.registrationId?.let { id ->
                require(!OperationCatalog.needsUpdatedAccess(wallet.snapshot.operations, id, executionContext.accessGeneration)) {
                    "Actualiza la clave guardada de este acceso antes de continuar"
                }
            }
            require(executionContext.authenticationIdentity == (spec.authenticationIdentity ?: request.identity)) { "El acceso no corresponde al contrato de esta operación" }
            if (executionContext.authenticationIdentity != request.identity && spec.sourcePolicy != SourcePolicy.NONE &&
                request.identity.profile == ProfileId.CLASSIC)
                require(executionContext.productNumber != null) { "Selecciona el instrumento vinculado al registro MiTransfer" }
            require(!spec.requiresConfirmation || approved) { "Confirma esta operación con biometría" }
            require(!busy && gateway.isIdle()) { "Hay una solicitud en curso" }
            require(canContinue()) { "Desbloquea la aplicación para continuar" }
            val defaultBpaQuery = wire?.singleOrNull()?.valueForTransport() == "*444*46#" &&
                request.operationId == "bpa.balance" && request.source == SourceSelector.Default
            val errors = OperationCatalog.validate(request).filterNot { defaultBpaQuery && it.fieldKey == "currency" }
            require(errors.isEmpty()) { errors.joinToString("; ") { it.message } }
            if (spec.requiresConfirmation) require(wallet.snapshot.operations.none { operation ->
                operation.status in setOf(OperationStatus.PREPARED, OperationStatus.SUBMITTING, OperationStatus.AWAITING_CONFIRMATION) &&
                    OperationCatalog.find(operation.specId)?.requiresConfirmation == true
            }) { "Hay una operación pendiente de resultado. Revísala antes de continuar." }
            val execution = Execution(request, executionContext, pin?.copyOf(), epoch, legacy, onPrepared, wire)
            created = execution
            active = execution; busy = true
            validateOriginalQr(execution)
            val registrationId = executionContext.registrationId
            if (!spec.requiresSession || spec.service == 70 || registrationId != null &&
                session?.isValidFor(executionContext.authenticationIdentity, registrationId, executionContext.subscriptionId, now()) == true) {
                persistAndSend(execution, request, wire)
            } else {
                requireNotNull(execution.pin) { "Configura la clave del acceso seleccionado" }
                val authIdentity = executionContext.authenticationIdentity
                val authId = authIdentity.bank?.let { "${it.name.lowercase()}.authenticate" }
                    ?: "wallet.authenticate"
                val authSpec = requireNotNull(OperationCatalog.find(authId))
                require(authSpec.supports(authIdentity)) { "Este perfil necesita un acceso de sesión compatible" }
                val auth = ServiceRequest(authId, authIdentity, values = mapOf("pin" to String(execution.pin)))
                persistAndSend(execution, auth, authenticationStep = true)
            }
        } catch (failure: Exception) {
            created?.let(::finish)
            onResult(failure.message ?: "No se pudo preparar la operación")
        } finally { pin?.fill('\u0000') }
    }

    private fun alive(execution: Execution): Boolean = active === execution && execution.token == epoch &&
        canContinue() && contextValid(execution.context)

    private fun authorizedRequest(execution: Execution, request: ServiceRequest): ServiceRequest {
        val spec = requireNotNull(OperationCatalog.find(request.operationId))
        val supplied = spec.fields.filter { it.suppliedByAccess }
        if (supplied.isEmpty()) return request
        val pin = requireNotNull(execution.pin) { "Falta la clave del acceso autorizado" }
        return ServiceRequest(request.operationId, request.identity, request.source, request.currency,
            request.values + supplied.associate { it.key to String(pin) })
    }

    fun validateBindings(request: ServiceRequest, context: ServiceExecutionContext): List<OperationValidationError> =
        runCatching {
            require(contextValid(context)) { "El origen ha cambiado. Revisa de nuevo la operación." }
            validateOriginalQr(Execution(request, context, null, epoch, null, null, null))
            emptyList<OperationValidationError>()
        }.getOrElse { listOf(OperationValidationError("source", it.message ?: "La solicitud ha cambiado")) }

    private fun validateOriginalQr(execution: Execution) {
        val request = execution.request
        val date = now().atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        if (MiTurnoContracts.action(request.operationId) != null) {
            val errors = MiTurnoContracts.validateForExecution(request, date)
            require(errors.isEmpty()) { errors.joinToString("; ") { it.message } }
        }
        if (request.operationId == "service.cash.extra") {
            val qr = requireNotNull(execution.context.originalQr) { "Escanea el QR estático del comercio" }
            val errors = CashExtraOperations.validateOriginal(request, qr, date)
            require(errors.isEmpty()) { errors.joinToString("; ") { it.message } }
            return
        }
        val walletQr = WalletQrOperations.all.any { it.id == request.operationId }
        if (!walletQr && request.operationId != "bank.qr") return
        val qr = requireNotNull(execution.context.originalQr ?: execution.legacy?.qr) { "Escanea o importa el QR que deseas pagar" }
        val today = now().atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        qr.validateDate(today)
        if (request.operationId == "bank.qr") {
            val action = requireNotNull(execution.legacy) { "Faltan los datos originales del pago QR" }
            require(action.qr == qr && action.amount.currency == qr.amount.currency && action.amount.amount.signum() > 0 &&
                (qr.editableAmount || action.amount.sameValue(qr.amount)) && (qr.description.isEmpty() || action.description == qr.description)) {
                "Los datos del pago no coinciden con el QR revisado"
            }
            return
        }
        val amount = Money(requireNotNull(request.values["amount"]) { "Indica el importe" }.toBigDecimal(), qr.amount.currency)
        val canonical = WalletQrOperations.fromQr(request.identity, request.source, request.currency, qr, amount,
            request.values["description"] ?: qr.description, request.values["phone"]?.takeIf(String::isNotEmpty), today)
        require(canonical.operationId == request.operationId && canonical.source == request.source) { "La operación no corresponde al QR revisado" }
        for (key in setOf("transaction", "provider", "auxiliary", "qrId", "amountCurrency", "amount", "description", "phone")) {
            require(request.values[key].orEmpty() == canonical.values[key].orEmpty()) { "El campo $key no coincide con el QR revisado" }
        }
    }

    private fun persistAndSend(execution: Execution, request: ServiceRequest, wire: List<UssdCommand>? = null,
                               authenticationStep: Boolean = false, balanceChallenge: Boolean = false) {
        if (!alive(execution)) return finish(execution)
        if (request === execution.request) try { validateOriginalQr(execution) }
        catch (failure: Exception) { onResult(failure.message ?: "El QR ya no es válido"); finish(execution); return }
        if (!authenticationStep && !balanceChallenge && request === execution.request) {
            if (request.operationId == "bandec.card-balance") { startCardQuery(execution, false); return }
            if (request.operationId == "bandec.transfer") {
                val proof = execution.originProof
                if (proof == null) { startCardQuery(execution, true); return }
                if (proof.source != request.source || proof.currency != request.currency ||
                    proof.revision != (evidenceRevision[execution.context.subscriptionId] ?: 0L)) {
                    onResult("El origen ha cambiado después de comprobar su moneda. Revisa la transferencia.")
                    finish(execution); return
                }
            }
        }
        val spec = requireNotNull(OperationCatalog.find(request.operationId))
        val encoded = try {
            wire ?: OperationCatalog.encode(authorizedRequest(execution, request)).let { command ->
                val time = LocalTime.now()
                command.commandsForTransport(request.identity, "${time.minute}${time.second.toString().padStart(2, '0')}")
            }
        } catch (failure: Exception) { onResult(failure.message ?: "No se pudo preparar la solicitud"); finish(execution); return }
        if (spec.transport == OperationTransport.INTERACTIVE_USSD && encoded.size != 1) {
            onResult("La solicitud no cabe en un único diálogo del operador."); finish(execution); return
        }
        val record = operationRecord(execution, request, spec, authenticationStep || balanceChallenge)
        if (authenticationStep || balanceChallenge) authentication = Authentication(record.id, execution, now(), balanceChallenge)
        wallet.transact("No se pudo guardar la operación. No se ha enviado.", block = {
            execution.context.registrationId?.let { id ->
                check(snapshot().registrations.singleOrNull { it.id == id } == execution.context.registration) { "El registro de acceso ha cambiado" }
            }
            putOperation(record)
            check(updateOperationStatus(record.id, OperationStatus.PREPARED, OperationStatus.SUBMITTING, now().toEpochMilli()))
        }, onFailure = { markUncertain(record.id); finish(execution) }, onSuccess = {
            if (!alive(execution)) {
                markUncertain(record.id); finish(execution); return@transact
            }
            if (request === execution.request) try { validateOriginalQr(execution) }
            catch (failure: Exception) {
                markUncertain(record.id); onResult(failure.message ?: "El QR ya no es válido"); finish(execution); return@transact
            }
            if (execution.originProof?.let { it.revision != (evidenceRevision[execution.context.subscriptionId] ?: 0L) } == true) {
                markUncertain(record.id); onResult("El origen ha cambiado. No se ha enviado la transferencia."); finish(execution); return@transact
            }
            if (!authenticationStep && !balanceChallenge) try { execution.prepared?.invoke(record) }
            catch (_: Exception) {
                markUncertain(record.id); onResult("No se pudo guardar la preparación. No se ha enviado la solicitud.")
                finish(execution); return@transact
            }
            if (spec.transport in setOf(OperationTransport.SYSTEM_DIAL, OperationTransport.INTERACTIVE_USSD)) {
                dial(SystemDialRequest(execution.context, encoded.single(), complete = { launched ->
                    markUncertain(record.id)
                    onResult(if (!launched) "No se pudo abrir la operación con la SIM seleccionada." else if (spec.transport == OperationTransport.INTERACTIVE_USSD)
                        "La operación continúa en el diálogo del operador."
                        else "La llamada se ha enviado al sistema. El consumo no está confirmado.")
                    finish(execution)
                }, transport = spec.transport))
            } else sendPart(execution, request, record, encoded, 0, authenticationStep || balanceChallenge)
        })
    }

    private fun operationRecord(execution: Execution, request: ServiceRequest, spec: OperationSpec, auxiliary: Boolean): OperationRecord {
        val legacy = execution.legacy.takeUnless { auxiliary }
        val fullInvoice = request.operationId == "service.electricity" ||
            request.operationId == "service.telephone" && request.values["amount"].isNullOrEmpty()
        val productId = if (spec.sourcePolicy == SourcePolicy.NONE && request.identity.profile == ProfileId.CLASSIC)
            execution.context.registrationId?.let { "registration:$it" } else execution.context.productId
        val parameters = OperationResultProjection.safeParameters(spec, request) +
            // Service 35 fixes its amount currency to BANK code 1 (CUP); request.currency selects the debit source.
            (if (request.operationId == "service.fuel") mapOf("amountCurrency" to "1") else emptyMap()) +
            (if (fullInvoice) mapOf("paymentMode" to "FULL_INVOICE") else emptyMap()) +
            (request.currency?.let { mapOf("sourceCurrency" to it.name) } ?: emptyMap()) +
            (if (execution.context.registrationId != null) mapOf("accessGeneration" to execution.context.accessGeneration.toString()) else emptyMap()) +
            (productId?.let { mapOf("walletProductId" to it) } ?: emptyMap()) +
            (if (auxiliary) mapOf("internalStep" to "true") else emptyMap())
        val currency = legacy?.amount?.currency?.name ?: when (parameters["amountCurrency"]) {
            "1" -> Currency.CUP.name; "2" -> Currency.CUC.name; "3" -> Currency.USD.name
            "0" -> null
            null -> if (request.operationId.startsWith("service.")) Currency.CUP.name else request.currency?.name ?:
                if (request.identity.profile == ProfileId.CLASSIC) Currency.USD.name else null
            else -> parameters["amountCurrency"]
        }
        return OperationRecord(UUID.randomUUID().toString(), legacy?.kind?.name ?: request.operationId,
            request.identity.bank?.code, execution.context.subscriptionId,
            legacy?.destination ?: listOf("destination", "mobile", "invoice", "account", "identity").firstNotNullOfOrNull { parameters[it] }.orEmpty(),
            if (fullInvoice) null else legacy?.amount?.amount?.toPlainString() ?: parameters["amount"], currency, now().toEpochMilli(),
            registrationId = execution.context.registrationId, source = request.source.wireValue,
            phone = legacy?.phone ?: parameters["phone"] ?: parameters["notificationPhone"],
            description = legacy?.description ?: parameters["description"].orEmpty(),
            qr = (legacy?.qr ?: execution.context.originalQr.takeUnless { auxiliary })?.let { qr -> QrDetails(qr.transactionId, qr.provider, qr.amount.amount.toPlainString(), qr.amount.currency.name,
                qr.auxiliary, qr.description, qr.descriptionHint, qr.qrId, qr.validFrom?.toString(), qr.validThrough?.toString()) },
            reviewRequired = spec.requiresConfirmation,
            specId = request.operationId, providerId = request.identity.provider.name, profileId = request.identity.profile.name,
            parameters = parameters)
    }

    private fun sendPart(execution: Execution, request: ServiceRequest, operation: OperationRecord, parts: List<UssdCommand>,
                         index: Int, authenticationStep: Boolean) {
        if (!alive(execution)) { markUncertain(operation.id); finish(execution); return }
        if (index == 0 && request.operationId == "bandec.recent-operations") {
            historyQueries.removeAll { now() >= it.sentAt.plusSeconds(30) }
            historyQueries += HistoryQuery(operation.id, execution.context, now())
        }
        if (index == 0 && request.operationId == "service.fuel") {
            val wait = FuelPurchaseWait(operation.id, execution.context.subscriptionId, now())
            fuelPurchases += wait
            main.postDelayed({ fuelPurchases.remove(wait); markUncertain(operation.id) }, 30_000)
        }
        gateway.send(parts[index], execution.context.subscriptionId) { result ->
            if (!alive(execution)) { markUncertain(operation.id); finish(execution); return@send }
            val response = (result as? UssdResult.Response)?.let { BankResponse.parse(it.text) }
            fuelPurchases.singleOrNull { it.operationId == operation.id }?.let { wait ->
                if (response == BankResponse.PROCESSING && index == parts.lastIndex) { wait.accepted = true; settleFuel(wait) }
                else if (response != BankResponse.PROCESSING) fuelPurchases.remove(wait)
            }
            historyQueries.singleOrNull { it.operationId == operation.id }?.let { wait ->
                if (response == BankResponse.PROCESSING) { wait.accepted = true; bindHistory(wait) }
                else historyQueries.remove(wait)
            }
            if (authenticationStep && response == BankResponse.ALREADY_AUTHENTICATED && authentication?.balanceChallenge == false) {
                markUncertain(operation.id)
                val bank = request.identity.bank
                if (bank == Bank.BPA || bank == Bank.BANDEC) {
                    val balance = ServiceRequest("${bank.name.lowercase()}.balance", request.identity)
                    persistAndSend(execution, balance, listOf(BankCommands().defaultBalance()), balanceChallenge = true)
                } else if (bank != null && (execution.request.source is SourceSelector.Explicit ||
                    bank == Bank.BANMET && execution.request.currency != null)) {
                    val balance = ServiceRequest("${bank.name.lowercase()}.balance", request.identity, execution.request.source,
                        execution.request.currency.takeIf { execution.request.source == SourceSelector.Default })
                    persistAndSend(execution, balance, balanceChallenge = true)
                } else {
                    onResult("El proveedor aún no ha confirmado el acceso para este origen."); finish(execution)
                }
                return@send
            }
            if (response == BankResponse.PROCESSING && index + 1 < parts.size) {
                sendPart(execution, request, operation, parts, index + 1, authenticationStep)
                return@send
            }
            if (response != BankResponse.PROCESSING && parts.size > 1 || result !is UssdResult.Response) {
                markUncertain(operation.id)
                onResult("No se pudo confirmar el resultado. Revisa la operación antes de repetirla.")
                finish(execution); return@send
            }
            wallet.transact(block = { updateOperationStatus(operation.id, OperationStatus.SUBMITTING, OperationStatus.AWAITING_CONFIRMATION, now().toEpochMilli()) })
            if (authenticationStep) {
                val wait = authentication?.takeIf { it.operationId == operation.id } ?: return@send
                wait.accepted = response == BankResponse.PROCESSING
                completeAuthentication(wait)
            } else {
                onResult("Solicitud enviada. El proveedor aún no ha confirmado el resultado.")
                finish(execution)
            }
            main.postDelayed({
                markUncertain(operation.id)
                if (authentication?.operationId == operation.id) {
                    onResult("El proveedor aún no ha confirmado el acceso. No se ha enviado la operación pendiente.")
                    finish(execution)
                }
            }, 30_000)
        }
    }

    private fun markUncertain(id: String) = wallet.transact(block = {
        val operation = snapshot().operations.singleOrNull { it.id == id } ?: return@transact
        if (operation.status in setOf(OperationStatus.SUBMITTING, OperationStatus.AWAITING_CONFIRMATION))
            updateOperationStatus(id, operation.status, OperationStatus.UNCERTAIN, now().toEpochMilli())
    })

    private fun finish(execution: Execution) {
        execution.pin?.fill('\u0000')
        if (active === execution) { active = null; authentication = null; cardQuery = null; busy = false }
    }

    private fun startCardQuery(execution: Execution, forMoney: Boolean) {
        require(execution.context.authenticationIdentity == ProviderIdentity(ProviderId.BANDEC))
        val flow = CardQuery(execution, forMoney)
        cardQuery = flow
        if (flow.selectId != null) sendCardQueryStep(flow, select = true) else sendCardQueryStep(flow, select = false)
    }

    private fun sendCardQueryStep(flow: CardQuery, select: Boolean) {
        val execution = flow.execution
        if (!alive(execution) || cardQuery !== flow) return
        val source = execution.request.source
        val id = if (select) "bandec.card-select" else if (source is SourceSelector.Explicit) "bandec.card-balance" else "bandec.origin-balance"
        val request = ServiceRequest(id, execution.request.identity, source)
        val command = if (select) BankCommands().associateAccount(Bank.BANDEC, source.wireValue) else BankCommands().defaultBalance()
        val baseRecord = operationRecord(execution, request, requireNotNull(OperationCatalog.find(id)), auxiliary = select || flow.forMoney)
        val record = baseRecord.copy(
            id = if (select) requireNotNull(flow.selectId) else flow.readId,
            parameters = baseRecord.parameters + if (select) mapOf("verification" to "SELECTED_CARD_BALANCE", "readOperationId" to flow.readId)
                else mapOf("wireSource" to "0000"),
        )
        if (!select) flow.startedReading = Instant.ofEpochMilli(record.startedAt)
        wallet.transact("No se pudo guardar la consulta. No se ha enviado.", block = {
            check(snapshot().registrations.singleOrNull { it.id == execution.context.registrationId } == execution.context.registration)
            putOperation(record)
            check(updateOperationStatus(record.id, OperationStatus.PREPARED, OperationStatus.SUBMITTING, now().toEpochMilli()))
        }, onFailure = { abortCardQuery(flow, "No se pudo guardar la consulta del origen.") }, onSuccess = {
            if (!alive(execution) || cardQuery !== flow) { markUncertain(record.id); return@transact }
            gateway.send(command, execution.context.subscriptionId) { result ->
                if (!alive(execution) || cardQuery !== flow) { markUncertain(record.id); return@send }
                if (result !is UssdResult.Response || BankResponse.parse(result.text) != BankResponse.PROCESSING) {
                    abortCardQuery(flow, if (select) "No se pudo confirmar la selección de la tarjeta. No se ha consultado otro saldo."
                        else "BANDEC aún no ha confirmado el saldo del origen seleccionado.")
                    return@send
                }
                wallet.transact(block = { updateOperationStatus(record.id, OperationStatus.SUBMITTING, OperationStatus.AWAITING_CONFIRMATION, now().toEpochMilli()) })
                if (select) sendCardQueryStep(flow, select = false)
                else { flow.accepted = true; completeCardQuery(flow) }
            }
            main.postDelayed({
                if (cardQuery === flow) abortCardQuery(flow, "BANDEC aún no ha confirmado el origen. No se ha enviado dinero.")
            }, 30_000)
        })
    }

    private fun abortCardQuery(flow: CardQuery, message: String) {
        flow.selectId?.let(::markUncertain); markUncertain(flow.readId)
        if (cardQuery === flow) { onResult(message); finish(flow.execution) }
    }

    private fun completeCardQuery(flow: CardQuery) {
        val (record, result) = flow.evidence ?: return
        if (cardQuery !== flow || !flow.accepted || flow.completing || !alive(flow.execution)) return
        val message = result.message as? BankMessage.Balance ?: return
        val latest = latestEvidence[flow.execution.context.subscriptionId]
        if (latest != null && (latest.identity != flow.execution.context.authenticationIdentity || latest.at > record.receivedAt)) {
            abortCardQuery(flow, "El origen activo ha cambiado. No se ha enviado dinero."); return
        }
        val source = flow.execution.request.source
        val balance = if (source is SourceSelector.Explicit) message.accounts.filter {
            it.account?.let { mask -> matchesAccount(mask, source.account) } == true
        }.singleOrNull() else message.accounts.singleOrNull()
        if (balance == null) { abortCardQuery(flow, "El saldo recibido no identifica inequívocamente el origen seleccionado."); return }
        val approvedNumber = (if (source is SourceSelector.Explicit) source.account else flow.execution.context.productNumber)
            ?.takeIf { it.matches(Regex("[0-9]{16}")) }
        if (approvedNumber != null) {
            if (balance.account?.let { matchesAccount(it, approvedNumber) } != true) {
                abortCardQuery(flow, "La cuenta predeterminada ya no coincide con el origen revisado. No se ha enviado dinero."); return
            }
            val mask = requireNotNull(balance.account)
            val known = (wallet.snapshot.cards.filter { it.registrationId == flow.execution.context.registrationId }.map { it.number } +
                wallet.snapshot.accounts.filter { it.registrationId == flow.execution.context.registrationId }.map { it.number } + approvedNumber)
                .distinct().filter { matchesAccount(mask, it) }
            if (known.singleOrNull() != approvedNumber) { abortCardQuery(flow, "La cuenta recibida coincide con varias tarjetas. No se ha vinculado el saldo."); return }
        }
        flow.completing = true
        val revision = evidenceRevision[flow.execution.context.subscriptionId] ?: 0L
        wallet.transact(block = {
            val read = completeQuery(flow.readId, result.stored.eventId, now().toEpochMilli())
            read && (flow.selectId == null || completeQuery(flow.selectId, result.stored.eventId, now().toEpochMilli()))
        }, onFailure = { abortCardQuery(flow, "No se pudo confirmar de forma segura la consulta.") }, onSuccess = { completed ->
            if (!completed || !alive(flow.execution) || cardQuery !== flow) { abortCardQuery(flow, "No se pudo confirmar el origen de la consulta."); return@transact }
            if (flow.forMoney) {
                if (balance.available.currency != flow.execution.request.currency) {
                    abortCardQuery(flow, "La cuenta de origen está en ${balance.available.currency.name}. Revisa la moneda y el importe de la transferencia.")
                    return@transact
                }
                flow.execution.originProof = OriginProof(source, balance.available.currency, revision)
                cardQuery = null
                persistAndSend(flow.execution, flow.execution.request, flow.execution.wire)
            } else {
                onResult("${balance.account.orEmpty()} · ${balance.available.amount.toPlainString()} ${balance.available.currency.name}")
                finish(flow.execution)
            }
        })
    }

    fun observed(record: BankSmsRecord, result: SmsIngestResult) {
        if (!result.evidenceEligible || record.subscriptionId == null) return
        if (result.stored.fuelReceiptIds.isNotEmpty()) {
            fuelPurchases.filter { it.subscriptionId == record.subscriptionId && record.receivedAt >= it.sentAt &&
                record.receivedAt <= now() && now() < it.sentAt.plusSeconds(30) }.forEach {
                it.receiptIds += result.stored.fuelReceiptIds
                settleFuel(it)
            }
        }
        val message = result.message
        if (message is BankHistory && message.bank == Bank.BANDEC) {
            val waits = historyQueries.filter { it.context.authenticationIdentity == ProviderIdentity(ProviderId.BANDEC) &&
                it.context.subscriptionId == record.subscriptionId && record.receivedAt >= it.sentAt && now() < it.sentAt.plusSeconds(30) }
            waits.singleOrNull()?.let { it.evidence = record to result; bindHistory(it) }
        }
        val observedBank = when (message) { is BankMessage.Authenticated -> message.bank; is BankMessage.Balance -> message.bank; else -> null }
        val observedIdentity = when (message) {
            is BankMessage.ProviderAuthenticated -> message.identity
            else -> observedBank?.let(ProviderIdentity::forBank)
        }
        if (observedIdentity != null) {
            val latest = latestEvidence[record.subscriptionId]
            if (!result.stored.duplicate && (latest == null || record.receivedAt >= latest.at))
                evidenceRevision[record.subscriptionId] = (evidenceRevision[record.subscriptionId] ?: 0L) + 1
            if (latest == null || record.receivedAt > latest.at)
                latestEvidence[record.subscriptionId] = SessionEvidence(observedIdentity, record.receivedAt, result.stored.canonicalEventId)
            else if (record.receivedAt == latest.at && (latest.identity != observedIdentity || latest.eventId != result.stored.canonicalEventId))
                latestEvidence[record.subscriptionId] = SessionEvidence(null, record.receivedAt, result.stored.canonicalEventId)
        }
        val flow = cardQuery
        if (flow != null && message is BankMessage.Balance && record.subscriptionId == flow.execution.context.subscriptionId &&
            flow.startedReading?.let { record.receivedAt >= it } == true) {
            if (message.bank != Bank.BANDEC) abortCardQuery(flow, "El banco que respondió no coincide con el origen seleccionado.")
            else { flow.evidence = record to result; completeCardQuery(flow) }
        }
        session?.let { current -> if (observedIdentity != null && record.subscriptionId == current.subscriptionId &&
            record.receivedAt >= current.authenticatedAt && current.identity != observedIdentity) session = null }
        val wait = authentication
        val authenticationOperationId = wait?.operationId
        if (wait != null && record.subscriptionId == wait.execution.context.subscriptionId &&
            record.receivedAt >= wait.started && record.receivedAt <= now() && now() < wait.started.plusSeconds(30) &&
            observedIdentity == wait.execution.context.authenticationIdentity &&
            (message is BankMessage.Authenticated || message is BankMessage.ProviderAuthenticated || wait.balanceChallenge && message is BankMessage.Balance)) {
            wait.evidence = record to result
            completeAuthentication(wait)
        }
        wallet.transact(block = {
            val snapshot = snapshot()
            if (message is BankMessage.Balance) persistBalances(snapshot, record, message)
            val candidates = snapshot.operations.filter { operation ->
                operation.status in setOf(OperationStatus.SUBMITTING, OperationStatus.AWAITING_CONFIRMATION, OperationStatus.UNCERTAIN) &&
                    !operation.restored && operation.subscriptionId == record.subscriptionId && operation.startedAt <= record.receivedAt.toEpochMilli() &&
                    OperationResultProjection.couldBelongTo(operation, message)
            }
            val financial = candidates.singleOrNull()?.takeIf { OperationResultProjection.matches(it, message) }
            if (financial != null && result.stored.receiptId != null) {
                confirmOperation(financial.id, result.stored.receiptId, now().toEpochMilli(), null)
            }
            var completedQueryId: String? = null
            if (message is BankMessage.Authenticated || message is BankMessage.ProviderAuthenticated || message is BankMessage.Balance) {
                val matches = snapshot.operations.filter { operation ->
                    operation.id != authenticationOperationId && operation.bankCode == observedBank?.code &&
                        operation.providerId == observedIdentity?.provider?.name && operation.profileId == observedIdentity?.profile?.name &&
                        operation.subscriptionId == record.subscriptionId &&
                        operation.startedAt <= record.receivedAt.toEpochMilli() && !operation.restored &&
                        operation.status in setOf(OperationStatus.SUBMITTING, OperationStatus.AWAITING_CONFIRMATION) &&
                        ((message is BankMessage.Authenticated || message is BankMessage.ProviderAuthenticated) && operation.specId.endsWith(".authenticate") ||
                            message is BankMessage.Balance && operation.specId.endsWith(".balance"))
                }
                matches.singleOrNull()?.let { if (completeQuery(it.id, result.stored.eventId, now().toEpochMilli())) completedQueryId = it.id }
            }
            financial?.id to completedQueryId
        }, onSuccess = { (id, queryId) ->
            val confirmed = wallet.snapshot.operations.singleOrNull { it.id == id && it.status == OperationStatus.CONFIRMED }
            if (confirmed != null) {
                onConfirmed(confirmed, message)
                onResult("Operación confirmada por el comprobante del banco.")
            }
            if (queryId != null) {
                val operation = wallet.snapshot.operations.singleOrNull { it.id == queryId }
                if (message is BankMessage.Balance) onResult(message.accounts.joinToString("\n") {
                    listOfNotNull(it.label, it.account, "${it.available.amount.toPlainString()} ${it.available.currency.name}").joinToString(" · ")
                })
                else if (operation?.registrationId != null && observedIdentity != null) {
                    session = ProviderSession(observedIdentity, operation.registrationId, operation.subscriptionId, record.receivedAt)
                    onResult("Acceso confirmado por ${observedIdentity.provider.name}.")
                }
            }
        })
    }

    private fun WalletRepository.persistBalances(snapshot: WalletSnapshot, record: BankSmsRecord, message: BankMessage.Balance) {
        val registrations = snapshot.registrations.filter { it.bankCode == message.bank.code && it.subscriptionId == record.subscriptionId && it.enabled }
        val registration = registrations.singleOrNull()
        val rows = message.accounts.map { balance ->
            val cards = snapshot.cards.filter { it.registrationId == registration?.id &&
                balance.account?.let { mask -> matchesAccount(mask, it.number) } == true && (it.currency == null || it.currency == balance.available.currency.name) }
            val card = cards.singleOrNull()
            val accounts = snapshot.accounts.filter { it.registrationId == registration?.id &&
                balance.account?.let { mask -> mask == it.number || matchesAccount(mask, it.number) } == true &&
                (it.currency == null || it.currency == balance.available.currency.name) }
            val account = accounts.singleOrNull().takeIf { card == null }
            BalanceRecord("${message.bank.code}:${record.subscriptionId}:${registration?.id.orEmpty()}:${card?.id ?: account?.id.orEmpty()}:${balance.account.orEmpty()}:${balance.available.currency}",
                message.bank.code, requireNotNull(record.subscriptionId), record.receivedAt.toEpochMilli(), balance.account,
                balance.available.amount.toPlainString(), balance.available.currency.name, balance.ledger?.amount?.toPlainString(),
                balance.label, registration?.id, card?.id, account?.id)
        }
        upsertBalances(rows)
    }

    private fun completeAuthentication(wait: Authentication) {
        val (record, result) = wait.evidence ?: return
        if (authentication !== wait || !wait.accepted || wait.completing || !alive(wait.execution)) return
        val latest = latestEvidence[wait.execution.context.subscriptionId]
        if (latest != null && latest.at >= record.receivedAt && latest.identity != wait.execution.context.authenticationIdentity) {
            onResult("El proveedor activo no coincide con el acceso seleccionado. No se ha enviado la operación.")
            finish(wait.execution); return
        }
        val registrationId = wait.execution.context.registrationId ?: return
        wait.completing = true
        wallet.transact(block = { completeQuery(wait.operationId, result.stored.eventId, now().toEpochMilli()) }, onSuccess = { completed ->
            if (!completed || !alive(wait.execution)) { finish(wait.execution); return@transact }
            authentication = null
            session = ProviderSession(wait.execution.context.authenticationIdentity, registrationId, wait.execution.context.subscriptionId, record.receivedAt)
            persistAndSend(wait.execution, wait.execution.request, wait.execution.wire)
        }, onFailure = { finish(wait.execution) })
    }

    private fun bindHistory(wait: HistoryQuery) {
        val (_, result) = wait.evidence ?: return
        if (!wait.accepted || wait !in historyQueries || !contextValid(wait.context) || now() >= wait.sentAt.plusSeconds(30)) return
        val candidates = historyQueries.filter { it.context.subscriptionId == wait.context.subscriptionId &&
            it.context.authenticationIdentity == wait.context.authenticationIdentity && now() < it.sentAt.plusSeconds(30) }
        if (candidates.singleOrNull() !== wait) return
        historyQueries.remove(wait)
        wallet.transact(block = { bindHistoryToQuery(result.stored.eventId, wait.operationId, now().toEpochMilli()) },
            onSuccess = { if (it) onResult("Últimas operaciones recibidas de BANDEC.") })
    }

    /** Coupon secrets remain in the encrypted store. Only immutable purchase evidence participates here. */
    private fun settleFuel(wait: FuelPurchaseWait) {
        if (!wait.accepted || wait.settling || wait !in fuelPurchases || wait.receiptIds.isEmpty() || now() >= wait.sentAt.plusSeconds(30)) return
        wait.settling = true
        val receiptIds = wait.receiptIds.toSet()
        wait.receiptIds.removeAll(receiptIds)
        wallet.transact("No se pudo vincular el comprobante de combustible", block = {
            val current = snapshot()
            if (now() >= wait.sentAt.plusSeconds(30)) return@transact false
            val observations = current.fuelObservations.filter { it.receiptId in receiptIds && it.kind == "PURCHASE" && it.result == "APPLIED" }
            for (observation in observations) {
                val proof = observation.purchaseEvidence ?: continue
                if (proof.receivedAt < wait.sentAt.toEpochMilli() || proof.receivedAt > now().toEpochMilli()) continue
                val candidates = current.operations.filter { !it.restored &&
                    it.status in setOf(OperationStatus.SUBMITTING, OperationStatus.AWAITING_CONFIRMATION, OperationStatus.UNCERTAIN) &&
                    fuelPurchaseMatches(it, proof) }
                if (candidates.singleOrNull()?.id != wait.operationId) continue
                if (confirmOperation(wait.operationId, requireNotNull(observation.receiptId), now().toEpochMilli(), null)) return@transact true
            }
            false
        }, onFailure = { wait.settling = false; wait.receiptIds += receiptIds }, onSuccess = { confirmed ->
            wait.settling = false
            if (confirmed) {
                fuelPurchases.remove(wait)
                onResult("Compra del cupón confirmada por el comprobante del banco.")
            } else if (wait.receiptIds.isNotEmpty()) settleFuel(wait)
        })
    }
}
