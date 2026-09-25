package dev.duardo.neotransfer.core

import java.util.Locale

// APK 1.260416, g0xM9kQCsqu: bank routing 226-246; gas 320-331;
// telephone 539-577; electricity 578-589. Query replies and BFI have no template there.
private val billHeader = Regex(
    "^Banco\\s+(Bandec|BPA|Popular de Ahorros?|Metropolitano)\\s*:?\\s+" +
        "El pago de la factura (de electricidad|telefonica|del Gas) fue completado(?:\\.|(?=\\s))",
    RegexOption.IGNORE_CASE,
)
private val billAmount = "([0-9]+(?:\\.[0-9]{1,2})?)\\s+(CUP|USD|CUC)(?=\\.|\\s|$)"

internal fun parseBillReceipt(text: String): BankMessage.BillPaymentCompleted? {
    val header = billHeader.find(text) ?: return null
    val bank = when (header.groupValues[1].lowercase(Locale.ROOT)) {
        "bandec" -> Bank.BANDEC
        "metropolitano" -> Bank.BANMET
        else -> Bank.BPA
    }
    val service = when (header.groupValues[2].lowercase(Locale.ROOT)) {
        "de electricidad" -> BillService.ELECTRICITY
        "telefonica" -> BillService.TELEPHONE
        "del gas" -> BillService.GAS
        else -> return null
    }
    val details = text.substring(header.range.last + 1)
    fun occurrences(label: String) = Regex("(?<![A-Za-z])$label\\s*:", RegexOption.IGNORE_CASE).findAll(details).count()
    fun money(label: String): Money? = Regex("(?<![A-Za-z])$label\\s*:\\s*$billAmount", RegexOption.IGNORE_CASE)
        .findAll(details).singleOrNull()?.let { Money(it.groupValues[1].toBigDecimal(), Currency.valueOf(it.groupValues[2].uppercase(Locale.ROOT))) }
    fun identifier(label: String): String? = Regex("(?<![A-Za-z])$label\\s*:\\s*([A-Za-z0-9-]+)(?=\\.|\\s|$)", RegexOption.IGNORE_CASE)
        .findAll(details).singleOrNull()?.groupValues?.get(1)
    val paidLabel = if (service == BillService.TELEPHONE) "Monto Pagado" else "Importe Pagado"
    val paid = money(paidLabel)
    val nominal = if (service == BillService.TELEPHONE) money("Importe Factura") else null
    if (occurrences(paidLabel) > 1 || (occurrences(paidLabel) == 1 && paid == null)) return null
    if (service == BillService.TELEPHONE && (occurrences("Importe Factura") > 1 ||
            (occurrences("Importe Factura") == 1 && nominal == null))) return null
    if (paid == null && nominal == null) return null
    if (paid != null && nominal != null && paid.currency != nominal.currency) return null
    if (occurrences("Nro\\. Transaccion") != 1 || occurrences("Nro\\. Factura Pagada") > 1) return null
    val reference = identifier("Nro\\. Transaccion") ?: return null
    val account = identifier("Nro\\. Factura Pagada")
    if (occurrences("Nro\\. Factura Pagada") == 1 && account == null) return null
    return BankMessage.BillPaymentCompleted(bank, service, account, paid, nominal, reference)
}
