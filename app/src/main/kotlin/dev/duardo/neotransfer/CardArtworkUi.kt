package dev.duardo.neotransfer

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.core.*

internal data class CardArtwork(val resource: Int, val numberLeft: Float, val numberTop: Float, val numberWidth: Float)

internal fun cardArtwork(identity: ProviderIdentity): CardArtwork? = when {
    identity.profile == ProfileId.CLASSIC -> CardArtwork(R.drawable.card_clasica_personal, .43f, .715f, .54f)
    identity.provider == ProviderId.BANDEC -> CardArtwork(R.drawable.card_bandec_red, .108f, .46f, .82f)
    identity.provider == ProviderId.BPA -> CardArtwork(R.drawable.card_bpa_red, .10f, .445f, .82f)
    identity.provider == ProviderId.BANMET -> CardArtwork(R.drawable.card_banmet_red, .10f, .44f, .80f)
    else -> null
}

/** Printed artwork is separate from accessible, known account data. */
@Composable
internal fun RestoredCard(product: WalletProductUi, artwork: CardArtwork, modifier: Modifier, click: () -> Unit) {
    val density = LocalDensity.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth().aspectRatio(1.586f)
            .graphicsLayer { shadowElevation = 5.dp.toPx(); shape = RoundedCornerShape(12.dp); clip = true }
            .clickable(onClick = click)
            .clearAndSetSemantics {
                contentDescription = listOfNotNull(product.identity.title(), product.name, product.number?.let(::readableAccount), product.currency?.name,
                    product.card?.holderName, product.card?.expiry?.let { "Vence $it" }).joinToString(" · ")
                role = Role.Button
                onClick("Gestionar tarjeta") { click(); true }
            }) {
            Image(painterResource(artwork.resource), null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
            product.number?.let { number ->
                val width = maxWidth * artwork.numberWidth
                Box(Modifier.offset(x = maxWidth * artwork.numberLeft, y = maxHeight * artwork.numberTop)
                    .width(width).height(maxHeight * .14f), contentAlignment = Alignment.Center) {
                    Text(readableAccount(number), color = Color(0xFF111411), fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Normal, maxLines = 1,
                        fontSize = with(density) { (width / 10.8f).toSp() })
                }
            }
            if (product.identity.bank in setOf(Bank.BPA, Bank.BANDEC)) {
                val holderTop = if (product.identity.bank == Bank.BPA) .72f else .76f
                product.card?.holderName?.let { name ->
                    Text(name, Modifier.offset(x = maxWidth * .087f, y = maxHeight * holderTop).width(maxWidth * .70f),
                        color = Color(0xFF111411), fontSize = with(density) { (maxHeight * .085f).toSp() }, maxLines = 1)
                }
                product.currency?.let { currency ->
                    Text(currency.name, Modifier.offset(x = maxWidth * .06f, y = maxHeight * .858f),
                        color = Color(0xFF111411), fontSize = with(density) { (maxHeight * .085f).toSp() })
                }
                Text("VENCE:" + product.card?.expiry?.let { " $it" }.orEmpty(),
                    Modifier.offset(x = maxWidth * .30f, y = maxHeight * .858f),
                    color = Color(0xFF111411), fontSize = with(density) { (maxHeight * .085f).toSp() })
            }
            product.card?.expiry?.takeIf { product.identity.bank !in setOf(Bank.BPA, Bank.BANDEC) }?.let { expiry ->
                val classic = product.identity.profile == ProfileId.CLASSIC
                val left = when { classic -> .735f; product.identity.provider == ProviderId.BANMET -> .49f; else -> .445f }
                val top = when { classic -> .90f; product.identity.provider == ProviderId.BANMET -> .81f; else -> .858f }
                Text(expiry, Modifier.offset(x = maxWidth * left, y = maxHeight * top),
                    color = if (classic) Color(0xFFC6B75D) else Color(0xFF111411),
                    fontSize = with(density) { (maxHeight * if (classic) .052f else .085f).toSp() })
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(product.name.ifBlank { product.identity.title() }, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            product.currency?.let { Text(it.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (density.fontScale > 1.2f) product.number?.let { Text(readableAccount(it), style = MaterialTheme.typography.titleMedium) }
    }
}
