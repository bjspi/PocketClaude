package de.smartzone.pocketclaude.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat

/**
 * Schlanker Foreground-Service. Tut nichts aktiv — der Stream läuft im
 * `viewModelScope` des ChatViewModel weiter. Der Service ist nur ein
 * "Lebens-Anker": solange ein Foreground-Service läuft, killt Android den
 * App-Prozess nicht, auch wenn der User die App in den Hintergrund schickt.
 *
 * Start: [start] aus dem ChatViewModel, wenn ein Send losgeht.
 * Stop:  [stop]  aus dem ChatViewModel, wenn der Stream fertig oder
 *               abgebrochen ist.
 *
 * Die "Antwort fertig"-Notification (wenn die App im Hintergrund war) wird
 * NICHT von hier aus geschickt — das macht direkt das ChatViewModel über
 * den NotificationHelper. So bleibt der Service maximal dumm.
 *
 * **Android 14+ (API 34):** `startForeground` MUSS den Service-Type passend
 * zum Manifest-Eintrag mitgeben — sonst `MissingForegroundServiceTypeException`
 * direkt beim Start. ServiceCompat liefert auf alten APIs einen No-Op-Type.
 */
class StreamingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE)
        val cid = intent?.getStringExtra(EXTRA_CID).orEmpty()
        NotificationHelper.ensureChannels(this)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(
            this,
            NotificationHelper.NOTIF_ID_STREAMING,
            NotificationHelper.buildStreamingNotification(this, title, cid),
            type,
        )
        return START_NOT_STICKY
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_CID = "cid"

        fun start(context: Context, title: String?, conversationId: String) {
            val intent = Intent(context, StreamingService::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_CID, conversationId)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StreamingService::class.java))
        }
    }
}
