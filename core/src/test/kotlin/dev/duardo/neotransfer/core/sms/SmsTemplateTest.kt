package dev.duardo.neotransfer.core.sms

import dev.duardo.neotransfer.core.model.Bank
import dev.duardo.neotransfer.core.model.Currency
import dev.duardo.neotransfer.core.model.Money
import dev.duardo.neotransfer.core.sms.SmsMessage.*
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** Synthetic bodies written with the observed PAGOxMOVIL templates; no real numbers, names or amounts. */
class SmsTemplateTest {
    private fun cup(value: String) = Money.cup(value)
    private fun parse(body: String) = SmsParser.parse(body)

    @Test fun authentication() {
        assertEquals(Authenticated(Bank.BPA, null), parse("Usted se ha autenticado en la plataforma de pagos moviles, en el Banco Popular de Ahorro, puede comenzar a utilizar nuestros servicios de pagos a traves del movil"))
        assertEquals(Authenticated(Bank.BPA, null), parse("Usted se ha autenticado en la plataforma de pagos moviles, en el Banco Popular de Ahorro, puede comenzar a utilizar nuestros servicios de pagos a traves del movil\n INFORMACION: \n\nNueva Version de Transfermovil: V9.9. Novedades: mejoras."))
        assertEquals(Authenticated(Bank.BANDEC, "920400XXXXXX0001"), parse("Usted se ha autenticado en la plataforma de pagos moviles, en el Banco Bandec con la cuenta 920400XXXXXX0001, puede comenzar a utilizar nuestros servicios de pagos a traves del movil"))
        assertEquals(Authenticated(Bank.BANDEC, null), parse("Usted se ha autenticado en la plataforma de pagos moviles, en el Banco Bandec con la cuenta , puede comenzar a utilizar nuestros servicios de pagos a traves del movil"))
        assertEquals(AuthenticationFailed(Bank.BANDEC, true, "Clave incorrecta"), parse("Banco Bandec: Clave incorrecta Error de autenticacion, intente nuevamente. Si persiste el problema, contacte con ETECSA. "))
        assertEquals(AuthenticationFailed(Bank.BPA, false, "No se pudo autenticar"), parse("Banco Popular de Ahorro: LA AUTENTICACION HA FALLADO Error de autenticacion, intente nuevamente. Si persiste el problema, contacte con ETECSA. "))
        assertEquals(AuthenticationFailed(null, false, "No se pudo autenticar"), parse("vuelva a intentarlo, si persiste el problema, contacte con ETECSA Error de autenticacion, intente nuevamente. Si persiste el problema, contacte con ETECSA. "))
        assertEquals(Busy, parse("Usted tiene una solicitud en proceso, en breve recibira respuesta y podra realizar otra operacion. "))
    }

    @Test fun balances() {
        assertEquals(Balance(Bank.BANDEC, listOf(BalanceRow("920400XXXXXX0001", null, cup("1200.50"), cup("1150.50")))),
            parse("Banco Bandec La consulta de saldo fue completada. \n Cuenta;Saldo Contable;Saldo Disponible;Moneda \n920400XXXXXX0001; CR 1200.50 ; CR 1150.50 ;CUP | \n"))
        assertEquals(Balance(Bank.BPA, listOf(BalanceRow(null, null, null, cup("845.10")))),
            parse("Banco Popular de Ahorro:  La consulta de saldo fue completada. \n Saldo Disponible: CR 845.10 CUP"))
        val table = parse("Banco Popular de Ahorro La consulta de saldo fue completada. \n Nombre Cuenta;Nro Cuenta; Saldo Disponible; Moneda \n; DPF-AI ; 5000.00; CUP \n; Ahorro ; 120.00; CUP \n; Dep.USD ; 3.00; USD \n")
        assertEquals(listOf("DPF-AI", "Ahorro", "Dep.USD"), (table as Balance).rows.map { it.label })
        assertEquals(Money.cup("3.00").amount, table.rows.last().available.amount)
        assertEquals(Currency.USD, table.rows.last().available.currency)
        // Tolerance: case, spacing, no colon.
        assertEquals(Balance(Bank.BPA, listOf(BalanceRow(null, null, null, cup("10")))), parse("BANCO POPULAR DE AHORRO  la consulta de saldo fue completada.   Saldo Disponible CR 10 CUP"))
    }

