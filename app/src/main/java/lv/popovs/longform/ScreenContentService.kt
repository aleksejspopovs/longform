package lv.popovs.longform

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class ScreenContentService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val rootNode = rootInActiveWindow ?: return
                val sb = StringBuilder()
                dumpNode(rootNode, sb, 0)
                Log.d(TAG, sb.toString())
            }
        }
    }

    override fun onInterrupt() {
        // Not needed for this implementation
    }

    private fun dumpNode(node: AccessibilityNodeInfo?, sb: StringBuilder, indent: Int) {
        if (node == null) {
            return
        }
        if (node.text != null) {
            sb.append(" ".repeat(indent * 2))
                .append("{${node.isImportantForAccessibility}} ${node.text}\n")
        }
        for (i in 0 until node.childCount) {
            dumpNode(node.getChild(i), sb, indent + 1)
        }
    }

    companion object {
        private const val TAG = "ScreenContentService"
    }
}
