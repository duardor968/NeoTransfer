package dev.duardo.neotransfer.app

import android.Manifest
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.duardo.neotransfer.designsystem.format.formatAmount
import dev.duardo.neotransfer.ui.activity.ActivityActions
import dev.duardo.neotransfer.ui.activity.ActivityScreen
import dev.duardo.neotransfer.ui.contacts.ContactsActions
import dev.duardo.neotransfer.ui.contacts.ContactsScreen
import dev.duardo.neotransfer.ui.home.HomeActions
import dev.duardo.neotransfer.ui.home.HomeScreen
import dev.duardo.neotransfer.ui.model.ActivityContent
import dev.duardo.neotransfer.ui.model.ActivityFilter
import dev.duardo.neotransfer.ui.model.ActivityUi
import dev.duardo.neotransfer.ui.model.ContactUi
import dev.duardo.neotransfer.ui.model.ContactsContent
import dev.duardo.neotransfer.ui.model.ContactsUi
import dev.duardo.neotransfer.ui.model.CounterpartUi
import dev.duardo.neotransfer.ui.model.HomeUi
import dev.duardo.neotransfer.ui.model.OperationUi
import dev.duardo.neotransfer.ui.model.ServiceKind
import dev.duardo.neotransfer.ui.model.ServicesUi
import dev.duardo.neotransfer.ui.services.ServicesActions
import dev.duardo.neotransfer.ui.services.ServicesScreen
import dev.duardo.neotransfer.ui.shell.MainShell
import dev.duardo.neotransfer.ui.shell.Tab
import java.text.Normalizer

/** The four tabs under the bottom bar. */
class Main(private val app: AppModel) : Destination(), HomeActions, ContactsActions, ServicesActions {
    private var recharge by mutableStateOf(false)
    private var importing by mutableStateOf(false)

    @Composable
    override fun Content(app: AppModel, book: Book) {
        MainShell(app.tab, { app.tab = it }) {
            Crossfade(app.tab, label = "tab") { tab ->
                when (tab) {
                    Tab.Home -> HomeScreen(home(book), this)
                    Tab.Activity -> ActivityScreen(app.activity.ui(book), app.activity)
                    Tab.Contacts -> ContactsScreen(contacts(book), this)
                    Tab.Services -> ServicesScreen(ServicesUi(book.references), this)
                }
            }
        }
    }

    // ---- Home ----

    private fun home(book: Book) = HomeUi(
        cards = book.wallet,
        selected = book.wallet.indexOfFirst { it.card.id == app.homeCard }.coerceAtLeast(0),
        inProgress = book.inProgress,
        recent = if (book.domain.loaded) book.recent else null,
        recharge = if (recharge) book.rechargeTargets else null,
    )

    private val selectedCard: String? get() = app.book.value.wallet.let { wallet -> wallet.firstOrNull { it.card.id == app.homeCard } ?: wallet.firstOrNull() }?.card?.id

    override fun selectCard(index: Int) {
        app.homeCard = app.book.value.wallet.getOrNull(index)?.card?.id
    }

    override fun refreshBalance(cardId: String) = app.query { app.controller.refreshBalance(cardId) }
    override fun openCard(cardId: String) {
        val card = app.book.value.card(cardId) ?: return
        app.navigator.push(BankSettingsDestination(app, card.card.card.bank.ui()))
    }
    override fun addCard() = app.navigator.push(BankChoiceDestination(app, setup = false))
    /** Sending or receiving needs a card of one's own: without one, setting up a bank comes first. */
    override fun transfer() = if (selectedCard == null) addCard() else TransferFlow(app, selectedCard).let { it.start(RecipientDestination(it)) }
    override fun pay() = app.navigator.push(PayDestination(app))
    override fun receive() = if (selectedCard == null) addCard() else app.navigator.push(ReceiveDestination(app, selectedCard))
    override fun recharge() { recharge = true }
    override fun rechargeTo(targetId: String) {
        recharge = false
        val reference = app.book.value.reference(targetId) ?: return
        app.navigator.push(ServiceDestination(app, reference.kind.ui(), reference = reference))
    }
    override fun rechargeNew(kind: ServiceKind) {
        recharge = false
        app.navigator.push(ServiceDestination(app, kind))
    }
    override fun closeRecharge() { recharge = false }
    override fun openOperation(id: String) = app.navigator.push(OperationDestination(app, id))
    override fun retry(id: String) = app.retry(id)
    override fun allActivity() { app.tab = Tab.Activity }
    override fun settings() = app.navigator.push(SettingsDestination(app))

