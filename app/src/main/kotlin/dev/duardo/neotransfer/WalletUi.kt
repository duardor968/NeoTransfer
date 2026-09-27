package dev.duardo.neotransfer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.duardo.neotransfer.core.*
import dev.duardo.neotransfer.data.*
import kotlin.math.absoluteValue
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.distinctUntilChanged

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WalletHome(state: AppUiState, actions: UiActions, settings: () -> Unit, manage: () -> Unit,
                        banks: () -> Unit, transfer: () -> Unit, scan: () -> Unit, recharge: () -> Unit,
                        services: () -> Unit, activity: () -> Unit, receipt: (MovementItem) -> Unit,
                        resolve: () -> Unit, configure: (ProviderIdentity) -> Unit, receive: () -> Unit = {}, addCard: (WalletProductUi?) -> Unit) {
    val products = remember(state.wallet, state.accounts, state.configuredBanks, state.bank, state.balanceAt) { walletProducts(state) }
    val selected = selectedProduct(state, products)
    val pager = rememberPagerState(initialPage = products.indexOf(selected).coerceAtLeast(0), pageCount = { products.size })
    val latestProducts by rememberUpdatedState(products)
    val latestSelected by rememberUpdatedState(selected)
    val selectionReady = !pager.isScrollInProgress && products.getOrNull(pager.settledPage)?.id == selected?.id
    LaunchedEffect(pager) {
        snapshotFlow { pager.settledPage }.distinctUntilChanged().drop(1).collect { index ->
            latestProducts.getOrNull(index)?.takeIf { it.id != latestSelected?.id }?.let { actions.selectProduct(it.id) }
        }
    }
    LaunchedEffect(selected?.id) {
        val index = products.indexOfFirst { it.id == selected?.id }
        if (index >= 0 && index != pager.currentPage) pager.scrollToPage(index)
    }
    fun query() {
        if (pager.isScrollInProgress) return
        val product = products.getOrNull(pager.settledPage) ?: return
        if (!hasProductAccess(state, product)) configure(product.identity.accessIdentity())
        else if (!state.permissions) actions.permissions() else actions.balanceProduct(product.id)
    }
    PullToRefreshBox(isRefreshing = state.busy, onRefresh = { if (!state.busy) query() }) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    BrandArtwork(R.drawable.nt_brand_textured, 42.dp, 3.dp)
                    Text("NeoTransfer", Modifier.weight(1f).padding(start = 10.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = settings) { Icon(painterResource(R.drawable.ic_settings), "Ajustes") }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Mi cartera", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = manage) { Text("Gestionar") }
                }
                if (products.isEmpty()) Column(Modifier.padding(horizontal = 24.dp)) {
                    Primary("Añadir tarjeta o cuenta") { addCard(null) }
                } else {
                    HorizontalPager(pager, contentPadding = PaddingValues(horizontal = 24.dp), pageSpacing = 12.dp,
                        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp), key = { products[it].id }) { index ->
                        val offset = ((pager.currentPage - index) + pager.currentPageOffsetFraction).coerceIn(-1f, 1f)
                        ProductVisual(products[index], Modifier.graphicsLayer {
                            rotationY = offset * -7f; cameraDistance = 18 * density
                            scaleX = 1f - offset.absoluteValue * .045f; scaleY = scaleX
                        }, manage)
                    }
                    if (products.size > 1) Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.Center) {
                        products.forEachIndexed { index, _ ->
                            val width by animateFloatAsState(if (pager.currentPage == index) 20f else 6f, label = "Tarjeta seleccionada")
                            Box(Modifier.padding(horizontal = 3.dp).width(width.dp).height(6.dp).background(
                                if (pager.currentPage == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape))
                        }
                    }
                    val current = products.getOrNull(pager.currentPage) ?: products.first()
                    if (current.card == null && current.account == null && current.identity.bank != null) {
                        TextButton(onClick = { addCard(current) }, modifier = Modifier.padding(horizontal = 12.dp)) {
                            Icon(painterResource(R.drawable.ic_add), null, Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp)); Text("Añadir tarjeta de ${current.identity.title()}")
                        }
                    }
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
                        Text("Saldo disponible", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(current.available?.let(::formatMoney) ?: "Sin consultar", Modifier.weight(1f),
                                style = if (current.available == null) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.SemiBold)
                            IconButton(onClick = ::query, enabled = !state.busy && !pager.isScrollInProgress) { Icon(painterResource(R.drawable.ic_refresh), "Consultar saldo") }
                        }
                        current.balanceAt?.let { Text(dateText(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    QuickAction("Transferir", R.drawable.ic_swap_horiz, transfer, selectionReady && !state.busy)
                    QuickAction("Escanear", R.drawable.ic_qr_code_scanner, scan, !state.busy && (products.isEmpty() || selectionReady))
                    QuickAction("Recargar", R.drawable.ic_smartphone, recharge, selectionReady && !state.busy)
                    QuickAction("Servicios", R.drawable.ic_receipt_long, services, !state.busy && (products.isEmpty() || selectionReady))
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = banks) { Text("Bancos y accesos") }
                    if (selected?.number?.matches(Regex("[0-9]{16}")) == true)
                        TextButton(onClick = receive, enabled = selected.id == products.getOrNull(pager.settledPage)?.id) {
                            Icon(painterResource(R.drawable.ic_qr_code_scanner), null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Mi QR")
                        }
                }
            }
            state.pending?.let { pending -> item {
                Surface(Modifier.padding(horizontal = 24.dp, vertical = 12.dp).fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("${pending.action.kind.label} pendiente", style = MaterialTheme.typography.titleMedium)
                        Text(if (pending.action.amount.amount.signum() == 0) pending.action.destination else formatMoney(pending.action.amount))
                        TextButton(onClick = resolve, enabled = !state.busy) { Text("Revisar resultado") }
                    }
                }
            } }
            state.confirmed?.let { confirmed -> item {
                Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    BrandArtwork(R.drawable.nt_payment_success_textured, 72.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Transferencia confirmada", style = MaterialTheme.typography.titleMedium)
                        Text(formatMoney(confirmed.amount)); Text(confirmed.reference, style = MaterialTheme.typography.bodySmall)
                    }
                }
            } }
            item { Box(Modifier.padding(horizontal = 24.dp)) { SectionTitle("Actividad reciente", "Ver toda", activity) } }
            val recent = appFinancialHistory(state).take(4)
            if (recent.isEmpty()) item { Box(Modifier.padding(horizontal = 24.dp)) { EmptyAction("Todavía no hay movimientos", "Actualizar actividad", actions.refresh) } }
            items(recent, key = { it.key }) { movement ->
                Box(Modifier.padding(horizontal = 24.dp)) { MovementRow(movement, displayRecipients(state), showDate = true) { receipt(movement) } }
            }
        }
    }
}

