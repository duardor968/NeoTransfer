package dev.duardo.neotransfer.preview

import dev.duardo.neotransfer.ui.model.BalanceUi
import dev.duardo.neotransfer.ui.model.Bank
import dev.duardo.neotransfer.ui.model.CardUi
import dev.duardo.neotransfer.ui.model.ContactUi
import dev.duardo.neotransfer.ui.model.CounterpartUi
import dev.duardo.neotransfer.ui.model.ForeignCardUi
import dev.duardo.neotransfer.ui.model.Money
import dev.duardo.neotransfer.ui.model.OperationKind
import dev.duardo.neotransfer.ui.model.OperationStatus
import dev.duardo.neotransfer.ui.model.OperationUi
import dev.duardo.neotransfer.ui.model.RecipientUi
import dev.duardo.neotransfer.ui.model.SavedReferenceUi
import dev.duardo.neotransfer.ui.model.ServiceKind
import dev.duardo.neotransfer.ui.model.WalletCardUi
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Invented data for previews and screenshot tests. Card numbers start with 92 and pass Luhn; names and phones
 * belong to nobody.
 */
object PreviewData {
    val zone: ZoneId = ZoneId.of("America/Havana")
    private val now: ZonedDateTime = ZonedDateTime.of(2026, 10, 15, 14, 40, 0, 0, zone)
    val clock: Clock = Clock.fixed(now.toInstant(), zone)

    fun ago(minutes: Long): Instant = now.minusMinutes(minutes).toInstant()
    fun at(daysAgo: Long, hour: Int, minute: Int): Instant = now.minusDays(daysAgo).withHour(hour).withMinute(minute).toInstant()

    fun cup(value: String) = Money(BigDecimal(value), "CUP")

    val bpa = CardUi("bpa-1", Bank.BPA, "9212060123456781", "CUP", holder = "LAURA MENDEZ SOTO", expiry = "03/29")
    val bandec = CardUi("bandec-1", Bank.BANDEC, "9205129987654325", "CUP", holder = "LAURA MENDEZ SOTO", expiry = "11/30")
    val bpaUsd = CardUi("bpa-2", Bank.BPA, "9212060155520199", "USD")

    val wallet = listOf(
        WalletCardUi(bpa, BalanceUi.Known(cup("12480.50"), ago(5))),
        WalletCardUi(bandec, BalanceUi.Known(cup("3215.00"), ago(185))),
    )

    // Contacts --------------------------------------------------------------------------------------------------
    val juan = ContactUi(
        "c-juan", "Juan Carlos Pérez",
        listOf(
            ForeignCardUi("jc-1", "9205990144718265", "BANDEC", "Salario"),
            ForeignCardUi("jc-2", "9212333188042154", "BPA"),
        ),
        listOf("53124578", "78302214"),
    )
    val maria = ContactUi("c-maria", "María Fernanda Ruiz", listOf(ForeignCardUi("mf-1", "9205770422901639", "BANDEC")), listOf("52981134"))
    val carmen = ContactUi("c-carmen", "Abuela Carmen", listOf(ForeignCardUi("ca-1", "9225950003124472", "BANMET")), listOf("54417720"))
    val yunior = ContactUi("c-yunior", "Yunior Castillo", listOf(ForeignCardUi("yc-1", "9205112233445565", "BANDEC")), emptyList())
    val daniela = ContactUi("c-daniela", "Daniela Ortiz", listOf(ForeignCardUi("do-1", "9212077700319588", "BPA")), listOf("58337102"))
    val rafael = ContactUi("c-rafael", "Rafael Torres", emptyList(), listOf("53660981"))
    val contacts = listOf(carmen, daniela, juan, maria, rafael, yunior)

    val juanRecipient = RecipientUi(juan.cards.first(), juan.name, juan.id)

