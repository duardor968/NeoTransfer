package dev.duardo.neotransfer.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import dev.duardo.neotransfer.designsystem.R as Ds
import dev.duardo.neotransfer.designsystem.component.AvatarBadge
import dev.duardo.neotransfer.designsystem.component.IconAvatar
import dev.duardo.neotransfer.designsystem.component.MoneySign
import dev.duardo.neotransfer.designsystem.component.MoneyText
import dev.duardo.neotransfer.designsystem.component.NameAvatar
import dev.duardo.neotransfer.designsystem.component.NeoRow
import dev.duardo.neotransfer.designsystem.component.StatusChip
import dev.duardo.neotransfer.designsystem.component.StatusTone
import dev.duardo.neotransfer.designsystem.component.content
import dev.duardo.neotransfer.designsystem.theme.Neo
import dev.duardo.neotransfer.ui.model.OperationKind
import dev.duardo.neotransfer.ui.model.OperationStatus
import dev.duardo.neotransfer.ui.model.OperationUi
import dev.duardo.neotransfer.ui.model.ServiceKind

val OperationStatus.label: String
    get() = when (this) {
        OperationStatus.Sent -> "Enviada"
        OperationStatus.Confirmed -> "Confirmada"
        OperationStatus.Rejected -> "Rechazada"
        OperationStatus.NoResponse -> "Sin respuesta"
        OperationStatus.NotExecuted -> "No se ejecutó"
    }

val OperationStatus.tone: StatusTone
    get() = when (this) {
        OperationStatus.Sent -> StatusTone.Pending
        OperationStatus.Confirmed -> StatusTone.Positive
        OperationStatus.Rejected -> StatusTone.Danger
        OperationStatus.NoResponse -> StatusTone.Neutral
        OperationStatus.NotExecuted -> StatusTone.Warning
    }

@get:DrawableRes
val OperationStatus.icon: Int
    get() = when (this) {
        OperationStatus.Sent -> Ds.drawable.ic_schedule
        OperationStatus.Confirmed -> Ds.drawable.ic_check_circle
        OperationStatus.Rejected -> Ds.drawable.ic_cancel
        OperationStatus.NoResponse -> Ds.drawable.ic_hourglass_empty
        OperationStatus.NotExecuted -> Ds.drawable.ic_sync_problem
    }

/** Glyph inside the state mark: the operation's own while it travels, then the state's. */
@DrawableRes
fun OperationStatus.markIcon(kind: OperationKind): Int = when (this) {
    OperationStatus.Sent, OperationStatus.Confirmed -> kind.icon
    OperationStatus.Rejected -> Ds.drawable.ic_close
    OperationStatus.NoResponse -> Ds.drawable.ic_hourglass_empty
    OperationStatus.NotExecuted -> Ds.drawable.ic_sync_problem
}

/** Money did not move: the amount is shown struck through. */
val OperationStatus.moneyMoved: Boolean get() = this != OperationStatus.Rejected && this != OperationStatus.NotExecuted

@Composable
fun OperationStatusChip(status: OperationStatus, modifier: Modifier = Modifier) {
    StatusChip(status.label, status.tone, status.icon, modifier)
}

val OperationKind.label: String
    get() = when (this) {
        OperationKind.Transfer -> "Transferencia"
        OperationKind.Incoming -> "Transferencia recibida"
        OperationKind.QrPayment -> "Pago con QR"
        OperationKind.Electricity -> "Electricidad"
        OperationKind.Water -> "Agua"
        OperationKind.Landline -> "Teléfono fijo"
        OperationKind.NautaHome -> "Nauta Hogar"
        OperationKind.NautaWifi -> "Nauta Wi‑Fi"
        OperationKind.MobileTopUp -> "Recarga móvil"
        OperationKind.CubacelPlan -> "Paquete Cubacel"
        OperationKind.CubacelTransfer -> "Saldo Cubacel transferido"
    }