@Composable
internal fun ProductVisual(product: WalletProductUi, modifier: Modifier = Modifier, click: () -> Unit) {
    if (product.card == null && product.account == null && product.number == null) {
        Box(modifier.fillMaxWidth()) {
            ActionRow(product.identity.title(), R.drawable.ic_account_balance,
                product.name.takeIf { it.isNotBlank() && it != product.identity.title() }, click = click)
        }
        return
    }
    val isCard = product.kind == ProductKind.CARD
    val artwork = cardArtwork(product.identity)
    if (isCard && artwork != null) { RestoredCard(product, artwork, modifier, click); return }
    val largeText = LocalDensity.current.fontScale > 1.2f
    val shape = RoundedCornerShape(if (isCard) 24.dp else 18.dp)
    val content = MaterialTheme.colorScheme.onSurface
    Box(modifier.fillMaxWidth().then(if (!largeText && isCard) Modifier.aspectRatio(1.586f) else Modifier.heightIn(min = 186.dp))
        .graphicsLayer { shadowElevation = if (isCard) 6.dp.toPx() else 0f; this.shape = shape; clip = true }
        .background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable(onClick = click)) {
        Column(Modifier.fillMaxWidth().then(if (!largeText && isCard) Modifier.fillMaxHeight() else Modifier).padding(24.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(product.identity.title(), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, color = content)
            Icon(painterResource(if (isCard) R.drawable.ic_credit_card else R.drawable.ic_account_balance), null, tint = content.copy(alpha = .8f))
        }
        Spacer(Modifier.height(16.dp))
        product.number?.let { Text(readableAccount(it), style = MaterialTheme.typography.titleLarge.copy(letterSpacing = if (isCard) 1.sp else 0.sp), color = content) }
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.Bottom) {
            Text(product.name.ifBlank { if (isCard) "Tarjeta" else if (product.kind == ProductKind.WALLET) "Monedero" else "Cuenta" },
                Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = content.copy(alpha = .8f))
            product.currency?.let { Text(it.name, style = MaterialTheme.typography.labelLarge, color = content.copy(alpha = .8f)) }
        }
        }
    }
}

