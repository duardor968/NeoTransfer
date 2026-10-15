package dev.duardo.neotransfer.designsystem.component

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.duardo.neotransfer.designsystem.theme.NeoSpace
import java.math.BigDecimal

/**
 * Last look before authorising money, drawn as the receipt it will become: who ([lead]) and how much above the
 * perforation, the lines that matter below it, and one button that carries the amount and the fingerprint. Extra
 * choices (save contact) go in [extra], under the ticket. [lines] is never empty: there is always a source.
 */
@Composable
fun ReviewLayout(
    amount: BigDecimal,
    currency: String,
    lines: List<SummaryLine>,
    confirm: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes confirmIcon: Int? = null,
    confirming: Boolean = false,
    lead: @Composable ColumnScope.() -> Unit = {},
    extra: @Composable ColumnScope.() -> Unit = {},
) {
    Column(modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = NeoSpace.gutter).padding(top = NeoSpace.s, bottom = NeoSpace.l),
        ) {
            ReceiptTicket(
                header = {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = NeoSpace.xl, vertical = NeoSpace.xxl),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        lead()
                        Spacer(Modifier.height(NeoSpace.l))
                        MoneyText(amount, currency, style = MaterialTheme.typography.displayMedium, hero = true)
                    }
                },
                body = { SummaryLines(lines, Modifier.padding(vertical = NeoSpace.s)) },
            )
            extra()
        }
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = NeoSpace.gutter, vertical = NeoSpace.l),
            verticalArrangement = Arrangement.spacedBy(NeoSpace.s),
        ) {
            NeoButton(confirm, onConfirm, icon = confirmIcon, loading = confirming)
        }
    }
}
