package lv.popovs.longform

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat

class ScreenContentService : AccessibilityService() {

    private val capturedParagraphs = LinkedHashSet<String>()
    private var isAutoScrolling = false
    private var unproductiveScrolls = 0
    private var lastScrollTime = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        capturedParagraphs.clear()
        isAutoScrolling = true
        unproductiveScrolls = 0
        lastScrollTime = 0L
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!isAutoScrolling) return

        val rootNode = rootInActiveWindow ?: return

        val lastSize = capturedParagraphs.size
        val paragraphs = mutableListOf<String>()
        collectText(rootNode, paragraphs)
        capturedParagraphs.addAll(paragraphs)

        if (capturedParagraphs.size > lastSize) {
            unproductiveScrolls = 0
        } else {
            unproductiveScrolls++
        }

        val canScrollNow = if (unproductiveScrolls == 0) {
            true // Productive scroll, go again immediately
        } else {
            // Unproductive, wait for debounce
            System.currentTimeMillis() - lastScrollTime > SCROLL_DEBOUNCE_DELAY
        }

        if (canScrollNow) {
            val scrollableNode = findScrollableNode(rootNode)
            if (scrollableNode != null && unproductiveScrolls < 5) {
                scrollableNode.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id)
                lastScrollTime = System.currentTimeMillis()
            } else {
                isAutoScrolling = false
                disableSelf()
            }
        }
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null

        val actionList = node.actionList
        for (action in actionList) {
            if (action.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD.id) {
                return node
            }
        }

        for (i in 0 until node.childCount) {
            val child = findScrollableNode(node.getChild(i))
            if (child != null) return child
        }
        return null
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
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
        if (node == null) return
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
        private const val SCROLL_DEBOUNCE_DELAY = 1000L
    }
}
