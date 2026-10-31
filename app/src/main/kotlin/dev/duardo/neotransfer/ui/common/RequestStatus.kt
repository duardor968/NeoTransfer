package dev.duardo.neotransfer.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.designsystem.R as Ds
import dev.duardo.neotransfer.designsystem.component.NeoGroup
import dev.duardo.neotransfer.designsystem.component.NeoTopBar
import dev.duardo.neotransfer.designsystem.component.StatusMark
import dev.duardo.neotransfer.designsystem.component.StatusTone
import dev.duardo.neotransfer.designsystem.theme.Neo
import dev.duardo.neotransfer.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.ui.model.Bank
import dev.duardo.neotransfer.ui.model.LineStateUi
import dev.duardo.neotransfer.ui.onboarding.BottomAction
import java.time.Instant

/** What a request's status says: what happened, in a few words, and [body] under it. */
@Immutable
data class RequestCopy(val title: String, val body: String?)

/**
 * The words for where a request stands. Each request words its own [sent] and [confirmed]; a refusal is titled
 * [refused], naming who refused, and a send that did not go out [notExecuted], both over their reason as a sentence.
 * An answer in words the app does not know goes under the request's [name].
 */
fun LineStateUi.words(sent: RequestCopy, confirmed: RequestCopy, refused: String, notExecuted: String, name: String): RequestCopy = when (this) {
    LineStateUi.Sent -> sent
    is LineStateUi.Confirmed -> confirmed
    is LineStateUi.Rejected -> RequestCopy(refused, reason.asSentence())
    is LineStateUi.NotExecuted -> RequestCopy(notExecuted, reason.asSentence())
    LineStateUi.NoResponse -> RequestCopy("Sin respuesta", "Si el SMS llega más tarde, aparece aquí.")
    is LineStateUi.Answered -> RequestCopy(name, null)
}

/** Who answers a request of the line: the bank, or MiTransfer for the Clásica. */
val Bank.party: String get() = if (this == Bank.CLASSIC) "MiTransfer" else shortName

/** A reason as a sentence of its own: «el banco estaba ocupado» → «El banco estaba ocupado.». */
private fun String.asSentence(): String = trim().replaceFirstChar(Char::uppercase).let { if (it.isEmpty() || it.last() in ".!?…") it else "$it." }

/**
 * Where a request of the line stands, on a screen of its own or in place of its form, laid out like a transfer's
 * result: the mark at the same height ([icon], the service's glyph, while it travels and once confirmed; the state's
 * otherwise), [copy] under it with an answer as it came from [party], and [actions] at the bottom once it is not on its
 * way, in a place kept from the start. A long answer scrolls under the mark.
 */
@Composable
fun RequestStatusScreen(
    state: LineStateUi,
    @DrawableRes icon: Int,
    copy: RequestCopy,
    party: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable ColumnScope.() -> Unit,
) {
    Scaffold(
        modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { NeoTopBar(onBack = onClose, backIcon = Ds.drawable.ic_close, backDescription = "Cerrar") },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val top = maxHeight * 0.16f
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = NeoSpace.gutter),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(top))
                    StatusMark(state.tone, state.glyph(icon), positive = { ConfirmedMark(112.dp) })
                    Spacer(Modifier.height(NeoSpace.xxl))
                    Text(copy.title, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    if (state is LineStateUi.Answered) AnswerText(party, state, Modifier.padding(top = NeoSpace.l), Alignment.CenterHorizontally)
                    copy.body?.let {
                        Text(
                            it, Modifier.padding(top = NeoSpace.s),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (state is LineStateUi.Rejected) Neo.colors.danger else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Spacer(Modifier.height(NeoSpace.xxl))
                }
            }
            BottomAction {
                // The button's place, kept empty while the request travels: the mark does not move when the answer comes.
                if (state == LineStateUi.Sent) Spacer(Modifier.height(56.dp)) else actions()
            }
        }
    }
}

/** An answer as [party] wrote it, under who sent it and when: «Respuesta de BPA, hoy 11:42». */
@Composable
fun AnswerText(party: String, answer: LineStateUi.Answered, modifier: Modifier = Modifier, alignment: Alignment.Horizontal = Alignment.Start) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(NeoSpace.s), horizontalAlignment = alignment) {
        AnswerCaption(party, answer.at)
        NeoGroup {
            Text(answer.text.trim(), Modifier.padding(NeoSpace.l), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** Who answered and when, the same over every answer: «Respuesta de BPA, hoy 11:42». */
@Composable
fun AnswerCaption(party: String, at: Instant) {
    val labels = timeLabels
    Text(
        "Respuesta de $party, ${labels.shortDay(at)} ${labels.clockTime(at)}",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val LineStateUi.tone: StatusTone
    get() = when (this) {
        LineStateUi.Sent -> StatusTone.Pending
        is LineStateUi.Confirmed -> StatusTone.Positive
        is LineStateUi.Rejected -> StatusTone.Danger
        is LineStateUi.NotExecuted -> StatusTone.Warning
        LineStateUi.NoResponse, is LineStateUi.Answered -> StatusTone.Neutral
    }

/** The service's own glyph while it travels and once confirmed, then the state's. */
@DrawableRes
private fun LineStateUi.glyph(@DrawableRes service: Int): Int = when (this) {
    LineStateUi.Sent, is LineStateUi.Confirmed -> service
    is LineStateUi.Rejected -> Ds.drawable.ic_close
    is LineStateUi.NotExecuted -> Ds.drawable.ic_sync_problem
    LineStateUi.NoResponse -> Ds.drawable.ic_hourglass_empty
    is LineStateUi.Answered -> Ds.drawable.ic_sms
}
