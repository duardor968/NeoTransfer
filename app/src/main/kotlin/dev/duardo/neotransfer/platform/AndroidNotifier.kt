package dev.duardo.neotransfer.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.duardo.neotransfer.MainActivity
import dev.duardo.neotransfer.R
import dev.duardo.neotransfer.engine.Notice
import dev.duardo.neotransfer.engine.NoticeKind
import dev.duardo.neotransfer.engine.Notifier
import dev.duardo.neotransfer.engine.Routes

/** Private notifications with a generic public version for the lock screen. */
class AndroidNotifier(context: Context) : Notifier {
    private val context = context.applicationContext
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannels(listOf(
            NotificationChannel(CREDITS, "Transferencias recibidas", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(RESULTS, "Resultado de operaciones", NotificationManager.IMPORTANCE_DEFAULT),
        ))
    }

    override fun post(notice: Notice) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val channel = if (notice.kind == NoticeKind.CREDIT) CREDITS else RESULTS
        val open = PendingIntent.getActivity(context, 0, routeIntent(context, notice.route).setIdentifier(notice.key),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val public = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_neotransfer_monochrome)
            .setContentTitle(notice.publicTitle)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        val notification = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_neotransfer_monochrome)
            .setContentTitle(notice.title)
            .apply { notice.text?.let(::setContentText) }
            .setCategory(Notification.CATEGORY_STATUS)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        manager.notify(notice.key, 0, notification)
    }

    private companion object {
        const val CREDITS = "incoming_transfers"
        const val RESULTS = "operation_results"
    }
}

/** Opens the app on [route] ([Routes]); an open app receives it in `onNewIntent`. */
fun routeIntent(context: Context, route: String): Intent = Intent(context, MainActivity::class.java)
    .putExtra(Routes.EXTRA, route)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