    // Operations ------------------------------------------------------------------------------------------------
    val sentToJuan = OperationUi(
        "op-sent", OperationKind.Transfer, "Juan Carlos Pérez", OperationStatus.Sent, ago(1),
        amount = cup("300.00"), card = bpa, counterpart = CounterpartUi.Card("9205990144718265", "BANDEC"), contactId = juan.id,
    )
    val notExecuted = OperationUi(
        "op-busy", OperationKind.Electricity, "Casa", OperationStatus.NotExecuted, ago(4),
        amount = cup("412.35"), card = bpa, counterpart = CounterpartUi.Reference("01234567891"),
        reason = "El banco estaba ocupado",
    )
    val confirmedToMaria = OperationUi(
        "op-maria", OperationKind.Transfer, "María Fernanda Ruiz", OperationStatus.Confirmed, ago(52),
        amount = cup("1500.00"), card = bpa, counterpart = CounterpartUi.Card("9205770422901639", "BANDEC"), contactId = maria.id,
        transactionId = "TRF8826104173", balanceAfter = cup("12780.50"),
    )
    val incomingFromDaniela = OperationUi(
        "op-in", OperationKind.Incoming, "Daniela Ortiz", OperationStatus.Confirmed, ago(130),
        amount = cup("2000.00"), card = bpa, counterpart = CounterpartUi.Phone("58337102"), contactId = daniela.id,
        transactionId = "TRF8826100952",
    )
    val qrCafe = OperationUi(
        "op-qr", OperationKind.QrPayment, "Cafetería La Esquina", OperationStatus.Confirmed, at(1, 9, 12),
        amount = cup("480.00"), card = bpa, counterpart = CounterpartUi.Reference("CLE-00417"), transactionId = "QRP5513920087",
        balanceAfter = cup("11280.50"),
    )
    val rejectedToYunior = OperationUi(
        "op-rejected", OperationKind.Transfer, "Yunior Castillo", OperationStatus.Rejected, at(1, 18, 47),
        amount = cup("5000.00"), card = bandec, counterpart = CounterpartUi.Card("9205112233445565", "BANDEC"), contactId = yunior.id,
        reason = "Saldo insuficiente",
    )
    val noResponseNauta = OperationUi(
        "op-noresp", OperationKind.NautaWifi, "laura.mendez", OperationStatus.NoResponse, at(1, 16, 5),
        amount = cup("250.00"), card = bandec, counterpart = CounterpartUi.Reference("laura.mendez@nauta.com.cu"),
    )
    val electricity = OperationUi(
        "op-elec", OperationKind.Electricity, "Casa", OperationStatus.Confirmed, at(3, 11, 30),
        amount = cup("387.10"), card = bpa, counterpart = CounterpartUi.Reference("01234567891"), transactionId = "SRV2207719433",
    )
    val incomingUnknown = OperationUi(
        "op-in2", OperationKind.Incoming, "5299 1043", OperationStatus.Confirmed, at(3, 8, 2),
        amount = cup("750.00"), card = bandec, counterpart = CounterpartUi.Phone("52991043"), transactionId = "TRF8826087710",
    )
    val topUp = OperationUi(
        "op-topup", OperationKind.MobileTopUp, "Mi móvil", OperationStatus.Confirmed, at(6, 20, 15),
        amount = cup("500.00"), card = bandec, counterpart = CounterpartUi.Reference("53124578"), transactionId = "SRV2207640021",
    )
    val toUnknownCard = OperationUi(
        "op-card", OperationKind.Transfer, "9212 3331 8804 2154", OperationStatus.Confirmed, at(6, 10, 41),
        amount = cup("120.00"), card = bpa, counterpart = CounterpartUi.Card("9212333188042154", "BPA"), transactionId = "TRF8825990345",
    )

    val inProgress = listOf(sentToJuan, notExecuted)
    val history = listOf(
        sentToJuan, notExecuted, confirmedToMaria, incomingFromDaniela, qrCafe, rejectedToYunior, noResponseNauta,
        electricity, incomingUnknown, topUp, toUnknownCard,
    )
    val recent = listOf(confirmedToMaria, incomingFromDaniela, qrCafe, rejectedToYunior, electricity)

    const val receivePayload = "TRANSFERMOVIL_ETECSA,TRANSFERENCIA,9212060123456781,LAURA MENDEZ SOTO"
    const val merchantPayload = "{\"id_transaccion\":\"CLE-00417\",\"importe\":480.0,\"moneda\":\"CUP\",\"numero_proveedor\":\"0001234\"}"

    // Services --------------------------------------------------------------------------------------------------
    val references = listOf(
        SavedReferenceUi("ref-casa", ServiceKind.Electricity, "Casa", "01234567891", cup("387.10")),
        SavedReferenceUi("ref-abuela", ServiceKind.Electricity, "Casa de la abuela", "01987654320", cup("142.60")),
        SavedReferenceUi("ref-fijo", ServiceKind.Landline, "Fijo de la casa", "760412233100481"),
        SavedReferenceUi("ref-hogar", ServiceKind.NautaHome, "Nauta Hogar", "laura.mendez.hogar", cup("1000.00")),
        SavedReferenceUi("ref-wifi", ServiceKind.NautaWifi, "Nauta", "laura.mendez@nauta.com.cu", cup("250.00")),
        SavedReferenceUi("ref-movil", ServiceKind.MobileTopUp, "Mi móvil", "53124578", cup("500.00")),
    )
}
