package dev.duardo.neotransfer.core

import kotlin.test.Test
import kotlin.test.assertEquals

class BankResponseTest {
    @Test fun alreadyAuthenticatedIsRecognizedWithoutClaimingABank() {
        for (text in listOf(
            "usted ya se encuentra autenticado en el sistema",
            "Usted ya se encuentra autenticado en el sistema.",
            "  USTED YA SE ENCUENTRA\nAUTENTICADO EN EL SISTEMA!  ",
        )) assertEquals(BankResponse.ALREADY_AUTHENTICATED, BankResponse.parse(text))
    }

    @Test fun processingAllowsFormattingWithoutRequiringTheOriginalComma() {
        for (text in listOf(
            "Su solicitud está siendo procesada, espere un SMS",
            "Su solicitud esta siendo procesada.",
            "SU SOLICITUD ESTA SIENDO PROCESADA",
        )) assertEquals(BankResponse.PROCESSING, BankResponse.parse(text))
    }

    @Test fun rejectionAndUnknownResponsesCannotAuthorizeContinuation() {
        for (text in listOf(
            "Usted no se encuentra autenticado en el sistema",
            "Su solicitud no esta siendo procesada, intente luego",
            "Error: usted ya se encuentra autenticado en el sistema",
            "Usted ya se encuentra autenticado en el sistema, pero su cuenta esta bloqueada",
            "PIN incorrecto", "Sesion expirada", "", "La transferencia fue completada.",
        )) assertEquals(BankResponse.OTHER, BankResponse.parse(text), text)
    }
}
