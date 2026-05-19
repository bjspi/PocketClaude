package de.smartzone.pocketclaude.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import de.smartzone.pocketclaude.MainActivity
import de.smartzone.pocketclaude.R

/**
 * Notification-Channels + Builder für die App.
 *
 * Wir nutzen zwei Channels:
 *  - `streaming` (low importance): die Foreground-Notification während Claude antwortet.
 *    Macht keinen Sound, kein Vibrieren — es ist nur ein Status-Anker, der den App-Prozess
 *    am Leben hält, wenn der User die App in den Hintergrund schiebt.
 *  - `result`   (default importance): die "Antwort fertig"-Notification, sobald Claude
 *    geantwortet hat und die App im Hintergrund ist. Macht einen Ton + Heads-Up.
 *
 * Beim ersten Aufruf von [ensureChannels] werden die Channels (idempotent) im System
 * registriert. PocketClaudeApp.onCreate() ruft das einmal auf.
 */
object NotificationHelper {

    const val CHANNEL_STREAMING = "claude_streaming"
    const val CHANNEL_RESULT = "claude_result"

    const val NOTIF_ID_STREAMING = 1001
    const val NOTIF_ID_RESULT_BASE = 2000  // pro Konversation eine eigene ID via Hash

    /** Optional Extra: Activity öffnet sich auf diesen Chat. */
    const val EXTRA_OPEN_CID = "pocket_claude_open_cid"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService<NotificationManager>() ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_STREAMING,
                "Claude antwortet…",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Hält die App am Leben, während Claude eine Antwort streamt."
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_RESULT,
                "Antwort fertig",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "Benachrichtigt, sobald Claude mit seiner Antwort fertig ist."
            }
        )
    }

    /**
     * Notification, die während des Streamens dauerhaft sichtbar ist (Foreground-Service).
     * Klick darauf öffnet den Chat wieder.
     */
    fun buildStreamingNotification(
        context: Context,
        title: String?,
        conversationId: String,
    ): android.app.Notification {
        val pendingIntent = PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            chatIntent(context, conversationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_STREAMING)
            .setContentTitle("Claude antwortet…")
            .setContentText(title?.takeIf { it.isNotBlank() } ?: "Stream läuft im Hintergrund")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(pendingIntent)
            .build()
    }

    /**
     * Notification, die nach dem Stream-Ende erscheint, **wenn die App im Hintergrund ist**.
     * Klick darauf öffnet die App auf dem entsprechenden Chat.
     */
    fun showResultNotification(
        context: Context,
        conversationTitle: String,
        conversationId: String,
        snippet: String,
    ) {
        val pendingIntent = PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            chatIntent(context, conversationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_RESULT)
            .setContentTitle(conversationTitle.ifBlank { "Pocket Claude" })
            .setContentText("Antwort fertig — antippen zum Lesen")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    snippet.take(280).ifBlank { "Antwort fertig — antippen zum Lesen" }
                )
            )
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pendingIntent)
            .build()

        val nm = context.getSystemService<NotificationManager>() ?: return
        nm.notify(NOTIF_ID_RESULT_BASE + conversationId.hashCode().and(0x7fff), notif)
    }

    private fun chatIntent(context: Context, conversationId: String): Intent {
        return Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_OPEN_CID, conversationId)
        }
    }
}
