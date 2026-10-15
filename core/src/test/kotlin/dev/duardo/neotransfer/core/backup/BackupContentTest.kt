package dev.duardo.neotransfer.core.backup

import dev.duardo.neotransfer.core.model.Bank
import dev.duardo.neotransfer.core.model.Contact
import dev.duardo.neotransfer.core.model.Currency
import dev.duardo.neotransfer.core.model.OwnCard
import dev.duardo.neotransfer.core.model.ServiceKind
import dev.duardo.neotransfer.core.model.ServiceReference
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackupContentTest {
    private val password = "contraseña de prueba".toCharArray()
    private val content = BackupContent(
        cards = listOf(OwnCard("9204000000000001", Bank.BANDEC, alias = "Nómina"), OwnCard("9205000000000001", Bank.BPA)),
        contacts = listOf(Contact("c1", "Ana Prueba", listOf("50000001"), listOf("9204990000000007"))),
        services = listOf(ServiceReference(ServiceKind.NAUTA_HOME, "usuario@nauta.com.cu", "Casa"), ServiceReference(ServiceKind.ELECTRICITY, "0000000000001")),
    )

    @Test fun `format 4 round trip has no secret fields`() {
        val file = BackupFile.export(content, password)
        assertEquals(content, BackupFile.import(file, password))
        val plain = BackupEnvelope.decrypt(file, password).toString(Charsets.UTF_8)
        assertTrue(plain.startsWith("{\"format\":4,"))
        for (word in listOf("pin", "clave", "key", "token", "password")) assertTrue(word !in plain.lowercase(), word)
        assertFailsWith<BackupFailure.AuthenticationFailed> { BackupFile.import(file, "otra".toCharArray()) }
    }

    @Test fun `older binary payloads and unknown formats are refused clearly`() {
        assertFailsWith<BackupFailure.UnsupportedVersion> { BackupFile.import(BackupEnvelope.encrypt("NTPAYLD\u0003...".toByteArray(), password), password) }
        assertFailsWith<BackupFailure.UnsupportedVersion> { BackupFile.import(BackupEnvelope.encrypt("{\"format\":5,\"content\":{}}".toByteArray(), password), password) }
        assertFailsWith<BackupFailure.InvalidInput> { BackupFile.import(BackupEnvelope.encrypt("{\"format\":4,\"content\":{\"cards\":[{\"number\":\"1\",\"bank\":\"BPA\"}]}}".toByteArray(), password), password) }
    }

    /** Builds a `.trm` the way Transfermóvil does: key hex, SHA-512 hex, then AES/ECB base64 of the JSON. */
    private fun trm(json: String): ByteArray {
        val keyHex = "0123456789abcdef".repeat(8)
        val plain = json.toByteArray()
        val hash = MessageDigest.getInstance("SHA-512").digest(plain).joinToString("") { "%02x".format(it) }
        val key = MessageDigest.getInstance("SHA-256").digest(keyHex.toByteArray())
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }
        return (keyHex + hash).toByteArray() + Base64.getEncoder().encode(cipher.doFinal(plain))
    }

    @Test fun `Transfermovil backup imports cards, contacts and services but never keys`() {
        val json = """{"scheme":[],"version_apk":"1.260416","movil":"none","datos":[
            {"tabla":"MCBank","dataJSON":[{"id":1,"agencia":"BANDEC","cuenta":"9204-0000-0000-0001","alias":"Principal","tipo_moneda":"CUP"},
                                          {"id":2,"agencia":"Metropolitano","cuenta":"9225000000000001","alias":"x","tipo_moneda":"CUP"}]},
            {"tabla":"Client","dataJSON":"[{\"id\":7,\"name\":\"Ana Prueba\",\"phone\":\"5350000001\"}]"},
            {"tabla":"TeflMovil","dataJSON":[{"id":1,"id_client":7,"number":"50000002"}]},
            {"tabla":"CuentaBanco","dataJSON":[{"id":1,"id_client":7,"cuenta":"9205990000000007","descripcion":"BPA"}]},
            {"tabla":"Nauta","dataJSON":[{"id":1,"id_client":7,"cuenta":"usuario","tipo_cuenta":2}]},
            {"tabla":"Factura","dataJSON":[{"id":1,"id_client":7,"factura":"0000000000001"},{"id":2,"id_client":7,"factura":"00000000000001"}]},
            {"tabla":"AOperKey","dataJSON":[{"id":1,"clave":"9999"}]},
            {"tabla":"Pin","dataJSON":[{"id":1,"pin":"000000000001"}]}
        ]}"""
        val result = TrmImporter.import(trm(json))
        assertEquals(listOf(OwnCard("9204000000000001", Bank.BANDEC, Currency.CUP, "Principal")), result.content.cards)
        assertEquals(listOf(Contact("trm-7", "Ana Prueba", listOf("50000001", "50000002"), listOf("9205990000000007"))), result.content.contacts)
        assertEquals(listOf(ServiceKind.NAUTA, ServiceKind.ELECTRICITY, ServiceKind.PHONE), result.content.services.map { it.kind })
        assertEquals("usuario@nauta.co.cu", result.content.services.first().value)
        assertEquals(mapOf("MCBank" to 1, "Pin" to 1), result.skipped)
        assertTrue("9999" !in result.toString())
    }

    @Test fun `tampered or foreign files are refused`() {
        val file = trm("""{"scheme":[],"datos":[]}""")
        file[260] = (file[260].toInt() xor 1).toByte() // inside the encrypted body
        assertFailsWith<TrmFailure> { TrmImporter.import(file) }
        assertFailsWith<TrmFailure.InvalidFile> { TrmImporter.import("hola".toByteArray()) }
    }
}
