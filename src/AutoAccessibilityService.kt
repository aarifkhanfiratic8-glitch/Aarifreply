package com.autoreply.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
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
    private var casualIdx: Int = 0
    private var wrongPkgCount: Int = 0
    private val handledAt: HashMap<String, Long> = HashMap()

    // === NAYE LOCKS: one-time paste + no double analyze ===
    @Volatile private var sending: Boolean = false
    @Volatile private var analyzing: Boolean = false
    private var analyzedKey: String = ""
    private var analyzedAt: Long = 0L

    private val casuals: List<String> = listOf(
        "aap kaha se ho batao na",
        "khana khaya kya aapne",
        "aaj kya kiya aapne, batao na",
        "kya kar rahe ho aajkal",
        "aapke yahan mausam kaisa hai"
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

    private fun wasRecentlyHandled(name: String): Boolean {
        val t: Long = handledAt[name] ?: return false
        return System.currentTimeMillis() - t < 180000L
    }

    fun startQueue() {
        queueActive = true
        expectingChat = false
        wrongPkgCount = 0
        sending = false
        analyzing = false
        analyzedKey = ""
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
        qHandler.postDelayed(queueStep, 2000)
    }

    fun stopQueue() {
        queueActive = false
        expectingChat = false
        sending = false
        analyzing = false
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
        val field: AccessibilityNodeInfo = findInput(root) ?: return false
        val r = Rect()
        field.getBoundsInScreen(r)
        val dh: Int = resources.displayMetrics.heightPixels
        return r.top > dh * 0.55
    }

    private fun isOnList(root: AccessibilityNodeInfo): Boolean {
        return findNodeWithText(root, "most chatted", 0) != null ||
                findNodeWithText(root, "unread", 0) != null
    }

    private val queueStep: Runnable = object : Runnable {
        override fun run() {
            if (!queueActive) return
            if (!Prefs.masterEnabled(this@AutoAccessibilityService)) return
            val pkg: String = Prefs.queuePkg(this@AutoAccessibilityService)

            val root: AccessibilityNodeInfo? = rootInActiveWindow
            if (root == null) {
                qHandler.postDelayed(this, 2500)
                return
            }

            val currentPkg: String? = root.packageName?.toString()
            if (currentPkg != null && currentPkg != pkg && currentPkg != packageName) {
                wrongPkgCount++
                if (wrongPkgCount < 3) {
                    qHandler.postDelayed(this, 2500)
                    return
                }
                dbg("PAUSED (user in other app)")
                expectingChat = false
                qHandler.postDelayed(this, 10000)
                return
            }
            wrongPkgCount = 0

            if (currentPkg != pkg) {
                qHandler.postDelayed(this, 3500)
                return
            }

            val leaveDialog: AccessibilityNodeInfo? =
                findNodeWithText(root, "are you sure to leave", 0)
            if (leaveDialog != null) {
                dbg("Dialog - auto Cancel")
                val cancelBtn: AccessibilityNodeInfo? = findNodeWithText(root, "cancel", 0)
                if (cancelBtn != null) {
                    cancelBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                expectingChat = false
                qHandler.postDelayed(this, 3500)
                return
            }

            if (expectingChat) {
                expectingChat = false
                if (!isInChat(root)) {
                    dbg("Chat not opened - rescanning")
                    qHandler.postDelayed(this, 4000)
                    return
                }
                dbg("Chat opened: " + lastSender)
                armWatchdog()
                analyzeChat(root)
                return
            }

            // AI reply ya paste chal raha hai to haath mat lagao
            if (sending || analyzing) {
                qHandler.postDelayed(this, 3000)
                return
            }

            if (isInChat(root)) {
                dbg("In chat - analyzing")
                armWatchdog()
                analyzeChat(root)
                return
            }

            if (!isOnList(root)) {
                val chatBtn: AccessibilityNodeInfo? = findNodeWithText(root, "chat", 0)
                if (chatBtn != null) {
                    dbg("Profile - opening chat")
                    chatBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    expectingChat = true
                    qHandler.removeCallbacksAndMessages(null)
                    qHandler.postDelayed(this, 9000)
                    return
                }
                dbg("Other screen - back to list")
                performGlobalAction(GLOBAL_ACTION_BACK)
                expectingChat = false
                qHandler.postDelayed(this, 3000)
                return
            }

            val work: List<Pair<AccessibilityNodeInfo, String>> = findWorkRows(root)
            dbg("Work rows: " + work.size)
            val fresh: List<Pair<AccessibilityNodeInfo, String>> =
                work.filter { !wasRecentlyHandled(it.second) }
            if (fresh.isEmpty()) {
                dbg("No fresh work - waiting")
                qHandler.postDelayed(this, 60000)
                return
            }

            val pick: Pair<AccessibilityNodeInfo, String> = fresh[0]
            lastSender = pick.second
            handledAt[pick.second] = System.currentTimeMillis()
            // naya chat = naye locks
            sending = false
            analyzing = false
            analyzedKey = ""
            dbg("Open: " + pick.second)
            clickRowTextArea(pick.first, pick.second)
            expectingChat = true
            qHandler.removeCallbacksAndMessages(null)
            qHandler.postDelayed(this, 12000)
        }
    }

    private fun armWatchdog() {
        qHandler.removeCallbacksAndMessages(null)
        qHandler.postDelayed({
            if (queueActive) {
                // reply ke beech watchdog tang na kare
                if (!sending && !analyzing) {
                    val root: AccessibilityNodeInfo? = rootInActiveWindow
                    if (root != null && isInChat(root)) {
                        dbg("Watchdog: back to list")
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    }
                    sending = false
                    analyzing = false
                    analyzedKey = ""
                    qHandler.postDelayed(queueStep, 3000)
                } else {
                    armWatchdog()
                }
            }
        }, 60000)
    }

    private fun analyzeChat(root: AccessibilityNodeInfo) {
        // same chat dobara analyze na ho (30 min lock)
        val key: String = lastSender
        if (analyzing) return
        if (analyzedKey == key && System.currentTimeMillis() - analyzedAt < 30 * 60 * 1000L) {
            dbg("Already analyzed - skip")
            qHandler.postDelayed(queueStep, 4000)
            return
        }
        val msgs: List<Pair<String, Boolean>> = scrapeMessages(root)
        if (msgs.isEmpty()) {
            dbg("No msgs - wait")
            qHandler.postDelayed(queueStep, 4000)
            return
        }
        analyzedKey = key
        analyzedAt = System.currentTimeMillis()
        analyzing = true
        val last: Pair<String, Boolean>? = msgs.lastOrNull()
        if (last != null && !last.second) {
            dbg("Their msg - AI reply")
            handleTheirMessage(msgs, lastSender)
        } else {
            dbg("Our last/none - question poocho")
            sendCasualText()
        }
    }

    private fun sendCasualText() {
        val msg: String = casuals[casualIdx % casuals.size]
        casualIdx++
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
            analyzing = false
            if (reply.isNullOrBlank()) {
                dbg("AI failed - casual fallback")
                handler.post { sendCasualText() }
                return@thread
            }
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
                    out.add(Pair(t, cx > dw / 2))
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

    // === ONE-TIME PASTE: sending lock ke bina dobara paste nahi ===
    private fun typeAndSend(reply: String) {
        if (sending) {
            dbg("Already sending - skip")
            return
        }
        sending = true
        handler.postDelayed({
            val root: AccessibilityNodeInfo? = rootInActiveWindow
            if (root == null) {
                sending = false
                return@postDelayed
            }
            val field: AccessibilityNodeInfo? = findInput(root)
            if (field == null) {
                dbg("No input field - NO back")
                sending = false
                return@postDelayed
            }
            // agar field mein pehle se yehi text hai to dobara paste mat karo
            val existing: String = field.text?.toString() ?: ""
            if (existing.trim() == reply.trim()) {
                dbg("Already pasted - direct SEND")
                handler.postDelayed({ trySendClick(0) }, 1000)
                return@postDelayed
            }
            dbg("Pasting...")
            val clipboard: android.content.ClipboardManager =
                getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(
                android.content.ClipData.newPlainText("reply", reply)
            )
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            field.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            handler.postDelayed({
                val root2: AccessibilityNodeInfo? = rootInActiveWindow
                val field2: AccessibilityNodeInfo? =
                    if (root2 == null) null else findInput(root2)
                val txt: String = field2?.text?.toString() ?: ""
                if (txt.isNotBlank()) {
                    dbg("Paste confirmed")
                    handler.postDelayed({ trySendClick(0) }, 1200)
                } else {
                    dbg("Retry with SET_TEXT")
                    val args = Bundle()
                    args.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        reply
                    )
                    if (field2 != null) {
                        field2.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    }
                    handler.postDelayed({
                        val root3: AccessibilityNodeInfo? = rootInActiveWindow
                        val field3: AccessibilityNodeInfo? =
                            if (root3 == null) null else findInput(root3)
                        val t3: String = field3?.text?.toString() ?: ""
                        if (t3.isNotBlank()) {
                            dbg("Text confirmed")
                            handler.postDelayed({ trySendClick(0) }, 1200)
                        } else {
                            dbg("No text - NO back, waiting")
                            sending = false
                        }
                    }, 1200)
                }
            }, 1200)
        }, 1200)
    }

    private fun trySendClick(attempt: Int) {
        if (attempt > 10) {
            dbg("Send button NOT found - NO back, waiting")
            sending = false
            qHandler.postDelayed(queueStep, 5000)
            return
        }
        val root: AccessibilityNodeInfo = rootInActiveWindow ?: return
        val send: AccessibilityNodeInfo? = findSendButton(root, 0)
        if (send == null) {
            handler.postDelayed({ trySendClick(attempt + 1) }, 700)
            return
        }
        dbg("Clicking SEND")
        val ok: Boolean = send.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (ok) {
            dbg("SENT!")
            handler.postDelayed({ goNextOrBack() }, 4000)
            return
        }
        handler.postDelayed({ trySendClick(attempt + 1) }, 700)
    }

    private fun goNextOrBack() {
        sending = false
        analyzing = false
        handler.postDelayed({
            val clicked: Boolean = findNextUnreadAndClick()
            if (!clicked) backToListIfInChat()
        }, 3000)
    }

    private fun findNextUnreadAndClick(): Boolean {
        val root: AccessibilityNodeInfo = rootInActiveWindow ?: return false
        val node: AccessibilityNodeInfo? = findNodeWithText(root, "next unread", 0)
        if (node != null) {
            val ok: Boolean = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            if (ok) {
                expectingChat = true
                // naya chat = locks reset
                sending = false
                analyzing = false
                analyzedKey = ""
                dbg("Next unread clicked")
                armWatchdog()
                return true
            }
        }
        return false
    }

    private fun backToListIfInChat() {
        qHandler.removeCallbacksAndMessages(null)
        val root: AccessibilityNodeInfo? = rootInActiveWindow
        if (root != null && isInChat(root)) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            qHandler.postDelayed(queueStep, 3500)
        } else {
            qHandler.postDelayed(queueStep, 3000)
        }
    }

    private fun findWorkRows(
        root: AccessibilityNodeInfo
    ): List<Pair<AccessibilityNodeInfo, String>> {
        val dw: Int = resources.displayMetrics.widthPixels
        val found = ArrayList<Triple<AccessibilityNodeInfo, String, Int>>()
        findSignalsIn(root, dw, found, 0)
        found.sortBy { it.third }
        val out = ArrayList<Pair<AccessibilityNodeInfo, String>>()
        val seen = HashSet<String>()
        for (t in found) {
            if (t.second !in seen) {
                seen.add(t.second)
                out.add(Pair(t.first, t.second))
            }
        }
        return out
    }

    private fun findSignalsIn(
        node: AccessibilityNodeInfo,
        dw: Int,
        out: MutableList<Triple<AccessibilityNodeInfo, String, Int>>,
        depth: Int
    ) {
        if (depth > 18) return
        if (node.childCount == 0) {
            val t: String? = node.text?.toString()?.trim()
            val cd: String? = node.contentDescription?.toString()?.trim()
            val s: String = if (t != null && t.isNotEmpty()) t
                            else if (cd != null && cd.isNotEmpty()) cd
                            else ""
            if (s.isNotEmpty()) {
                val isBadge: Boolean = s.matches(Regex("^\\d{1,2}$"))
                val isFreshTime: Boolean =
                    s.matches(Regex("^\\d{1,2}:\\d{2}$")) && isNow(s)
                if (isBadge || isFreshTime) {
                    val r = Rect()
                    node.getBoundsInScreen(r)
                    if (((r.left + r.right) / 2) > dw * 0.60) {
                        val row: AccessibilityNodeInfo? = findRowContainer(node, dw, 0)
                        if (row != null) {
                            val texts = ArrayList<String>()
                            collectLeafTexts(row, texts, 0)
                            var name: String = "friend"
                            for (x in texts) {
                                if (x.length in 2..30 &&
                                    !x.contains(":") &&
                                    !x.startsWith("[Match]") &&
                                    !x.matches(Regex("^\\d{1,2}(:\\d{2})?.*")) &&
                                    !isSystemRow(x)
                                ) {
                                    name = x
                                    break
                                }
                            }
                            if (!isSystemRow(name) && out.none { it.second == name }) {
                                out.add(Triple(row, name, r.top))
                            }
                        }
                    }
                }
            }
        }
        for (i in 0 until node.childCount) {
            val c: AccessibilityNodeInfo? = node.getChild(i)
            if (c != null) findSignalsIn(c, dw, out, depth + 1)
        }
    }

    private fun isNow(timeStr: String): Boolean {
        try {
            val parts: List<String> = timeStr.split(":")
            val h: Int = parts[0].toInt()
            val m: Int = parts[1].toInt()
            val cal: java.util.Calendar = java.util.Calendar.getInstance()
            val nowH: Int = cal.get(java.util.Calendar.HOUR_OF_DAY)
            val nowM: Int = cal.get(java.util.Calendar.MINUTE)
            val diff: Int = kotlin.math.abs((h * 60 + m) - (nowH * 60 + nowM))
            return diff <= 2
        } catch (e: Exception) {
            return false
        }
    }

    private fun findRowContainer(
        node: AccessibilityNodeInfo,
        dw: Int,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (depth > 8) return null
        val p: AccessibilityNodeInfo? = node.parent
        if (p == null) return node
        val r = Rect()
        p.getBoundsInScreen(r)
        if (r.width() > dw * 0.5) return p
        return findRowContainer(p, dw, depth + 1)
    }

    private fun clickRowTextArea(row: AccessibilityNodeInfo, name: String) {
        val nameNode: AccessibilityNodeInfo? = findLeafWithText(row, name, 0)
        var target: AccessibilityNodeInfo? = nameNode
        var steps = 0
        while (target != null && !target.isClickable && steps < 6) {
            target = target.parent
            steps++
        }
        if (target != null && target.isClickable) {
            val ok: Boolean = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            dbg(if (ok) "Row clicked" else "Row click failed")
            return
        }
        if (nameNode != null) {
            val r = Rect()
            nameNode.getBoundsInScreen(r)
            dbg("Tapped: " + name)
            tap(((r.left + r.right) / 2).toFloat(), ((r.top + r.bottom) / 2).toFloat())
            return
        }
        val r2 = Rect()
        row.getBoundsInScreen(r2)
        tap((r2.left + r2.width() * 0.6f), ((r2.top + r2.bottom) / 2).toFloat())
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

    private fun isSystemRow(name: String): Boolean {
        if (name.contains("TOKI TEAM", ignoreCase = true)) return true
        if (name == "My chat room") return true
        if (name == "Moments") return true
        if (name == "Contacts") return true
        if (name.contains("Join a family")) return true
        if (name.contains("Interaction notifications")) return true
        return false
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

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg: String = event.packageName?.toString() ?: return
        if (queueActive && pkg == Prefs.queuePkg(this) &&
            (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
             event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        ) {
            // reply/paste chal raha ho to event se disturb mat karo
            if (sending || analyzing) return
            qHandler.removeCallbacks(queueStep)
            qHandler.postDelayed(queueStep, 1500)
        }
    }

    override fun onInterrupt() { }
}
package com.autoreply.ai

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

object ReplyGenerator {

    fun generate(
        context: Context,
        sender: String,
        screenLines: List<String>,
        newMessage: String
    ): String? {
        if (newMessage.isBlank()) return null
        val apiKey = Prefs.apiKey(context)
        if (apiKey.isNotBlank()) {
            try {
                return askOpenAI(context, apiKey, sender, screenLines, newMessage)
            } catch (e: Exception) {
                // fail ho to templates
            }
        }
        return localReply(newMessage)
    }

    private fun askOpenAI(
        context: Context,
        apiKey: String,
        sender: String,
        screenLines: List<String>,
        newMessage: String
    ): String {
        val persona = Prefs.persona(context).ifBlank {
            "a sweet Indian girl, 22, from Delhi, who talks in Hinglish with words like 'yrr', 'dilse', warm and caring"
        }
        val history = ChatHistory.get(context, sender)

        val convo = StringBuilder()
        screenLines.takeLast(14).forEach { convo.append(it).append("\n") }
        if (history.isNotEmpty()) {
            convo.append("Your previous replies in this chat: ")
            convo.append(history.filter { it.first == "me" }.takeLast(5)
                .joinToString(" | ") { it.second })
        }

        val systemPrompt = "You are " + persona + " chatting with \"" + sender + "\" on a dating app. " +
            "You reply AS the user like a real human. Never admit you are AI.\n\n" +
            "LENGTH RULE (very important): every reply must be STRICTLY between 4 and 18 words. " +
            "Never reply in 1-2 words. Never write more than 18 words.\n\n" +
            "REPLY RULE (very important):\n" +
            "- Agar unhone kuch POOCHA hai → uska warm, emotional jawab do.\n" +
            "- Agar kuch nahi poocha (sirf 'hi', 'hello' ya koi statement) → " +
            "tu EK CHHOTA SA QUESTION poochhe unse, jaise: " +
            "'aap kaha se ho?', 'khana khaya kya?', 'aaj kya kiya?'.\n\n" +
            "STYLE RULE: write like a real Indian girl from Delhi texting - Hinglish, emotional, warm. " +
            "Use words like 'yrr', 'dilse', 'sach me'.\n\n" +
            "STRICT SAFETY RULES:\n" +
            "- NEVER share phone number, WhatsApp, Instagram, address, photos, social media.\n" +
            "- If they ask for number/contact/photo: politely DEFLECT - " +
            "'abhi nahi yrr, pehle thodi aur baat karo na 😊', 'itni jaldi kya hai 😄'.\n" +
            "- Never be rude. Keep them interested."

        val userPrompt = "Chat with " + sender + " so far:\n" + convo.toString() +
            "\nNew message from " + sender + ": " + newMessage +
            "\n\nWrite ONLY your next reply as the user. Between 4 and 18 words. " +
            "If they asked something - answer it. If not - ask them a small question."

        val conn = URL("https://api.openai.com/v1/chat/completions").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 20000
        conn.readTimeout = 20000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer " + apiKey)

        val body = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("temperature", 0.9)
            put("max_tokens", 80)
            put("messages", org.json.JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", systemPrompt))
                put(JSONObject().put("role", "user").put("content", userPrompt))
            })
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        if (conn.responseCode != 200) throw RuntimeException("HTTP " + conn.responseCode)
        val resp = JSONObject(conn.inputStream.bufferedReader().readText())
        val content = resp.getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
        val cleaned = content.trim().trim('"').trim()
        return if (cleaned.isBlank()) throw RuntimeException("empty") else cleaned
    }

    private fun localReply(message: String): String {
        val lower = message.lowercase().trim()
        fun pick(list: List<String>) = list[Random.nextInt(list.size)]

        // PERSONAL INFO maangi hai — kabhi share nahi, hamesha taalo
        val personalAsk = listOf(
            "number", "numbr", "no do", "whatsapp", "wp do", "insta", "instagram",
            "photo", "pic", "selfi", "address", "ghar kaha", "milna hai", "real me mil",
            "facebook", "fb do", "snap", "call karo", "phone"
        ).any { it in lower }
        if (personalAsk) {
            return pick(listOf(
                "abhi nahi yrr 😅 pehle thodi aur baat karo na",
                "itni jaldi kya hai yrr 😄 pehle jaan lo mujhe",
                "haan sab milega... dhire dhire 😊",
                "abhi busy hoon yrr, shaam ko baat karte hain 😊"
            ))
        }

        // romantic/emotional message — emotional jawab
        val romantic = listOf("peyar", "pyaar", "pyar", "love", "dil", "miss", "marry", "shaadi", "jaan").any { it in lower }
        if (romantic) {
            return pick(listOf(
                "sach me yrr? mujhe bhi aap ache lagte ho 😊",
                "dilse batao yrr, kya sach me itna miss karte ho mujhe",
                "aap bahut sweet ho yrr, sach me 😊"
            ))
        }

        // Question poocha hai — jawab do (Delhi girl persona)
        return when {
            listOf("kaha se", "kha se", "where", "city", "kidhar").any { it in lower } ->
                pick(listOf(
                    "main delhi se hu yrr, aap batao kaha se ho 😊",
                    "delhi ki hoon main, aap kahan se ho?"
                ))
            listOf("khana", "kha liya", "lunch", "dinner", "breakfast", "khaye").any { it in lower } ->
                pick(listOf(
                    "haan khana kha liya yrr 😊 aapne khaya?",
                    "abhi nahi khaya, bhookh lagi hai 😅 aap batao"
                ))
            listOf("kaise ho", "kese ho", "how are you", "kya haal").any { it in lower } ->
                pick(listOf(
                    "main theek hu yrr 😊 aap sunao kaise ho",
                    "badhiya hoon 😊 aap batao aap kaise ho"
                ))
            listOf("gf", "boyfriend", "single", "married", "shaadi", "relation").any { it in lower } ->
                pick(listOf(
                    "nahi yrr abhi single hoon 😊 aap batao aapka kya scene hai",
                    "abhi koi nahi hai yrr, aap batao aap single ho?"
                ))
            listOf("hi", "hello", "hey", "hii", "heyy", "hola").any { lower.startsWith(it) } ->
                pick(listOf(
                    "heyy! kaha se ho aap? 😊",
                    "hi yrr 😊 khana khaya kya aapne?",
                    "hello! aaj kya kiya aapne, batao na"
                ))
            listOf("thank", "shukriya", "dhanyavad").any { it in lower } ->
                pick(listOf("arre koi baat nahi yrr 😊", "itna formal mat bano yrr 😊"))
            listOf("bye", "gtg", "chalta").any { it in lower } ->
                pick(listOf(
                    "theek hai yrr, baad mein baat karte hain 👋 aap kaha se ho waise?",
                    "ok yrr take care 😊 kal baat karna"
                ))
            listOf("lol", "haha", "😂", "🤣").any { it in lower } ->
                pick(listOf(
                    "haha 😂 aap bahut funny ho yrr, aap kaha se ho?",
                    "😂😂 hasi rok nahi pa rahi yrr"
                ))
            // Kuch nahi poocha → QUESTION poochho (user ka rule)
            else ->
                pick(listOf(
                    "acha yrr 😊 aap kaha se ho?",
                    "hmm theek hai 😊 aapne khana khaya kya?",
                    "sahi hai yrr 😊 aaj kya kiya aapne?",
                    "ok yrr 😊 aapka din kaisa gaya aaj?"
                ))
        }
    }
}
