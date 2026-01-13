package lv.popovs.longform

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat

class ScreenContentService : AccessibilityService() {

    private val capturedParagraphs = LinkedHashSet<String>()

    override fun onServiceConnected() {
        Log.d(TAG, "Connected")
        super.onServiceConnected()
        capturedParagraphs.clear()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val rootNode = rootInActiveWindow ?: return
                val paragraphs = mutableListOf<String>()
                collectText(rootNode, paragraphs)
                capturedParagraphs.addAll(paragraphs)
                Log.d(TAG, "Captured ${paragraphs.size} paragraphs, now at ${capturedParagraphs.size} total")
            }
        }
    }

    override fun onInterrupt() {
        // This is called when feedback is interrupted, not when the service is stopped.
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.d(TAG, "Unbinding")
        val collectedText = capturedParagraphs.joinToString("\n\n")

        val articleIntent = Intent(this, ArticleActivity::class.java).apply {
            putExtra(ArticleActivity.EXTRA_TEXT, collectedText)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(this, 0, articleIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val channel = NotificationChannel(CHANNEL_ID, "Captured Articles", NotificationManager.IMPORTANCE_DEFAULT)
        notificationManager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Article Captured")
            .setContentText("Tap to view the extracted text.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(1, notification)

        return super.onUnbind(intent)
    }

    private fun collectText(node: AccessibilityNodeInfo?, paragraphs: MutableList<String>) {
        if (node == null) {
            return
        }

        if (node.isImportantForAccessibility && !node.text.isNullOrBlank()) {
            paragraphs.add(node.text.toString())
        }

        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), paragraphs)
        }
    }

    companion object {
        private const val TAG = "ScreenContentService"
        private const val CHANNEL_ID = "longform_channel"
    }
}