    @Test fun history() {
        val message = parse("Banco Bandec Ultimas operaciones. \n Fecha;Servicio;Operacion;Monto;Moneda;NoTransaccion \n 3/10/2026;POS Extraccion Ref: AP000TEST1;Db;100.00;CUP; | \n12/10/2026;Banca Movil Transferencia Ref: KW000TEST2;Cr;250.00;CUP; | \n12/10/2026;Credito:  Ref: EA0001;Cr;1.25;CUP; | \n")
        assertIs<History>(message)
        assertEquals(3, message.entries.size)
        assertEquals(HistoryEntry(LocalDate.of(2026, 10, 3), "POS Extraccion", false, cup("100.00"), "AP000TEST1"), message.entries[0])
        assertEquals("KW000TEST2", message.entries[1].reference)
        assertEquals("Credito", message.entries[2].description)
    }

    @Test fun metropolitano() {
        assertEquals(Authenticated(Bank.BANMET, null), parse("Usted se ha autenticado en la plataforma de pagos moviles, en el Banco Metropolitano, puede comenzar a utilizar nuestros servicios de pagos a traves del movil"))
        // Seen in 2025: an account closed by the bank.
        assertEquals(AuthenticationFailed(Bank.BANMET, false, "No se pudo autenticar"),
            parse("Banco Metropolitano: Usuario cancelado en el sistema Error de autenticacion, intente nuevamente. Si persiste el problema, contacte con ETECSA."))
        assertEquals(Balance(Bank.BANMET, listOf(BalanceRow(null, null, null, cup("845.10")))),
            parse("Banco Metropolitano: La consulta de saldo fue completada.\n Saldo Contable: CR 900.00 CUP\n Saldo Disponible: CR 845.10 CUP"))
        assertEquals(Rejected(Bank.BANMET, RejectedOperation.TRANSFER, "Excedido el limite de extracciones."),
            parse("Fallo la transferencia. Banco Metropolitano: Excedido el limite de extracciones"))
    }

    @Test fun `online payments of every bank`() {
        // Seen in August 2026, one line per field; the bank's reference follows "Nro. Transaccion Banco".
        assertEquals(PaymentCompleted(Bank.BANMET, "Tienda Prueba", "1234567890", null, cup("1.00"), "MM00000000001", null),
            parse("Banco Metropolitano El pago\nexterno fue completado.\nFecha: 11/8/2026\nEntidad: Tienda Prueba\nNro. Transaccion: 1234567890\nMonto Pagado:1.00 CUP\nNro. Transaccion Banco:\nMM00000000001."))
        assertEquals(PaymentCompleted(Bank.BANDEC, "Tienda Prueba", "1234567891", null, cup("840.00"), "KW00000000002", null),
            parse("Banco Bandec Elpago externo fue\ncompletado\nFecha: 11/8/2026\nEntidad: Tienda Prueba\nNro. Transaccion: 1234567891\nMonto Pagado: 840.00 CUP\nNro. Transaccion Banco:\nKW00000000002."))
        assertEquals(PaymentCompleted(Bank.BPA, "Tienda Prueba", "1234567892", null, cup("280.00"), "BR00000000003", null),
            parse("Banco Popular de Ahorro El pago\nexterno fue completado\nFecha: 11/8/2026\nEntidad: Tienda Prueba\nNro. Transaccion: 1234567892\nMonto Pagado: 280.00 CUP\nNro. Transaccion Banco:\nBR00000000003."))
    }

    @Test fun `BANDEC alerts`() {
        assertEquals(CardOperation(Bank.BANDEC, "9204000000000001", "Extracción en sucursal", false, cup("15000.00"), "OL000TEST1"),
            parse("BANDEC >> Operacion en Linea Sucursal por 15000.00 Tarjeta 9204000000000001. Referencia OL000TEST1. Configure en https://kiosco.bandec.cu."))
        // A wording whose direction is unknown is never guessed.
        assertEquals(Unrecognized, parse("BANDEC >> Operacion Nueva por 10.00 Tarjeta 9204000000000001. Referencia OL000TEST2. Configure en https://kiosco.bandec.cu."))
    }

