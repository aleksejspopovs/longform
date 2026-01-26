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

@SuppressLint("AccessibilityPolicy")
class ScreenContentService : AccessibilityService() {
    private class SnapshotNode(
        val key: String,
        val className: CharSequence?,
        val text: CharSequence?,
        val actionIds: List<Int>,
        val uniqueId: String?
    ) {
        val children = mutableListOf<SnapshotNode>()
        private val childKeys = mutableSetOf<String>()

        fun addChild(child: SnapshotNode) {
            if (childKeys.add(child.key)) {
                children.add(child)
            }
        }
    }

    private val allNodes = mutableMapOf<String, SnapshotNode>()
    private var rootSnapshot: SnapshotNode? = null
    private var unproductiveScrolls = 0
    private var articleTitle: CharSequence? = null

    private val scrollHandler = Handler(Looper.getMainLooper())
    private lateinit var scrollRunnable: Runnable

    override fun onServiceConnected() {
        super.onServiceConnected()
        allNodes.clear()
        rootSnapshot = null
        unproductiveScrolls = 0
        articleTitle = null

        scrollRunnable = Runnable {
            val rootNode = rootInActiveWindow ?: return@Runnable
            if (articleTitle == null) {
                articleTitle = rootNode.window?.title
            }

            val lastTreeSize = allNodes.size
            captureToSnapshot(rootNode, null)

            if (rootSnapshot == null) {
                rootSnapshot = allNodes[generateKey(rootNode)]
            }

            if (allNodes.size > lastTreeSize) {
                unproductiveScrolls = 0
                Log.d(TAG, "productive: tree size ${allNodes.size} (was $lastTreeSize), dumping tree:")
                logTree(rootSnapshot)
                Log.d(TAG, "----")
            } else {
                unproductiveScrolls++
                Log.d(TAG, "unproductive: tree size ${allNodes.size}")
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

    private fun generateKey(node: AccessibilityNodeInfo): String {
        return "${node.uniqueId}_${node.hashCode()}_${node.className}"
    }

    private fun captureToSnapshot(node: AccessibilityNodeInfo, parentSnapshot: SnapshotNode?) {
        val key = generateKey(node)
        var snapshot = allNodes[key]
        if (snapshot == null) {
            snapshot = SnapshotNode(
                key = key,
                className = node.className,
                text = node.text,
                actionIds = node.actionList.map { it.id },
                uniqueId = node.uniqueId
            )
            allNodes[key] = snapshot
            parentSnapshot?.addChild(snapshot)
        } else {
            if (isWebView(node)) {
                // WebView generally exposes all elements without needing to scroll, and it has
                // weird behaviors where scrolling can change the structure of the accessibility
                // tree, so do not snapshot a WebView more than once.
                return
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            if (child != null) {
                captureToSnapshot(child, snapshot)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    private fun findScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null

        if (node.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_DOWN)) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            val scrollable = findScrollableNode(child)
            if (scrollable != null) return scrollable
        }
        return null
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        scrollHandler.removeCallbacks(scrollRunnable) // Clean up the handler

        val paragraphs = mutableListOf<String>()
        rootSnapshot?.let { collectText(it, paragraphs) }

        if (paragraphs.isEmpty()) {
            return super.onUnbind(intent)
        }

        val collectedText = paragraphs.joinToString("\n\n")
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

        // Clear tree data to free memory
        allNodes.clear()
        rootSnapshot = null

        return super.onUnbind(intent)
    }

    private fun emphasize(text: String, left: String, right: String): String {
        val trimmedText = text.trim()
        if (trimmedText.isEmpty()) return text

        val firstNonWhitespace = text.indexOfFirst { !it.isWhitespace() }
        val lastNonWhitespace = text.indexOfLast { !it.isWhitespace() }

        val leadingWhitespace = text.take(firstNonWhitespace)
        val trailingWhitespace = text.substring(lastNonWhitespace + 1)

        return leadingWhitespace + left + trimmedText + right + trailingWhitespace
    }

    private fun isTextView(node: SnapshotNode): Boolean = node.className?.endsWith(".TextView") == true
    private fun isView(node: SnapshotNode): Boolean = node.className?.endsWith(".View") == true
    private fun isWebView(node: AccessibilityNodeInfo): Boolean = node.className?.endsWith(".WebView") == true
    private fun isLeaf(node: SnapshotNode): Boolean = node.children.isEmpty()

    private fun isLink(node: SnapshotNode): Boolean {
        // A link is a clickable View with one or more children, all of whom are leaf TextViews.
        val hasClickAction = node.actionIds.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK.id)
        if (!hasClickAction || node.children.isEmpty() || !isView(node)) return false

        for (child in node.children) {
            if (!(isTextView(child) && isLeaf(child))) {
                return false
            }
        }
        return true
    }

    private fun isCohesiveParagraph(node: SnapshotNode): Boolean {
        // Rule 1: Single TextView
        if (isTextView(node)) {
            return true
        }

        // Rule 1a: A leaf View with some text in it will do too
        if (isView(node) && isLeaf(node) && !node.text.isNullOrBlank()) {
            return true
        }

        // Rule 2: Cohesive View (must have children to be a container)
        if (!node.children.isEmpty()) {
            for (child in node.children) {
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

    private fun extractCohesiveText(node: SnapshotNode): String {
        // If it's a link, return the special text by concatenating all leaf TextView children.
        if (isLink(node)) {
            val parts = node.children.mapNotNull { it.text?.toString() }.filter { it.isNotBlank() }
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
        val parts = node.children.map { extractCohesiveText(it) }.filter { it.isNotBlank() }
        return parts.joinToString("")
    }

    private fun collectText(node: SnapshotNode, paragraphs: MutableList<String>) {
        if (isCohesiveParagraph(node)) {
            val text = extractCohesiveText(node)
            if (text.isNotBlank()) {
                paragraphs.add(text.trim())
                Log.d(TAG, "in ${node.uniqueId}, adding ${formatSnippet(text)}")
            }
        } else {
            // Not a cohesive paragraph, so its children are treated as separate potential paragraphs (implicit breaks)
            for (child in node.children) {
                collectText(child, paragraphs)
            }
        }
    }

    private fun formatSnippet(text: String?): String {
        if (text == null) return "null"
        if (text.length <= 30) return text
        return "${text.take(15)}...${text.takeLast(15)}"
    }

    private fun logTree(node: SnapshotNode?, depth: Int = 0) {
        if (node == null) return

        val text = node.text?.toString()
        val isParagraph = isCohesiveParagraph(node)
        
        val nodeId = node.uniqueId
        val clickable = node.actionIds.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK.id)

        Log.d(TAG, "  ".repeat(depth)
                + "${nodeId ?: "null"}/${node.key} [${formatSnippet(text)}]"
                + (if (isParagraph) " (paragraph)" else "")
                + (if (clickable) " (clickable)" else "")
        )

        // Log spans if the text is Spanned
        val textCharSequence = node.text
        if (textCharSequence is Spanned) {
            val spans = textCharSequence.getSpans(0, textCharSequence.length, Any::class.java)
            spans.forEach { span ->
                val start = textCharSequence.getSpanStart(span)
                val end = textCharSequence.getSpanEnd(span)
                Log.d(TAG, "  ".repeat(depth + 1) + "SPAN: ${span::class.java.simpleName} ($start-$end)")
            }
        }

        for (child in node.children) {
            logTree(child, depth + 1)
        }
    }

    companion object {
        private const val TAG = "ScreenContentService"
        private const val CHANNEL_ID = "longform_channel"
        private var notificationIdCounter = 1
    }
}