    // ---- Contacts ----

    private fun contacts(book: Book): ContactsUi {
        val query = app.contactsQuery
        val content = when {
            book.contacts.isEmpty() -> ContactsContent.Empty
            else -> book.contacts.filter { it.matches(query) }.let { if (it.isEmpty()) ContactsContent.NoResults else ContactsContent.Items(it) }
        }
        return ContactsUi(query, content, importing)
    }

    override fun query(value: String) { app.contactsQuery = value }
    override fun open(id: String) = app.navigator.push(ContactDestination(app, id))
    override fun create() = app.navigator.push(ContactEditDestination(app, null))
    override fun importFromPhone() {
        app.launch {
            importing = true
            try {
                app.importPhoneContact()?.let { app.navigator.push(ContactDestination(app, it.id)) }
            } finally {
                importing = false
            }
        }
    }

    // ---- Services ----

    override fun open(kind: ServiceKind) =
        app.navigator.push(if (kind == ServiceKind.Cubacel) CubacelDestination(app) else ServiceDestination(app, kind))

    override fun pay(referenceId: String) {
        val reference = app.book.value.reference(referenceId) ?: return
        app.navigator.push(ServiceDestination(app, reference.kind.ui(), reference = reference))
    }
}

/** Activity's search and filters, kept while the person moves around the app. */
class ActivityState(private val app: AppModel) : ActivityActions {
    private var query by mutableStateOf("")
    private var filter by mutableStateOf(ActivityFilter.All)
    private var card by mutableStateOf<String?>(null)

    fun ui(book: Book): ActivityUi {
        val content = when {
            !book.domain.loaded -> ActivityContent.Loading
            !app.granted(Manifest.permission.READ_SMS) -> ActivityContent.NoAccess
            book.activity.isEmpty() -> ActivityContent.Empty
            else -> book.activity.filter { item ->
                (filter == ActivityFilter.All || item.kind.incoming == (filter == ActivityFilter.In)) &&
                    (card == null || item.card?.id == card) && item.matches(query)
            }.let { if (it.isEmpty()) ActivityContent.NoResults else ActivityContent.Items(it) }
        }
        return ActivityUi(query, filter, book.cards, card?.takeIf { id -> book.cards.any { it.id == id } }, content)
    }

    override fun query(value: String) { query = value }
    override fun filter(value: ActivityFilter) { filter = value }
    override fun card(cardId: String?) { card = cardId }
    override fun open(id: String) = app.navigator.push(OperationDestination(app, id))
    override fun allowSms() {
        app.launch {
            val host = app.host ?: return@launch
            if (host.shouldExplain(Manifest.permission.READ_SMS) || !app.granted(Manifest.permission.READ_SMS)) {
                if (host.requestPermission(Manifest.permission.READ_SMS)) app.controller.onPermissionsChanged() else host.openAppSettings()
            }
        }
    }
    override fun checkWithBank() {
        val card = app.book.value.wallet.let { wallet -> wallet.firstOrNull { it.card.id == app.homeCard } ?: wallet.firstOrNull() } ?: return
        app.query { app.controller.refreshHistory(card.card.id) }
    }
}

/** Accent- and case-insensitive text, digits compared without spaces. */
private fun normalized(text: String) = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

private fun OperationUi.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val words = normalized(query).trim()
    val digits = query.filter(Char::isDigit)
    val counterpart = when (val c = counterpart) {
        is CounterpartUi.Card -> c.number
        is CounterpartUi.Phone -> c.number
        is CounterpartUi.Reference -> c.value
        null -> ""
    }
    return words in normalized(title) || (digits.length >= 3 && (digits in counterpart.filter(Char::isDigit) || card?.number?.contains(digits) == true)) ||
        amount?.let { money -> words.replace(",", "") in formatAmount(money.amount).replace(",", "") } == true
}

private fun ContactUi.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val digits = query.filter(Char::isDigit)
    return normalized(query).trim() in normalized(name) ||
        (digits.length >= 3 && (cards.any { digits in it.number } || phones.any { digits in it }))
}