    @Test fun records() {
        val alert = "BANDEC >> Operacion en Linea Sucursal por 1.00 Tarjeta 9204000000000001. Referencia OL000TEST3. Configure en https://kiosco.bandec.cu."
        // BANDEC's alerts carry a stamp 11 hours off: they are ordered by reception.
        val record = SmsRecord.from(1, "BANDEC", 1_000_000_000_000, 1_000_000_000_000 - 11 * 3_600_000, alert)!!
        assertEquals(record.receivedAt, record.sentAt)
        assertIs<CardOperation>(record.message)
        val pagos = SmsRecord.from(2, "pagoxmovil", 1_000_000_000_000, 999_999_990_000, "Usted tiene una solicitud en proceso, en breve recibira respuesta y podra realizar otra operacion. ")!!
        assertEquals(999_999_990_000, pagos.sentAt.toEpochMilli())
        assertNull(SmsRecord.from(3, "+5350000001", 1_000_000_000_000, 0, alert))
    }

    @Test fun transfers() {
        assertEquals(TransferSent(Bank.BANDEC, "9204XXXXXXXX0002", null, cup("300.00"), "KW000TEST3", cup("700.00")),
            parse("Banco Bandec:  La Transferencia fue completada. \n Fecha: 12/10/2026 \n Beneficiario: 9204XXXXXXXX0002 \n Ordenante: CUP \n Monto: 300.00 CUP \n Nro. Transaccion: KW000TEST3 \n Saldo restante: CR 700.00 CUP "))
        assertEquals(TransferSent(Bank.BPA, "9205XXXXXXXX0002", "9205XXXXXXXX0001", cup("10.00"), "BR000TEST4", null),
            parse("Banco Popular de Ahorro:  La Transferencia fue completada. \n Fecha: 1/9/2026 \n Beneficiario: 9205XXXXXXXX0002 \n Ordenante: 9205XXXXXXXX0001 \n Monto: 10.00 CUP \n Nro. Transaccion: BR000TEST4"))
        assertEquals(TransferReceived("9204000000000001", "5350000001", cup("10000.00"), "AY000TEST5"),
            parse("El titular del telefono 5350000001 le ha realizado una transferencia a la cuenta 9204000000000001 de 10000.00 CUP. Nro. Transaccion AY000TEST5. Fecha: 13/10/2026."))
        // Older wording: no "Fecha", reference without colon.
        assertEquals(TransferReceived("9204000000000001", "5350000001", cup("50.00"), "BR000TEST6"),
            parse("El titular del telefono 5350000001 le ha realizado una transferencia a la cuenta 9204000000000001 de 50.00 CUP. Nro. Transaccion BR000TEST6"))
        assertEquals(TransferNotice("9204000000000002", cup("300.00"), "KW000TEST3"),
            parse("Se ha realizado una transferencia a la cuenta 9204000000000002 de 300.00 CUP. Nro. Transaccion KW000TEST3. Fecha: 12/10/2026."))
    }

    @Test fun payments() {
        assertEquals(PaymentCompleted(Bank.BANDEC, "Comercio de Prueba", "1000000001", cup("125.00"), cup("125.00"), "KW000TEST7", cup("875.00")),
            parse("Banco Bandec: Pago completado. \n Fecha: 12/10/2026 \n Entidad: Comercio de Prueba\n Id Compra: 1000000001 \n Importe: 125.00 CUP \n Importe pagado: 125.00 CUP \n No. Transaccion: KW000TEST7 \n Saldo disponible: CR 875.00 CUP"))
        val bpa = parse("Banco Popular de Ahorro: Pago completado. \n Fecha: 12/10/2026 \n Entidad: Tienda\n Id Compra: 1000000002 \n Importe: 50.00 CUP \n Importe pagado: 50.00 CUP \n No. Transaccion: BR000TEST8 \n Saldo disponible: DB 450.00 CUP")
        assertEquals(cup("450.00"), (bpa as PaymentCompleted).balance) // DB is the balance, not a debt
    }

