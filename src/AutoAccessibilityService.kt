package com.autoreply.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.concurrent.thread

class AutoAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AutoAccessibilityService? = null
        private val handler = Handler(Looper.getMainLooper())
    }

    override fun onServiceConnected() {
        instance = this
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            canRetrieveWindowContent = true
            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (!Prefs.isPackageEnabled(this, pkg)) return

        val pending = ReplyService.pendingChat ?: return
        ReplyService.pendingChat = null

        val newMessage = pending.first
        val sender = pending.third

        val root = rootInActiveWindow ?: return
        val lines = scrapeChatTexts(root)
        root.recycle()

        thread {
            val reply = try {
                ReplyGenerator.generate(this, sender, lines, newMessage)
            } catch (e: Exception) { null }

            if (reply.isNullOrBlank()) return@thread

            ChatHistory.add(this, sender, "them", newMessage)
            ChatHistory.add(this, sender, "me", reply)

            handler.post { typeAndSend(reply) }
        }
    }

    private fun typeAndSend(reply: String) {
        handler.postDelayed({
            val root = rootInActiveWindow ?: return@postDelayed
            val field = findEditText(root) ?: run {
                performGlobalAction(GLOBAL_ACTION_BACK)
                return@postDelayed
            }
            val args = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, reply)
            }
            field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

            handler.postDelayed({
                val root2 = rootInActiveWindow
                val send = root2?.let { findSendButton(it) }
                send?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 800)
            }, 900)
        }, 1200)
    }

    private fun scrapeChatTexts(root: AccessibilityNodeInfo): List<String> {
        val out = mutableListOf<String>()
        collectTexts(root, out)
        return out.filter { it.isNotBlank() }.takeLast(30)
    }

    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<String>) {
        if (node.isEditable) return
        val text = node.text?.toString()
        if (!text.isNullOrBlank() && node.childCount == 0) {
            out.add(text.trim())
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTexts(child, out)
        }
    }

    private fun findEditText(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.className?.toString()?.contains("EditText") == true) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findEditText(child)
            if (found != null) return found
        }
        return null
    }

    private fun findSendButton(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val cls = node.className?.toString() ?: ""
        if (node.isClickable && (desc.contains("send") || cls.endsWith("ImageButton") || cls.endsWith("Button"))) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findSendButton(child)
            if (found != null) return found
        }
        return null
    }

    override fun onInterrupt() {}
}
