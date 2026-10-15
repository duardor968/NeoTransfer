package dev.duardo.neotransfer.ui.receive

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.duardo.neotransfer.designsystem.R as Ds
import dev.duardo.neotransfer.designsystem.component.MoneyText
import dev.duardo.neotransfer.designsystem.component.NeoButton
import dev.duardo.neotransfer.designsystem.component.NeoButtonStyle
import dev.duardo.neotransfer.designsystem.component.NeoIconButton
import dev.duardo.neotransfer.designsystem.component.NeoTopBar
import dev.duardo.neotransfer.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.ui.common.BrandMark
import dev.duardo.neotransfer.ui.common.CardPicker
import dev.duardo.neotransfer.ui.model.ReceiveUi
import dev.duardo.neotransfer.ui.onboarding.BottomAction

interface ReceiveActions {
    fun back()
    fun card(index: Int)
    fun requestAmount()
    fun clearAmount()
    fun share()
    fun copyNumber()
}

/** QR of one of my cards for someone else to transfer to it, optionally with the amount already set. */
@Composable
fun ReceiveScreen(state: ReceiveUi, actions: ReceiveActions, modifier: Modifier = Modifier) {
    val card = state.cards[state.selected]
    Scaffold(
        modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { NeoTopBar("Cobrar", onBack = actions::back, actions = { NeoIconButton(Ds.drawable.ic_content_copy, "Copiar número", actions::copyNumber) }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(NeoSpace.xl))
                state.amount?.let { money ->
                    MoneyText(money.amount, money.currency, style = MaterialTheme.typography.displaySmall, hero = true)
                    Spacer(Modifier.height(NeoSpace.l))
                }
                // A QR is read dark on light: the plate stays white in both themes.
                Box(
                    Modifier
                        .padding(horizontal = NeoSpace.huge)
                        .widthIn(max = 320.dp)
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(Color.White, RoundedCornerShape(28.dp))
                        .padding(NeoSpace.xl),
                    contentAlignment = Alignment.Center,
                ) {
                    QrCode(state.payload, Modifier.fillMaxSize().semantics { contentDescription = "Código QR de la tarjeta ${card.bank.shortName}" })
                    Box(Modifier.size(64.dp).background(Color.White, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                        BrandMark(52.dp)
                    }
                }
                Spacer(Modifier.height(NeoSpace.xl))
                CardPicker(
                    state.cards, state.selected, actions::card, "Cobrar en",
                    { listOfNotNull(state.cards[it].bank.shortName, state.cards[it].currency, state.cards[it].holder).joinToString(" · ") },
                    Modifier.padding(horizontal = NeoSpace.gutter),
                )
            }
            BottomAction {
                Row(horizontalArrangement = Arrangement.spacedBy(NeoSpace.s)) {
                    if (state.amount == null) NeoButton("Pedir importe", actions::requestAmount, Modifier.weight(1f), style = NeoButtonStyle.Tonal)
                    else NeoButton("Quitar importe", actions::clearAmount, Modifier.weight(1f), style = NeoButtonStyle.Tonal)
                    NeoButton("Compartir", actions::share, Modifier.weight(1f), icon = Ds.drawable.ic_share)
                }
            }
        }
    }
}

/** QR modules drawn as squares, high error correction so the mark in the middle never breaks the read. */
@Composable
fun QrCode(payload: String, modifier: Modifier = Modifier, color: Color = Color(0xFF111316)) {
    val matrix = remember(payload) { encode(payload) }
    Canvas(modifier) {
        val cell = size.minDimension / matrix.width
        val offset = (size.minDimension - cell * matrix.width) / 2
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            if (matrix[x, y]) drawRect(color, Offset(offset + x * cell, offset + y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}

private fun encode(payload: String): BitMatrix = QRCodeWriter().encode(
    payload, BarcodeFormat.QR_CODE, 0, 0,
    mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H, EncodeHintType.MARGIN to 0, EncodeHintType.CHARACTER_SET to "UTF-8"),
)
