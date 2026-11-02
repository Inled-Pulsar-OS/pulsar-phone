package es.inled.pulsarphone.net

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class FlydropActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_ACCEPT = "es.inled.pulsarphone.ACTION_ACCEPT_TRANSFER"
        const val ACTION_DECLINE = "es.inled.pulsarphone.ACTION_DECLINE_TRANSFER"
        const val EXTRA_SESSION_ID = "extra_session_id"
        const val EXTRA_NOTIF_ID = "extra_notif_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return
        val notifId = intent.getIntExtra(EXTRA_NOTIF_ID, 0)
        if (notifId != 0) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(notifId)
        }

        when (intent.action) {
            ACTION_ACCEPT -> FlydropServer.respondDecision(sessionId, true)
            ACTION_DECLINE -> FlydropServer.respondDecision(sessionId, false)
        }
    }
}
