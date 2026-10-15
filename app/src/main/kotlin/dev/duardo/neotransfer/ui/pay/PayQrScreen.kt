package dev.duardo.neotransfer.ui.pay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.designsystem.R as Ds
import dev.duardo.neotransfer.designsystem.component.IconAvatar
import dev.duardo.neotransfer.designsystem.component.MoneyText
import dev.duardo.neotransfer.designsystem.component.NeoButton
import dev.duardo.neotransfer.designsystem.component.NeoButtonStyle
import dev.duardo.neotransfer.designsystem.component.NeoTextField
import dev.duardo.neotransfer.designsystem.format.formatMoney
import dev.duardo.neotransfer.designsystem.theme.Neo
import dev.duardo.neotransfer.designsystem.theme.NeoRadius
import dev.duardo.neotransfer.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.ui.common.LightSystemBars
import dev.duardo.neotransfer.ui.common.SourceCardPicker
import dev.duardo.neotransfer.ui.model.PayQrUi
import dev.duardo.neotransfer.ui.model.PayReviewUi
import java.math.BigDecimal

interface PayQrActions {
    fun close()
    fun addCard()
    fun amount(value: String)
    fun reference(value: String)
    fun source(index: Int)
    fun pay()
    fun rescan()
}

/**
 * Pay a merchant QR. The camera fills the screen ([cameraContent] is the scanner module's view, with its own frame,
 * gallery and torch); once a code is read, the review rises from the bottom with only what can still change.
 */
@Composable
fun PayQrScreen(state: PayQrUi, actions: PayQrActions, cameraContent: @Composable () -> Unit, modifier: Modifier = Modifier) {
    LightSystemBars()
    Box(modifier.fillMaxSize().background(Color.Black)) {
        cameraContent()
        CameraBar(actions, Modifier.align(Alignment.TopCenter))
        AnimatedVisibility(
            visible = state !is PayQrUi.Scanning,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it },
            exit = slideOutVertically { it },
        ) {
            ReviewPanel {
                when (state) {
                    is PayQrUi.Review -> Review(state.review, actions)
                    is PayQrUi.Unsupported -> Unsupported(state, actions)
                    is PayQrUi.Scanning -> Unit
                }
            }
        }
    }
}

@Composable
private fun CameraBar(actions: PayQrActions, modifier: Modifier) {
    Row(
        modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = NeoSpace.s, vertical = NeoSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CameraButton(Ds.drawable.ic_close, "Cerrar", actions::close)
        Text("Pagar", Modifier.weight(1f).padding(start = NeoSpace.m), style = MaterialTheme.typography.titleLarge, color = Color.White)
    }
}

@Composable
private fun CameraButton(icon: Int, description: String, onClick: () -> Unit) {
    IconButton(onClick, Modifier.size(48.dp).background(Color.Black.copy(alpha = 0.38f), CircleShape)) {
        Icon(painterResource(icon), description, tint = Color.White)
    }
}

@Composable
private fun ReviewPanel(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Neo.colors.group, RoundedCornerShape(topStart = NeoRadius.sheet, topEnd = NeoRadius.sheet))
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = NeoSpace.gutter)
            .padding(top = NeoSpace.s, bottom = NeoSpace.l),
    ) {
        Box(
            Modifier.align(Alignment.CenterHorizontally).padding(vertical = NeoSpace.s).size(width = 32.dp, height = 4.dp)
                .background(MaterialTheme.colorScheme.outlineVariant, CircleShape),
        )
        content()
    }
}

@Composable
private fun Review(review: PayReviewUi, actions: PayQrActions) {
    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(NeoSpace.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NeoSpace.m)) {
            IconAvatar(Ds.drawable.ic_qr_code)
            Text(review.merchant, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
        }
        if (review.amountEditable) {
            NeoTextField(review.amountInput, actions::amount, "Importe", keyboardType = KeyboardType.Decimal, suffix = review.currency)
        } else {
            val amount = review.amountInput.toBigDecimalOrNull() ?: BigDecimal.ZERO
            MoneyText(amount, review.currency, Modifier.padding(vertical = NeoSpace.xs), style = MaterialTheme.typography.displaySmall, hero = true)
        }
        if (review.referenceEditable) NeoTextField(review.reference, actions::reference, review.referenceLabel)
        else Row(Modifier.fillMaxWidth().padding(vertical = NeoSpace.xs), horizontalArrangement = Arrangement.spacedBy(NeoSpace.m)) {
            Text(review.referenceLabel, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(review.reference, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium))
        }
        SourceCardPicker(review.sources, review.source, actions::source)
        val amount = review.amountInput.toBigDecimalOrNull()
        NeoButton(
            if (amount != null && amount.signum() > 0) "Pagar ${formatMoney(amount, review.currency)}" else "Pagar",
            actions::pay,
            icon = Ds.drawable.ic_fingerprint,
            enabled = review.canPay,
            loading = review.paying,
        )
    }
}

@Composable
private fun Unsupported(state: PayQrUi.Unsupported, actions: PayQrActions) {
    Column(Modifier.padding(vertical = NeoSpace.s), verticalArrangement = Arrangement.spacedBy(NeoSpace.l)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(NeoSpace.m)) {
            Box(Modifier.size(44.dp).background(Neo.colors.warningContainer, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(Ds.drawable.ic_error), null, tint = Neo.colors.warning)
            }
            Text(state.message, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        }
        Column(verticalArrangement = Arrangement.spacedBy(NeoSpace.s)) {
            if (state.addCard) NeoButton("Añadir tarjeta", actions::addCard, icon = Ds.drawable.ic_add_card)
            NeoButton("Escanear otro", actions::rescan, style = NeoButtonStyle.Tonal)
        }
    }
}
