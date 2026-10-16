package dev.duardo.neotransfer.app

import android.net.Uri
import dev.duardo.neotransfer.platform.BiometricResult
import javax.crypto.Cipher

/** What only the activity can do: system dialogs and other apps. Each call suspends until the person answers. */
interface Host {
    suspend fun biometric(title: String, subtitle: String? = null, cipher: Cipher? = null): BiometricResult

    /** System "save as": null when the person backs out. */
    suspend fun createDocument(name: String): Uri?
    suspend fun openDocument(): Uri?
    fun displayName(uri: Uri): String?

    /** Asks for the runtime permissions still missing, all in one go; the answer goes to [AppModel.onPermissionsResult]. */
    fun requestPermissions(permissions: List<String>)
    fun shouldExplain(permission: String): Boolean

    /** One permission asked where it is needed (the address book from Contacts); true when granted. */
    suspend fun requestPermission(permission: String): Boolean

    fun share(text: String)
    fun copy(text: String)
    fun open(url: String)
    /** The person in the phone's Contacts app, to change their name or phones; [key] is the lookup key. */
    fun openContact(key: String)
    fun openAppSettings()
    fun openNotificationSettings()
}