    @Test fun serviceReceipts() {
        assertEquals(ServicePaid(Bank.BPA, PaidService.NAUTA_HOME, "usuario@nauta.com.cu", cup("300.00"), cup("270"), "BR000TEST9", cup("500.00")),
            parse("Banco Popular de Ahorro:  La cuenta Nauta Hogar: usuario@nauta.com.cu ha sido pagada con 300.00 CUP. Monto Pagado: 270 CUP. \n Id Transaccion: BR000TEST9. \n Saldo Restante: DB 500.00 CUP. \n Se aplico un descuento del 10% al pago realizado. Gracias por utilizar nuestros servicios, ETECSA."))
        assertEquals(ServicePaid(Bank.BPA, PaidService.NAUTA_HOME, "usuario@nauta.com.cu", null, cup("300.00"), "BR000TESTA", cup("500.00")),
            parse("Banco Popular de Ahorro:  La cuenta Nauta Hogar:usuario@nauta.com.cu ha sido pagada con 300.00 CUP. Id Transaccion: BR000TESTA. Saldo Restante: DB 500.00 CUP. "))
        assertEquals(ServicePaid(Bank.BPA, PaidService.NAUTA_RECHARGE, "usuario@nauta.com.cu", null, cup("100.00"), "BR000TESTB", cup("400.00")),
            parse("Banco Popular de Ahorro:  La cuenta :usuario@nauta.com.cu ha sido recargada con 100.00 CUP. Id Transaccion: BR000TESTB. Saldo Restante: DB 400.00 CUP. \n Se aplico un descuento del 10% al pago realizado."))
        assertEquals(ServicePaid(Bank.BPA, PaidService.MOBILE_RECHARGE, "50000001", null, cup("250.00"), "BR000TESTC", cup("150.00")),
            parse("Banco Popular de Ahorro:  La recarga se realizo con exito. Saldo a acreditar: 250 CUP. Saldo acreditado: 250 CUP. Monto Pagado: 250.00 CUP. Telefono: 50000001. Id transaccion: BR000TESTC. Saldo Restante: DB 150.00. \n Gracias por utilizar nuestros servicios, ETECSA."))
        assertEquals(ServicePaid(Bank.BPA, PaidService.STAMP, null, null, cup("50.00"), "BR000TESTD", cup("500.00")),
            parse("Banco Popular de Ahorro El pago del impuesto sobre el documento (sello del timbre) fue completado. \n CI: 00000000000 \n Entidad: Oficina \n Valor del sello: 50.00 CUP \n Importe Pagado: 50.00 CUP \n Nro. Transaccion Banco: BR000TESTD \n Saldo Restante: 500.00 CUP \n IdSello: 000000000001."))
        // Bill templates from Transfermóvil's parser (g0xM9kQCsqu); not seen on the phone.
        assertEquals(ServicePaid(Bank.BANDEC, PaidService.ELECTRICITY, "0000000000001", null, cup("85.40"), "KW000TESTE", null),
            parse("Banco Bandec:  El pago de la factura de electricidad fue completado. Nro. Transaccion: KW000TESTE Nro. Factura Pagada: 0000000000001 Importe Pagado: 85.40 CUP"))
        assertEquals(ServicePaid(Bank.BPA, PaidService.PHONE, "00000000000001", cup("120.00"), cup("100.00"), "BR000TESTF", null),
            parse("Banco Popular de Ahorro:  El pago de la factura telefonica fue completado. Nro. Transaccion: BR000TESTF Nro. Factura Pagada: 00000000000001 Importe Factura: 120.00 CUP Monto Pagado: 100.00 CUP"))
        assertEquals(ServiceNotice(PaidService.NAUTA_HOME, "usuario@nauta.com.cu", cup("300.00"), "BR000TEST9"),
            parse("Se ha pagado la cuenta nauta hogar usuario@nauta.com.cu con 300.00 CUP. Nro. Transaccion BR000TEST9"))
        assertEquals(ServiceNotice(PaidService.NAUTA_RECHARGE, "usuario@nauta.com.cu", cup("100.00"), "BR000TESTB"),
            parse("Se ha recargado la cuenta usuario@nauta.com.cu con 100.00 CUP. Nro. Transaccion BR000TESTB"))
    }

