package dev.duardo.neotransfer.ui.contacts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
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
import dev.duardo.neotransfer.designsystem.component.NeoButtonStyle
import dev.duardo.neotransfer.designsystem.component.NeoConfirmDialog
import dev.duardo.neotransfer.designsystem.component.NeoGroup
import dev.duardo.neotransfer.designsystem.component.NeoIconButton
import dev.duardo.neotransfer.designsystem.component.NeoRow
import dev.duardo.neotransfer.designsystem.component.NeoSearchField
import dev.duardo.neotransfer.designsystem.component.NeoTextButton
import dev.duardo.neotransfer.designsystem.component.NeoTextField
import dev.duardo.neotransfer.designsystem.component.NeoTopBar
import dev.duardo.neotransfer.designsystem.component.SectionHeader
import dev.duardo.neotransfer.designsystem.component.groupItem
import dev.duardo.neotransfer.designsystem.format.formatCardNumber
import dev.duardo.neotransfer.designsystem.format.formatPhone
import dev.duardo.neotransfer.designsystem.theme.Neo
import dev.duardo.neotransfer.designsystem.theme.NeoSpace
import dev.duardo.neotransfer.ui.model.ContactEditUi
import dev.duardo.neotransfer.ui.model.ContactUi
import dev.duardo.neotransfer.ui.model.ContactsContent
import dev.duardo.neotransfer.ui.model.ContactsUi
import dev.duardo.neotransfer.ui.onboarding.CardNumberTransformation

// ---- List ---------------------------------------------------------------------------------------------------

interface ContactsActions {
    fun query(value: String)
    fun open(id: String)
    fun create()
    fun importFromPhone()
}

@Composable
fun ContactsScreen(state: ContactsUi, actions: ContactsActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = NeoSpace.gutter, end = NeoSpace.xs, top = NeoSpace.m, bottom = NeoSpace.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Contactos", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
            NeoIconButton(Ds.drawable.ic_person_add, "Nuevo contacto", actions::create)
        }
        when (val content = state.content) {
            ContactsContent.Empty -> {
                EmptyState(
                    Ds.drawable.ic_group, "Sin contactos",
                    action = "Importar del teléfono", onAction = actions::importFromPhone,
                )
            }
            else -> {
                NeoSearchField(state.query, actions::query, "Buscar nombre, tarjeta o teléfono", Modifier.padding(horizontal = NeoSpace.gutter))
                if (content is ContactsContent.Items) ContactList(content.contacts, state.importing, actions)
                else EmptyState(Ds.drawable.ic_search, "Nada coincide con la búsqueda")
            }
        }
    }
}

/** One list: importing sits on top as its first row, then everyone in alphabetical order. */
@Composable
private fun ContactList(contacts: List<ContactUi>, importing: Boolean, actions: ContactsActions) {
    val count = contacts.size + 1
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = NeoSpace.gutter, end = NeoSpace.gutter, top = NeoSpace.l, bottom = NeoSpace.xxl)) {
        item(key = "import") {
            Column(Modifier.groupItem(0, count, Neo.colors.group)) {
                NeoRow(
                    "Importar del teléfono",
                    leading = { IconAvatar(Ds.drawable.ic_contacts) },
                    trailing = if (importing) ({ CircularProgressIndicator(Modifier.padding(end = NeoSpace.s), strokeWidth = 2.dp) }) else null,
                    onClick = if (importing) null else actions::importFromPhone,
                )
            }
        }
        itemsIndexed(contacts, key = { _, it -> it.id }) { index, contact ->
            Column(Modifier.groupItem(index + 1, count, Neo.colors.group)) {
                GroupDivider()
                NeoRow(
                    contact.name,
                    supporting = contact.summary(),
                    leading = { NameAvatar(contact.name) },
                    onClick = { actions.open(contact.id) },
                )
            }
        }
    }
}

/** First card in full, then how many more; phone when there is no card. */
private fun ContactUi.summary(): String {
    val card = cards.firstOrNull() ?: return phones.firstOrNull()?.let(::formatPhone) ?: "Sin tarjetas"
    val more = cards.size - 1
    return formatCardNumber(card.number) + if (more > 0) " · ${more + 1} tarjetas" else ""
}

// ---- Detail -------------------------------------------------------------------------------------------------

interface ContactActions {
    fun back()
    fun edit()
    fun transfer(cardId: String)
    fun copy(value: String)
    fun delete()
    fun confirmDelete()
    fun cancelDelete()
}

@Composable
fun ContactDetailScreen(contact: ContactUi, actions: ContactActions, modifier: Modifier = Modifier, confirmingDelete: Boolean = false) {
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
            contact.cards.firstOrNull()?.let { first ->
                NeoButton("Transferir", { actions.transfer(first.id) }, icon = Ds.drawable.ic_arrow_outward)
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
                            trailing = { NeoIconButton(Ds.drawable.ic_content_copy, "Copiar número", { actions.copy(card.number) }) },
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
                            trailing = { NeoIconButton(Ds.drawable.ic_content_copy, "Copiar teléfono", { actions.copy(phone) }) },
                        )
                    }
                }
            }
            NeoGroup(Modifier.padding(top = NeoSpace.xxl)) {
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
    if (confirmingDelete) NeoConfirmDialog(
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
            SectionHeader("Tarjetas", Modifier.padding(top = NeoSpace.m))
            state.cards.forEachIndexed { index, card ->
                if (index > 0) Spacer(Modifier.height(NeoSpace.s))
                NeoTextField(
                    card.number, { actions.cardNumber(card.id, it) }, "Número de tarjeta",
                    keyboardType = KeyboardType.Number, error = card.error,
                    visualTransformation = CardNumberTransformation,
                    suffix = card.bankName,
                    trailing = { NeoIconButton(Ds.drawable.ic_close, "Quitar tarjeta", { actions.removeCard(card.id) }) },
                )
                NeoTextField(card.label, { actions.cardLabel(card.id, it) }, "Nombre de la tarjeta (opcional)")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(NeoSpace.s)) {
                NeoButton("Añadir tarjeta", actions::addCard, style = NeoButtonStyle.Tonal, icon = Ds.drawable.ic_add, fill = false)
                NeoButton("Escanear", actions::scanCard, style = NeoButtonStyle.Tonal, icon = Ds.drawable.ic_photo_camera, fill = false)
            }
            SectionHeader("Teléfonos", Modifier.padding(top = NeoSpace.m))
            state.phones.forEach { phone ->
                NeoTextField(
                    phone.number, { actions.phone(phone.id, it) }, "Teléfono",
                    keyboardType = KeyboardType.Phone, error = phone.error,
                    trailing = { NeoIconButton(Ds.drawable.ic_close, "Quitar teléfono", { actions.removePhone(phone.id) }) },
                )
            }
            Row {
                NeoButton("Añadir teléfono", actions::addPhone, style = NeoButtonStyle.Tonal, icon = Ds.drawable.ic_add, fill = false)
            }
        }
    }
}
