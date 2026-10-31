package dev.duardo.neotransfer.ui.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.designsystem.R as Ds
import dev.duardo.neotransfer.designsystem.component.EmptyState
import dev.duardo.neotransfer.designsystem.component.GroupDivider
import dev.duardo.neotransfer.designsystem.component.IconAvatar
import dev.duardo.neotransfer.designsystem.component.NameAvatar
import dev.duardo.neotransfer.designsystem.component.NeoButton
import dev.duardo.neotransfer.designsystem.component.NeoConfirmSheet
import dev.duardo.neotransfer.designsystem.component.NeoGroup
import dev.duardo.neotransfer.designsystem.component.NeoIconButton
import dev.duardo.neotransfer.designsystem.component.NeoRow
import dev.duardo.neotransfer.designsystem.component.NeoSearchField
import dev.duardo.neotransfer.designsystem.component.NeoTextButton
import dev.duardo.neotransfer.designsystem.component.NeoTextField
import dev.duardo.neotransfer.designsystem.component.NeoTopBar
import dev.duardo.neotransfer.designsystem.component.SectionHeader
import dev.duardo.neotransfer.designsystem.component.SeparatedText
import dev.duardo.neotransfer.designsystem.component.TabHeader
import dev.duardo.neotransfer.designsystem.component.flushEnd
import dev.duardo.neotransfer.designsystem.component.flushStart
import dev.duardo.neotransfer.designsystem.component.groupItem
import dev.duardo.neotransfer.designsystem.format.formatCardNumber
import dev.duardo.neotransfer.designsystem.format.formatMoney
import dev.duardo.neotransfer.designsystem.format.formatPhone
import dev.duardo.neotransfer.designsystem.format.unbroken
import dev.duardo.neotransfer.designsystem.theme.Neo
import dev.duardo.neotransfer.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.ui.common.CardNumberTransformation
import dev.duardo.neotransfer.ui.common.ForeignCardFace
import dev.duardo.neotransfer.ui.common.ForeignCardThumb
import dev.duardo.neotransfer.ui.common.OperationRow
import dev.duardo.neotransfer.ui.common.PhoneTransformation
import dev.duardo.neotransfer.ui.common.TimeLabels
import dev.duardo.neotransfer.ui.common.timeLabels
import dev.duardo.neotransfer.ui.model.AllContactsUi
import dev.duardo.neotransfer.ui.model.CardEditUi
import dev.duardo.neotransfer.ui.model.CardFieldUi
import dev.duardo.neotransfer.ui.model.ContactEditUi
import dev.duardo.neotransfer.ui.model.ContactUi
import dev.duardo.neotransfer.ui.model.ContactsContent
import dev.duardo.neotransfer.ui.model.ContactsUi
import dev.duardo.neotransfer.ui.model.OperationKind
import dev.duardo.neotransfer.ui.model.OperationUi
import dev.duardo.neotransfer.ui.model.issuerOf
import java.text.Normalizer

// ---- List ---------------------------------------------------------------------------------------------------

interface ContactsActions {
    fun query(value: String)
    fun open(id: String)
    fun create()
    fun everyone()
    fun allowContacts()
}

/**
 * The people money moves with: a movement or a saved card, newest movement first. The rest of the address book is
 * one row away ("Todos los contactos") and in the search.
 */
