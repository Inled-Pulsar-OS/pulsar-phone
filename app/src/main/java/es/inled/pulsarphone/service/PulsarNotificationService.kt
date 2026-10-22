package es.inled.pulsarphone.service

import android.app.Notification
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import es.inled.pulsarphone.PulsarPhoneApp
import es.inled.pulsarphone.data.NotificationPayload
import es.inled.pulsarphone.net.PulsarApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PulsarNotificationService : NotificationListenerService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return

        val pkg = sbn.packageName ?: return
        // Do not forward our own notifications
        if (pkg == packageName) return

        // Skip ongoing persistent notifications (like media players or background services)
        if ((sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return

        if (!PulsarPhoneApp.instance.isConfigured()) return

        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""

        if (title.isBlank() && text.isBlank()) return

        val appName = try {
            val appInfo = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            pkg
        }

        val payload = NotificationPayload(
            appName = appName,
            title = title,
            text = text,
            packageName = pkg
        )

        scope.launch {
            PulsarApiClient.sendNotification(payload)
        }
    }
}
