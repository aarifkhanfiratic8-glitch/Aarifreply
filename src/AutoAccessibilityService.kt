package com.autoreply.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import kotlin.concurrent.thread

class AutoAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AutoAccessibilityService? = null
        @Volatile var queueActive = false
        private val handler = Handler(Looper.getMainLooper())
        private val qHandler = Handler(Looper.getMainLooper())
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var expectingChat = false
    private var lastSender = ""
    private var wm: WindowManager? = null
    private var overlayView: View? = null

    override fun onServiceConnected() {
        instance = this
        setServiceInfo(AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPES_ALL_MASK
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                    AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        })
    }

    // ---------------- QUEUE MODE ----------------

    fun startQueue() {
        queueActive = true
        expectingChat = false
        qHandler.removeCallbacksAndMessages(null)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "autoreply:queue").apply {
            acquire(60 * 60 * 1000L)
        }
        showOverlay()
        qHandler.postDelayed(queueStep, 1500)
    }

    fun stopQueue() {
        queueActive = false
        expectingChat = false
        qHandler.removeCallbacksAndMessages(null)
        try { wakeLock?.release() } catch (_: Exception) {}
        wakeLock = null
        hideOverlay()
    }

    // ---------------- FLOATING BUTTONS ----------------

    private fun showOverlay() {
        if (overlayView != null) return
        try {
            wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(6, 6, 6, 6)
            }
            fun ob(text: String, color: Int, action: () -> Unit): Button =
                Button(this).apply {
                    this.text = text
                    textSize = 11f
                    setTextColor(0xFFFFFFFF.toInt())
                    setBackgroundColor(color)
                    setOnClickListener { action() }
                }
            box.addView(ob("REPLY", 0xFF2E7D32.toInt()) { replyCurrentChat() })
            box.addView(ob("NEXT", 0xFF1565C0.toInt()) { manualNext() })
            box.addView(ob("BACK", 0xFFF9A825.toInt()) { performGlobalAction(GLOBAL_ACTION_BACK) })
            box.addView(ob("STOP", 0xFFC62828.toInt()) { stopQueue() })
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 10
                y = 250
            }
            overlayView = box
            wm?.addView(box, params)
        } catch (e: Exception) { }
    }

    private fun hideOverlay() {
        try { overlayView?.let { wm?.removeView(it) } } catch (e: Exception) { }
        overlayView = null
    }

    private fun replyCurrentChat() {
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != Prefs.queuePkg(this)) return
        handleOpenChat(root, lastSender.ifBlank { "friend" })
    }

    private fun manualNext() {
        qHandler.removeCallbacksAndMessages(null)
        val root = rootInActiveWindow
        if (root != null && root.packageName?.toString() == Prefs.queuePkg(this) && findInput(root) != null) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            qHandler.postDelayed(queueStep, 1800)
        } else {
            qHandler.postDelayed(queueStep, 600)
        }
    }

    // ---------------- QUEUE STEP ----------------

    private val queueStep = object : Runnable {
        override fun run() {
            if (!queueActive || !Prefs.masterEnabled(this@AutoAccessibilityService)) return
            val pkg = Prefs.queuePkg(this@AutoAccessibilityService)
            val root = rootInActiveWindow
            val currentPkg = root?.packageName?.toString()

            // USER kisi aur app mein hai ya settings mein hai — RUKO
            if (currentPkg != null && currentPkg != pkg && currentPkg != packageName) {
                qHandler.postDelayed(this, Prefs.queueIntervalMs(this@AutoAccessibilityService))
                return
            }
            if (currentPkg == packageName) {
                qHandler.postDelayed(this, 3000)
                return
            }

            if (root == null || currentPkg != pkg) {
                expectingChat = false
                val intent = packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }
                qHandler.postDelayed(this, 5000)
                return
            }

            if (expectingChat) {
                val sender = lastSender
                expectingChat = false
                handleOpenChat(root, sender)
                // watchdog: agar 35s mein kuch na ho to force back
                qHandler.postDelayed({
                    if (queueActive) {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        qHandler.postDelayed(queueStep, 2500)
                    }
                }, 35000)
                return
            }

            val row = findPendingRow(root)
            root.recycle()
            if (row != null) {
                lastSender = row.second
                if (row.first.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    expectingChat = true
                    qHandler.postDelayed(this, 10000)
                } else {
                    qHandler.postDelayed(this, 3000)
                }
            } else {
                qHandler.postDelayed(this, Prefs.queueIntervalMs(this@AutoAccessibilityService))
            }
        }
    }

    private fun handleOpenChat(root: AccessibilityNodeInfo, sender: String) {
        val lines = scrapeChatTexts(root)
        val newMsg = lines.lastOrNull() ?: ""
        thread {
            val reply = try {
                ReplyGenerator.generate(this, sender, lines, newMsg)
            } catch (e: Exception) { null }
            if (reply.isNullOrBlank()) return@thread
            ChatHistory.add(this, sender, "them", newMsg)
            ChatHistory.add(this, sender, "me", reply)
            handler.post { typeAndSend(reply, thenBack = true) }
        }
    }

    private fun isSystemRow(name: String): Boolean {
        if (name.contains("TOKI TEAM", ignoreCase = true)) return true
        if (name == "My chat room" || name == "Moments" || name == "Contacts") return true
        if (name.contains("Join a family") || name.contains("Coins")) return true
        if (name.contains("Interaction notifications")) return true
        return false
    }

    private fun findPendingRow(root: AccessibilityNodeInfo): Pair<AccessibilityNodeInfo, String>? {
        val candidates = mutableListOf<Triple<AccessibilityNodeInfo, String, String>>()
        fun gatherTexts(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
            if (depth > 9) return
            val t = node.text?.toString()?.trim()
            if (!t.isNullOrEmpty() && node.childCount == 0) out.add(t)
            for (i in 0 until node.childCount) {
                val c = node.getChild(i) ?: continue
                gatherTexts(c, out, depth + 1)
            }
        }
        fun walk(node: AccessibilityNodeInfo) {
            if (node.isClickable) {
                val texts = mutableListOf<String>()
                gatherTexts(node, texts, 0)
                if (texts.size >= 2 && texts[0].length in 2..30 && !texts[0].contains(":")) {
                    candidates.add(Triple(node, texts[0], texts[1]))
                }
            }
            for (i in 0 until node.childCount) {
                val c = node.getChild(i) ?: continue
                walk(c)
            }
        }
        walk(root)
        candidates.sortByDescending { it.first.childCount }
        for ((node, name, preview) in candidates) {
            if (isSystemRow(name)) continue
            if (preview.contains("[Match]") || preview.contains("birthday", ignoreCase = true)) continue
            val lastMe = ChatHistory.lastMeText(this, name)
            if (lastMe == null || preview != lastMe) {
                return node to name
            }
        }
        return null
    }

    // ---------------- NORMAL MODE ----------------

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return

        if (queueActive && pkg == Prefs.queuePkg(this) &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            qHandler.removeCallbacks(queueStep)
            qHandler.postDelayed(queueStep, 800)
            return
        }

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

            handler.post { typeAndSend(reply, thenBack = true) }
        }
    }

    // ---------------- TYPE + SEND (ab back sirf send ke BAAD) ----------------

    private fun typeAndSend(reply: String, thenBack: Boolean) {
        handler.postDelayed({
            val root = rootInActiveWindow ?: return@postDelayed
            val field = findInput(root) ?: run {
                if (thenBack) backAndNext()
                return@postDelayed
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("reply", reply))

            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val pasted = field.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            if (!pasted) {
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, reply)
                }
                field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }

            handler.postDelayed({ trySendClick(0, thenBack) }, 900)
        }, 1200)
    }

    private fun trySendClick(attempt: Int, thenBack: Boolean) {
        if (attempt > 10) {
            if (thenBack) backAndNext()
            return
        }
        val root = rootInActiveWindow ?: return
        val send = findSendButton(root)
        if (send != null) {
            val ok = send.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (ok) {
                handler.postDelayed({
                    if (thenBack) backAndNext()
                }, 1300)
                return
            }
        }
        handler.postDelayed({ trySendClick(attempt + 1, thenBack) }, 500)
    }

    private fun backAndNext() {
        performGlobalAction(GLOBAL_ACTION_BACK)
        qHandler.postDelayed(queueStep, 2500)
    }

    /** sirf screen ke bottom-right zone mein send button — profile/avatar nahi */
    private fun findSendButton(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (depth > 14) return null
        val desc = node.contentDescription?.toString()?.lowercase() ?: ""
        val cls = node.className?.toString() ?: ""
        val r = Rect()
        node.getBoundsInScreen(r)
        val dw = resources.displayMetrics.widthPixels
        val dh = resources.displayMetrics.heightPixels
        val cx = (r.left + r.right) / 2
        val cy = (r.top + r.bottom) / 2
        val inSendZone = cx > dw * 0.55 && cy > dh * 0.70

        if (inSendZone && node.isEnabled && node.isClickable &&
            (desc.contains("send") || cls.endsWith("ImageButton") || cls.endsWith("Button"))) return node

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findSendButton(child, depth + 1)
            if (found != null) return found
        }
        // last resort: zone mein koi bhi clickable enabled node
        if (inSendZone && node.isEnabled && node.isClickable && node.childCount == 0) return node
        return null
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

    private fun findInput(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = findInput(child)
            if (found != null) return found
        }
        return null
    }

    override fun onInterrupt() {}
}
