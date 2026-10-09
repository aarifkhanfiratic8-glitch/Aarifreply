package com.autoreply.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
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

    // ============ BLOCK 1: LIFECYCLE + VARS ============
    companion object {
        var instance: AutoAccessibilityService? = null
        var recorder: MacroRecorder? = null
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
    private var wrongPkgCount: Int = 0
    private var profileBackCount: Int = 0
    private val handledAt: HashMap<String, Long> = HashMap()

    @Volatile private var sending: Boolean = false
    @Volatile private var analyzing: Boolean = false
    private var analyzedKey: String = ""
    private var analyzedAt: Long = 0L
    private var pendingReply: String = ""

    private var openedAt: Long = 0L
    private var ourLastCount: Int = 0

    override fun onServiceConnected() {
        instance = this
        recorder = MacroRecorder(this)
        val info: AccessibilityServiceInfo = AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        info.notificationTimeout = 100
        setServiceInfo(info)
        ObserverLog.log(this, "SERVICE CONNECTED")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopQueue()
        hideOverlay()
        recorder = null
        instance = null
        ObserverLog.log(this, "SERVICE DISCONNECTED")
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

    // ============ BLOCK 2: OVERLAY UI (OFF/REC/PLAY + LOGS long-press) ============
    private fun lighten(color: Int): Int {
        val a = android.graphics.Color.alpha(color)
        val r = (android.graphics.Color.red(color) * 0.65 + 255 * 0.35).toInt()
        val g = (android.graphics.Color.green(color) * 0.65 + 255 * 0.35).toInt()
        val b = (android.graphics.Color.blue(color) * 0.65 + 255 * 0.35).toInt()
        return android.graphics.Color.argb(a, r, g, b)
    }

    private fun bg3d(color: Int): GradientDrawable {
        val d = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(lighten(color), color)
        )
        d.cornerRadius = 45f
        return d
    }

    private fun makeOverlayButton(text: String, color: Int, action: () -> Unit): Button {
        val b = Button(this)
        b.text = text
        b.textSize = 11f
        b.setTextColor(0xFFFFFFFF.toInt())
        b.background = bg3d(color)
        b.elevation = 16f
        b.minWidth = 0
        b.minimumWidth = 0
        b.minHeight = 0
        b.minimumHeight = 0
        b.setPadding(22, 8, 22, 8)
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
            box.elevation = 20f

            val dt = TextView(this)
            dt.textSize = 9f
            dt.maxLines = 2
            dt.setTextColor(0xFFFFFFFF.toInt())
            dt.text = "AutoReply: ready (drag me)"
            dt.setPadding(18, 8, 18, 8)
            dt.background = bg3d(0xFF37474F.toInt())
            dt.elevation = 12f
            debugText = dt
            box.addView(dt)

            // LOGS: debug text ko LONG-PRESS karo -> observer log clipboard me copy
            dt.setOnLongClickListener {
                val txt: String = ObserverLog.dump(this)
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("logs", txt))
                dbg("Log copied - kahin bhi paste karke padho")
                true
            }

            val pb: Button = makeOverlayButton("OFF", 0xFFC62828.toInt()) {
                if (queueActive) stopQueue() else startQueue()
            }
            pauseBtn = pb
            box.addView(pb)

            val recB: Button = makeOverlayButton("REC", 0xFF6A1B9A.toInt()) {
                toggleRec()
            }
            box.addView(recB)

            val playB: Button = makeOverlayButton("PLAY", 0xFF2E7D32.toInt()) {
                togglePlay()
            }
            box.addView(playB)

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
        val b = pauseBtn ?: return
        if (queueActive) {
            b.text = "ON"
            b.background = bg3d(0xFF2E7D32.toInt())
        } else {
            b.text = "OFF"
            b.background = bg3d(0xFFC62828.toInt())
        }
    }

    // REC/PLAY toggles - macro recorder control
    private fun toggleRec() {
        val r = recorder ?: return
        val pkg: String = Prefs.queuePkg(this)
        if (r.isRecording) {
            val n: Int = r.stopRecording("m1")
            dbg("Macro saved: m1 (" + n + " steps)")
        } else {
            r.startRecording(pkg)
            dbg("REC ON - jo bhi karo record hoga. Dubara dabao = save")
        }
    }

    private fun togglePlay() {
        val r = recorder ?: return
        if (r.isPlaying) {
            r.stopPlay()
            dbg("Play stop")
            return
        }
        val pkg: String = Prefs.queuePkg(this)
        r.play(pkg, "m1") { ok ->
            dbg(if (ok) "Macro DONE" else "Macro m1 nahi mila - pehle REC se banao")
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

    private fun isProfile(root: AccessibilityNodeInfo): Boolean {
        return findNodeWithText(root, "private album", 0) != null ||
                findNodeWithText(root, "add voice intro", 0) != null ||
                findNodeWithText(root, "profile tags", 0) != null
    }

    // ============ BLOCK 3: QUEUE ENGINE (list -> chat) ============
    private val heartbeatRunnable: Runnable = object : Runnable {
        override fun run() {
            if (queueActive) {
                process()
                qHandler.postDelayed(this, 12000)
            }
        }
    }

    private fun scheduleProcess(delayMs: Long) {
        qHandler.removeCallbacks(processRunnable)
        qHandler.postDelayed(processRunnable, delayMs)
    }

    private val processRunnable: Runnable = Runnable { process() }

    private fun process() {
        if (!queueActive) return
        if (!Prefs.masterEnabled(this)) return
        if (sending || analyzing) {
            return
        }
        val pkg: String = Prefs.queuePkg(this)
        val root: AccessibilityNodeInfo? = rootInActiveWindow
        if (root == null) {
            return
        }
        val currentPkg: String? = root.packageName?.toString()
        if (currentPkg != null && currentPkg != pkg && currentPkg != packageName) {
            wrongPkgCount++
            if (wrongPkgCount >= 3) {
                dbg("PAUSED (user in other app)")
                return
            }
            return
        }
        wrongPkgCount = 0
        if (currentPkg != pkg) {
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
            return
        }
        if (isInChat(root)) {
            profileBackCount = 0
            if (expectingChat) {
                expectingChat = false
                openedAt = System.currentTimeMillis()
                dbg("Chat opened: " + lastSender)
                return
            }
            handleChat(root)
            return
        }
        expectingChat = false
        if (isProfile(root)) {
            profileBackCount++
            val chatBtn: AccessibilityNodeInfo? = findNodeWithText(root, "chat", 0)
            if (chatBtn != null) {
                dbg("Profile - opening chat")
                chatBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                expectingChat = true
                return
            }
            dbg("Profile page - back")
            performGlobalAction(GLOBAL_ACTION_BACK)
            if (profileBackCount >= 2) {
                profileBackCount = 0
                handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 800)
            }
            return
        }
        if (isOnList(root)) {
            handleList(root)
            return
        }
        dbg("Other screen - back")
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun startQueue() {
        queueActive = true
        expectingChat = false
        wrongPkgCount = 0
        sending = false
        analyzing = false
        ourLastCount = 0
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
        ObserverLog.log(this, "QUEUE ON pkg=" + Prefs.queuePkg(this))
        scheduleProcess(500)
        qHandler.postDelayed(heartbeatRunnable, 12000)
    }

    fun stopQueue() {
        queueActive = false
        expectingChat = false
        sending = false
        analyzing = false
        qHandler.removeCallbacksAndMessages(null)
        handler.removeCallbacksAndMessages(null)
        try { wakeLock?.release() } catch (_: Exception) { }
        wakeLock = null
        updatePauseBtn()
        dbg("Queue OFF - sirf data save")
        ObserverLog.log(this, "QUEUE OFF")
    }

    private fun handleList(root: AccessibilityNodeInfo) {
        val work: List<Pair<AccessibilityNodeInfo, String>> = findWorkRows(root)
        val fresh: List<Pair<AccessibilityNodeInfo, String>> =
            work.filter { !wasRecentlyHandled(it.second) }
        dbg("List: " + work.size + " rows, " + fresh.size + " fresh")
        ObserverLog.log(this, "LIST rows=" + work.size + " fresh=" + fresh.size)
        if (fresh.isEmpty()) {
            return
        }
        val pick: Pair<AccessibilityNodeInfo, String> = fresh[0]
        lastSender = pick.second
        handledAt[pick.second] = System.currentTimeMillis()
        sending = false
        analyzing = false
        ourLastCount = 0
        dbg("Open: " + pick.second)
        clickRowTextArea(pick.first, pick.second)
        expectingChat = true
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
            ObserverLog.log(this, "ROW CLICK " + name + " ok=" + ok)
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

    // ============ BLOCK 4: CHAT ENGINE (scrape + reply) ============
    private fun handleChat(root: AccessibilityNodeInfo) {
        if (System.currentTimeMillis() - openedAt < 2000) {
            dbg("Chat loading...")
            scheduleProcess(1200)
            return
        }
        val key: String = lastSender
        val msgs: List<Pair<String, Boolean>> = scrapeMessages(root)
        if (msgs.isEmpty()) {
            dbg("No msgs yet - waiting")
            scheduleProcess(2500)
            return
        }
        val last: Pair<String, Boolean>? = msgs.lastOrNull()
        if (last != null && !last.second) {
            ourLastCount = 0
            if (analyzing) return
            analyzing = true
            analyzedKey = key
            analyzedAt = System.currentTimeMillis()
            dbg("Their msg - analyzing")
            ObserverLog.log(this, "CHAT their msg from=" + key + ": " + last.first.take(30))
            handleTheirMessage(msgs, key)
            return
        }
        if (analyzedKey == key &&
            System.currentTimeMillis() - analyzedAt < 5 * 60 * 1000L) {
            dbg("Already replied (5min lock) - next/back")
            goNextOrBack()
            return
        }
        ourLastCount++
        if (ourLastCount >= 1) {
            ourLastCount = 0
            analyzing = true
            analyzedKey = key
            analyzedAt = System.currentTimeMillis()
            dbg("Template reply")
            handleTheirMessage(listOf(Pair("hi", false)), key)
            return
        }
        scheduleProcess(3000)
    }

    private fun handleTheirMessage(msgs: List<Pair<String, Boolean>>, sender: String) {
        val recent: List<Pair<String, Boolean>> = msgs.takeLast(8)
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
                dbg("Reply failed - skip chat")
                ObserverLog.log(this, "REPLY FAILED sender=" + sender)
                handler.post { goNextOrBack() }
                return@thread
            }
            ChatHistory.add(this, sender, "them", newMsg)
            ChatHistory.add(this, sender, "me", reply)
            ObserverLog.log(this, "REPLY -> " + reply.take(40))
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

    private fun hasTextChild(node: AccessibilityNodeInfo): Boolean {
        for (i in 0 until node.childCount) {
            val c: AccessibilityNodeInfo? = node.getChild(i)
            val ct: String? = c?.text?.toString()
            if (ct != null && ct.isNotEmpty()) return true
        }
        return false
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
            if (t != null && t.isNotEmpty() && !hasTextChild(node)) {
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
        if (t.matches(Regex("^\\d+/\\d+$"))) return true
        val low: String = t.lowercase()
        if (low == "say something") return true
        if (t.endsWith("…") || t.endsWith("...")) return true
        if (low.contains("great fit")) return true
        if (low.contains("say hi now")) return true
        if (low.contains("no need to pay")) return true
        if (low.contains("the partner is online")) return true
        if (low.contains("go have a chat")) return true
        if (low.contains("congrats")) return true
        if (low.contains("streak")) return true
        if (low.contains("intimacy")) return true
        if (low.contains("restore")) return true
        if (low.contains("next unread")) return true
        if (low.contains("birthday") && low.contains("blessing")) return true
        if (low.contains("best wishes")) return true
        if (low.contains("replying to the other party")) return true
        if (low.contains("disturbance")) return true
        if (low.contains("only 10 messages")) return true
        if (low.contains("mutual following")) return true
        if (low.contains("voice & video")) return true
        if (low.contains("unlocked")) return true
        if (low.contains("view now")) return true
        if (low.contains("reply earns")) return true
        if (low.contains("diamond")) return true
        if (low.contains("double the reward")) return true
        if (t == "View" || t == "New" || t == "Online") return true
        if (low == "online" || low.startsWith("online |")) return true
        if (low.endsWith("km") && t.length <= 20) return true
        if (low == "vip" || low.endsWith(" vip")) return true
        if (low.matches(Regex("^x\\d+$"))) return true
        return false
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

    private fun readChatName(root: AccessibilityNodeInfo): String {
        try {
            val dh: Int = resources.displayMetrics.heightPixels
            val texts = ArrayList<String>()
            collectHeaderTexts(root, texts, dh, 0)
            for (x in texts) {
                if (x.length in 2..30 &&
                    !x.contains(":") &&
                    !x.startsWith("[") &&
                    !looksLikeMeta(x)
                ) {
                    return x
                }
            }
        } catch (e: Exception) { }
        return lastSender
    }

    private fun collectHeaderTexts(
        node: AccessibilityNodeInfo,
        out: ArrayList<String>,
        dh: Int,
        depth: Int
    ) {
        if (depth > 10) return
        val t: String? = node.text?.toString()?.trim()
        if (t != null && t.isNotEmpty() && node.childCount == 0) {
            val r = Rect()
            node.getBoundsInScreen(r)
            val cy: Int = (r.top + r.bottom) / 2
            if (cy > dh * 0.04 && cy < dh * 0.16) {
                out.add(t)
            }
        }
        for (i in 0 until node.childCount) {
            val c: AccessibilityNodeInfo? = node.getChild(i)
            if (c != null) collectHeaderTexts(c, out, dh, depth + 1)
        }
    }

    private val recordHandler: Handler = Handler(Looper.getMainLooper())
    private val recordRunnable: Runnable = Runnable {
        try {
            val root: AccessibilityNodeInfo = rootInActiveWindow ?: return@Runnable
            val pkg: String? = root.packageName?.toString()
            if (pkg != Prefs.queuePkg(this)) return@Runnable
            if (!isInChat(root)) return@Runnable
            val msgs: List<Pair<String, Boolean>> = scrapeMessages(root)
            if (msgs.size >= 2) {
                val name: String = readChatName(root)
                ReplyGenerator.recordChat(this, name, msgs)
            }
        } catch (e: Exception) { }
    }

    private fun scheduleRecord() {
        recordHandler.removeCallbacks(recordRunnable)
        recordHandler.postDelayed(recordRunnable, 1500)
    }

    // ============ BLOCK 5: SEND + NAVIGATION + HELPERS ============
    private fun typeAndSend(reply: String) {
        if (sending) {
            dbg("Already sending - skip")
            return
        }
        sending = true
        pendingReply = reply
        doSetText()
    }

    private fun doSetText() {
        val root: AccessibilityNodeInfo? = rootInActiveWindow
        val field: AccessibilityNodeInfo? = if (root == null) null else findInput(root)
        if (field == null) {
            handler.postDelayed({ doSetText() }, 600)
            return
        }
        val args = Bundle()
        args.putCharSequence(
            AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
            pendingReply
        )
        dbg("Setting text...")
        field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        handler.postDelayed({
            val root2: AccessibilityNodeInfo? = rootInActiveWindow
            val field2: AccessibilityNodeInfo? = if (root2 == null) null else findInput(root2)
            val txt: String = field2?.text?.toString() ?: ""
            if (txt.contains(pendingReply)) {
                dbg("Text OK - sending")
                sendFlow()
            } else {
                doSetText()
            }
        }, 700)
    }

    // Send button dhoondhta hai: input field ke same row mein, right side pe
    private fun findSendNodeInRow(
        root: AccessibilityNodeInfo,
        fieldRect: Rect,
        dw: Int
    ): AccessibilityNodeInfo? {
        val out = ArrayList<AccessibilityNodeInfo>()
        collectSendCandidates(root, fieldRect, dw, out, 0)
        for (c in out) {
            if (c.isClickable) return c
        }
        return out.firstOrNull()
    }

    private fun collectSendCandidates(
        node: AccessibilityNodeInfo,
        fieldRect: Rect,
        dw: Int,
        out: MutableList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (depth > 14) return
        val cd: String? = node.contentDescription?.toString()?.lowercase()
        val txt: String? = node.text?.toString()?.lowercase()
        val label: String? = cd ?: txt
        if (label != null && label.contains("send")) {
            out.add(node)
        } else {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (!r.isEmpty) {
                val cy = (r.top + r.bottom) / 2
                val cx = (r.left + r.right) / 2
                val fieldCy = (fieldRect.top + fieldRect.bottom) / 2
                val sameRow = kotlin.math.abs(cy - fieldCy) <= fieldRect.height()
                val rightSide = cx > fieldRect.right - dp(8)
                val smallEnough = r.width() <= dw / 5
                if (sameRow && rightSide && smallEnough && (node.isClickable || node.childCount == 0)) {
                    out.add(node)
                }
            }
        }
        for (i in 0 until node.childCount) {
            val c: AccessibilityNodeInfo? = node.getChild(i)
            if (c != null) collectSendCandidates(c, fieldRect, dw, out, depth + 1)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun sendFlow() {
        val root: AccessibilityNodeInfo? = rootInActiveWindow
        val field: AccessibilityNodeInfo? = if (root == null) null else findInput(root)
        if (root == null || field == null) {
            handler.postDelayed({ sendFlow() }, 700)
            return
        }
        val fr = Rect()
        field.getBoundsInScreen(fr)
        if (fr.isEmpty) {
            handler.postDelayed({ sendFlow() }, 700)
            return
        }
        val dw: Int = resources.displayMetrics.widthPixels
        val btn: AccessibilityNodeInfo? = findSendNodeInRow(root, fr, dw)
        var sx: Float = dw * 0.885f
        var sy: Float = ((fr.top + fr.bottom) / 2).toFloat()
        if (btn != null) {
            val br = Rect()
            btn.getBoundsInScreen(br)
            if (!br.isEmpty) {
                sx = ((br.left + br.right) / 2).toFloat()
                sy = ((br.top + br.bottom) / 2).toFloat()
            }
        }
        if (btn != null) {
            dbg("Click SEND (node)")
            btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            dbg("Tap SEND")
            tap(sx, sy)
        }
        handler.postDelayed({
            val root2: AccessibilityNodeInfo? = rootInActiveWindow
            val field2: AccessibilityNodeInfo? = if (root2 == null) null else findInput(root2)
            if (field2 == null) {
                handler.postDelayed({ sendFlow() }, 700)
                return@postDelayed
            }
            val txt: String = (field2.text?.toString() ?: "").trim()
            if (txt.isEmpty() || txt.equals("say something", ignoreCase = true)) {
                dbg("SENT!")
                onSent()
            } else {
                sendFlow()
            }
        }, 900)
    }

    private fun onSent() {
        sending = false
        handler.postDelayed({
            try {
                val root: AccessibilityNodeInfo = rootInActiveWindow ?: run {
                    goNextOrBack()
                    return@postDelayed
                }
                if (!isInChat(root)) {
                    goNextOrBack()
                    return@postDelayed
                }
                val msgs: List<Pair<String, Boolean>> = scrapeMessages(root)
                val last: Pair<String, Boolean>? = msgs.lastOrNull()
                if (last != null && !last.second && !looksLikeMeta(last.first)) {
                    dbg("New msg during send - replying")
                    analyzing = true
                    analyzedKey = lastSender
                    analyzedAt = System.currentTimeMillis()
                    handleTheirMessage(msgs, lastSender)
                    return@postDelayed
                }
            } catch (e: Exception) { }
            goNextOrBack()
        }, 600)
    }

    private fun goNextOrBack() {
        val root: AccessibilityNodeInfo? = rootInActiveWindow
        if (root != null) {
            val node: AccessibilityNodeInfo? = findNodeWithText(root, "next unread", 0)
            if (node != null) {
                var target: AccessibilityNodeInfo? = node
                var steps = 0
                while (target != null && !target.isClickable && steps < 6) {
                    target = target.parent
                    steps++
                }
                var ok = false
                if (target != null && target.isClickable) {
                    ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                if (!ok) {
                    val r = Rect()
                    node.getBoundsInScreen(r)
                    if (!r.isEmpty) {
                        dbg("Next unread - tap fallback")
                        tap(((r.left + r.right) / 2).toFloat(), ((r.top + r.bottom) / 2).toFloat())
                        ok = true
                    }
                }
                if (ok) {
                    dbg("Next unread clicked")
                    ObserverLog.log(this, "NEXT UNREAD clicked")
                    expectingChat = true
                    sending = false
                    analyzing = false
                    ourLastCount = 0
                    return
                }
            }
        }
        dbg("Back to list")
        ObserverLog.log(this, "BACK to list")
        performGlobalAction(GLOBAL_ACTION_BACK)
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
                                    !x.startsWith("[Online]") &&
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

    private fun tap(x: Float, y: Float) {
        try {
            val p = Path()
            p.moveTo(x, y)
            val g: GestureDescription = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0, 80))
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

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg: String = event.packageName?.toString() ?: return
        if (pkg != Prefs.queuePkg(this)) return

        scheduleRecord()

        // [OBSERVER] har event log + macro recorder hook
        ObserverLog.log(this, "EVT pkg=" + pkg + " type=" + event.eventType + " cls=" + (event.className ?: "-"))
        recorder?.onEvent(event)

        if (!queueActive) return
        if (sending || analyzing) return
        scheduleProcess(600)
    }

    override fun onInterrupt() { }
}
