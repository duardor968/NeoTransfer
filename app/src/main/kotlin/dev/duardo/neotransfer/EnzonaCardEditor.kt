package dev.duardo.neotransfer

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
internal fun EnzonaCardEditor(state: AppUiState, cardId: String, actions: UiActions, back: () -> Unit) {
    val wallet = state.enzonaWallet
    val owner = wallet.owner
    val card = wallet.cards.singleOrNull { it.fundingSourceUuid == cardId }
    val link = wallet.links.singleOrNull { it.ownerSubject == owner && it.cardUuid == cardId }
    var pan by rememberSaveable(owner, cardId) { mutableStateOf(card?.knownPan(wallet).orEmpty()) }
    var alias by rememberSaveable(owner, cardId) { mutableStateOf(link?.alias.orEmpty()) }
    var savedCardId by rememberSaveable(owner, cardId) { mutableStateOf<String?>(null) }
    var saving by remember(owner, cardId) { mutableStateOf<Pair<String, String?>?>(null) }
    var error by remember(owner, cardId) { mutableStateOf<String?>(null) }
    var noticeBeforeSave by remember(owner, cardId) { mutableStateOf<String?>(null) }
    val explicitBank = enzonaBankIdentity(card?.bankName)
    val localProducts = walletProducts(state, includeHistorical = true, includeEnzona = false)
    val conflictingBank = explicitBank != null && localProducts.any {
        it.kind == ProductKind.CARD && it.number == pan && it.identity != explicitBank
    }
    val knownCards = state.wallet.cards.filter { local ->
        local.number.matches(Regex("[0-9]{16}")) &&
            (card?.last4.isNullOrBlank() || local.number.takeLast(4) == card?.last4) &&
            state.wallet.registrations.any { it.id == local.registrationId && it.identity() in authenticationProviders() } &&
            (explicitBank == null || localProducts.singleOrNull { it.id == local.id }?.identity == explicitBank) &&
            (explicitBank == null || localProducts.none { it.kind == ProductKind.CARD && it.number == local.number && it.identity != explicitBank })
    }
    val validPan = pan.matches(Regex("[0-9]{16}")) && !conflictingBank && (card?.last4.isNullOrBlank() || pan.takeLast(4) == card?.last4)
    LaunchedEffect(link, saving) {
        val expected = saving
        if (expected != null && link?.pan == expected.first && link?.alias == expected.second) {
            saving = null
            back()
        }
    }
    LaunchedEffect(wallet.notice, saving) {
        if (wallet.notice == null) noticeBeforeSave = null
        if (saving != null && wallet.notice != null && wallet.notice != noticeBeforeSave) {
            error = wallet.notice
            saving = null
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        PageHeader("Tarjeta ENZONA", back)
        if (owner == null || card == null) {
            EmptyAction("La tarjeta ya no está disponible", "Volver", back)
        } else {
            card.bankName?.takeIf(String::isNotBlank)?.let { Detail("Banco", it) }
            card.last4?.takeIf { it.matches(Regex("[0-9]{4}")) }?.let { Detail("Terminación", it) }
            if (knownCards.isNotEmpty()) ChoiceField(
                knownCards.singleOrNull { it.id == savedCardId }?.let { "${it.label} · ${readableAccount(it.number)}" }
                    ?: "Asociar una tarjeta guardada",
                if (saving != null) emptyList() else knownCards.map { it.id to "${it.label} · ${readableAccount(it.number)}" }) { id ->
                knownCards.singleOrNull { it.id == id }?.let { pan = it.number; savedCardId = id; error = null }
            }
            Field("Número de tarjeta", pan, { pan = digits(it, 16); savedCardId = null; error = null }, KeyboardType.Number, readOnly = saving != null)
            if (pan.isNotBlank() && !validPan) Text(
                if (conflictingBank) "La tarjeta guardada pertenece a otro banco"
                else if (pan.length == 16) "El número no coincide con la terminación" else "Introduce los 16 dígitos de la tarjeta",
                color = MaterialTheme.colorScheme.error)
            Field("Nombre en la cartera", alias, { alias = it; error = null }, readOnly = saving != null)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Primary(if (saving != null) "Guardando" else "Guardar", validPan && saving == null && !wallet.busy) {
                error = null
                noticeBeforeSave = wallet.notice
                actions.dismissNotice()
                saving = pan to alias.trim().ifBlank { null }
                runCatching { actions.saveEnzonaCard(cardId, pan, alias.trim().ifBlank { null }) }
                    .onFailure { error = it.message ?: "No se pudo guardar la tarjeta"; saving = null }
            }
        }
    }
}
