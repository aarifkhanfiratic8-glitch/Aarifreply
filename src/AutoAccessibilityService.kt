package com.autoreply.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Path
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
    private var pauseBtn: Button? = null

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

    override fun onUnbind(intent: Intent?): Boolean {
        stopQueue()
        hideOverlay()
        instance = null
        return super.onUnbind(intent)
    }

    // ---------------- DEBUG ----------------

    private fun dbg(msg: String) {
        handler.post {
            try { debugText?.text = msg } catch (e: Exception) { }
        }
    }

    // ---------------- START / STOP ----------------

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
        updatePauseBtn()
        dbg("Queue STARTED")
        qHandler.postDelayed(queueStep, 1500)
    }

    fun stopQueue() {
        queueActive = false
        expectingChat = false
        qHandler.removeCallbacksAndMessages(null)
        try { wakeLock?.release() } catch (_: Exception) { }
        wakeLock = null
        updatePauseBtn()
        dbg("Queue PAUSED")
    }

    // ---------------- OVERLAY (sirf REPLY + PAUSE) ----------------

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

            val pb: Button = makeOverlayButton("PAUSE", 0xFFC62828.toInt()) {
                if (queueActive) stopQueue() else startQueue()
            }
            pauseBtn = pb
            box.addView(pb)

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
        pauseBtn = null
    }

    private fun updatePauseBtn() {
        if (queueActive) {
            pauseBtn?.text = "PAUSE"
            pauseBtn?.setBackgroundColor(0xFFC62828.toInt())
        } else {
            pauseBtn?.text = "RESUME"
            pauseBtn?.setBackgroundColor(0xFF2E7D32.toInt())
        }
    }

    private fun replyCurrentChat() {
        val root: AccessibilityNodeInfo? = rootInActiveWindow
        if (root == null) { dbg("REPLY: no window"); return }
        if (root.packageName?.toString() != Prefs.queuePkg(this)) {
            dbg("REPLY: not in app")
            return
        }
        dbg("REPLY: analyzing")
        armWatchdog()
        analyzeChat(root)
    }

    // ---------------- MAIN QUEUE LOOP ----------------

    private val queueStep: Runnable = object : Runnable {
        override fun run() {
            if (!queueActive) return
            if (!Prefs.masterEnabled(this@AutoAccessibilityService)) return
            val pkg: String = Prefs.queuePkg(this@AutoAccessibilityService)
            val root: AccessibilityNodeInfo? = rootInActiveWindow
            val currentPkg: String? = root?.packageName?.toString()

            if (currentPkg != null && currentPkg != pkg && currentPkg != packageName) {
                dbg("PAUSED (user in other app)")
                expectingChat = false
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
                expectingChat = false
                dbg("Chat opened: " + lastSender)
                armWatchdog()
                analyzeChat(root)
                return
            }

            dbg("Scanning list...")
            val row: Pair<AccessibilityNodeInfo, String>? = findPendingRow(root)
            if (row != null) {
                lastSender = row.second
                dbg("Pending: " + row.second)
                clickRowTextArea(row.first, row.second)
                expectingChat = true
                qHandler.removeCallbacksAndMessages(null)
                qHandler.postDelayed(this, 12000)
            } else {
                dbg("No pending - waiting")
                qHandler.postDelayed(this, Prefs.queueIntervalMs(this@AutoAccessibilityService))
            }
        }
    }

    private fun armWatchdog() {
        qHandler.removeCallbacksAndMessages(null)
        qHandler.postDelayed({
            if (queueActive) {
                dbg("Watchdog: force back")
                performGlobalAction(GLOBAL_ACTION_BACK)
                qHandler.postDelayed(queueStep, 2500)
            }
        }, 40000)
    }

    // ---------------- CHAT ANALYSIS ----------------

    private fun analyzeChat(root: AccessibilityNodeInfo) {
        val msgs: List<Pair<String, Boolean>> = scrapeMessages(root)
        if (msgs.isEmpty()) {
            dbg("No messages found")
            goNextOrBack()
            return
        }
        val last: Pair<String, Boolean> = msgs[msgs.size - 1]
        if (last.second) {
            dbg("Last msg is OURS - skip")
            goNextOrBack()
            return
        }
        dbg("Their msg - replying")
        handleTheirMessage(msgs, lastSender)
    }

    private fun handleTheirMessage(msgs: List<Pair<String, Boolean>>, sender: String) {
        val recent: List<Pair<String, Boolean>> = msgs.takeLast(6)
        val contextLines: List<String> = recent.map {
            if (it.second) "You: " + it.first else "Them: " + it.first
        }
        val newMsg: String = msgs[msgs.size - 1].first
        thread {
            val reply: String? = try {
                ReplyGenerator.generate(this, sender, contextLines, newMsg)
            } catch (e: Exception) { null }
            if (reply.isNullOrBlank()) {
                dbg("Reply generation FAILED")
                handler.post { goNextOrBack() }
                return@thread
            }
            ChatHistory.add(this, sender, "them", newMsg)
            ChatHistory.add(this, sender, "me", reply)
            handler.post { typeAndSend(reply) }
        }
    }

    /** RIGHT bubble = humara (true), LEFT = unka (false) */
    private fun scrapeMessages(root: AccessibilityNodeInfo): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        val dw: Int = resources.displayMetrics.widthPixels
        val dh: Int = resources.displayMetrics.heightPixels
        collectMessages(root, out, dw, dh, 0)
        return out.takeLast(12)
    }

    private fun collectMessages(
        node: AccessibilityNodeInfo,
        out: ArrayList<Pair<String, Boolean>>,
        dw: Int,
        dh: Int,
        depth: Int
    ) {
        if (depth > 16) return
        if (!node.isEditable) {
            val t: String? = node.text?.toString()?.trim()
            if (t != null && t.isNotEmpty() && node.childCount == 0) {
                val r = Rect()
                node.getBoundsInScreen(r)
                val cy: Int = (r.top + r.bottom) / 2
                val cx: Int = (r.left + r.right) / 2
                if (cy > dh * 0.20 && !looksLikeMeta(t)) {
                    val mine: Boolean = cx > dw / 2
                    out.add(Pair(t, mine))
                }
            }
        }
        for (i in 0 until node.childCount) {
            val c: AccessibilityNodeInfo? = node.getChild(i)
            if (c != null) collectMessages(c, out, dw, dh, depth + 1)
        }
    }

    private fun looksLikeMeta(t: String): Boolean {
        if (t.length <= 1) return true
        if (t.matches(Regex("^\\d{1,2}:\\d{2}.*"))) return true
        if (t.matches(Regex("^\\d{4}/.*"))) return true
        val low: String = t.lowercase()
        if (low.contains("congrats")) return true
        if (low.contains("streak")) return true
        if (low.contains("intimacy")) return true
        if (low.contains("restore")) return true
        if (low.contains("next unread")) return true
        if (low.contains("go have a chat")) return true
        if (t == "View" || t == "New" || t == "Online") return true
        if (low.endsWith("km") && t.length <= 8) return true
        return false
    }

    // ---------------- TYPE + SEND + NEXT ----------------

    private fun typeAndSend(reply: String) {
        handler.postDelayed({
            val root: AccessibilityNodeInfo? = rootInActiveWindow
            if (root == null) return@postDelayed
            val field: AccessibilityNodeInfo? = findInput(root)
            if (field == null) {
                dbg("No input field found")
                goNextOrBack()
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
            handler.postDelayed({ trySendClick(0) }, 900)
        }, 1000)
    }

    private fun trySendClick(attempt: Int) {
        if (attempt > 12) {
            dbg("Send button NOT found")
            goNextOrBack()
            return
        }
        val root: AccessibilityNodeInfo = rootInActiveWindow ?: return
        val send: AccessibilityNodeInfo? = findSendButton(root, 0)
        if (send == null) {
            handler.postDelayed({ trySendClick(attempt + 1) }, 500)
            return
        }
        dbg("Clicking SEND")
        val ok: Boolean = send.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (ok) {
            dbg("SENT!")
            handler.postDelayed({ goNextOrBack() }, 1500)
            return
        }
        handler.postDelayed({ trySendClick(attempt + 1) }, 500)
    }

    private fun goNextOrBack() {
        handler.postDelayed({
            val clicked: Boolean = findNextUnreadAndClick()
            if (!clicked) backAndNext()
        }, 1200)
    }

    private fun findNextUnreadAndClick(): Boolean {
        val root: AccessibilityNodeInfo = rootInActiveWindow ?: return false
        val node: AccessibilityNodeInfo? = findNodeWithText(root, "next unread", 0)
        if (node != null) {
            val ok: Boolean = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (ok) {
                expectingChat = true
                dbg("Next unread clicked")
                armWatchdog()
                return true
            }
        }
        return false
    }

    private fun backAndNext() {
        qHandler.removeCallbacksAndMessages(null)
        performGlobalAction(GLOBAL_ACTION_BACK)
        qHandler.postDelayed(queueStep, 2800)
    }

    // ---------------- LIST HELPERS ----------------

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
            if (child != null) gatherRows(child, candidates, depth + 1)
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
            if (c != null) collectLeafTexts(c, out, depth + 1)
        }
    }

    /** AVATAR pe nahi — naam/text ke exact coordinates pe gesture tap */
    private fun clickRowTextArea(row: AccessibilityNodeInfo, name: String) {
        val nameNode: AccessibilityNodeInfo? = findLeafWithText(row, name, 0)
        if (nameNode != null) {
            val r = Rect()
            nameNode.getBoundsInScreen(r)
            tap(((r.left + r.right) / 2).toFloat(), ((r.top + r.bottom) / 2).toFloat())
            return
        }
        val r2 = Rect()
        row.getBoundsInScreen(r2)
        tap((r2.left + r2.width() * 0.55f), ((r2.top + r2.bottom) / 2).toFloat())
    }

    private fun findLeafWithText(
        node: AccessibilityNodeInfo,
        text: String,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (depth > 12) return null
        val t: String? = node.text?.toString()
        if (t != null && t.trim() == text && node.childCount == 0) return node
        for (i in 0 until node.childCount) {
            val c: AccessibilityNodeInfo? = node.getChild(i)
            if (c != null) {
                val f: AccessibilityNodeInfo? = findLeafWithText(c, text, depth + 1)
                if (f != null) return f
            }
        }
        return null
    }

    private fun findNodeWithText(
        node: AccessibilityNodeInfo,
        text: String,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (depth > 14) return null
        val t: String? = node.text?.toString()
        if (t != null && t.contains(text, ignoreCase = true)) return node
        val cd: String? = node.contentDescription?.toString()
        if (cd != null && cd.contains(text, ignoreCase = true)) return node
        for (i in 0 until node.childCount) {
            val c: AccessibilityNodeInfo? = node.getChild(i)
            if (c != null) {
                val f: AccessibilityNodeInfo? = findNodeWithText(c, text, depth + 1)
                if (f != null) return f
            }
        }
        return null
    }

    private fun tap(x: Float, y: Float) {
        try {
            val p = Path()
            p.moveTo(x, y)
            val g: GestureDescription = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0, 60))
                .build()
            dispatchGesture(g, null, null)
        } catch (e: Exception) { }
    }

    // ---------------- INPUT / SEND FINDERS ----------------

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

    // ---------------- EVENTS ----------------

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
        val lines: List<String> = scrapeMessages(root).map { it.first }
        root.recycle()

        thread {
            val reply: String? = try {
                ReplyGenerator.generate(this, sender, lines, newMessage)
            } catch (e: Exception) { null }
            if (reply.isNullOrBlank()) return@thread
            ChatHistory.add(this, sender, "them", newMessage)
            ChatHistory.add(this, sender, "me", reply)
            handler.post { typeAndSend(reply) }
        }
    }

    override fun onInterrupt() { }
}