@Composable
private fun QuickAction(label: String, icon: Int, click: () -> Unit, enabled: Boolean = true) {
    Column(Modifier.widthIn(min = 72.dp).graphicsLayer { alpha = if (enabled) 1f else .38f }.clip(MaterialTheme.shapes.large).clickable(enabled = enabled, onClick = click).padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(52.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(painterResource(icon), null, Modifier.size(24.dp)) }
        }
        Text(label, Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun WalletManager(state: AppUiState, back: () -> Unit, add: () -> Unit, edit: (WalletProductUi) -> Unit, banks: () -> Unit) {
    val products = walletProducts(state)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp)) {
        item { PageHeader("Mi cartera", back) { IconButton(onClick = add) { Icon(painterResource(R.drawable.ic_add), "Añadir tarjeta o cuenta") } } }
        if (products.isEmpty()) item { Primary("Añadir tarjeta o cuenta", onClick = add) }
        items(products, key = { it.id }) { product ->
            ActionRow(product.name.ifBlank { product.identity.title() }, if (product.kind == ProductKind.CARD) R.drawable.ic_credit_card else R.drawable.ic_account_balance,
                listOfNotNull(product.identity.title(), product.number?.let(::readableAccount), product.currency?.name).joinToString(" · ")) { edit(product) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        item { Spacer(Modifier.height(24.dp)); ActionRow("Bancos y accesos", R.drawable.ic_account_balance, click = banks) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProductEditor(state: AppUiState, existing: WalletProductUi?, actions: UiActions, back: () -> Unit,
                           addBank: () -> Unit, scan: ((String) -> Unit) -> Unit) {
    val registrations = state.wallet.registrations.filter { it.identity() in authenticationProviders() }
    val editingStoredProduct = existing?.card != null || existing?.account != null
    var kind by rememberSaveable(existing?.id) { mutableStateOf(existing?.kind?.takeIf { editingStoredProduct } ?: ProductKind.CARD) }
    var registrationId by rememberSaveable(existing?.id) { mutableStateOf(existing?.registrationId ?: state.wallet.settings.selectedRegistrationId?.takeIf { id -> registrations.any { it.id == id } } ?: registrations.firstOrNull()?.id) }
    var profile by rememberSaveable(existing?.id) { mutableStateOf(existing?.identity?.profile ?: ProfileId.PERSONAL) }
    var number by rememberSaveable(existing?.id) { mutableStateOf(existing?.number?.takeIf { editingStoredProduct || it.all(Char::isDigit) }.orEmpty()) }
    var name by rememberSaveable(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var holder by rememberSaveable(existing?.id) { mutableStateOf(existing?.card?.holderName.orEmpty()) }
    var expiry by rememberSaveable(existing?.id) { mutableStateOf(existing?.card?.expiry.orEmpty().replace("/", "")) }
    var currency by rememberSaveable(existing?.id) { mutableStateOf(existing?.currency?.name) }
    var error by remember { mutableStateOf<String?>(null) }
    var remove by remember { mutableStateOf(false) }
    var savingCard by remember { mutableStateOf<CardRecord?>(null) }
    var savingAccount by remember { mutableStateOf<AccountRecord?>(null) }
    val saving = savingCard != null || savingAccount != null
    LaunchedEffect(registrations.map { it.id }) {
        if (registrationId == null && registrations.size == 1) registrationId = registrations.single().id
    }
    LaunchedEffect(state.wallet.cards, state.wallet.accounts, savingCard, savingAccount) {
        if (savingCard?.let { expected -> state.wallet.cards.any { it == expected } } == true ||
            savingAccount?.let { expected -> state.wallet.accounts.any { it == expected } } == true) {
            savingCard = null; savingAccount = null; back()
        }
    }
    LaunchedEffect(state.notice) { if (saving && state.notice != null) { error = state.notice; savingCard = null; savingAccount = null } }
    val registration = registrations.firstOrNull { it.id == registrationId }
    val identity = registration?.productIdentity(profile.name)
    val miTransfer = registration?.identity() == ProviderIdentity(ProviderId.MITRANSFER)
    val monedero = miTransfer && profile == ProfileId.PERSONAL && existing?.card == null
    val validProfile = identity != null && (if (miTransfer) profile in setOf(ProfileId.PERSONAL, ProfileId.CLASSIC) else profile == ProfileId.PERSONAL) &&
        !(miTransfer && existing?.card != null && profile == ProfileId.PERSONAL)
    val currencies = identity?.let(::productCurrencies).orEmpty()
    val selectedCurrency = currency?.let { runCatching { Currency.valueOf(it) }.getOrNull() }?.takeIf { it in currencies }
        ?: currencies.singleOrNull()
    val validNumber = monedero || number.matches(Regex("[0-9]{16}"))
    val resultingKind = if (monedero) ProductKind.WALLET else if (miTransfer && !editingStoredProduct) ProductKind.CARD else kind
    val validExpiry = resultingKind != ProductKind.CARD || expiry.isEmpty() || expiry.matches(Regex("(0[1-9]|1[0-2])[0-9]{2}"))
    val duplicate = registration != null && if (resultingKind == ProductKind.CARD) {
        state.wallet.cards.any { it.id != existing?.card?.id && it.registrationId == registration.id && it.number == number }
    } else state.wallet.accounts.any { it.id != existing?.account?.id && it.registrationId == registration.id &&
        (if (monedero) it.number.isEmpty() && it.currency == selectedCurrency?.name && registration.productIdentity(it.profileId)?.profile == ProfileId.PERSONAL else it.number == number) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        PageHeader(if (!editingStoredProduct) "Añadir a la cartera" else "Editar producto", back)
        Text("Banco o proveedor", style = MaterialTheme.typography.titleMedium)
        if (editingStoredProduct) Detail(registration?.identity()?.title() ?: "Acceso", registration?.let { registrationLabel(it, state) } ?: "Acceso no disponible")
        else {
            ChoiceField(registration?.let { registrationLabel(it, state) } ?: "Elegir acceso",
                registrations.map { it.id to registrationLabel(it, state) }) { id ->
                registrationId = id; profile = ProfileId.PERSONAL; currency = null; number = ""
                kind = if (registrations.first { it.id == id }.providerId == ProviderId.MITRANSFER.name) ProductKind.WALLET else ProductKind.CARD
            }
            TextButton(onClick = addBank) { Text("Añadir banco o proveedor") }
        }
        if (miTransfer) {
            val profiles = if (existing?.card != null) listOf(ProfileId.CLASSIC)
                else if (existing?.account != null) listOf(existing.identity.profile)
                else listOf(ProfileId.PERSONAL, ProfileId.CLASSIC)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                profiles.forEach { value -> FilterChip(profile == value, { profile = value; currency = null; if (!editingStoredProduct) number = "" },
                    label = { Text(if (value == ProfileId.PERSONAL) "Monedero" else ProviderIdentity(ProviderId.MITRANSFER, value).title()) }) }
            }
        } else if (!editingStoredProduct) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(ProductKind.CARD, ProductKind.ACCOUNT).forEach { item -> FilterChip(kind == item, { kind = item }, label = { Text(if (item == ProductKind.CARD) "Tarjeta" else "Cuenta") }) }
        }
        Field("Nombre en la cartera", name, { name = it })
        if (!monedero) Field(if (resultingKind == ProductKind.CARD) "Número de tarjeta" else "Número de cuenta", number, { number = digits(it, 16) }, KeyboardType.Number,
            trailing = if (resultingKind == ProductKind.CARD) { { IconButton(onClick = { scan { number = it } }) { Icon(painterResource(R.drawable.ic_qr_code_scanner), "Leer número de tarjeta") } } } else null)
        if (resultingKind == ProductKind.CARD) {
            Field("Titular impreso (opcional)", holder, { holder = it.take(80) })
            Field("Vencimiento · MM/AA (opcional)", expiry, { text ->
                expiry = digits(text.replace("/", ""), 4)
            }, KeyboardType.Number, visualTransformation = cardExpiryTransformation)
            if (expiry.length >= 4 && !validExpiry)
                Text("Vencimiento no válido", color = MaterialTheme.colorScheme.error)
        }
        if (currencies.size == 1) Detail("Moneda", currencies.single().name)
        else ChoiceField(selectedCurrency?.name ?: "Moneda", currencies.map { it.name to it.name }) { currency = it }
        if (duplicate) Text(if (monedero) "Este monedero ya está en la cartera" else "Este número ya está en la cartera", color = MaterialTheme.colorScheme.error)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Primary(if (saving) "Guardando" else "Guardar", !saving && !duplicate && validProfile && name.isNotBlank() && validNumber && validExpiry && registration != null && selectedCurrency != null) {
            runCatching {
                actions.dismissNotice(); error = null
                val id = existing?.card?.id ?: existing?.account?.id ?: java.util.UUID.randomUUID().toString()
                if (resultingKind == ProductKind.CARD) {
                    val value = CardRecord(id, requireNotNull(registrationId), number, name.trim(), existing?.card?.accountId, requireNotNull(selectedCurrency).name,
                        existing?.card?.isDefault ?: false, existing?.card?.favorite ?: false, profile.name,
                        holder.trim().ifEmpty { null }, expiry.takeIf { it.isNotEmpty() }?.let { it.take(2) + "/" + it.drop(2) })
                    savingCard = value; actions.saveCard(value)
                } else {
                    val value = AccountRecord(id, requireNotNull(registrationId), if (monedero) "" else number, name.trim(), requireNotNull(selectedCurrency).name,
                        if (monedero) false else existing?.account?.isDefault ?: false, existing?.account?.favorite ?: false, profile.name)
                    savingAccount = value; actions.saveAccount(value)
                }
            }.onFailure { error = it.message; savingCard = null; savingAccount = null }
        }
        if (existing?.card != null || existing?.account != null) TextButton(onClick = { remove = true }) { Text("Eliminar de la cartera", color = MaterialTheme.colorScheme.error) }
    }
    if (remove) AlertDialog(onDismissRequest = { remove = false }, title = { Text("Eliminar de la cartera") },
        text = { Text("Se quitará ${existing?.name}. Su cuenta bancaria y su historial se conservan.") },
        confirmButton = { TextButton(onClick = { if (existing?.card != null) actions.deleteCard(existing.id) else existing?.let { actions.deleteAccount(it.id) }; back() }) { Text("Eliminar") } },
        dismissButton = { TextButton(onClick = { remove = false }) { Text("Cancelar") } })
}

private val cardExpiryTransformation = VisualTransformation { text ->
    if (text.length <= 2) TransformedText(text, OffsetMapping.Identity)
    else TransformedText(AnnotatedString(text.text.take(2) + "/" + text.text.drop(2)), object : OffsetMapping {
        override fun originalToTransformed(offset: Int) = if (offset <= 2) offset else offset + 1
        override fun transformedToOriginal(offset: Int) = if (offset <= 2) offset else offset - 1
    })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChoiceField(label: String, choices: List<Pair<String, String>>, choose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    Box {
        OutlinedButton(onClick = { query = ""; expanded = true }, Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = choices.isNotEmpty(), shape = MaterialTheme.shapes.medium) {
            Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
            Icon(painterResource(R.drawable.ic_more_vert), null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (choices.size <= 8) DropdownMenu(expanded, { expanded = false }) {
            choices.forEach { (key, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { choose(key); expanded = false }) }
        }
    }
    if (expanded && choices.size > 8) ModalBottomSheet(onDismissRequest = { expanded = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(.8f).padding(horizontal = 24.dp)) {
            Text(label, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            SearchField(query, { query = it }, "Buscar")
            val visible = choices.filter { (key, title) -> query.isBlank() || title.contains(query, true) || key.contains(query, true) }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 12.dp)) {
                if (visible.isEmpty()) item { Text("No hay coincidencias", Modifier.padding(vertical = 20.dp)) }
                items(visible) { (key, title) ->
                    TextButton(onClick = { choose(key); expanded = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text(title, Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
    }
}
