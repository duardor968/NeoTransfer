package dev.duardo.neotransfer.ui.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import dev.duardo.neotransfer.designsystem.component.TabHeader
import dev.duardo.neotransfer.designsystem.component.flushEnd
import dev.duardo.neotransfer.designsystem.component.flushStart
import dev.duardo.neotransfer.designsystem.component.groupItem
import dev.duardo.neotransfer.designsystem.format.NoBreak
import dev.duardo.neotransfer.designsystem.format.formatCardNumber
import dev.duardo.neotransfer.designsystem.format.formatMoney
import dev.duardo.neotransfer.designsystem.format.formatPhone
import dev.duardo.neotransfer.designsystem.format.unbroken
import dev.duardo.neotransfer.designsystem.theme.Neo
import dev.duardo.neotransfer.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.ui.common.CardNumberTransformation
import dev.duardo.neotransfer.ui.common.OperationRow
import dev.duardo.neotransfer.ui.common.PhoneTransformation
import dev.duardo.neotransfer.ui.common.TimeLabels
import dev.duardo.neotransfer.ui.common.timeLabels
import dev.duardo.neotransfer.ui.model.AllContactsUi
import dev.duardo.neotransfer.ui.model.ContactEditUi
import dev.duardo.neotransfer.ui.model.ContactUi
import dev.duardo.neotransfer.ui.model.ContactsContent
import dev.duardo.neotransfer.ui.model.ContactsUi
import dev.duardo.neotransfer.ui.model.OperationKind
import dev.duardo.neotransfer.ui.model.OperationUi
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
                    section("people", "Con movimientos", content.people) { PersonRow(it, labels) { actions.open(it.id) } }
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
            Text(
                last?.let { "${it.kind.verb()}${it.amount?.let { a -> " " + formatMoney(a.amount, a.currency).unbroken() }.orEmpty()}$NoBreak· " + labels.shortDay(it.at).unbroken() }
                    ?: "Tarjeta guardada",
                style = MaterialTheme.typography.bodyMedium,
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
    return formatCardNumber(card.number) + if (more > 0) "$NoBreak· " + "${more + 1} tarjetas".unbroken() else ""
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

// ---- Detail -------------------------------------------------------------------------------------------------

interface ContactActions {
    fun back()
    /** The whole contact; only its cards for someone in the address book. */
    fun edit()
    fun editInContacts()
    fun transfer(cardId: String)
    fun openMovement(id: String)
    /** Activity, searched by this person's name. */
    fun allMovements()
    fun copy(value: String)
    fun delete()
    fun confirmDelete()
    fun cancelDelete()
}

/**
 * Someone in the address book is changed in Contacts; here only their cards, and they are never deleted from here.
 * [movements]: the newest money moved with them.
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
            when {
                first != null -> NeoButton("Transferir", { actions.transfer(first.id) }, icon = Ds.drawable.ic_arrow_outward)
                contact.fromAddressBook -> NeoButton("Añadir tarjeta", actions::edit, icon = Ds.drawable.ic_add_card)
            }
            if (movements.isNotEmpty()) {
                SectionHeader("Movimientos", Modifier.padding(top = NeoSpace.l), action = "Ver todo", onAction = actions::allMovements)
                NeoGroup {
                    movements.forEachIndexed { index, operation ->
                        if (index > 0) GroupDivider()
                        OperationRow(operation, labels.moment(operation.at), { actions.openMovement(operation.id) }, title = operation.kind.verb())
                    }
                }
            }
            if (contact.cards.isNotEmpty()) {
                SectionHeader("Tarjetas", Modifier.padding(top = NeoSpace.l))
                NeoGroup {
                    contact.cards.forEachIndexed { index, card ->
                        if (index > 0) GroupDivider()
                        NeoRow(
                            formatCardNumber(card.number),
                            supporting = listOfNotNull(card.bankName, card.label).joinToString(" · ").ifEmpty { null },
                            leading = { IconAvatar(Ds.drawable.ic_credit_card) },
                            trailing = { NeoIconButton(Ds.drawable.ic_content_copy, "Copiar número", { actions.copy(card.number) }, Modifier.flushEnd()) },
                            onClick = { actions.transfer(card.id) },
                        )
                    }
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
                    if (first != null) {
                        NeoRow("Editar tarjetas", leading = { IconAvatar(Ds.drawable.ic_credit_card) }, onClick = actions::edit)
                        GroupDivider()
                    }
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

// ---- Create / edit ------------------------------------------------------------------------------------------

interface ContactEditActions {
    fun back()
    fun save()
    fun name(value: String)
    fun cardNumber(id: String, value: String)
    fun cardLabel(id: String, value: String)
    fun removeCard(id: String)
    fun addCard()
    fun scanCard()
    fun phone(id: String, value: String)
    fun removePhone(id: String)
    fun addPhone()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContactEditScreen(state: ContactEditUi, actions: ContactEditActions, modifier: Modifier = Modifier) {
    Scaffold(
        modifier.imePadding(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            NeoTopBar(
                when {
                    state.id == null -> "Nuevo contacto"
                    state.fromAddressBook -> "Tarjetas de ${state.name.substringBefore(' ')}"
                    else -> "Editar contacto"
                },
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
            if (!state.fromAddressBook) {
                NeoTextField(state.name, actions::name, "Nombre")
                SectionHeader("Tarjetas", Modifier.padding(top = NeoSpace.m))
            }
            state.cards.forEachIndexed { index, card ->
                if (index > 0) Spacer(Modifier.height(NeoSpace.s))
                NeoTextField(
                    card.number, { actions.cardNumber(card.id, it) }, "Número de tarjeta",
                    keyboardType = KeyboardType.Number, error = card.error,
                    visualTransformation = CardNumberTransformation,
                    supporting = card.bankName,
                    trailing = if (state.cards.size > 1 || card.number.isNotEmpty()) {
                        { NeoIconButton(Ds.drawable.ic_delete, "Quitar tarjeta", { actions.removeCard(card.id) }, tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    } else null,
                )
                NeoTextField(card.label, { actions.cardLabel(card.id, it) }, "Nombre de la tarjeta (opcional)")
            }
            FlowRow(Modifier.flushStart()) {
                if (state.cards.none { it.number.isEmpty() }) NeoTextButton("Añadir tarjeta", actions::addCard, icon = Ds.drawable.ic_add)
                NeoTextButton("Escanear", actions::scanCard, icon = Ds.drawable.ic_photo_camera)
            }
            if (state.fromAddressBook) return@Column
            SectionHeader("Teléfonos", Modifier.padding(top = NeoSpace.m))
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
