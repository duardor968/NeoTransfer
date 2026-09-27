package dev.duardo.neotransfer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.duardo.neotransfer.core.Bank
import dev.duardo.neotransfer.data.*

private val cardDraftSaver = listSaver<List<ContactCard>, String>(
    save = { list -> list.flatMap { listOf(it.number, it.label, it.bankCode.orEmpty()) } },
    restore = { list -> list.chunked(3).map { ContactCard(it[0], it[1], it[2].ifBlank { null }) } })
private val phoneDraftSaver = listSaver<List<ContactPhone>, String>(
    save = { list -> list.flatMap { listOf(it.number, it.label) } },
    restore = { list -> list.chunked(2).map { ContactPhone(it[0], it[1]) } })

@Composable
internal fun ContactsScreen(state: AppUiState, actions: UiActions, add: () -> Unit, open: (ContactRecord) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var favorites by rememberSaveable { mutableStateOf(false) }
    val contacts = remember(state.wallet.contacts, query, favorites) {
        state.wallet.contacts.filter { contact -> (!favorites || contact.favorite) &&
            (query.isBlank() || contact.name.contains(query, true) || contact.cards.any { it.number.contains(query.filterNot(Char::isWhitespace)) } ||
                contact.phones.any { it.number.contains(query.filterNot(Char::isWhitespace)) }) }
            .sortedWith(compareByDescending<ContactRecord> { it.favorite }.thenBy { it.name.lowercase() })
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Contactos", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            IconButton(onClick = add) { Icon(painterResource(R.drawable.ic_add), "Crear contacto") }
        }
        Spacer(Modifier.height(12.dp)); SearchField(query, { query = it }, "Nombre, tarjeta o teléfono")
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(favorites, { favorites = !favorites }, label = { Text("Favoritos") }, leadingIcon = { Icon(painterResource(R.drawable.ic_star), null, Modifier.size(18.dp)) })
            Spacer(Modifier.weight(1f)); TextButton(onClick = actions.importContacts) { Text("Importar") }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
            if (contacts.isEmpty()) item {
                EmptyAction(if (query.isNotBlank() || favorites) "No hay contactos que coincidan" else "Guarda tus contactos", if (query.isNotBlank() || favorites) "Limpiar filtros" else "Crear contacto") {
                    if (query.isNotBlank() || favorites) { query = ""; favorites = false } else add()
                }
            }
            items(contacts, key = { it.id }) { contact ->
                ContactRow(contact) { open(contact) }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
internal fun ContactRow(contact: ContactRecord, click: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = click).padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(44.dp)) {
            Box(contentAlignment = Alignment.Center) { Text(contact.name.trim().split(Regex("\\s+")).take(2).mapNotNull { it.firstOrNull() }.joinToString(""), style = MaterialTheme.typography.titleMedium) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(contact.name, style = MaterialTheme.typography.titleMedium)
            Text(listOfNotNull(contact.cards.size.takeIf { it > 0 }?.let { "$it ${if (it == 1) "tarjeta" else "tarjetas"}" },
                contact.phones.size.takeIf { it > 0 }?.let { "$it ${if (it == 1) "teléfono" else "teléfonos"}" }).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (contact.favorite) Icon(painterResource(R.drawable.ic_star), "Favorito", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
internal fun ContactDetail(contact: ContactRecord, actions: UiActions, back: () -> Unit, edit: () -> Unit,
                           transfer: (ContactCard) -> Unit, recharge: (ContactPhone) -> Unit) {
    var remove by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp)) {
        item {
            PageHeader("Contacto", back) { IconButton(onClick = edit) { Icon(painterResource(R.drawable.ic_edit), "Editar contacto") } }
            Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(contact.name, Modifier.weight(1f), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = { actions.saveContact(contact.copy(favorite = !contact.favorite)) }) {
                    Icon(painterResource(if (contact.favorite) R.drawable.ic_star else R.drawable.ic_star_outline), if (contact.favorite) "Quitar de favoritos" else "Añadir a favoritos")
                }
            }
            SectionTitle("Tarjetas")
        }
        if (contact.cards.isEmpty()) item { TextButton(onClick = edit) { Text("Añadir tarjeta") } }
        items(contact.cards) { card ->
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val bank = Bank.entries.firstOrNull { it.code == card.bankCode }?.name
                Text(listOfNotNull(card.label.takeIf(String::isNotBlank), bank).distinct().joinToString(" · ").ifBlank { "Tarjeta" }, style = MaterialTheme.typography.titleMedium)
                Text(readableAccount(card.number), style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = { transfer(card) }) { Icon(painterResource(R.drawable.ic_swap_horiz), null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Transferir") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        item { SectionTitle("Teléfonos") }
        if (contact.phones.isEmpty()) item { TextButton(onClick = edit) { Text("Añadir teléfono") } }
        items(contact.phones) { phone ->
            Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(phone.label.ifBlank { "Móvil" }, style = MaterialTheme.typography.titleMedium)
                Text(phone.number, style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = { recharge(phone) }) { Icon(painterResource(R.drawable.ic_smartphone), null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Recargar") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        item { TextButton(onClick = { remove = true }, modifier = Modifier.padding(top = 24.dp)) { Text("Eliminar contacto", color = MaterialTheme.colorScheme.error) } }
    }
    if (remove) AlertDialog(onDismissRequest = { remove = false }, title = { Text("Eliminar contacto") }, text = { Text("Se eliminará ${contact.name} de tus contactos. Su actividad se conserva.") },
        confirmButton = { TextButton(onClick = { actions.deleteContact(contact.id); back() }) { Text("Eliminar") } },
        dismissButton = { TextButton(onClick = { remove = false }) { Text("Cancelar") } })
}

@Composable
internal fun ContactEditor(state: AppUiState, existing: ContactRecord?, actions: UiActions, back: () -> Unit,
                           scanCard: ((String) -> Unit) -> Unit, scanPhone: ((String) -> Unit) -> Unit) {
    var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var favorite by rememberSaveable(existing?.id) { mutableStateOf(existing?.favorite ?: false) }
    var cards by rememberSaveable(existing?.id, stateSaver = cardDraftSaver) { mutableStateOf(existing?.cards.orEmpty()) }
    var phones by rememberSaveable(existing?.id, stateSaver = phoneDraftSaver) { mutableStateOf(existing?.phones.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf<ContactRecord?>(null) }
    LaunchedEffect(state.wallet.contacts, saving) {
        saving?.let { expected -> if (state.wallet.contacts.any { it == expected }) { saving = null; back() } }
    }
    LaunchedEffect(state.notice) { if (saving != null && state.notice != null) { error = state.notice; saving = null } }
    LaunchedEffect(state.contact) {
        state.contact?.let { picked ->
            if (name.isBlank()) name = picked.name
            if (phones.none { it.number == picked.phone }) phones = phones + ContactPhone(picked.phone)
            actions.clearContact()
        }
    }
    val valid = name.isNotBlank() && (cards.isNotEmpty() || phones.isNotEmpty()) &&
        cards.all { it.number.matches(Regex("[0-9]{16}")) } && phones.all { it.number.matches(Regex("[0-9]{8}")) } &&
        cards.map { it.number }.distinct().size == cards.size && phones.map { it.number }.distinct().size == phones.size
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        PageHeader(if (existing == null) "Crear contacto" else "Editar contacto", back)
        Field("Nombre", name, { name = it })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Favorito", Modifier.weight(1f)); Switch(favorite, { favorite = it })
        }
        TextButton(onClick = actions.pickContact) { Icon(painterResource(R.drawable.ic_contacts), null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Elegir del teléfono") }
        SectionTitle("Tarjetas", "Añadir") { cards = cards + ContactCard("") }
        cards.forEachIndexed { index, card ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Tarjeta ${index + 1}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = { cards = cards.filterIndexed { i, _ -> i != index } }) { Icon(painterResource(R.drawable.ic_close), "Quitar tarjeta ${index + 1}") }
                }
                Field("Número de tarjeta", card.number, { value -> cards = cards.mapIndexed { i, old -> if (i == index) old.copy(number = digits(value, 16)) else old } }, KeyboardType.Number,
                    trailing = { IconButton(onClick = { scanCard { number -> cards = cards.mapIndexed { i, old -> if (i == index) old.copy(number = number) else old } } }) { Icon(painterResource(R.drawable.ic_qr_code_scanner), "Leer tarjeta ${index + 1}") } })
                Field("Nombre de la tarjeta (opcional)", card.label, { value -> cards = cards.mapIndexed { i, old -> if (i == index) old.copy(label = value) else old } })
                ChoiceField(Bank.entries.firstOrNull { it.code == card.bankCode }?.name ?: "Banco (opcional)", listOf("" to "Sin especificar") + Bank.entries.map { it.code to it.name }) { code ->
                    cards = cards.mapIndexed { i, old -> if (i == index) old.copy(bankCode = code.ifBlank { null }) else old }
                }
            }
        }
        SectionTitle("Teléfonos", "Añadir") { phones = phones + ContactPhone("") }
        phones.forEachIndexed { index, phone ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Teléfono ${index + 1}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    IconButton(onClick = { phones = phones.filterIndexed { i, _ -> i != index } }) { Icon(painterResource(R.drawable.ic_close), "Quitar teléfono ${index + 1}") }
                }
                Field("Número móvil", phone.number, { value -> phones = phones.mapIndexed { i, old -> if (i == index) old.copy(number = digits(value, 8)) else old } }, KeyboardType.Phone,
                    trailing = { IconButton(onClick = { scanPhone { number -> phones = phones.mapIndexed { i, old -> if (i == index) old.copy(number = number) else old } } }) { Icon(painterResource(R.drawable.ic_qr_code_scanner), "Leer teléfono ${index + 1}") } })
                Field("Nombre del teléfono (opcional)", phone.label, { value -> phones = phones.mapIndexed { i, old -> if (i == index) old.copy(label = value) else old } })
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Primary(if (saving == null) "Guardar contacto" else "Guardando contacto", valid && saving == null) {
            val value = ContactRecord(existing?.id ?: java.util.UUID.randomUUID().toString(), name.trim(), phones, cards, favorite)
            saving = value; error = null
            runCatching { actions.saveContact(value) }.onFailure { error = it.message; saving = null }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContactDestinationPicker(contacts: List<ContactRecord>, phones: Boolean, dismiss: () -> Unit, choose: (String, String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val eligible = contacts.filter { it.name.contains(query, true) && (if (phones) it.phones.isNotEmpty() else it.cards.isNotEmpty()) }.sortedByDescending { it.favorite }
    ModalBottomSheet(onDismissRequest = dismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
            Text(if (phones) "Elegir teléfono" else "Elegir tarjeta", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp)); SearchField(query, { query = it }, "Buscar contacto")
            LazyColumn(contentPadding = PaddingValues(vertical = 16.dp)) {
                if (eligible.isEmpty()) item {
                    EmptyAction(if (query.isBlank()) "No hay ${if (phones) "teléfonos" else "tarjetas"} guardados" else "No hay contactos que coincidan", if (query.isBlank()) "Volver" else "Limpiar búsqueda") {
                        if (query.isBlank()) dismiss() else query = ""
                    }
                }
                eligible.forEach { contact ->
                    if ((phones && contact.phones.isNotEmpty()) || (!phones && contact.cards.isNotEmpty())) {
                        item { Text(contact.name, Modifier.padding(top = 20.dp, bottom = 8.dp), style = MaterialTheme.typography.titleMedium) }
                        if (phones) items(contact.phones) { phone -> ActionRow(phone.label.ifBlank { "Móvil" }, R.drawable.ic_smartphone, phone.number) { choose(phone.number, contact.name) } }
                        else items(contact.cards) { card -> ActionRow(card.label.ifBlank { "Tarjeta" }, R.drawable.ic_credit_card, readableAccount(card.number)) { choose(card.number, contact.name) } }
                    }
                }
            }
        }
    }
}
