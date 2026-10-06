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
import android.widget.TextView
import kotlin.concurrent.thread

class AutoAccessibilityService : AccessibilityService() {

    companion object {
        var instance: AutoAccessibilityService? = null
        @Volatile var queueActive: Boolean = false
        private val handler: Handler = Handler(Looper.getMainLooper())
        private val qHandler: Handler = Handler(Looper.getMainLooper())
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var expectingChat: Boolean = false
    private var lastSender: String = ""
    private var wm: WindowManager? = null
    private var overlayView: View? = null
    private var debugText: TextView? = null

    override fun onServiceConnected() {
        instance = this
        val info: AccessibilityServiceInfo = AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        info.notificationTimeout = 100
        setServiceInfo(info)
    }

    // ---------------- DEBUG ----------------

    private fun dbg(msg: String) {
        handler.post {
            try { debugText?.text = msg } catch (e: Exception) { }
        }
    }

    // ---------------- QUEUE MODE ----------------

    fun startQueue() {
        queueActive = true
        expectingChat = false
        qHandler.removeCallbacksAndMessages(null)
        val pm: PowerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        try { wakeLock?.release() } catch (_: Exception) { }
        val wl: PowerManager.WakeLock =
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "autoreply:queue")
        wl.acquire(60 * 60 * 1000L)
        wakeLock = wl
        showOverlay()
        dbg("Queue STARTED")
        qHandler.postDelayed(queueStep, 1500)
    }

    fun stopQueue() {
        queueActive = false
        expectingChat = false
        qHandler.removeCallbacksAndMessages(null)
        try { wakeLock?.release() } catch (_: Exception) { }
        wakeLock = null
        dbg("Queue STOPPED")
        hideOverlay()
    }

    // ---------------- FLOATING BUTTONS + DEBUG PANEL ----------------

    private fun makeOverlayButton(text: String, color: Int, action: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.textSize = 11f
        b.setTextColor(0xFFFFFFFF.toInt())
        b.setBackgroundColor(color)
        b.setOnClickListener { action() }
        return b
    }

    private fun showOverlay() {
        if (overlayView != null) return
        try {
            wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val box = LinearLayout(this)
            box.orientation = LinearLayout.VERTICAL
            box.setPadding(6, 6, 6, 6)

            val dt = TextView(this)
            dt.textSize = 10f
            dt.setTextColor(0xFF00FF00.toInt())
            dt.text = "AutoReply: ready"
            debugText = dt
            box.addView(dt)

            box.addView(makeOverlayButton("REPLY", 0xFF2E7D32.toInt()) { replyCurrentChat() })
            box.addView(makeOverlayButton("NEXT", 0xFF1565C0.toInt()) { manualNext() })
            box.addView(makeOverlayButton("BACK", 0xFFF9A825.toInt()) { performGlobalAction(GLOBAL_ACTION_BACK) })
            box.addView(makeOverlayButton("STOP", 0xFFC62828.toInt()) { stopQueue() })

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.END
            params.x = 10
            params.y = 250
            overlayView = box
            wm?.addView(box, params)
        } catch (e: Exception) { }
    }

    private fun hideOverlay() {
        try {
            val v: View? = overlayView
            if (v != null) wm?.removeView(v)
        } catch (e: Exception) { }
        overlayView = null
        debugText = null
    }

    private fun replyCurrentChat() {
        val root: AccessibilityNodeInfo? = rootInActiveWindow
        if (root == null) { dbg("REPLY: no window"); return }
        if (root.packageName?.toString() != Prefs.queuePkg(this)) {
            dbg("REPLY: not in " + Prefs.queuePkg(this))
            return
        }
        dbg("REPLY: generating...")
        handleOpenChat(root, lastSender.ifBlank { "friend" })
    }

    private fun manualNext() {
        qHandler.removeCallbacksAndMessages(null)
        val root: AccessibilityNodeInfo? = rootInActiveWindow
        if (root != null &&
            root.packageName?.toString() == Prefs.queuePkg(this) &&
            findInput(root) != null
        ) {
            dbg("NEXT: back first")
            performGlobalAction(GLOBAL_ACTION_BACK)
            qHandler.postDelayed(queueStep, 1800)
        } else {
            dbg("NEXT: scanning list")
            qHandler.postDelayed(queueStep, 600)
        }
    }

    // ---------------- QUEUE STEP ----------------