    @Test fun rejections() {
        assertEquals(Rejected(Bank.BPA, RejectedOperation.TRANSFER, "La cuenta no está asociada a una tarjeta magnética."),
            parse("Fallo la transferencia. Banco Popular de Ahorro: LA CUENTA NO ESTA ASOCIADA A UNA TARJETA MAGNETICA"))
        assertEquals(Rejected(null, RejectedOperation.BALANCE, "El banco no pudo consultar el saldo."),
            parse("Fallo la consulta de saldo. vuelva a intentarlo, si persiste el problema, contacte con su proveedor de servicios."))
        assertEquals(Rejected(null, RejectedOperation.MOBILE_RECHARGE, "El móvil llegó al límite de recarga de 30 días (360 CUP). Se podrá recargar después del 15-11-2026.", "50000001"),
            parse("Fallo la recarga del movil 50000001 alcanzo el monto limite de recarga permitido en 30 dias (360 CUP), puede recargar posterior al dia 15-11-2026."))
        assertEquals(Rejected(null, RejectedOperation.NAUTA_HOME, "La cuenta Nauta Hogar usuario@nauta.com.cu no es correcta.", "usuario@nauta.com.cu"),
            parse("Fallo el pago de la cuenta Nauta Hogar, la cuenta usuario@nauta.com.cu es incorrecta."))
        assertEquals(Rejected(null, RejectedOperation.NAUTA_HOME, "El importe tiene que ser mayor que 125.00 CUP."),
            parse("Fallo el pago de la cuenta Nauta Hogar. El monto tiene que ser mayor que 125.00"))
        assertEquals(Rejected(null, RejectedOperation.NAUTA_HOME_DEBT, "La cuenta Nauta Hogar no tiene deuda."),
            parse("Fallo el pago de la deuda de la cuenta Nauta Hogar, no tiene deuda"))
        assertEquals(Rejected(Bank.BANDEC, RejectedOperation.CARD_ASSOCIATION, "Esta tarjeta ya está asociada a un celular."),
            parse("Error en la asociacion de tarjeta. Banco Bandec: Esta tarjeta ya esta asociada a un celular"))
        assertEquals(RejectedOperation.OTHER, (parse("Banco Popular de Ahorro Fallo el cierre del deposito a plazo fijo de la cuenta Banco Popular de Ahorro. 0") as Rejected).operation)
        val unknown = parse("Fallo la operacion. vuelva a intentarlo, si persiste el problema, contacte con ETECSA.")
        assertEquals(Rejected(null, RejectedOperation.UNKNOWN, "Fallo la operacion."), unknown)
    }

    @Test fun otherMessages() {
        assertEquals(MobileBalanceReceived(cup("360"), "5350000002"),
            parse("Usted ha recibido una recarga de saldo movil por un monto de 360 CUP del numero 5350000002. Monto restante a recargar para el periodo: 0 CUP. Para conocer su saldo principal consultelo a traves del *222#"))
        assertEquals(Registration, parse("Banco Bandec:  La operacion de registro fue completada. \n Bienvenido a Transfermovil.Clave de Autenticacion: X"))
        assertEquals(Unrecognized, parse("Mensaje sin plantilla conocida"))
        assertNull(SmsRecord.from(1, "ETECSA", 1000, 0, "Usted se ha autenticado en el Banco Bandec"))
        val record = SmsRecord.from(7, "PAGOxMOVIL", 5_000, 1_000, "Usted tiene una solicitud en proceso")!!
        assertEquals(1_000, record.sentAt.toEpochMilli())
        assertEquals(5_000, record.receivedAt.toEpochMilli())
        assertEquals(5_000, SmsRecord.from(8, "PAGOxMOVIL", 5_000, 0, "x")!!.sentAt.toEpochMilli())
    }
}
