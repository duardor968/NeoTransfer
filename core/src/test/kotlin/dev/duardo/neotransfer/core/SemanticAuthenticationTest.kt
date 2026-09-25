package dev.duardo.neotransfer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Synthetic semantic compatibility fixtures; these are not captured network responses. */
class SemanticAuthenticationTest {
    private val parser = BankSmsParser()
    private fun statement(provider: String) = "Usted se ha autenticado en la plataforma de pagos moviles, en $provider, puede comenzar a utilizar nuestros servicios de pagos a traves del movil."

    @Test fun `explicit bank and wallet identities can establish their own access only`() {
        assertEquals(Bank.BANMET, assertIs<BankMessage.Authenticated>(parser.parse("PAGOxMOVIL", statement("el Banco Metropolitano"))).bank)
        assertEquals(Bank.BFI, assertIs<BankMessage.Authenticated>(parser.parse("PAGOxMOVIL", statement("el Banco BFI con la cuenta 0000XXXXXXXX0001"))).bank)
        assertEquals(ProviderIdentity(ProviderId.MITRANSFER), assertIs<BankMessage.ProviderAuthenticated>(
            parser.parse("PAGOxMOVIL", statement("el monedero MiTransfer"))).identity)
    }

    @Test fun `generic acknowledgements negation other providers and appended instructions do not authenticate`() {
        for (value in listOf("Su solicitud esta siendo procesada, espere un SMS", "Usted ya se encuentra autenticado en el sistema.",
            statement("el Banco BFI").replace("se ha autenticado", "no se ha autenticado"),
            statement("el Banco Desconocido"), statement("el monedero MiTransfer") + " Cambie a otro banco.",
            statement("MiTransfer Empresarial"))) assertEquals(BankMessage.Unrecognized, parser.parse("PAGOxMOVIL", value))
        assertEquals(null, parser.parse("otro", statement("el Banco BFI")))
    }

    @Test fun `explicit table semantics preserve bank account and currency without extending BPA`() {
        val bfi = "Banco BFI: La consulta de saldo fue completada.\nCuenta;Saldo Contable;Saldo Disponible;Moneda\n0000XXXXXXXX0001; CR 100.00; CR 90.00;EUR |"
        assertEquals(Currency.EUR, assertIs<BankMessage.Balance>(parser.parse("PAGOxMOVIL", bfi)).accounts.single().available.currency)
        assertEquals(BankMessage.Unrecognized, parser.parse("PAGOxMOVIL", bfi.replace("Banco BFI", "Banco BPA")))
        assertEquals(BankMessage.Unrecognized, parser.parse("PAGOxMOVIL", bfi.replace("CR 90.00", "DB 90.00")))
        assertEquals(BankMessage.Unrecognized, parser.parse("PAGOxMOVIL", bfi.replace("0000XXXXXXXX0001", "")))
        assertEquals(BankMessage.Unrecognized, parser.parse("PAGOxMOVIL", bfi + "otra respuesta"))
    }
}