    private val queueStep: Runnable = object : Runnable {
        override fun run() {
            if (!queueActive) return
            if (!Prefs.masterEnabled(this@AutoAccessibilityService)) return
            val pkg: String = Prefs.queuePkg(this@AutoAccessibilityService)
            val root: AccessibilityNodeInfo? = rootInActiveWindow
            val currentPkg: String? = root?.packageName?.toString()

            if (currentPkg != null && currentPkg != pkg && currentPkg != packageName) {
                dbg("PAUSED (user in other app)")
                qHandler.postDelayed(this, Prefs.queueIntervalMs(this@AutoAccessibilityService))
                return
            }
            if (currentPkg != null && currentPkg == packageName) {
                qHandler.postDelayed(this, 3000)
                return
            }

            if (root == null || currentPkg != pkg) {
                expectingChat = false
                dbg("Opening " + pkg)
                val intent: Intent? = packageManager.getLaunchIntentForPackage(pkg)
                if (intent != null) {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                }
                qHandler.postDelayed(this, 5000)
                return
            }

            if (expectingChat) {
                val sender: String = lastSender
                expectingChat = false
                dbg("Chat opened: " + sender)
                handleOpenChat(root, sender)
                qHandler.postDelayed({
                    if (queueActive) {
                        dbg("Watchdog: force back")
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        qHandler.postDelayed(this, 2500)
                    }
                }, 35000)
                return
            }

            dbg("Scanning list...")
            val row: Pair<AccessibilityNodeInfo, String>? = findPendingRow(root)
            root.recycle()
            if (row != null) {
                lastSender = row.second
                dbg("Pending: " + row.second)
                val clicked: Boolean =
                    row.first.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (clicked) {
                    expectingChat = true
                    qHandler.postDelayed(this, 10000)
                } else {
                    qHandler.postDelayed(this, 3000)
                }
            } else {
                dbg("No pending - waiting")
                qHandler.postDelayed(this, Prefs.queueIntervalMs(this@AutoAccessibilityService))
            }
        }
    }

    private fun handleOpenChat(root: AccessibilityNodeInfo, sender: String) {
        val lines: List<String> = scrapeChatTexts(root)
        val newMsg: String = lines.lastOrNull() ?: ""
        thread {
            val reply: String? = try {
                ReplyGenerator.generate(this, sender, lines, newMsg)
            } catch (e: Exception) { null }
            if (reply.isNullOrBlank()) {
                dbg("Reply generation FAILED")
                return@thread
            }
            ChatHistory.add(this, sender, "them", newMsg)
            ChatHistory.add(this, sender, "me", reply)
            handler.post { typeAndSend(reply, true) }
        }
    }

    private fun isSystemRow(name: String): Boolean {
        if (name.contains("TOKI TEAM", ignoreCase = true)) return true
        if (name == "My chat room") return true
        if (name == "Moments") return true
        if (name == "Contacts") return true
        if (name.contains("Join a family")) return true
        if (name.contains("Interaction notifications")) return true
        return false
    }

    private fun findPendingRow(root: AccessibilityNodeInfo): Pair<AccessibilityNodeInfo, String>? {
        val candidates = ArrayList<Triple<AccessibilityNodeInfo, String, String>>()
        gatherRows(root, candidates, 0)
        candidates.sortByDescending { it.first.childCount }
        for (entry in candidates) {
            val node: AccessibilityNodeInfo = entry.first
            val name: String = entry.second
            val preview: String = entry.third
            if (isSystemRow(name)) continue
            if (preview.contains("[Match]")) continue
            if (preview.contains("birthday", ignoreCase = true)) continue
            val lastMe: String? = ChatHistory.lastMeText(this, name)
            if (lastMe == null || preview != lastMe) {
                return Pair(node, name)
            }
        }
        return null
    }

    private fun gatherRows(
        node: AccessibilityNodeInfo,
        candidates: MutableList<Triple<AccessibilityNodeInfo, String, String>>,
        depth: Int
    ) {
        if (depth > 14) return
        if (node.isClickable) {
            val texts = ArrayList<String>()
            collectLeafTexts(node, texts, 0)
            if (texts.size >= 2) {
                val name: String = texts[0]
                val preview: String = texts[1]
                if (name.length in 2..30 && !name.contains(":")) {
                    candidates.add(Triple(node, name, preview))
                }
            }
        }
        for (i in 0 until node.childCount) {
            val child: AccessibilityNodeInfo? = node.getChild(i)
            if (child != null) {
                gatherRows(child, candidates, depth + 1)
            }
        }
    }

    private fun collectLeafTexts(node: AccessibilityNodeInfo, out: MutableList<String>, depth: Int) {
        if (depth > 9) return
        val t: String? = node.text?.toString()?.trim()
        if (t != null && t.isNotEmpty() && node.childCount == 0) {
            out.add(t)
        }
        for (i in 0 until node.childCount) {
            val c: AccessibilityNodeInfo? = node.getChild(i)
            if (c != null) {
                collectLeafTexts(c, out, depth + 1)
            }
        }
    }

    // ---------------- NORMAL MODE ----------------

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg: String = event.packageName?.toString() ?: return