@Composable
fun ContactsScreen(state: ContactsUi, actions: ContactsActions, modifier: Modifier = Modifier) {
    val labels = timeLabels
    Column(modifier.fillMaxSize()) {
        TabHeader("Contactos") { NeoIconButton(Ds.drawable.ic_person_add, "Nuevo contacto", actions::create) }
        NeoSearchField(state.query, actions::query, "Buscar nombre, tarjeta o teléfono", Modifier.padding(horizontal = NeoSpace.gutter))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = NeoSpace.gutter, end = NeoSpace.gutter, top = NeoSpace.l, bottom = NeoSpace.xxl)) {
            if (!state.addressBook) item(key = "allow") {
                NeoGroup(Modifier.padding(bottom = NeoSpace.l)) {
                    NeoRow(
                        "Sin acceso a tu agenda",
                        supporting = "Solo ves los contactos guardados en la app",
                        leading = { IconAvatar(Ds.drawable.ic_contacts) },
                        trailing = { Text("Permitir", style = MaterialTheme.typography.labelLarge, color = Neo.colors.accentInk) },
                        onClick = actions::allowContacts,
                        trailingBelowWhenLarge = true,
                    )
                }
            }
            when (val content = state.content) {
                is ContactsContent.People -> {
                    val everyone = if (state.addressBook) 1 else 0
                    val count = content.people.size + everyone
                    if (everyone == 1) item(key = "everyone") {
                        Column(Modifier.groupItem(0, count)) {
                            NeoRow(
                                "Todos los contactos",
                                supporting = if (state.everyone == 1) "1 contacto" else "${state.everyone} contactos",
                                leading = { IconAvatar(Ds.drawable.ic_contacts) },
                                onClick = actions::everyone,
                            )
                        }
                    }
                    itemsIndexed(content.people, key = { _, it -> it.id }) { index, contact ->
                        Column(Modifier.groupItem(index + everyone, count)) {
                            if (index + everyone > 0) GroupDivider()
                            PersonRow(contact, labels) { actions.open(contact.id) }
                        }
                    }
                    if (content.people.isEmpty()) item(key = "empty") {
                        EmptyState(
                            Ds.drawable.ic_group, "Aún sin movimientos",
                            body = "Aquí aparece la gente a la que envías o que te envía dinero, y a quien le guardas una tarjeta.",
                        )
                    }
                }
                is ContactsContent.Results -> {
                    section("people", "Con tarjeta o movimientos", content.people) { PersonRow(it, labels) { actions.open(it.id) } }
                    section("others", if (state.addressBook) "En tu agenda" else "Contactos", content.others) { ContactRow(it) { actions.open(it.id) } }
                }
                ContactsContent.NoResults -> item(key = "none") { EmptyState(Ds.drawable.ic_search, "Nada coincide con la búsqueda") }
            }
        }
    }
}

private fun LazyListScope.section(key: String, title: String, contacts: List<ContactUi>, row: @Composable (ContactUi) -> Unit) {
    if (contacts.isEmpty()) return
    item(key = "$key-title") { SectionHeader(title) }
    itemsIndexed(contacts, key = { _, it -> "$key-${it.id}" }) { index, contact ->
        Column(Modifier.groupItem(index, contacts.size)) {
            if (index > 0) GroupDivider()
            row(contact)
        }
    }
}

