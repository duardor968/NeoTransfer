package dev.duardo.neotransfer.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.R
import dev.duardo.neotransfer.designsystem.card.CardAspectRatio
import dev.duardo.neotransfer.designsystem.card.CardFace
import dev.duardo.neotransfer.designsystem.card.CardFaceLayout
import dev.duardo.neotransfer.designsystem.card.CardPrint
import dev.duardo.neotransfer.designsystem.format.formatCardNumber
import dev.duardo.neotransfer.designsystem.theme.Neo
import dev.duardo.neotransfer.ui.model.Bank
import dev.duardo.neotransfer.ui.model.CardUi

/**
 * Print positions measured on rectified photos of physical cards (ID-1, card-printer output, Arial metrics):
 * number centred in its band, then holder, then currency and expiry on one line. BANDEC prints lower than BPA.
 */
private val BpaPrint = CardFaceLayout(
    number = CardPrint(x = 0.107f, baseline = 0.582f, size = 0.083f, maxWidth = 0.84f),
    holder = CardPrint(x = 0.093f, baseline = 0.794f, size = 0.041f, maxWidth = 0.68f),
    currency = CardPrint(x = 0.059f, baseline = 0.895f, size = 0.041f, maxWidth = 0.20f),
    expiry = CardPrint(x = 0.303f, baseline = 0.895f, size = 0.041f, maxWidth = 0.45f),
)

private val BandecPrint = CardFaceLayout(
    number = CardPrint(x = 0.107f, baseline = 0.600f, size = 0.083f, maxWidth = 0.84f),
    holder = CardPrint(x = 0.092f, baseline = 0.830f, size = 0.041f, maxWidth = 0.70f),
    currency = CardPrint(x = 0.057f, baseline = 0.926f, size = 0.041f, maxWidth = 0.20f),
    expiry = CardPrint(x = 0.302f, baseline = 0.926f, size = 0.041f, maxWidth = 0.45f),
)

@get:DrawableRes
val Bank.art: Int
    get() = when (this) {
        Bank.BPA -> R.drawable.card_bpa_red
        Bank.BANDEC -> R.drawable.card_bandec_red
    }

private val Bank.print: CardFaceLayout
    get() = when (this) {
        Bank.BPA -> BpaPrint
        Bank.BANDEC -> BandecPrint
    }

/** The user's card, 1:1 with the plastic. */
@Composable
fun BankCard(card: CardUi, modifier: Modifier = Modifier, elevation: Dp = 10.dp, onClick: (() -> Unit)? = null) {
    CardFace(
        art = painterResource(card.bank.art),
        layout = card.bank.print,
        number = card.number,
        holder = card.holder,
        expiry = card.expiry,
        currency = card.currency,
        contentDescription = "Tarjeta ${card.bank.shortName} ${formatCardNumber(card.number)}, ${card.currency}",
        modifier = modifier,
        elevation = elevation,
        onClick = onClick,
    )
}

/** The issuer's card with nothing printed: choosing a bank. */
@Composable
fun BankCardArt(bank: Bank, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    CardFace(
        art = painterResource(bank.art),
        layout = bank.print,
        number = "",
        contentDescription = bank.fullName,
        modifier = modifier,
        onClick = onClick,
    )
}

/** Card art without print, for list rows and pickers. */
@Composable
fun BankCardThumb(bank: Bank, modifier: Modifier = Modifier, width: Dp = 44.dp) {
    val shape = RoundedCornerShape(percent = 10)
    Image(
        painterResource(bank.art),
        null,
        modifier
            .width(width)
            .aspectRatio(CardAspectRatio)
            .clip(shape)
            .border(1.dp, Neo.colors.divider, shape),
        contentScale = ContentScale.FillBounds,
    )
}