        if (queueActive && pkg == Prefs.queuePkg(this) &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            qHandler.removeCallbacks(queueStep)
            qHandler.postDelayed(queueStep, 800)
            return
        }

        if (!Prefs.isPackageEnabled(this, pkg)) return

        val pending: Triple<String, android.app.PendingIntent, String>? =
            ReplyService.pendingChat
        if (pending == null) return
        ReplyService.pendingChat = null

        val newMessage: String = pending.first
        val sender: String = pending.third

        val root: AccessibilityNodeInfo = rootInActiveWindow ?: return
        val lines: List<String> = scrapeChatTexts(root)
        root.recycle()

        thread {
            val reply: String? = try {
                ReplyGenerator.generate(this, sender, lines, newMessage)
            } catch (e: Exception) { null }
            if (reply.isNullOrBlank()) return@thread
            ChatHistory.add(this, sender, "them", newMessage)
            ChatHistory.add(this, sender, "me", reply)
            handler.post { typeAndSend(reply, true) }
        }
    }

    // ---------------- TYPE + SEND ----------------

    private fun typeAndSend(reply: String, thenBack: Boolean) {
        handler.postDelayed({
            val root: AccessibilityNodeInfo? = rootInActiveWindow
            if (root == null) return@postDelayed
            val field: AccessibilityNodeInfo? = findInput(root)
            if (field == null) {
                dbg("No input field found")
                if (thenBack) backAndNext()
                return@postDelayed
            }
            dbg("Pasting reply...")
            val clipboard: ClipboardManager =
                getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("reply", reply))

            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val pasted: Boolean = field.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            if (!pasted) {
                val args = Bundle()
                args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    reply
                )
                field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }

            handler.postDelayed({ trySendClick(0, thenBack) }, 900)
        }, 1200)
    }

    private fun trySendClick(attempt: Int, thenBack: Boolean) {
        if (attempt > 10) {
            dbg("Send button NOT found")
            if (thenBack) backAndNext()
            return
        }
        val root: AccessibilityNodeInfo = rootInActiveWindow ?: return
        val send: AccessibilityNodeInfo? = findSendButton(root, 0)
        if (send == null) {
            handler.postDelayed({ trySendClick(attempt + 1, thenBack) }, 500)
            return
        }
        dbg("Clicking SEND")
        val ok: Boolean = send.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (ok) {
            dbg("SENT! going back...")
            handler.postDelayed({
                if (thenBack) backAndNext()
            }, 1300)
            return
        }
        handler.postDelayed({ trySendClick(attempt + 1, thenBack) }, 500)
    }

    private fun backAndNext() {
        performGlobalAction(GLOBAL_ACTION_BACK)
        qHandler.postDelayed(queueStep, 2500)
    }

    private fun findSendButton(node: AccessibilityNodeInfo, depth: Int): AccessibilityNodeInfo? {
        if (depth > 14) return null
        val desc: String = node.contentDescription?.toString()?.lowercase() ?: ""
        val cls: String = node.className?.toString() ?: ""
        val r = Rect()
        node.getBoundsInScreen(r)
        val dw: Int = resources.displayMetrics.widthPixels
        val dh: Int = resources.displayMetrics.heightPixels
        val cx: Int = (r.left + r.right) / 2
        val cy: Int = (r.top + r.bottom) / 2
        val inSendZone: Boolean = cx > dw * 0.55 && cy > dh * 0.70

        if (inSendZone && node.isEnabled && node.isClickable &&
            (desc.contains("send") || cls.endsWith("ImageButton") || cls.endsWith("Button"))
        ) {
            return node
        }

        for (i in 0 until node.childCount) {
            val child: AccessibilityNodeInfo? = node.getChild(i)
            if (child != null) {
                val found: AccessibilityNodeInfo? = findSendButton(child, depth + 1)
                if (found != null) return found
            }
        }
        if (inSendZone && node.isEnabled && node.isClickable && node.childCount == 0) {
            return node
        }
        return null
    }

    private fun scrapeChatTexts(root: AccessibilityNodeInfo): List<String> {
        val out = ArrayList<String>()
        collectTexts(root, out)
        return out.filter { it.isNotBlank() }.takeLast(30)
    }

    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<String>) {
        if (node.isEditable) return
        val text: String? = node.text?.toString()
        if (text != null && text.isNotBlank() && node.childCount == 0) {
            out.add(text.trim())
        }
        for (i in 0 until node.childCount) {
            val child: AccessibilityNodeInfo? = node.getChild(i)
            if (child != null) {
                collectTexts(child, out)
            }
        }
    }

    private fun findInput(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val child: AccessibilityNodeInfo? = node.getChild(i)
            if (child != null) {
                val found: AccessibilityNodeInfo? = findInput(child)
                if (found != null) return found
            }
        }
        return null
    }

    override fun onInterrupt() { }
}
