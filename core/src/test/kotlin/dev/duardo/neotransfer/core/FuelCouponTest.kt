package dev.duardo.neotransfer.core

import dev.duardo.neotransfer.core.fuel.*
import kotlin.test.*

class FuelCouponTest {
    private val pair = "W3ePK80M6VKxq9C60HXAHg==|+yRrfQTQ/zLIJShacXR3ow=="
    private val at = 1_790_251_200_000L

    private fun message(header: String, amountLabel: String, amount: String) = "Banco Bandec: $header\n" +
        "ID TM: TM1.\nNo. Transaccion : BANK1.\nNo. Serie: 0000000000001.\n$amountLabel: $amount CUP\nDatos del cupon: $pair"

    @Test fun `purchase preserves paid value without inventing available balance`() {
        FuelSmsParser.parse("PAGOxMOVIL", message("El pago del cupon de combustible fue completado.", "Importe pagado", "10.00"), at, 3, "event1")!!.use {
            val update = it.updates.single()
            assertEquals(FuelUpdateKind.PURCHASE, update.kind)
            assertEquals("10.00", update.coupon.paidAmount)
            assertNull(update.coupon.balance)
            assertNull(update.creditedAmount)
            assertEquals("BANK1", update.coupon.bankReference)
            assertEquals("TM1", update.coupon.tmReference)
            assertEquals("02", update.coupon.bankCode)
            assertEquals("fuel:0000000000001", update.coupon.id)
        }
    }

    @Test fun `status rekey and refund keep their distinct monetary meanings`() {
        for ((header, kind) in listOf("Estado del cupon de combustible con" to FuelUpdateKind.STATUS,
            "Actualizado el token del cupon de combustible con" to FuelUpdateKind.REKEY)) {
            FuelSmsParser.parse("PAGOxMOVIL", message(header, "Saldo actual", "0.00"), at, 3)!!.use {
                assertEquals(kind, it.updates.single().kind)
                assertEquals("0.00", it.updates.single().coupon.balance)
                assertNull(it.updates.single().coupon.paidAmount)
                assertNull(it.updates.single().creditedAmount)
            }
        }
        FuelSmsParser.parse("PAGOxMOVIL", message("Se ha realizado una devolucion al cupon de combustible con", "Importe acreditado", "3.50"), at, 3)!!.use {
            assertEquals(FuelUpdateKind.REFUND, it.updates.single().kind)
            assertEquals("3.50", it.updates.single().creditedAmount)
            assertNull(it.updates.single().coupon.balance)
            assertNull(it.updates.single().coupon.paidAmount)
        }
    }

    @Test fun `list records split on whitespace while encrypted components retain their pipe`() {
        val header = "Mis cupones de combustible.\nNo.Serie;Saldo actual;Importe pagado;Moneda;ID Banco;ID TM;Fecha;Banco;DatosCupon\n"
        val first = "0000000000001;5.00;10.00;CUP;BANK1;TM1;24/09/2026;BANDEC;$pair"
        val second = "0000000000002;0.00;20.00;CUP;BANK2;TM2;31/02/2026;BANDEC;$pair"
        FuelSmsParser.parse("PAGOxMOVIL", header + first + "\n" + second, at, 3)!!.use {
            assertEquals(2, it.updates.size)
            assertEquals("5.00", it.updates.first().coupon.balance)
            assertEquals("10.00", it.updates.first().coupon.paidAmount)
            assertEquals("2026-09-24", it.updates.first().coupon.reportedDate)
            assertNull(it.updates.last().coupon.reportedDate)
            assertFalse(it.updates.last().coupon.evidenceEligible)
            assertEquals(at, it.updates.last().coupon.observedAt)
        }
    }

    @Test fun `error or untrusted sender cannot manufacture a successful coupon`() {
        val body = message("El pago del cupon de combustible fue completado.", "Importe pagado", "10.00")
        assertNull(FuelSmsParser.parse("Someone", body, at, 3))
        assertNull(FuelSmsParser.parse("PAGOxMOVIL", "Fallo de la solicitud. $body", at, 3))
        assertNull(FuelSmsParser.parse("PAGOxMOVIL", body.replace(pair, "not-a-coupon"), at, 3))
        assertNull(FuelSmsParser.parse("PAGOxMOVIL", "La transferencia fue completada.", at, 3))
    }

    @Test fun `independent provider cipher fixture yields the exact two-field fuel QR`() {
        // Node crypto independently generated both AES-128-CBC blocks with explicit zero padding.
        val parsed = FuelSmsParser.parse("PAGOxMOVIL", message("El pago del cupon de combustible fue completado.", "Importe pagado", "10.00"), at, 3)!!
        parsed.use {
            val update = it.updates.single()
            FuelCredential.fromProviderEnvelope(update.coupon, update.envelope).use { credential ->
                assertEquals("{\"Pin\":\"1234\",\"Token\":\"TOKEN987\"}", credential.qrPayload())
                val pin = credential.copyPinForDisplay()
                try { assertEquals("1234", String(pin)) } finally { pin.fill('\u0000') }
                assertFalse(credential.toString().contains("1234"))
                assertFalse(update.toString().contains(pair))
                assertFails { FuelCredential.fromProviderEnvelope(update.coupon.copy(bankReference = "OTHER"), update.envelope) }
            }
        }
        assertFails { parsed.updates.single().envelope.copyForProtection() }
    }
}
