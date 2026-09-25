package dev.duardo.neotransfer.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrmWalletMapperTest {
    @Test fun `legacy accounts stay distinct from prepaid cards and recharge codes`() {
        val preview = TrmPreview("synthetic", null, "50000000", listOf(
            record(TrmRecordKind.CONTACT, "Client", "id" to "1", "name" to "Persona de prueba", "phone" to "50000001"),
            record(TrmRecordKind.RECIPIENT_ACCOUNT, "CuentaBanco", "id" to "2", "id_client" to "1", "cuenta" to "0000000000000001"),
            record(TrmRecordKind.OWN_ACCOUNT, "MCBank", "id" to "3", "agencia" to "Metropolitano", "cuenta" to "0000000000000002"),
            record(TrmRecordKind.PREPAID_CARD, "TarjetaPropia", "id" to "4", "serie" to "000000000001"),
            record(TrmRecordKind.NAUTA, "Nauta", "id" to "5", "cuenta" to "usuario@nauta.com.cu"),
            record(TrmRecordKind.BILL, "Factura", "id" to "6", "factura" to "000001"),
            record(TrmRecordKind.RECHARGE_CODE, "Pin", "id" to "7", "pin" to "123456789012"),
        ), mapOf("AOperKey" to 1), emptyMap())
        val first = TrmWalletMapper.map(preview)
        val second = TrmWalletMapper.map(preview)
        assertEquals(first.snapshot, second.snapshot)
        assertEquals("03", first.snapshot.registrations.single().bankCode)
        assertFalse(first.snapshot.registrations.single().enabled)
        assertEquals("0000000000000002", first.snapshot.accounts.single().number)
        assertEquals("0000000000000001", first.snapshot.contacts.single().cards.single().number)
        assertTrue(first.snapshot.cards.isEmpty())
        assertEquals(setOf("LEGACY_PREPAID_CARD", "NAUTA", "LEGACY_BILL", "LEGACY_RECHARGE_CODE"),
            first.snapshot.services.map { it.kind }.toSet())
        assertTrue(first.snapshot.registrations.all { it.credentialAlias == null })
    }

    private fun record(kind: TrmRecordKind, table: String, vararg fields: Pair<String, String>) =
        TrmRecord(kind, table, fields.toMap())
}
