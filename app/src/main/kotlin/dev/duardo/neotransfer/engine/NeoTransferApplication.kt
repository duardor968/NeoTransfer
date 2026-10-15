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

/** Starts the controller with the process: opening the app or an SMS arriving with the app closed. */
class NeoTransferApplication : Application() {
    lateinit var controller: AppController
        private set

    override fun onCreate() {
        super.onCreate()
        LegacyData.clearOnFirstStart(this)
        val inbox = AndroidSmsInbox(this)
        controller = AppController(
            store = RoomStore(NeoDatabase.open(this).dao()),
            modem = AndroidUssdModem(this),
            inbox = inbox,
            vault = BankPinVault(this),
            notifier = AndroidNotifier(this),
            documents = ContentDocuments(this),
            phoneContacts = AndroidPhoneContacts(this),
            clock = Instant::now,
            state = Dispatchers.Default.limitedParallelism(1),
            io = Dispatchers.IO,
        )
        inbox.observe(controller::onInboxChanged)
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = controller.onForeground()
            override fun onStop(owner: LifecycleOwner) = controller.onBackground()
        })
    }
}

val Context.controller: AppController get() = (applicationContext as NeoTransferApplication).controller
