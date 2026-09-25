package dev.duardo.neotransfer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TelecomOperationsTest {
    private fun request(id: String, vararg values: Pair<String, String>) =
        ServiceRequest("cubacel.$id", ProviderIdentity(ProviderId.CUBACEL), values = values.toMap())

    @Test
    fun `Cubacel uses original plain USSD without codec bank prefix or version`() {
        val controls = mapOf("balance" to "*222#", "postpaid" to "*111#", "bonus" to "*222*266#", "data" to "*222*328#",
            "voice" to "*222*869#", "sms" to "*222*767#", "friends" to "*222*264#", "advance" to "*234*3#", "plans" to "*133#")
        for ((id, expected) in controls) assertEquals(expected, TelecomOperations.encode(request(id), 37).valueForTransport())
        assertEquals("*662*1234567890#", TelecomOperations.encode(request("voucher", "voucher" to "1234567890")).valueForTransport())
        assertEquals("*234*1*50000001*1234*10.50#", TelecomOperations.encode(request("transfer", "mobile" to "50000001", "pin" to "1234", "amount" to "10.50")).valueForTransport())
        assertEquals("*234*2*1234*5678#", TelecomOperations.encode(request("pin", "pin" to "1234", "newPin" to "5678")).valueForTransport())
        assertEquals("50000001", TelecomOperations.encode(request("call", "mobile" to "50000001")).valueForTransport())
        assertEquals("*9950000001", TelecomOperations.encode(request("call99", "mobile" to "50000001")).valueForTransport())
        assertTrue(TelecomOperations.all.none { it.requiresSession })
        assertEquals(OperationTransport.SYSTEM_DIAL, TelecomOperations.all.first { it.id == "cubacel.call99" }.transport)
        assertEquals(OperationTransport.INTERACTIVE_USSD, TelecomOperations.all.first { it.id == "cubacel.plans" }.transport)
    }

    @Test
    fun `mobile PIN and voucher cannot inject a USSD instruction`() {
        assertFailsWith<IllegalArgumentException> { TelecomOperations.encode(request("call", "mobile" to "*444*70#")) }
        assertFailsWith<IllegalArgumentException> { TelecomOperations.encode(request("voucher", "voucher" to "123#")) }
        assertFailsWith<IllegalArgumentException> { TelecomOperations.encode(request("pin", "pin" to "123", "newPin" to "5678")) }
        assertFailsWith<IllegalArgumentException> { TelecomOperations.encode(ServiceRequest("cubacel.balance", ProviderIdentity(ProviderId.BPA))) }
    }
}