/** Someone money moved with: what moved last, or that a card is saved. */
@Composable
private fun PersonRow(contact: ContactUi, labels: TimeLabels, onClick: () -> Unit) {
    val last = contact.last
    NeoRow(
        contact.name,
        leading = { NameAvatar(contact.name) },
        supportingContent = {
            SeparatedText(
                last?.let { "${it.kind.verb()}${it.amount?.let { a -> " " + formatMoney(a.amount, a.currency).unbroken() }.orEmpty()} · " + labels.shortDay(it.at).unbroken() }
                    ?: "Tarjeta guardada",
                MaterialTheme.typography.bodyMedium,
                color = if (last?.kind?.incoming == true) Neo.colors.positive else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        onClick = onClick,
    )
}

/** What moved with someone, said from the user's side. */
private fun OperationKind.verb(): String = when (this) {
    OperationKind.Incoming -> "Te envió"
    OperationKind.MobileTopUp -> "Recarga"
    OperationKind.CubacelTransfer -> "Saldo"
    else -> "Enviaste"
}

/** Anyone in the list of everyone: first card in full and how many, or the phone. */
@Composable
private fun ContactRow(contact: ContactUi, onClick: () -> Unit) {
    NeoRow(contact.name, supporting = contact.summary(), leading = { NameAvatar(contact.name) }, onClick = onClick)
}

/** First card in full, then how many more; phone when there is no card. */
private fun ContactUi.summary(): String? {
    val card = cards.firstOrNull() ?: return phones.firstOrNull()?.let(::formatPhone)
    val more = cards.size - 1
    return formatCardNumber(card.number) + if (more > 0) " · " + "${more + 1} tarjetas".unbroken() else ""
}

// ---- Everyone -----------------------------------------------------------------------------------------------

interface AllContactsActions {
    fun back()
    fun query(value: String)
    fun open(id: String)
}

/** Everyone, by name, under the initial of their name. */
@Composable
fun AllContactsScreen(state: AllContactsUi, actions: AllContactsActions, modifier: Modifier = Modifier) {
    Scaffold(
        modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { NeoTopBar("Todos los contactos", onBack = actions::back) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            NeoSearchField(state.query, actions::query, "Buscar nombre, tarjeta o teléfono", Modifier.padding(horizontal = NeoSpace.gutter))
            if (state.contacts.isEmpty()) {
                EmptyState(Ds.drawable.ic_search, if (state.query.isBlank()) "Sin contactos" else "Nada coincide con la búsqueda")
                return@Column
            }
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = NeoSpace.gutter, end = NeoSpace.gutter, bottom = NeoSpace.xxl)) {
                state.contacts.groupBy { initialOf(it.name) }.forEach { (initial, people) ->
                    item(key = "letter-$initial") {
                        Text(
                            initial, Modifier.padding(top = NeoSpace.l, bottom = NeoSpace.s),
                            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    itemsIndexed(people, key = { _, it -> it.id }) { index, contact ->
                        Column(Modifier.groupItem(index, people.size)) {
                            if (index > 0) GroupDivider()
                            ContactRow(contact) { actions.open(contact.id) }
                        }
                    }
                }
            }
        }
    }
}

/** The letter a name is filed under: accents dropped; digits and symbols under "#". */
private fun initialOf(name: String): String {
    val first = Normalizer.normalize(name.trim().take(1), Normalizer.Form.NFD).firstOrNull()?.uppercaseChar() ?: return "#"
    return if (first in 'A'..'Z') first.toString() else "#"
}

/// ---- Detail -------------------------------------------------------------------------------------------------

interface ContactActions {
    fun back()
    /** Name and phones of a contact kept only in the app. */
    fun edit()
    fun editInContacts()
    fun transfer(cardId: String)
    fun addCard()
    fun editCard(cardId: String)
    fun removeCard(cardId: String)
    fun openMovement(id: String)
    /** Activity, searched by this person's name. */
    fun allMovements()
    fun copy(value: String)
    fun delete()
    fun confirmDelete()
    fun cancelDelete()
}

/**
 * Someone in the address book is changed in Contacts and never deleted from here; their cards are managed here, each
 * from its own menu. [movements]: the newest money moved with them.
 */
@Composable
fun ContactDetailScreen(
    contact: ContactUi, actions: ContactActions, modifier: Modifier = Modifier, confirmingDelete: Boolean = false,
    movements: List<OperationUi> = emptyList(),
) {
    val labels = timeLabels
    Scaffold(
        modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { NeoTopBar(onBack = actions::back) },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = NeoSpace.gutter).padding(bottom = NeoSpace.xxl),
        ) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(NeoSpace.m)) {
                NameAvatar(contact.name, size = 80.dp)
                Text(contact.name, style = MaterialTheme.typography.headlineSmall)
            }
            Spacer(Modifier.height(NeoSpace.xxl))
            val first = contact.cards.firstOrNull()
            if (first != null) NeoButton("Transferir", { actions.transfer(first.id) }, icon = Ds.drawable.ic_arrow_outward)
            else NeoButton("Añadir tarjeta", actions::addCard, icon = Ds.drawable.ic_add_card)
            if (movements.isNotEmpty()) {
                SectionHeader("Movimientos", Modifier.padding(top = NeoSpace.l), action = "Ver todo", onAction = actions::allMovements)
                NeoGroup {
                    movements.forEachIndexed { index, operation ->
                        if (index > 0) GroupDivider()
                        OperationRow(operation, labels.moment(operation.at, operation.dateOnly), { actions.openMovement(operation.id) }, title = operation.kind.verb())
                    }
                }
            }
            if (contact.cards.isNotEmpty()) {
                SectionHeader("Tarjetas", Modifier.padding(top = NeoSpace.l))
                NeoGroup {
                    contact.cards.forEach { card ->
                        NeoRow(
                            formatCardNumber(card.number),
                            supporting = listOfNotNull(card.issuer, card.label).joinToString(" · ").ifEmpty { null },
                            leading = { ForeignCardThumb(card.bank) },
                            trailing = { CardMenu({ actions.copy(card.number) }, { actions.editCard(card.id) }, { actions.removeCard(card.id) }) },
                            onClick = { actions.transfer(card.id) },
                        )
                        GroupDivider()
                    }
                    NeoRow(
                        "Añadir tarjeta",
                        leading = { IconAvatar(Ds.drawable.ic_add, tint = Neo.colors.accentInk) },
                        titleColor = Neo.colors.accentInk,
                        onClick = actions::addCard,
                    )
                }
            }
            if (contact.phones.isNotEmpty()) {
                SectionHeader("Teléfonos", Modifier.padding(top = NeoSpace.l))
                NeoGroup {
                    contact.phones.forEachIndexed { index, phone ->
                        if (index > 0) GroupDivider()
                        NeoRow(
                            formatPhone(phone),
                            leading = { IconAvatar(Ds.drawable.ic_call) },
                            trailing = { NeoIconButton(Ds.drawable.ic_content_copy, "Copiar teléfono", { actions.copy(phone) }, Modifier.flushEnd()) },
                        )
                    }
                }
            }
            NeoGroup(Modifier.padding(top = NeoSpace.xxl)) {
                if (contact.fromAddressBook) {
                    NeoRow("Editar en Contactos", leading = { IconAvatar(Ds.drawable.ic_edit) }, onClick = actions::editInContacts)
                } else {
                    NeoRow("Editar contacto", leading = { IconAvatar(Ds.drawable.ic_edit) }, onClick = actions::edit)
                    GroupDivider()
                    NeoRow(
                        "Eliminar contacto",
                        leading = { IconAvatar(Ds.drawable.ic_delete, tint = Neo.colors.danger) },
                        titleColor = Neo.colors.danger,
                        onClick = actions::delete,
                    )
                }
            }
        }
    }
    if (confirmingDelete) NeoConfirmSheet(
        title = "¿Eliminar a ${contact.name}?",
        text = "Se borran sus tarjetas y teléfonos guardados. Tu actividad no cambia.",
        confirm = "Eliminar",
        dismiss = "Cancelar",
        onConfirm = actions::confirmDelete,
        onDismiss = actions::cancelDelete,
        destructive = true,
    )
}

