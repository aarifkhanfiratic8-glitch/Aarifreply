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
import android.view.MotionEvent
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

    private var rotIdx: Int = 0
    private var casualIdx: Int = 0
    private var lastActionTime: Long = 0L

    private val casuals: List<String> = listOf(
        "kya kar rahe ho aaj 😊",
        "aaj kya khaya aapne",
        "kahan busy ho yrr, batao na",
        "kya chal raha hai aajkal 😄",
        "hello ji, miss kar rahi thi 😊"
    )

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

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopQueue()
        super.onTaskRemoved(rootIntent)
    }

    private fun dbg(msg: String) {
        handler.post {
            try { debugText?.text = msg } catch (e: Exception) { }
        }
    }

    fun startQueue() {
        queueActive = true
        expectingChat = false
        lastActionTime = System.currentTimeMillis()
        qHandler.removeCallbacksAndMessages(null)
        val pm: PowerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        try { wakeLock?.release() } catch (_: Exception) { }
        val wl: PowerManager.WakeLock =
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "autoreply:queue")
        wl.acquire(60 * 60 * 1000L)
        wakeLock = wl
        showOverlay()
        updatePauseBtn()
        dbg("Queue ON")
        qHandler.postDelayed(queueStep, 1500)
    }

    fun stopQueue() {
        queueActive = false
        expectingChat = false
        qHandler.removeCallbacksAndMessages(null)
        try { wakeLock?.release() } catch (_: Exception) { }
        wakeLock = null
        updatePauseBtn()
        dbg("Queue OFF")
    }

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
            dt.text = "AutoReply: ready (drag me)"
            debugText = dt
            box.addView(dt)

            val pb: Button = makeOverlayButton("OFF", 0xFFC62828.toInt()) {
                if (queueActive) stopQueue() else startQueue()
            }
            pauseBtn = pb
            box.addView(pb)

            dt.setOnTouchListener(object : View.OnTouchListener {
                private var downX: Float = 0f
                private var downY: Float = 0f
                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.action) {
                        MotionEvent.ACTION_DOWN -> {
                            downX = event.rawX
                            downY = event.rawY
                            return true
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx: Int = (event.rawX - downX).toInt()
                            val dy: Int = (event.rawY - downY).toInt()
                            downX = event.rawX
                            downY = event.rawY
                            val lp: WindowManager.LayoutParams =
                                v.rootView.layoutParams as WindowManager.LayoutParams
                            lp.x = lp.x - dx
                            lp.y = lp.y + dy
                            wm?.updateViewLayout(v.rootView, lp)
                            return true
                        }
                    }
                    return false
                }
            })

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
            pauseBtn?.text = "ON"
            pauseBtn?.setBackgroundColor(0xFF2E7D32.toInt())
        } else {
            pauseBtn?.text = "OFF"
            pauseBtn?.setBackgroundColor(0xFFC62828.toInt())
        }
    }

    private fun isInChat(root: AccessibilityNodeInfo): Boolean {
        return findInput(root) != null
    }

    private fun isOnList(root: AccessibilityNodeInfo): Boolean {
        return findNodeWithText(root, "most chatted", 0) != null
    }

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
                qHandler.postDelayed(this, 10000)
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

            // dialog aaya toh app khud cancel kare (tumhe kuch nahi karna)
            val leaveDialog: AccessibilityNodeInfo? =
                findNodeWithText(root, "are you sure to leave", 0)
            if (leaveDialog != null) {
                dbg("Dialog - auto Cancel")
                val cancelBtn: AccessibilityNodeInfo? = findNodeWithText(root, "cancel", 0)
                if (cancelBtn != null) {
                    cancelBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                expectingChat = false
                qHandler.postDelayed(this, 3000)
                return
            }

            // chat khulne ka wait chal raha tha
            if (expectingChat) {
                expectingChat = false
                if (!isInChat(root)) {
                    // tap fail - LIST pe hi rehna hai, back NAHI
                    dbg("Chat not opened - rescanning")
                    qHandler.postDelayed(this, 4000)
                    return
                }
                dbg("Chat opened: " + lastSender)
                armWatchdog()
                analyzeChat(root)
                return
            }

            // kahin chat khula mila -> analyze
            if (isInChat(root)) {
                dbg("In chat - analyzing")
                armWatchdog()
                analyzeChat(root)
                return
            }

            // koi AUR screen pe hain -> list pe wapas
            if (!isOnList(root)) {
                dbg("Other screen - back to list")
                performGlobalAction(GLOBAL_ACTION_BACK)
                qHandler.postDelayed(this, 2500)
                return
            }

            // LIST pe hain - kaam yahin se
            dbg("Scanning list...")
            val row: Pair<AccessibilityNodeInfo, String>? = findPendingRow(root)
            if (row != null) {
                lastSender = row.second
                dbg("Pending: " + row.second)
                clickRowTextArea(row.first, row.second)
                expectingChat = true
                qHandler.removeCallbacksAndMessages(null)
                qHandler.postDelayed(this, 12000)
                return
            }

            val elapsed: Long = System.currentTimeMillis() - lastActionTime
            if (elapsed < 20000) {
                qHandler.postDelayed(this, 20000 - elapsed)
                return
            }
            val top: List<Pair<AccessibilityNodeInfo, String>> = findTopRows(root, 5)
            if (top.isNotEmpty()) {
                val pick: Pair<AccessibilityNodeInfo, String> = top[rotIdx % top.size]
                rotIdx++
                lastSender = pick.second
                dbg("20s msg: " + pick.second)
                clickRowTextArea(pick.first, pick.second)
                expectingChat = true
                qHandler.removeCallbacksAndMessages(null)
                qHandler.postDelayed(this, 12000)
                return
            }

            dbg("Waiting on list")
            qHandler.postDelayed(this, 10000)
        }
    }

    private fun armWatchdog() {
        qHandler.removeCallbacksAndMessages(null)
        qHandler.postDelayed({
            if (queueActive) {
                val root: AccessibilityNodeInfo? = rootInActiveWindow
                if (root != null && isInChat(root)) {
                    dbg("Watchdog: back to list")
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
                qHandler.postDelayed(queueStep, 2500)
            }
        }, 40000)
    }

    private fun analyzeChat(root: AccessibilityNodeInfo) {
        val msgs: List<Pair<String, Boolean>> = scrapeMessages(root)
        if (msgs.isEmpty()) {
            dbg("No messages - casual msg")
            sendCasualText()
            return
        }
        val last: Pair<String, Boolean> = msgs[msgs.size - 1]
        if (last.second) {
            dbg("Our last - casual msg")
            sendCasualText()
            return
        }
        dbg("Their msg - AI reply")
        handleTheirMessage(msgs, lastSender)
    }

    private fun sendCasualText() {
        val msg: String = casuals[casualIdx % casuals.size]
        casualIdx++
        lastActionTime = System.currentTimeMillis()
        ChatHistory.add(this, lastSender, "me", msg)
        typeAndSend(msg)
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
                dbg("AI failed - casual fallback")
                handler.post { sendCasualText() }
                return@thread
            }
            lastActionTime = System.currentTimeMillis()
            ChatHistory.add(this, sender, "them", newMsg)
            ChatHistory.add(this, sender, "me", reply)
            handler.post { typeAndSend(reply) }
        }
    }

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
            dbg("Pasting...")
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
            if (!clicked) backToListIfInChat()
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

    /** back SIRF tab jab chat khula ho; list pe back kabhi nahi */
    private fun backToListIfInChat() {
        qHandler.removeCallbacksAndMessages(null)
        val root: AccessibilityNodeInfo? = rootInActiveWindow
        if (root != null && isInChat(root)) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            qHandler.postDelayed(queueStep, 2800)
        } else {
            qHandler.postDelayed(queueStep, 2500)
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

    private fun rowTop(node: AccessibilityNodeInfo): Int {
        val r = Rect()
        node.getBoundsInScreen(r)
        return r.top
    }

    private fun findTopRows(
        root: AccessibilityNodeInfo,
        n: Int
    ): List<Pair<AccessibilityNodeInfo, String>> {
        val candidates = ArrayList<Triple<AccessibilityNodeInfo, String, String>>()
        gatherRows(root, candidates, 0)
        candidates.sortBy { rowTop(it.first) }
        val out = ArrayList<Pair<AccessibilityNodeInfo, String>>()
        for (entry in candidates
