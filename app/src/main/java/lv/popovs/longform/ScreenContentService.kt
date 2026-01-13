package lv.popovs.longform

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat

class ScreenContentService : AccessibilityService() {

    private val capturedParagraphs = LinkedHashSet<String>()
    private var unproductiveScrolls = 0
    private var articleTitle: CharSequence? = null

    private val scrollHandler = Handler(Looper.getMainLooper())
    private lateinit var scrollRunnable: Runnable

    override fun onServiceConnected() {
        super.onServiceConnected()
        capturedParagraphs.clear()
        unproductiveScrolls = 0
        articleTitle = null

        scrollRunnable = Runnable {
            val rootNode = rootInActiveWindow ?: return@Runnable

            if (articleTitle == null) {
                articleTitle = rootNode.window?.title
            }

            val lastSize = capturedParagraphs.size
            val paragraphs = mutableListOf<String>()
            collectText(rootNode, paragraphs)
            capturedParagraphs.addAll(paragraphs)

            if (capturedParagraphs.size > lastSize) {
                unproductiveScrolls = 0
            } else {
                unproductiveScrolls++
            }

            val scrollableNode = findScrollableNode(rootNode)
            if (scrollableNode != null && unproductiveScrolls < 5) {
                scrollableNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN.id)
                scrollHandler.postDelayed(scrollRunnable, 200L)
            } else {
                disableSelf()
            }
        }

        scrollHandler.post(scrollRunnable) // Start the loop
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We are now driving the process with a Handler, not listening to events.
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null

        if (node.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN)) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = findScrollableNode(node.getChild(i))
            if (child != null) return child
        }
        return null
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        scrollHandler.removeCallbacks(scrollRunnable) // Clean up the handler

        if (capturedParagraphs.isEmpty()) {
            return super.onUnbind(intent)
        }

        val collectedText = capturedParagraphs.joinToString("\n\n")
        val articleIntent = Intent(this, ArticleActivity::class.java).apply {
            putExtra(ArticleActivity.EXTRA_TEXT, collectedText)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(this, notificationIdCounter, articleIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL_ID, "Captured Articles", NotificationManager.IMPORTANCE_DEFAULT)
        notificationManager.createNotificationChannel(channel)

        val title = if (articleTitle != null) "Captured Article from $articleTitle" else "Captured Article"
        val previewText = collectedText.take(100) + if (collectedText.length > 100) "..." else ""

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(previewText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(collectedText))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(notificationIdCounter++, notification)

        return super.onUnbind(intent)
    }

    private fun collectText(node: AccessibilityNodeInfo?, paragraphs: MutableList<String>) {
        if (node == null) return
        if (!node.text.isNullOrBlank()) {
            paragraphs.add(node.text.toString())
        }
        for (i in 0 until node.childCount) {
            collectText(node.getChild(i), paragraphs)
        }
    }

    companion object {
        private const val TAG = "ScreenContentService"
        private const val CHANNEL_ID = "longform_channel"
        private var notificationIdCounter = 1
    }
}
