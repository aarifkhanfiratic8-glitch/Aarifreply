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

    @Volatile private var sending: Boolean = false
    @Volatile private var analyzing: Boolean = false
    private var analyzedKey: String = ""
    private var analyzedAt: Long = 0L
    private var pendingReply: String = ""

    private val casuals: List<String> = listOf(
        "hii kese ho aap 😊",
        "hello ji, kya kar rahe ho",
        "hii, aap kaha se ho?",
        "hello, khana khaya kya aapne",
        "hii ji, kaisa chal raha hai aaj"
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
        pendingReply = ""
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
        scheduleProcess(500)
        qHandler.postDelayed(heartbeatRunnable, 12000)
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

    // === BLOCK 2 ISKE NICHE AAYEGA ===
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

    private fun isProfile(root: AccessibilityNodeInfo): Boolean {
        return findNodeWithText(root, "private album", 0) != null ||
                findNodeWithText(root, "add voice intro", 0) != null ||
                findNodeWithText(root, "profile tags", 0) != null
    }

    // === HEARTBEAT: sirf backup - koi event miss ho to self-check ===
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
            if (expectingChat) {
                expectingChat = false
                dbg("Chat opened: " + lastSender)
            }
            handleChat(root)
            return
        }
        expectingChat = false

        if (isProfile(root)) {
            val chatBtn: AccessibilityNodeInfo? = findNodeWithText(root, "chat", 0)
            if (chatBtn != null) {
                dbg("Profile - opening chat")
                chatBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                expectingChat = true
                return
            }
            dbg("Profile page - back")
            performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }

        if (isOnList(root)) {
            handleList(root)
            return
        }

        dbg("Other screen - back")
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun handleList(root: AccessibilityNodeInfo) {
        val work: List<Pair<AccessibilityNodeInfo, String>> = findWorkRows(root)
        val fresh: List<Pair<AccessibilityNodeInfo, String>> =
            work.filter { !wasRecentlyHandled(it.second) }
        dbg("List: " + work.size + " rows, " + fresh.size + " fresh")
        if (fresh.isEmpty()) {
            return
        }
        val pick: Pair<AccessibilityNodeInfo, String> = fresh[0]
        lastSender = pick.second
        handledAt[pick.second] = System.currentTimeMillis()
        sending = false
        analyzing = false
        analyzedKey = ""
        dbg("Open: " + pick.second)
        clickRowTextArea(pick.first, pick.second)
        expectingChat = true
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

    // === BLOCK 3 ISKE NICHE AAYEGA ===
    
        // === Conversation rule: unka msg = HAMESHA jawab | hamara/empty = ek greeting phir aage ===
    private fun handleChat(root: AccessibilityNodeInfo) {
        val key: String = lastSender
        val msgs: List<Pair<String, Boolean>> = scrapeMessages(root)
        if (msgs.isEmpty()) {
            dbg("No msgs yet - waiting for event")
            return
        }
        val last: Pair<String, Boolean>? = msgs.lastOrNull()

        if (last != null && !last.second) {
            if (analyzing) return
            analyzing = true
            analyzedKey = key
            analyzedAt = System.currentTimeMillis()
            dbg("Their msg - AI reply")
            handleTheirMessage(msgs, lastSender)
            return
        }

        if (analyzedKey == key && System.currentTimeMillis() - analyzedAt < 30 * 60 * 1000L) {
            dbg("Already replied - next/back")
            goNextOrBack()
            return
        }
        analyzedKey = key
        analyzedAt = System.currentTimeMillis()
        dbg("Our last/none - greeting bhejo")
        sendCasualText()
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
                dbg("AI failed - greeting fallback")
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
        if (low.contains("double the reward")) return true
        if (t == "View" || t == "New" || t == "Online") return true
        if (low == "online" || low.startsWith("online |")) return true
        if (low.endsWith("km") && t.length <= 20) return true
        if (low == "vip" || low.endsWith(" vip")) return true
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

    // === BLOCK 4 ISKE NICHE AAYEGA ===
        private fun typeAndSend(reply: String) {
        if (sending) {
            dbg("Already sending - skip")
            return
        }
        sending = true
        pendingReply = reply
        doSetText(0)
    }

    // SET_TEXT se direct text - clipboard/popup nahi, keyboard nahi
    private fun doSetText(tryCount: Int) {
        if (tryCount > 4) {
            dbg("Text failed - recovery back")
            sending = false
            analyzedKey = ""
            performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }
        val root: AccessibilityNodeInfo? = rootInActiveWindow
        val field: AccessibilityNodeInfo? = if (root == null) null else findInput(root)
        if (field == null) {
            handler.postDelayed({ doSetText(tryCount + 1) }, 600)
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
                sendFlow(0)
            } else {
                doSetText(tryCount + 1)
            }
        }, 700)
    }

    // Send: pehle button NODE par direct CLICK (performAction) - row-click jaisa
    private fun sendFlow(attempt: Int) {
        if (attempt > 10) {
            dbg("Send not working - recovery back")
            sending = false
            analyzedKey = ""
            performGlobalAction(GLOBAL_ACTION_BACK)
            return
        }
        val root: AccessibilityNodeInfo? = rootInActiveWindow
        val field: AccessibilityNodeInfo? = if (root == null) null else findInput(root)
        if (root == null || field == null) {
            handler.postDelayed({ sendFlow(attempt + 1) }, 700)
            return
        }
        val fr = Rect()
        field.getBoundsInScreen(fr)
        if (fr.isEmpty) {
            handler.postDelayed({ sendFlow(attempt + 1) }, 700)
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
        if (attempt % 2 == 0 && btn != null) {
            dbg("Click SEND " + attempt + " (node) at " + sx.toInt() + "," + sy.toInt())
            btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            dbg("Tap SEND " + attempt + " at " + sx.toInt() + "," + sy.toInt())
            tap(sx, sy)
        }
        handler.postDelayed({
            val root2: AccessibilityNodeInfo? = rootInActiveWindow
            val field2: AccessibilityNodeInfo? = if (root2 == null) null else findInput(root2)
            if (field2 == null) {
                handler.postDelayed({ sendFlow(attempt + 1) }, 700)
                return@postDelayed
            }
            val txt: String = field2.text?.toString() ?: ""
            if (txt.isBlank()) {
                dbg("SENT!")
                onSent()
            } else {
                sendFlow(attempt + 1)
            }
        }, 1300)
    }

    private fun findSendNodeInRow(
        root: AccessibilityNodeInfo,
        fieldRect: Rect,
        dw: Int
    ): AccessibilityNodeInfo? {
        val out = ArrayList<AccessibilityNodeInfo>()
        collectRowClickables(root, fieldRect, dw, out, 0)
        if (out.isEmpty()) return null
        var best: AccessibilityNodeInfo? = null
        var bestCx: Int = -1
        for (n in out) {
            val r = Rect()
            n.getBoundsInScreen(r)
            val cx: Int = (r.left + r.right) / 2
            if (cx > bestCx) {
                bestCx = cx
                best = n
            }
        }
        return best
    }

    private fun collectRowClickables(
        node: AccessibilityNodeInfo,
        fieldRect: Rect,
        dw: Int,
        out: ArrayList<AccessibilityNodeInfo>,
        depth: Int
    ) {
        if (depth > 14) return
        if (node.isClickable && node.isEnabled) {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (!r.isEmpty) {
                val cx: Int = (r.left + r.right) / 2
                val cy: Int = (r.top + r.bottom) / 2
                val inRow: Boolean =
                    cy >= fieldRect.top - 40 && cy <= fieldRect.bottom + 40
                val rightEnd: Boolean = cx > dw * 0.78
                val smallEnough: Boolean = r.width() <= fieldRect.width() / 2
                if (inRow && rightEnd && smallEnough) {
                    out.add(node)
                }
            }
        }
        for (i in 0 until node.childCount) {
            val c: AccessibilityNodeInfo? = node.getChild(i)
            if (c != null) collectRowClickables(c, fieldRect, dw, out, depth + 1)
        }
    }

    private fun onSent() {
        sending = false
        handler.postDelayed({
            goNextOrBack()
        }, 2000)
    }

    private fun goNextOrBack() {
        val root: AccessibilityNodeInfo? = rootInActiveWindow
        if (root != null) {
            val node: AccessibilityNodeInfo? = findNodeWithText(root, "next unread", 0)
            if (node != null) {
                val ok: Boolean = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (ok) {
                    dbg("Next unread clicked")
                    expectingChat = true
                    sending = false
                    analyzing = false
                    analyzedKey = ""
                    return
                }
            }
        }
        dbg("Back to list")
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
        if (queueActive && pkg == Prefs.queuePkg(this)) {
            if (sending || analyzing) return
            scheduleProcess(600)
        }
    }

    override fun onInterrupt() { }
}

    
