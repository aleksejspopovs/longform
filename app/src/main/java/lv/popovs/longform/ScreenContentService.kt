package lv.popovs.longform

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.text.Spanned
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat

@SuppressLint("AccessibilityPolicy")
class ScreenContentService : AccessibilityService() {
    private val capturedParagraphs = LinkedHashSet<String>()
    private var unproductiveScrolls = 0
    private var articleTitle: CharSequence? = null
    private val webViewCache = mutableSetOf<String>()

    private val scrollHandler = Handler(Looper.getMainLooper())
    private lateinit var scrollRunnable: Runnable

    override fun onServiceConnected() {
        super.onServiceConnected()
        capturedParagraphs.clear()
        unproductiveScrolls = 0
        articleTitle = null
        webViewCache.clear()

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
                Log.d(TAG, "productive ${capturedParagraphs.size} $lastSize, dumping tree:")
                logTree(rootInActiveWindow)
                Log.d(TAG, "----")
            } else {
                unproductiveScrolls++
                Log.d(TAG, "unproductive ${capturedParagraphs.size} $lastSize")
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

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

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
            putExtra(ArticleActivity.EXTRA_TITLE, articleTitle)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(this, notificationIdCounter, articleIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
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

    private fun emphasize(text: String, left: String, right: String): String {
        val trimmedText = text.trim()
        if (trimmedText.isEmpty()) return text

        val firstNonWhitespace = text.indexOfFirst { !it.isWhitespace() }
        val lastNonWhitespace = text.indexOfLast { !it.isWhitespace() }

        val leadingWhitespace = text.substring(0, firstNonWhitespace)
        val trailingWhitespace = text.substring(lastNonWhitespace + 1)

        return leadingWhitespace + left + trimmedText + right + trailingWhitespace
    }

    private fun isTextView(node: AccessibilityNodeInfo): Boolean {
        return node.className?.endsWith(".TextView") == true
    }

    private fun isView(node: AccessibilityNodeInfo): Boolean {
        return node.className?.endsWith(".View") == true
    }
    
    private fun isWebView(node: AccessibilityNodeInfo): Boolean {
        return node.className?.endsWith(".WebView") == true
    }

    private fun isLeaf(node: AccessibilityNodeInfo): Boolean {
        return node.childCount == 0
    }

    private fun isLink(node: AccessibilityNodeInfo): Boolean {
        // A link is a clickable View with one or more children, all of whom are leaf TextViews.
        val hasClickAction = node.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
        if (!hasClickAction || node.childCount == 0 || !isView(node)) return false

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: return false
            if (!(isTextView(child) && isLeaf(child))) {
                return false
            }
        }
        return true
    }

    private fun isCohesiveParagraph(node: AccessibilityNodeInfo): Boolean {
        // Rule 1: Single TextView
        if (isTextView(node)) {
            return true
        }

        // Rule 2: Cohesive View (must have children to be a container)
        if (node.childCount > 0) {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                // A cohesive view can only contain these types of children:
                // leaf TextViews
                val isLeafTextView = isTextView(child) && isLeaf(child)
                // leaf Views (not TextViews)
                val isLeafView = isView(child) && isLeaf(child)
                // links (clickable Views with one or more leaf TextView children)
                val isAnchorLink = isLink(child)

                if (!isLeafTextView && !isLeafView && !isAnchorLink) {
                    // Found a child that breaks the cohesion rule (e.g., a ViewGroup that is not a link)
                    return false
                }
            }
            // All children conform to the rules
            return true
        }

        // Not a TextView and no children. Not a paragraph we care about.
        return false
    }

    private fun extractCohesiveText(node: AccessibilityNodeInfo): String {
        // If it's a link, return the special text by concatenating all leaf TextView children.
        if (isLink(node)) {
            val parts = mutableListOf<String>()
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                val childText = child?.text?.toString()
                if (!childText.isNullOrBlank()) {
                    parts.add(childText)
                }
            }
            return emphasize(parts.joinToString(""), "[", "]")
        }

        // If it has text itself (Rule 1), return it.
        if (!node.text.isNullOrBlank()) {
            val text = node.text.toString()
            if (isView(node)) {
                return emphasize(text, "*", "*")
            }
            return text
        }

        // Otherwise, combine children's text (Rule 2 composition).
        val parts = mutableListOf<String>()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            // We only extract text from the conforming children.
            val childText = extractCohesiveText(child)
            if (childText.isNotBlank()) {
                parts.add(childText)
            }
        }
        return parts.joinToString("")
    }

    private fun formatSnippet(text: String?): String {
        if (text == null) return "null"
        if (text.length <= 30) return text
        return "${text.take(15)}...${text.takeLast(15)}"
    }

    private fun logTree(node: AccessibilityNodeInfo?, depth: Int = 0) {
        if (node == null) return

        val textCharSequence = node.text
        val text = textCharSequence?.toString()
        val isParagraph = isCohesiveParagraph(node)
        
        val isWebView = isWebView(node)
        val nodeId = node.uniqueId
        val seenWebView = if (isWebView && nodeId != null) webViewCache.contains(nodeId) else false
        val clickable = node.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)

        Log.d(TAG, "  ".repeat(depth)
                + "${nodeId ?: "null"}/${node.hashCode()}.${node.className} [${formatSnippet(text)}]"
                + (if (isParagraph) " (paragraph)" else "")
                + (if (isWebView) " (WEBVIEW)" else "")
                + (if (seenWebView) " (seen)" else "")
                + (if (clickable) " (clickable)" else "")
        )

        // Log spans if the text is Spanned
        if (textCharSequence is Spanned) {
            val spans = textCharSequence.getSpans(0, textCharSequence.length, Any::class.java)
            spans.forEach { span ->
                val start = textCharSequence.getSpanStart(span)
                val end = textCharSequence.getSpanEnd(span)
                Log.d(TAG, "  ".repeat(depth + 1) + "SPAN: ${span::class.java.simpleName} ($start-$end)")
            }
        }

        for (i in 0 until node.childCount) {
            logTree(node.getChild(i), depth + 1)
        }
    }

    private fun collectText(node: AccessibilityNodeInfo?, paragraphs: MutableList<String>) {
        if (node == null) return

        if (isWebView(node)) {
            val nodeId = node.uniqueId
            if (nodeId != null) {
                val isNew = webViewCache.add(nodeId)
                if (!isNew) {
                    // WebView already seen, skip processing children.
                    return
                }
            }
        }

        if (isCohesiveParagraph(node)) {
            val text = extractCohesiveText(node)
            if (text.isNotBlank()) {
                paragraphs.add(text.trim())
                Log.d(TAG, "in ${node.uniqueId}, adding ${formatSnippet(text)}")
            }

            // WebViews often flatten the accessibility tree, making a TextView a container
            // for other paragraph-level TextViews after a scroll. If we find a TextView
            // that is a paragraph but also has children, we must continue recursion.
            if (isTextView(node) && node.childCount > 0) {
                for (i in 0 until node.childCount) {
                    collectText(node.getChild(i), paragraphs)
                }
            }
        } else {
            // Not a cohesive paragraph, so its children are treated as separate potential paragraphs (implicit breaks)
            for (i in 0 until node.childCount) {
                collectText(node.getChild(i), paragraphs)
            }
        }
    }

    companion object {
        private const val TAG = "ScreenContentService"
        private const val CHANNEL_ID = "longform_channel"
        private var notificationIdCounter = 1
    }
}