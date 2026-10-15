package dev.duardo.neotransfer.engine

import android.app.Application
import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.duardo.neotransfer.data.LegacyData
import dev.duardo.neotransfer.data.NeoDatabase
import dev.duardo.neotransfer.data.RoomStore
import dev.duardo.neotransfer.platform.AndroidNotifier
import dev.duardo.neotransfer.platform.AndroidPhoneContacts
import dev.duardo.neotransfer.platform.AndroidSmsInbox
import dev.duardo.neotransfer.platform.AndroidUssdModem
import dev.duardo.neotransfer.platform.BankPinVault
import dev.duardo.neotransfer.platform.ContentDocuments
import kotlinx.coroutines.Dispatchers
import java.time.Instant

/** The SIM: what sends USSD and the inbox the bank's SMS land in. */
interface PhoneLine {
    val modem: UssdModem
    val inbox: SmsInbox

    /** Starts telling [controller] about SMS that arrive while the process lives. */
    fun attach(controller: AppController)
}

/** Starts the controller with the process: opening the app or an SMS arriving with the app closed. */
open class NeoTransferApplication : Application() {
    lateinit var controller: AppController
        private set

    override fun onCreate() {
        super.onCreate()
        LegacyData.clearOnFirstStart(this)
        val line = phoneLine()
        controller = AppController(
            store = RoomStore(NeoDatabase.open(this).dao()),
            modem = line.modem,
            inbox = line.inbox,
            vault = BankPinVault(this),
            notifier = AndroidNotifier(this),
            documents = ContentDocuments(this),
            phoneContacts = AndroidPhoneContacts(this),
            clock = Instant::now,
            state = Dispatchers.Default.limitedParallelism(1),
            io = Dispatchers.IO,
        )
        line.attach(controller)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = controller.onForeground()
            override fun onStop(owner: LifecycleOwner) = controller.onBackground()
        })
    }

    /** The phone's own SIM and SMS inbox. */
    protected open fun phoneLine(): PhoneLine = object : PhoneLine {
        override val modem = AndroidUssdModem(this@NeoTransferApplication)
        override val inbox = AndroidSmsInbox(this@NeoTransferApplication)
        override fun attach(controller: AppController) = inbox.observe(controller::onInboxChanged)
    }
}

val Context.controller: AppController get() = (applicationContext as NeoTransferApplication).controller