/** What else can be done with one card; tapping the row itself transfers to it. */
@Composable
private fun CardMenu(onCopy: () -> Unit, onEdit: () -> Unit, onRemove: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        NeoIconButton(Ds.drawable.ic_more_vert, "Opciones de la tarjeta", { open = true }, Modifier.flushEnd())
        DropdownMenu(open, { open = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            DropdownMenuItem(
                { Text("Copiar número") },
                { open = false; onCopy() },
                leadingIcon = { Icon(painterResource(Ds.drawable.ic_content_copy), null) },
            )
            DropdownMenuItem(
                { Text("Editar") },
                { open = false; onEdit() },
                leadingIcon = { Icon(painterResource(Ds.drawable.ic_edit), null) },
            )
            DropdownMenuItem(
                { Text("Quitar", color = Neo.colors.danger) },
                { open = false; onRemove() },
                leadingIcon = { Icon(painterResource(Ds.drawable.ic_delete), null, tint = Neo.colors.danger) },
            )
        }
    }
}

// ---- Create / edit ------------------------------------------------------------------------------------------

interface ContactEditActions {
    fun back()
    fun save()
    fun name(value: String)
    fun cardNumber(value: String)
    fun cardLabel(value: String)
    fun scanCard()
    fun phone(id: String, value: String)
    fun removePhone(id: String)
    fun addPhone()
}

