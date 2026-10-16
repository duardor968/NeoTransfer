package dev.duardo.neotransfer.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.R
import dev.duardo.neotransfer.designsystem.R as Ds
import dev.duardo.neotransfer.designsystem.card.CardAspectRatio
import dev.duardo.neotransfer.designsystem.card.CardFace
import dev.duardo.neotransfer.designsystem.card.CardFaceLayout
import dev.duardo.neotransfer.designsystem.card.CardPrint
import dev.duardo.neotransfer.designsystem.component.StatusChip
import dev.duardo.neotransfer.designsystem.component.StatusTone
import dev.duardo.neotransfer.designsystem.component.pressScale
import dev.duardo.neotransfer.designsystem.component.rim
import dev.duardo.neotransfer.designsystem.format.formatCardNumber
import dev.duardo.neotransfer.designsystem.theme.Neo
import dev.duardo.neotransfer.designsystem.theme.NeoRadius
import dev.duardo.neotransfer.designsystem.theme.NeoSpace
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

/** The card's own colour, sampled from its art: the halo behind it in the wallet. */
val Bank.artColor: Color
    get() = when (this) {
        Bank.BPA -> Color(0xFF1E7A5E)
        Bank.BANDEC -> Color(0xFFCC2A45)
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

@get:DrawableRes
private val Bank.logo: Int
    get() = when (this) {
        Bank.BPA -> R.drawable.bank_logo_bpa
        Bank.BANDEC -> R.drawable.bank_logo_bandec
    }

/** What each bank prints its logo on: BPA's green logo on white, BANDEC's white logo on its red. */
private val Bank.logoGround: Color
    get() = when (this) {
        Bank.BPA -> Color.White
        Bank.BANDEC -> artColor
    }

/** Heights that give both logos the same weight: BPA's heavy lettering goes smaller. */
private val Bank.logoHeight: Dp
    get() = when (this) {
        Bank.BPA -> 44.dp
        Bank.BANDEC -> 56.dp
    }

/**
 * The disabled look: [keep] of the colour over the [page] behind it. Material's 38 % on a light page; on a dark one
 * that leaves a white ground as bright as a live tile, so it keeps 25 %.
 */
private fun Color.fadedOver(page: Color, keep: Float): Color = copy(alpha = keep).compositeOver(page)

/** [fadedOver] for every pixel of an image, so a logo fades with its ground instead of into it. */
private fun FadedOver(page: Color, keep: Float) = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            keep, 0f, 0f, 0f, (1 - keep) * page.red * 255,
            0f, keep, 0f, 0f, (1 - keep) * page.green * 255,
            0f, 0f, keep, 0f, (1 - keep) * page.blue * 255,
            0f, 0f, 0f, 1f, 0f,
        ),
    ),
)

/**
 * The bank's logo on its own ground: choosing a bank. A bank already [configured] is faded like a disabled control,
 * with its status under the logo.
 */
@Composable
fun BankLogoTile(bank: Bank, configured: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(NeoRadius.group)
    val interaction = remember { MutableInteractionSource() }
    val page = MaterialTheme.colorScheme.background
    val keep = if (Neo.colors.isDark) 0.25f else 0.38f
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp)
            .pressScale(interaction, 0.98f)
            .clip(shape)
            .background(if (configured) bank.logoGround.fadedOver(page, keep) else bank.logoGround)
            // White needs the rim to stand off a light page; on BANDEC's red it only haloes the corners.
            .then(if (bank == Bank.BPA) Modifier.rim(shape) else Modifier)
            .then(if (configured) Modifier else Modifier.clickable(interaction, ripple(), onClick = onClick))
            .clearAndSetSemantics {
                contentDescription = if (configured) "${bank.fullName}, configurado" else bank.fullName
                if (!configured) role = Role.Button
            }
            .padding(vertical = NeoSpace.m),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(NeoSpace.s)) {
            Image(
                painterResource(bank.logo), null, Modifier.height(bank.logoHeight),
                contentScale = ContentScale.Fit,
                colorFilter = if (configured) FadedOver(page, keep) else null,
            )
            if (configured) StatusChip("Configurado", StatusTone.Positive, Ds.drawable.ic_check_circle)
        }
    }
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
