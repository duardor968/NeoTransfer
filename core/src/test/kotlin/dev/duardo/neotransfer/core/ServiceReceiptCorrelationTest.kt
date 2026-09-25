package dev.duardo.neotransfer.core

import kotlin.test.*

class ServiceReceiptCorrelationTest {
    private val identity = ProviderIdentity(ProviderId.BPA)
    private val nominal = Money("300.00".toBigDecimal(), Currency.CUP)
    private val fields = mapOf("amount" to "300", "username" to "fixture", "accountType" to "1")
    private val nauta = BankMessage.ServicePaymentCompleted(Bank.BPA, "Nauta Hogar", "fixture@nauta.com.cu", nominal,
        Money("270.00".toBigDecimal(), Currency.CUP), "NAUTA01")

    @Test fun `Nauta proof matches the family and exact account while retaining actual paid amount`() {
        assertTrue(ServiceReceiptCorrelation.matches("service.nauta.home", identity, nominal, fields, nauta))
        assertFalse(ServiceReceiptCorrelation.matches("service.nauta", identity, nominal, fields, nauta))
        assertFalse(ServiceReceiptCorrelation.matches("service.nauta.debt", identity, nominal, fields, nauta))
        assertTrue(ServiceReceiptCorrelation.couldBelongTo("service.nauta.debt", identity, nominal, fields, nauta))
        assertFalse(ServiceReceiptCorrelation.matches("service.stamp", identity, nominal, fields, nauta))
        assertFalse(ServiceReceiptCorrelation.matches("service.nauta.home", ProviderIdentity(ProviderId.BANDEC), nominal, fields, nauta))
        for (account in listOf("another@nauta.com.cu", "fixture@nauta.co.cu", "fixture", "fixture@nauta.com.cu.evil")) {
            assertFalse(ServiceReceiptCorrelation.matches("service.nauta.home", identity, nominal, fields, nauta.copy(account = account)))
        }
        assertFalse(ServiceReceiptCorrelation.matches("service.nauta.home", identity, nominal, fields, nauta.copy(paid = null)))
        assertFalse(ServiceReceiptCorrelation.matches("service.nauta.home", identity, nominal, fields, nauta.copy(reference = "")))
        assertFalse(ServiceReceiptCorrelation.matches("service.nauta.home", identity, nominal, fields, nauta.copy(paid = Money("301".toBigDecimal(), Currency.CUP))))
        assertFalse(ServiceReceiptCorrelation.matches("service.nauta.home", identity, nominal, fields, nauta.copy(nominal = Money("299".toBigDecimal(), Currency.CUP))))
        assertTrue(ServiceReceiptCorrelation.matches("service.nauta", identity, nominal, fields,
            nauta.copy(service = "Recarga Nauta"))) // Semantic compatibility fixture, not a captured network reply.
    }

    @Test fun `stamp proof requires the payer entity and stamp reference not just a matching amount`() {
        val amount = Money("50".toBigDecimal(), Currency.CUP)
        val details = StampReceiptDetails("00000000000", "Oficinas Tramites MININT", "000000000001")
        val stamp = BankMessage.ServicePaymentCompleted(Bank.BPA, "Sello del timbre", null, amount, amount, "STAMP01", details)
        val target = mapOf("amount" to "50", "taxpayer" to "00000000000", "entity" to "95016")
        assertTrue(ServiceReceiptCorrelation.matches("service.stamp", identity, amount, target, stamp))
        assertFalse(ServiceReceiptCorrelation.matches("service.onat.nit", identity, amount, target, stamp))
        assertFalse(ServiceReceiptCorrelation.matches("service.stamp", identity, amount, target + ("entity" to "95013"), stamp))
        assertFalse(ServiceReceiptCorrelation.matches("service.stamp", identity, amount, target + ("taxpayer" to "00000000001"), stamp))
        for (bad in listOf(null, details.copy(payerIdentity = ""), details.copy(recipientEntity = "Oficinas Tramites MININT otro"), details.copy(stampReference = ""))) {
            assertFalse(ServiceReceiptCorrelation.matches("service.stamp", identity, amount, target, stamp.copy(stamp = bad)))
        }
        assertFalse(ServiceReceiptCorrelation.matches("service.stamp", identity, amount, target, stamp.copy(paid = null)))
    }
}
