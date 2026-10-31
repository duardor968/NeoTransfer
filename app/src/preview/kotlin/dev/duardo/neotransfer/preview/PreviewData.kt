package dev.duardo.neotransfer.preview

import dev.duardo.neotransfer.designsystem.format.formatPhone
import dev.duardo.neotransfer.ui.model.BalanceUi
import dev.duardo.neotransfer.ui.model.Bank
import dev.duardo.neotransfer.ui.model.CardUi
import dev.duardo.neotransfer.ui.model.ContactUi
import dev.duardo.neotransfer.ui.model.CounterpartUi
import dev.duardo.neotransfer.ui.model.ForeignCardUi
import dev.duardo.neotransfer.ui.model.LastMovementUi
import dev.duardo.neotransfer.ui.model.Money
import dev.duardo.neotransfer.ui.model.OperationKind
import dev.duardo.neotransfer.ui.model.OperationStatus
import dev.duardo.neotransfer.ui.model.OperationUi
import dev.duardo.neotransfer.ui.model.Origin
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
    private const val DAY = 24 * 60L
    fun at(daysAgo: Long, hour: Int, minute: Int): Instant = now.minusDays(daysAgo).withHour(hour).withMinute(minute).toInstant()

    fun cup(value: String) = Money(BigDecimal(value), "CUP")

    val bpa = CardUi("bpa-1", Bank.BPA, "9212120123456783", "CUP", holder = "LAURA MENDEZ SOTO", expiry = "03/29")
    val bandec = CardUi("bandec-1", Bank.BANDEC, "9205069987654323", "CUP", holder = "LAURA MENDEZ SOTO", expiry = "11/30")
    val bpaUsd = CardUi("bpa-2", Bank.BPA, "9225120155520196", "USD")
    val banmet = CardUi("banmet-1", Bank.BANMET, "9205959800000003", "CUP", holder = "LAURA MENDEZ SOTO", expiry = "10/28")

    val wallet = listOf(
        WalletCardUi(bpa, BalanceUi.Known(cup("12480.50"), ago(5))),
        WalletCardUi(bandec, BalanceUi.Known(cup("3215.00"), ago(185))),
    )

    // Contacts --------------------------------------------------------------------------------------------------
    val juan = ContactUi(
        "c-juan", "Juan Carlos Pérez",
        listOf(
            ForeignCardUi("jc-1", "9204069901447185", Bank.BANDEC, "CUP", "Salario"),
            ForeignCardUi("jc-2", "9212129931880423", Bank.BPA, "CUP"),
        ),
        listOf("53124578", "78302214"),
        last = LastMovementUi(OperationKind.Transfer, cup("1500.00"), ago(13 * DAY)),
    )
    val maria = ContactUi("c-maria", "María Fernanda Ruiz", listOf(ForeignCardUi("mf-1", "9224069922901638", Bank.BANDEC, "CUP")), listOf("52981134"),
        fromAddressBook = true, last = LastMovementUi(OperationKind.Incoming, cup("300.00"), ago(17 * DAY)))
    val carmen = ContactUi("c-carmen", "Abuela Carmen", listOf(ForeignCardUi("ca-1", "9205959803124479", Bank.BANMET, "CUP")), listOf("54417720"))
    val yunior = ContactUi("c-yunior", "Yunior Castillo", listOf(ForeignCardUi("yc-1", "9204069912334455", Bank.BANDEC, "CUP")), listOf("52990127"),
        fromAddressBook = true, last = LastMovementUi(OperationKind.Incoming, cup("10000.00"), ago(2 * DAY + 190)))
    val daniela = ContactUi("c-daniela", "Daniela Ortiz", listOf(ForeignCardUi("do-1", "9212129977003195", Bank.BPA, "CUP")), listOf("58337102"),
        fromAddressBook = true, last = LastMovementUi(OperationKind.MobileTopUp, cup("500.00"), ago(4 * DAY)))
    val mama = ContactUi("c-mama", "Mamá", listOf(ForeignCardUi("ma-1", "9244069900123318", Bank.BANDEC, "CUP")), listOf("53019944"),
        fromAddressBook = true, last = LastMovementUi(OperationKind.Transfer, cup("2000.00"), ago(40)))
    val rafael = ContactUi("c-rafael", "Rafael Torres", emptyList(), listOf("53660981"), fromAddressBook = true)
    val mario = ContactUi("c-mario", "Mario Almeida", emptyList(), listOf("52339054"), fromAddressBook = true)
    val contacts = listOf(carmen, daniela, juan, maria, rafael, yunior)
    /** The Contacts tab: a movement or a card, newest movement first. */
    val people = listOf(mama, yunior, daniela, juan, maria, carmen)
    /** Everyone, as "Todos los contactos" lists them. */
    val everyone = listOf(
        carmen,
        ContactUi("c-adrian", "Adrián Gómez", emptyList(), listOf("53127781"), fromAddressBook = true),
        ContactUi("c-ana", "Ana Laura", emptyList(), listOf("53981204"), fromAddressBook = true),
        ContactUi("c-beatriz", "Beatriz Morales", emptyList(), listOf("52416630"), fromAddressBook = true),
        ContactUi("c-bryan", "Bryan", emptyList(), listOf("55770912"), fromAddressBook = true),
        ContactUi("c-carlos", "Carlos (taller)", emptyList(), listOf("53204417"), fromAddressBook = true),
        daniela, juan, mama, maria, mario, rafael, yunior,
    )

    val juanRecipient = RecipientUi(juan.cards.first(), juan.name, juan.id)

    // Operations ------------------------------------------------------------------------------------------------
    val sentToJuan = OperationUi(
        "op-sent", OperationKind.Transfer, "Juan Carlos Pérez", OperationStatus.Sent, ago(1),
        amount = cup("300.00"), card = bpa, counterpart = CounterpartUi.Card("9204069901447185", "BANDEC · CUP"), contactId = juan.id,
    )
    val notExecuted = OperationUi(
        "op-busy", OperationKind.Electricity, "Casa", OperationStatus.NotExecuted, ago(4),
        amount = cup("412.35"), card = bpa, counterpart = CounterpartUi.Reference("01234567891"),
        reason = "El banco estaba ocupado",
    )
    val confirmedToMaria = OperationUi(
        "op-maria", OperationKind.Transfer, "María Fernanda Ruiz", OperationStatus.Confirmed, ago(52),
        amount = cup("1500.00"), card = bpa, counterpart = CounterpartUi.Card("9224069922901638", "BANDEC · CUP"), contactId = maria.id,
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
        amount = cup("5000.00"), card = bandec, counterpart = CounterpartUi.Card("9204069912334455", "BANDEC · CUP"), contactId = yunior.id,
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
        amount = cup("120.00"), card = bpa, counterpart = CounterpartUi.Card("9212129931880423", "BPA · CUP"), transactionId = "TRF8825990345",
    )
    /** BANDEC's own alert of a withdrawal at a branch. */
    val branchWithdrawal = OperationUi(
        "op-branch", OperationKind.Transfer, "Extracción en sucursal", OperationStatus.Confirmed, at(6, 5, 1),
        amount = cup("15000.00"), card = bandec, transactionId = "OL60000000001",
    )

    /** The newest money moved with Yunior, for his page. */
    val withYunior = listOf(
        OperationUi("op-yc-in", OperationKind.Incoming, "Yunior Castillo", OperationStatus.Confirmed, at(2, 11, 29),
            amount = cup("10000.00"), card = bandec, counterpart = CounterpartUi.Phone("52990127"), contactId = yunior.id),
        OperationUi("op-yc-out", OperationKind.Transfer, "Yunior Castillo", OperationStatus.Confirmed, at(44, 16, 5),
            amount = cup("800.00"), card = bpa, counterpart = CounterpartUi.Card("9204069912334455", "BANDEC · CUP"), contactId = yunior.id),
    )

    val inProgress = listOf(sentToJuan, notExecuted)
    val history = listOf(
        sentToJuan, notExecuted, confirmedToMaria, incomingFromDaniela, qrCafe, rejectedToYunior, noResponseNauta,
        electricity, incomingUnknown, topUp, toUnknownCard, branchWithdrawal,
    )
    val recent = listOf(confirmedToMaria, incomingFromDaniela, qrCafe, rejectedToYunior, electricity)

    // With NeoTransfer+ installed ------------------------------------------------------------------------------------
    /** NeoTransfer+ paid through ENZONA; the bank has not answered yet. Read-only here. */
    val enzonaPending = OperationUi(
        "peer:enz-qr", OperationKind.QrPayment, "Farmacia Galeno", OperationStatus.Sent, ago(20),
        amount = cup("640.00"), card = bpa, counterpart = CounterpartUi.Reference("FG-118204"), origin = Origin.Enzona,
    )
    /** NeoTransfer+'s transfer and the bank's receipt of it here: one item. */
    val enzonaToCarmen = OperationUi(
        "ref:TRF8826099120", OperationKind.Transfer, "Abuela Carmen", OperationStatus.Confirmed, at(0, 10, 15),
        amount = cup("3000.00"), card = bandec, counterpart = CounterpartUi.Card("9205959803124479", "BANMET · CUP"), contactId = carmen.id,
        transactionId = "TRF8826099120", balanceAfter = cup("3215.00"), origin = Origin.Enzona,
    )
    val enzonaService = OperationUi(
        "peer:enz-gas", OperationKind.Service, "Gas licuado", OperationStatus.Confirmed, at(2, 19, 30),
        amount = cup("210.00"), card = bpa, counterpart = CounterpartUi.Reference("GL-30018827"), origin = Origin.Enzona,
    )
    private fun OperationUi.here() = copy(origin = Origin.Ussd)
    val sharedInProgress = inProgress.map { it.here() }
    /** Every operation marked by the app that made it; receipts of unknown origin, unmarked. */
    val sharedHistory = listOf(
        sentToJuan.here(), notExecuted.here(), enzonaPending, confirmedToMaria.here(), incomingFromDaniela, enzonaToCarmen, qrCafe.here(),
        rejectedToYunior.here(), noResponseNauta.here(), enzonaService, electricity.here(), incomingUnknown, topUp.here(), toUnknownCard,
        branchWithdrawal,
    )
    val sharedRecent = listOf(enzonaPending, confirmedToMaria.here(), incomingFromDaniela, enzonaToCarmen, qrCafe.here())

    const val receivePayload = "TRANSFERMOVIL_ETECSA,TRANSFERENCIA,9212120123456783,LAURA MENDEZ SOTO"
    const val merchantPayload = "{\"id_transaccion\":\"CLE-00417\",\"importe\":480.0,\"moneda\":\"CUP\",\"numero_proveedor\":\"0001234\"}"

    // The Clásica -----------------------------------------------------------------------------------------------
    fun usd(value: String) = Money(BigDecimal(value), "USD")

    /** A Clásica of Fincimex: `976003…`, always USD, no holder; its balance is MiTransfer's text as it came. */
    val classic = CardUi("classic-1", Bank.CLASSIC, "9760030312345679", "USD")
    val classicWallet = WalletCardUi(classic, BalanceUi.Text("Su saldo es 86.40 USD", ago(12)))
    val elena = ContactUi(
        "c-elena", "Elena Soto", listOf(ForeignCardUi("es-1", "9760030155200783", Bank.CLASSIC, "USD")), listOf("53774410"),
        last = LastMovementUi(OperationKind.Transfer, usd("20.00"), ago(3 * DAY)),
    )
    val elenaRecipient = RecipientUi(elena.cards.first(), elena.name, elena.id)
    val classicFuel = OperationUi(
        "op-fuel", OperationKind.QrPayment, "Servicentro La Rotonda", OperationStatus.Confirmed, at(0, 9, 12),
        amount = usd("18.00"), card = classic, counterpart = CounterpartUi.Reference("SLR-20931"), transactionId = "MT000P1",
    )
    val classicToElena = OperationUi(
        "op-elena", OperationKind.Transfer, "Elena Soto", OperationStatus.Confirmed, at(3, 17, 40),
        amount = usd("20.00"), card = classic, counterpart = CounterpartUi.Card("9760030155200783", "Clásica · USD"), contactId = elena.id,
        transactionId = "MT000T1",
    )
    val classicRecent = listOf(classicFuel, classicToElena)

    /** MiTransfer's last operations (`48`) in invented wording: the app shows them as they come, never as rows. */
    const val classicHistoryText = "Ultimas operaciones de la tarjeta 9760XXXXXXXX5679: 15/10/2026 Pago Servicentro La Rotonda 18.00 USD; " +
        "12/10/2026 Transferencia a 9760XXXXXXXX0783 20.00 USD; 08/10/2026 Transferencia recibida 50.00 USD."

    // Services --------------------------------------------------------------------------------------------------
    val references = listOf(
        SavedReferenceUi("ref-casa", ServiceKind.Electricity, "Casa", "01234567891", cup("387.10")),
        SavedReferenceUi("ref-abuela", ServiceKind.Electricity, "Casa de la abuela", "01987654320", cup("142.60")),
        SavedReferenceUi("ref-fijo", ServiceKind.Landline, "Fijo de la casa", "760412233100481", cup("155.40")),
        SavedReferenceUi("ref-hogar", ServiceKind.NautaHome, "Nauta Hogar", "laura.mendez.hogar", cup("1000.00")),
        SavedReferenceUi("ref-wifi", ServiceKind.NautaWifi, "Nauta", "laura.mendez@nauta.com.cu", cup("250.00")),
        SavedReferenceUi("ref-movil", ServiceKind.MobileTopUp, "Mi móvil", formatPhone("53124578"), cup("500.00")),
    )
}
