package dev.duardo.neotransfer.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.duardo.neotransfer.core.model.Money as CoreMoney
import dev.duardo.neotransfer.core.protocol.QrPay
import dev.duardo.neotransfer.core.qr.QrCode
import dev.duardo.neotransfer.core.qr.QrContent
import dev.duardo.neotransfer.core.qr.QrPayment
import dev.duardo.neotransfer.designsystem.format.formatMoney
import dev.duardo.neotransfer.scanner.QrVerdict
import dev.duardo.neotransfer.scanner.ScanMode
import dev.duardo.neotransfer.scanner.ScanResult
import dev.duardo.neotransfer.scanner.ScannerView
import dev.duardo.neotransfer.ui.common.LightSystemBars
import dev.duardo.neotransfer.ui.model.DuplicateUi
import dev.duardo.neotransfer.ui.model.PayQrUi
import dev.duardo.neotransfer.ui.model.PayReviewUi
import dev.duardo.neotransfer.ui.pay.PayQrActions
import dev.duardo.neotransfer.ui.pay.PayQrScreen
import dev.duardo.neotransfer.ui.transfer.DuplicateDialog
import java.math.BigDecimal
import java.time.LocalDate

/**
 * Pay a merchant QR: the camera at full screen, then the review over it. A payment link opens straight on the
 * review; scanning another code brings the camera back.
 */
class PayDestination(
    private val app: AppModel,
    initial: QrPayment? = null,
    unsupported: String? = null,
) : Destination(), PayQrActions {
    private var payment by mutableStateOf(initial)
    private var problem by mutableStateOf(unsupported)
    private var camera by mutableStateOf(initial == null && unsupported == null)
    private var amount by mutableStateOf("")
    private var description by mutableStateOf("")
    private var source by mutableStateOf(app.homeCard)
    private var paying by mutableStateOf(false)
    private var duplicate by mutableStateOf<DuplicateUi?>(null)

    @Composable
    override fun Content(app: AppModel, book: Book) {
        PayQrScreen(state(book), this, cameraContent = {
            if (camera) ScannerView(ScanMode.QR, ::classify, ::scanned, Modifier.fillMaxSize())
        })
        duplicate?.let { DuplicateDialog(it, ::confirmDuplicate) { duplicate = null } }
    }

    private fun state(book: Book): PayQrUi {
        problem?.let { return PayQrUi.Unsupported(it) }
        val payment = payment ?: return PayQrUi.Scanning
        val sources = sources(book, payment)
        if (sources.isEmpty()) return PayQrUi.Unsupported("Añade una tarjeta en ${payment.amount.currency.name} para pagar este código")
        val index = sources.indexOfFirst { it.card.id == source }.coerceAtLeast(0)
        val fixedDescription = payment.description.isNotEmpty()
        val (reference, label, editable) = when {
            payment.service == 30 -> Triple(payment.transactionId, "ID de pago", false)
            fixedDescription -> Triple(payment.description, "Descripción", false)
            else -> Triple(description, "Descripción", true)
        }
        return PayQrUi.Review(
            PayReviewUi(
                merchant = payment.title ?: "Pago con QR",
                amountInput = if (payment.editableAmount) amount else payment.amount.amount.toPlainString(),
                amountEditable = payment.editableAmount,
                reference = reference,
                referenceLabel = label,
                referenceEditable = editable,
                currency = payment.amount.currency.name,
                sources = sources,
                source = index,
                canPay = paidAmount(payment) != null && !paying,
                paying = paying,
            ),
        )
    }

    private fun sources(book: Book, payment: QrPayment) = book.wallet.filter { it.card.currency == payment.amount.currency.name }

    private fun paidAmount(payment: QrPayment): BigDecimal? =
        if (!payment.editableAmount) payment.amount.amount
        else amount.replace(',', '.').toBigDecimalOrNull()?.takeIf { it.signum() > 0 && it.scale() <= 2 }

    private fun classify(raw: String): QrVerdict = runCatching { QrCode.parse(raw, LocalDate.now()) }.fold(
        onSuccess = { if (it is QrContent.Payment) QrVerdict.Accepted else QrVerdict.Rejected("Este QR es de una tarjeta, no de un pago") },
        onFailure = { QrVerdict.Rejected(it.message ?: "Este QR no es de pago") },
    )

    private fun scanned(result: ScanResult) {
        if (payment != null || problem != null || result !is ScanResult.Qr) return
        val content = runCatching { QrCode.parse(result.raw, LocalDate.now()) }.getOrNull() as? QrContent.Payment ?: return
        amount = ""
        description = ""
        payment = content.payment
    }

    override fun close() { app.navigator.remove(this) }
    override fun amount(value: String) { amount = value.filter { it.isDigit() || it == '.' || it == ',' }.take(12) }
    override fun reference(value: String) { description = value.take(60) }
    override fun source(index: Int) {
        val payment = payment ?: return
        source = sources(app.book.value, payment).getOrNull(index)?.card?.id
    }

    override fun rescan() {
        payment = null
        problem = null
        camera = true
    }

    override fun pay() {
        val payment = payment ?: return
        val paid = paidAmount(payment) ?: return
        paying = true
        app.launch {
            val title = "Pagar ${formatMoney(paid, payment.amount.currency.name)}"
            if (app.authorize(title, payment.title)) send(payment, paid, confirmed = false)
            paying = false
        }
    }

    private fun confirmDuplicate() {
        val payment = payment ?: return
        val paid = paidAmount(payment) ?: return
        duplicate = null
        paying = true
        app.launch {
            send(payment, paid, confirmed = true)
            paying = false
        }
    }

    private suspend fun send(payment: QrPayment, paid: BigDecimal, confirmed: Boolean) {
        val book = app.book.value
        val sources = sources(book, payment)
        val chosen = sources.firstOrNull { it.card.id == source } ?: sources.firstOrNull() ?: return
        val card = book.card(chosen.card.id)?.card?.card ?: return
        val request = QrPay(
            card.bank, app.controller.sourceFor(card.number), payment, CoreMoney(paid, payment.amount.currency),
            description = payment.description.ifEmpty { description.trim() },
        )
        duplicate = app.submit(request, this, confirmed)
    }
}

/** The camera on its own, for whatever a screen needs read: a card number, a card code, a phone. */
class ScanDestination(
    private val app: AppModel,
    private val mode: ScanMode,
    private val classify: (String) -> QrVerdict = { QrVerdict.Accepted },
    private val onResult: (ScanResult) -> Unit,
) : Destination() {
    @Composable
    override fun Content(app: AppModel, book: Book) {
        LightSystemBars()
        ScannerView(mode, classify, { result ->
            app.navigator.remove(this)
            onResult(result)
        }, Modifier.fillMaxSize(), onClose = { app.navigator.remove(this) })
    }
}
