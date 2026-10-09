package com.example.jarvis.ai.device

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

interface DeviceControlProvider {
    val isServiceEnabled: Boolean
    fun performActionGlobal(action: Int): Boolean
    fun clickNodeByText(text: String): Boolean
    fun inputText(text: String): Boolean
    fun scrollForward(): Boolean
    fun getActivePackageName(): String?
}

class JarvisAccessibilityService : AccessibilityService(), DeviceControlProvider {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.packageName?.let {
            currentPackageName = it.toString()
        }
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
    }

    override val isServiceEnabled: Boolean
        get() = instance != null

    override fun performActionGlobal(action: Int): Boolean {
        return super.performGlobalAction(action)
    }

    override fun clickNodeByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            if (node.isClickable) {
                val success = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (success) return true
            } else {
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable) {
                        val success = parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        if (success) return true
                        break
                    }
                    parent = parent.parent
                }
            }
        }
        return false
    }

    override fun inputText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isEditable) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    findEditable(child)?.let { return it }
                }
            }
            return null
        }
        val editable = findEditable(root)
        if (editable != null) {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            }
            return editable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        }
        return false
    }

    override fun scrollForward(): Boolean {
        val root = rootInActiveWindow ?: return false
        fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.isScrollable) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { child ->
                    findScrollable(child)?.let { return it }
                }
            }
            return null
        }
        val scrollable = findScrollable(root)
        if (scrollable != null) {
            return scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        }
        return false
    }

    override fun getActivePackageName(): String? {
        return currentPackageName
    }

    companion object {
        @Volatile
        var instance: JarvisAccessibilityService? = null
            private set

        @Volatile
        private var currentPackageName: String? = null
    }
}