@get:DrawableRes
val OperationKind.icon: Int
    get() = when (this) {
        OperationKind.Transfer -> Ds.drawable.ic_arrow_outward
        OperationKind.Incoming -> Ds.drawable.ic_south_west
        OperationKind.QrPayment -> Ds.drawable.ic_qr_code
        OperationKind.Electricity -> Ds.drawable.ic_bolt
        OperationKind.Water -> Ds.drawable.ic_water_drop
        OperationKind.Landline -> Ds.drawable.ic_deskphone
        OperationKind.NautaHome -> Ds.drawable.ic_router
        OperationKind.NautaWifi -> Ds.drawable.ic_wifi
        OperationKind.MobileTopUp -> Ds.drawable.ic_smartphone
        OperationKind.CubacelPlan -> Ds.drawable.ic_package_2
        OperationKind.CubacelTransfer -> Ds.drawable.ic_swap_horiz
    }

@get:DrawableRes
val ServiceKind.icon: Int
    get() = when (this) {
        ServiceKind.Electricity -> Ds.drawable.ic_bolt
        ServiceKind.Water -> Ds.drawable.ic_water_drop
        ServiceKind.Landline -> Ds.drawable.ic_deskphone
        ServiceKind.NautaHome -> Ds.drawable.ic_router
        ServiceKind.NautaWifi -> Ds.drawable.ic_wifi
        ServiceKind.MobileTopUp -> Ds.drawable.ic_smartphone
        ServiceKind.Cubacel -> Ds.drawable.ic_sim_card
    }

/** Who or what: initials with a direction badge for people, the service glyph otherwise. */
@Composable
fun OperationAvatar(operation: OperationUi) {
    val person = operation.kind == OperationKind.Transfer || operation.kind == OperationKind.Incoming
    if (person && operation.contactId != null) {
        val badge = if (operation.kind.incoming) {
            AvatarBadge(Ds.drawable.ic_south_west, Neo.colors.accent, Neo.colors.onAccent)
        } else {
            AvatarBadge(Ds.drawable.ic_arrow_outward, MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurface)
        }
        NameAvatar(operation.title, badge = badge)
    } else {
        IconAvatar(operation.kind.icon)
    }
}

/** Amount as listed: incoming in positive ink with a plus; money that never moved, struck through. */
@Composable
fun OperationAmount(operation: OperationUi, modifier: Modifier = Modifier) {
    val money = operation.amount ?: return
    val moved = operation.status.moneyMoved
    val incoming = operation.kind.incoming
    MoneyText(
        money.amount,
        money.currency,
        modifier,
        style = MaterialTheme.typography.titleMedium.copy(textDecoration = if (moved) null else TextDecoration.LineThrough),
        color = when {
            !moved -> MaterialTheme.colorScheme.onSurfaceVariant
            incoming && operation.status == OperationStatus.Confirmed -> Neo.colors.positive
            else -> MaterialTheme.colorScheme.onSurface
        },
        sign = if (incoming) MoneySign.Plus else MoneySign.None,
    )
}

/**
 * Row of a movement. [when] is the time label chosen by the list (clock time inside a day group, short date
 * elsewhere); the state is added unless the operation is confirmed.
 */
@Composable
fun OperationRow(operation: OperationUi, timeLabel: String, onClick: () -> Unit, modifier: Modifier = Modifier, title: String = operation.title) {
    NeoRow(
        title = title,
        modifier = modifier,
        leading = { OperationAvatar(operation) },
        supportingContent = {
            val status = operation.status
            val text = if (status == OperationStatus.Confirmed) timeLabel else "$timeLabel · ${status.label}"
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = if (status.tone == StatusTone.Danger || status.tone == StatusTone.Warning) status.tone.content() else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailing = { OperationAmount(operation) },
        onClick = onClick,
        trailingBelowWhenLarge = true,
    )
}
