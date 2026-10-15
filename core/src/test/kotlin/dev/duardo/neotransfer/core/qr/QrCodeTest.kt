package dev.duardo.neotransfer.core.qr

import dev.duardo.neotransfer.core.model.Currency
import dev.duardo.neotransfer.core.model.Money
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QrCodeTest {
    private val today = LocalDate.of(2026, 10, 15)
    private fun payment(text: String) = (QrCode.parse(text, today) as QrContent.Payment).payment
    private fun card(text: String) = QrCode.parse(text, today) as QrContent.Card

    private fun extra(provider: String, id: String, content: String): String {
        fun prefix(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }.take(16).toByteArray()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(prefix(provider), "AES"), IvParameterSpec(prefix(id))) }
        return Base64.getEncoder().encodeToString(cipher.doFinal(content.toByteArray()))
    }

    @Test fun `payment JSON keeps values and picks the service`() {
        val dynamic = payment("""{"id_transaccion":"TEST123","importe":12.50,"moneda":"CUP","numero_proveedor":123,"version":"2","titulo":"Tienda de prueba"}""")
        assertEquals(30, dynamic.service)
        assertEquals("12.50", dynamic.amount.plain())
        assertEquals("123", dynamic.provider)
        assertEquals("2", dynamic.auxiliary)
        assertEquals("Tienda de prueba", dynamic.title)
        val static = payment("""{"id_transaccion":"TEST124","importe":"0","moneda":"1","numero_proveedor":"123"}""")
        assertTrue(static.editableAmount)
        assertEquals(31, static.service)
        assertEquals("1", static.auxiliary)
        val fixed = payment("""{"id_transaccion":"ESTATICO-9","importe":"25","moneda":"CUP","numero_proveedor":"123","descripcion":"Compra"}""")
        assertEquals(31, fixed.service)
        assertEquals("Compra", fixed.description)
        assertEquals(Currency.USD, payment("""{"id_transaccion":"T","importe":"3","moneda":"USD","numero_proveedor":"1"}""").amount.currency)
        assertEquals("10000000", payment("""{"id_transaccion":"T","importe":1.0E7,"moneda":"CUP","numero_proveedor":"1"}""").amount.plain())
        assertEquals(19, payment("""{"id_transaccion":"T","importe":"1","moneda":"CUP","numero_proveedor":"1","titulo":"${"x".repeat(45)}"}""").title!!.length)
    }

    @Test fun `single-quoted and unquoted JSON from public generators reads like in Transfermovil`() {
        val single = payment("""{'id_transaccion':'TEST125','importe':'15.00','moneda':'CUP','numero_proveedor':'456','descripcion':'Pan de flauta','titulo':'Pan "de flauta"'}""")
        assertEquals("TEST125", single.transactionId)
        assertEquals("15.00", single.amount.plain())
        assertEquals("456", single.provider)
        assertEquals("Pan de flauta", single.description)
        assertEquals("Pan \"de flauta\"", single.title)
        assertEquals("O'Reilly", payment("""{'id_transaccion':'T','importe':'1','moneda':'CUP','numero_proveedor':'1','titulo':'O\'Reilly'}""").title)
        assertEquals("456", payment("""{id_transaccion:TEST126,importe:3,moneda:CUP,numero_proveedor:456}""").provider)
        assertEquals("Cafe 'La Esquina'", payment("""{"id_transaccion":"T","importe":"1","moneda":"CUP","numero_proveedor":"1","titulo":"Cafe 'La Esquina'"}""").title)
    }

    @Test fun `extra gives the QR id and validity, and an unreadable extra is ignored like Transfermovil`() {
        val ok = payment("""{"id_transaccion":"TEST1","importe":"5","moneda":"CUP","numero_proveedor":"77","extra":"${extra("77", "TEST1", "pista,.*,0110202631122026,QR001")}"}""")
        assertEquals("QR001", ok.qrId)
        assertEquals(LocalDate.of(2026, 10, 1), ok.validFrom)
        val open = payment("""{"id_transaccion":"TEST1","importe":"5","moneda":"CUP","numero_proveedor":"77","extra":"${extra("77", "TEST1", "pista,.*,0000000000000000,")}"}""")
        assertNull(open.qrId)
        assertNull(open.validThrough)
        assertFailsWith<IllegalArgumentException> {
            payment("""{"id_transaccion":"TEST1","importe":"5","moneda":"CUP","numero_proveedor":"77","extra":"${extra("77", "TEST1", "p,r,0101202631012026,Q")}"}""")
        }
        assertNull(payment("""{"id_transaccion":"TEST1","importe":"5","moneda":"CUP","numero_proveedor":"77","extra":"no-es-base64!"}""").qrId)
    }

    @Test fun `plain text and links are accepted`() {
        val plain = payment("TRANSFERMOVIL_ETECSA-PAGO_EXTERNO-001,12.5,CUP,123,1")
        assertEquals("TRANSFERMOVIL_ETECSA-PAGO_EXTERNO-001", plain.transactionId)
        assertEquals("12.5", plain.amount.plain())
        val link = payment("transfermovil://tm_compra_en_linea/action?id_transaccion=TEST9&importe=12,50&moneda=CUP&numero_proveedor=123&descripcion=Compra%20X")
        assertEquals(Money.cup("12.50"), link.amount)
        assertEquals("Compra X", link.description)
        assertEquals("TEST9", payment("https://transfermovil.app/action?id_transaccion=TEST9&importe=1&moneda=CUP&numero_proveedor=123").transactionId)
        assertEquals("TEST9", payment("http://transfermovil.app/action?id_transaccion=TEST9&importe=1&moneda=CUP&numero_proveedor=123").transactionId)
        assertFailsWith<IllegalArgumentException> { payment("https://example.com/action?id_transaccion=1&importe=1&moneda=CUP&numero_proveedor=1") }
        assertFailsWith<IllegalArgumentException> { payment("https://transfermovil.app/action?id_transaccion=1&id_transaccion=2&importe=1&moneda=CUP&numero_proveedor=1") }
    }

    @Test fun `invalid payments fail before review`() {
        for (json in listOf(
            """{"importe":"1","moneda":"CUP","numero_proveedor":"1"}""",
            """{"id_transaccion":"T","importe":"1","moneda":"CUC","numero_proveedor":"1"}""",
            """{"id_transaccion":"T","importe":"-1","moneda":"CUP","numero_proveedor":"1"}""",
            """{"id_transaccion":"T","importe":"1.001","moneda":"CUP","numero_proveedor":"1"}""",
            """{"id_transaccion":"T*1","importe":"1","moneda":"CUP","numero_proveedor":"1"}""",
            """[{"id_transaccion":"T"}]""",
        )) assertFailsWith<IllegalArgumentException>(json) { QrCode.parse(json, today) }
    }

    @Test fun `card codes tolerate commas, spaces, case and missing fields`() {
        assertEquals(QrContent.Card("9204000000000001", "50000001"), card("TRANSFERMOVIL_ETECSA,TRANSFERENCIA,9204000000000001,50000001,"))
        assertEquals(QrContent.Card("9204000000000001", null), card("TRANSFERMOVIL_ETECSA,TRANSFERENCIA,9204000000000001,,"))
        assertEquals(QrContent.Card("9204000000000001", "50000001"), card("transfermovil_etecsa, transferencia, 9204 0000 0000 0001, 5350000001"))
        assertEquals(QrContent.Card("9204000000000001", null), card("TRANSFERMOVIL_ETECSA;TRANSFERENCIA;9204-0000-0000-0001"))
        assertEquals(QrContent.Card("9204000000000001", "50000001"), card("TRANSFERMOVIL_ETECSA TRANSFERENCIA 9204000000000001 +5350000001"))
        assertEquals(QrContent.Card("9204000000000001", null), card(" 9204 0000 0000 0001 "))
        assertFailsWith<IllegalArgumentException> { card("TRANSFERMOVIL_ETECSA,TRANSFERENCIA,92040000,") }
    }

    @Test fun `own card code reads back in NeoTransfer and keeps Transfermovil field positions`() {
        val code = CardQr.encode("9204 0000 0000 0001", "5350000001")
        assertEquals("TRANSFERMOVIL_ETECSA,TRANSFERENCIA,9204000000000001,50000001,", code)
        assertEquals(QrContent.Card("9204000000000001", "50000001"), card(code))
        // Java's split drops trailing empty fields: Transfermóvil sees 4 fields and reads the phone.
        assertEquals(4, code.split(',').dropLastWhile(String::isEmpty).size)
        assertIs<QrContent.Card>(QrCode.parse(CardQr.encode("9204000000000001"), today))
    }
}