/** A contact kept only in the app: its name and phones, and its first card when it is created. */
@Composable
fun ContactEditScreen(state: ContactEditUi, actions: ContactEditActions, modifier: Modifier = Modifier) {
    Scaffold(
        modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            NeoTopBar(
                if (state.id == null) "Nuevo contacto" else "Editar contacto",
                onBack = actions::back,
                backIcon = Ds.drawable.ic_close,
                backDescription = "Cerrar",
                actions = { NeoTextButton("Guardar", actions::save, enabled = state.canSave) },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = NeoSpace.gutter).padding(bottom = NeoSpace.xxl),
            verticalArrangement = Arrangement.spacedBy(NeoSpace.m),
        ) {
            NeoTextField(state.name, actions::name, "Nombre")
            // The contact, its card and its phones read as three groups.
            state.card?.let { card ->
                Spacer(Modifier.height(NeoSpace.s))
                CardFields(card, actions::cardNumber, actions::cardLabel, actions::scanCard)
            }
            Spacer(Modifier.height(NeoSpace.s))
            state.phones.forEach { phone ->
                NeoTextField(
                    phone.number, { actions.phone(phone.id, it) }, "Teléfono",
                    keyboardType = KeyboardType.Phone, error = phone.error,
                    visualTransformation = PhoneTransformation,
                    trailing = { NeoIconButton(Ds.drawable.ic_delete, "Quitar teléfono", { actions.removePhone(phone.id) }, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
            }
            NeoTextButton("Añadir teléfono", actions::addPhone, Modifier.flushStart(), icon = Ds.drawable.ic_add)
        }
    }
}

// ---- One card -----------------------------------------------------------------------------------------------

interface CardEditActions {
    fun back()
    fun save()
    fun number(value: String)
    fun label(value: String)
    fun scan()
}

/** A card of someone else, new or saved: how it will look, its number and an optional name. */
@Composable
fun CardEditScreen(state: CardEditUi, actions: CardEditActions, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    if (!state.editing) LaunchedEffect(Unit) { focus.requestFocus() }
    Scaffold(
        modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            NeoTopBar(
                if (state.editing) "Editar tarjeta" else "Nueva tarjeta",
                onBack = actions::back,
                backIcon = Ds.drawable.ic_close,
                backDescription = "Cerrar",
                actions = { NeoTextButton("Guardar", actions::save, enabled = state.canSave) },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = NeoSpace.gutter).padding(bottom = NeoSpace.xxl),
            verticalArrangement = Arrangement.spacedBy(NeoSpace.m),
        ) {
            Text("Para ${state.contactName}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ForeignCardFace(state.card.number, state.card.bank, state.card.currency, Modifier.padding(bottom = NeoSpace.s))
            CardFields(state.card, actions::number, actions::label, actions::scan, focus)
        }
    }
}

/** The number, with the camera at hand and what the number tells under it, then the optional name. */
@Composable
private fun CardFields(card: CardFieldUi, onNumber: (String) -> Unit, onLabel: (String) -> Unit, onScan: () -> Unit, focus: FocusRequester? = null) {
    NeoTextField(
        card.number, onNumber, "Número de tarjeta", if (focus != null) Modifier.focusRequester(focus) else Modifier,
        keyboardType = KeyboardType.Number, error = card.error,
        visualTransformation = CardNumberTransformation,
        supporting = issuerOf(card.bank, card.currency).orEmpty(),
        trailing = { NeoIconButton(Ds.drawable.ic_photo_camera, "Escanear", onScan, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
    )
    NeoTextField(card.label, onLabel, "Nombre de la tarjeta (opcional)", imeAction = ImeAction.Done)
}
