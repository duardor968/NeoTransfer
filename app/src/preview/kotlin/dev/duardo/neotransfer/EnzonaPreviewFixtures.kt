package dev.duardo.neotransfer

import dev.duardo.neotransfer.enzona.*
import dev.duardo.neotransfer.enzona.data.EnzonaCardLink

/** All identities and amounts are synthetic; the preview cannot open a banking connection. */
internal fun withEnzonaPreview(base: AppUiState): AppUiState {
    val cards = EnzonaReadJson.cards("""{"card":[
      {"funding_source_uuid":"demo-bandec","bank_name":"BANDEC","last4":"0001","currency":"CUP","cardholder":"PERSONA DE PRUEBA"},
      {"funding_source_uuid":"demo-bpa","bank_name":"BPA","last4":"0003","currency":"CUP","cardholder":"PERSONA DE PRUEBA"}] }""")
    val balance = EnzonaReadJson.balance("""{"balance":"8540.50","balance_available":"8240.50","currency":"CUP","operation":[
      {"operationDate":"07/10/2026","action":"Crédito","description":"Transferencia recibida","amount":"1500.00","codigo":"DEMO01"},
      {"operationDate":"06/10/2026","action":"Débito","description":"Pago de electricidad","amount":"300.00","codigo":"DEMO02"}]}""")
    val history = EnzonaReadJson.transactions("""{"transaction":[{"transaction_uuid":"demo-transaction","amount":"300.00","currency":"CUP",
      "transaction_description":"Electricidad","status_denom":"Aceptada","transaction_created_at":"2026-10-06","invoice_number":"DEMO02"}],"pagination":{"total":1}}""")
    return base.copy(enzonaSession = EnzonaSessionUiState(connected = true, ownerSubject = "demo@invalid"),
        enzonaWallet = EnzonaWalletState(owner = "demo@invalid", cards = cards,
            links = listOf(EnzonaCardLink("demo@invalid", "demo-bandec", "0000000000000001", "Uso diario")),
            balances = mapOf("demo-bandec" to EnzonaObservedBalance(balance, System.currentTimeMillis())),
            transactions = history.transactions, selectedCardId = "demo-bandec", cardsAt = System.currentTimeMillis()))
}

internal fun enzonaQrPreview(): EnzonaResolvedQr = EnzonaResolvedQr(
    ownerSubject = "demo@invalid", kind = EnzonaQrKind.MERCHANT_PAYMENT, code = "07-demo-code",
    vendorIdentityCode = "07-demo-code", accountUuid = null, username = null, merchantUuid = "demo-merchant",
    name = "Mercado de prueba", amount = java.math.BigDecimal("125.50"), currency = "CUP", description = "Compra de demostración",
    requirePassword = null, observedAt = System.currentTimeMillis())

internal fun enzonaInvoicePreview(service: EnzonaInvoiceService, reference: String): EnzonaInvoiceLookup =
    if (service == EnzonaInvoiceService.ELECTRICITY) EnzonaInvoiceLookup(service,
        bills = EnzonaServiceJson.electricityBills("""{"client":[{"invoice":[{"client_id":"000000000001","owner":"PERSONA DE PRUEBA",
          "folio":"001","route":"002","period":"202610","amount":"125.50","total_amount":"125.50","charged":"false"}]}]}"""))
    else EnzonaInvoiceLookup(service, etecsa = EnzonaEtecsaQuery("", "true", "PERSONA DE PRUEBA", listOf(
        EnzonaEtecsaAccount(service.responseType, reference, "DEMO-NUMBER", "true", java.math.BigDecimal("80.00"),
            null, null, null, java.math.BigDecimal("1.00"), java.math.BigDecimal("5000.00"), kotlinx.serialization.json.JsonObject(emptyMap())))))
