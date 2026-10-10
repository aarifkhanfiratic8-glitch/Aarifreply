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
import android.widget.FrameLayout
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
    private var menuBg: View? = null
    private var bubbleView: TextView? = null
    private val menuButtons: ArrayList<Button> = ArrayList()
    private var wrongPkgCount: Int = 0
    private val lockAt: HashMap<String, Long> = HashMap()
    private val ourSent: HashMap<String, MutableList<String>> = HashMap()
    private val LOCK_MS: Long = 20 * 60 * 1000L
    private val MAX_SENDS: Int = 1

    @Volatile private var sending: Boolean = false
    @Volatile private var analyzing: Boolean = false
    private var analyzedKey: String = ""
    private var analyzedAt: Long = 0L
    private var pendingReply: String = ""

    private var openedAt: Long = 0L
    private var ourLastCount: Int = 0
    private var sentInChat: Int = 0
    private var emptyTries: Int = 0
    private var profileBackCount: Int = 0

    // BRAIN: 1 min WAIT -> naqsh auto-play (msg aaya -> turant queue)
    private var observeMode: Boolean = false
    private var idleSince: Long = 0L
    private val OBSERVE_IDLE_MS: Long = 60000L

    // MENU: bubble tap -> arc menu, 5 sec baad khud hide
    private var menuVisible: Boolean = false
    private val menuHandler: Handler = Handler(Looper.getMainLooper())
    private val menuHideRunnable: Runnable = Runnable { hideMenu() }

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
        showOverlay()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopQueue()
        hideOverlay()
        recorder = null
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

    private fun chatLocked(name: String): Boolean {
        val t: Long = lockAt[name] ?: return false
        return System.currentTimeMillis() - t < LOCK_MS
    }

    private fun rememberSent(key: String, text: String) {
        val l: MutableList<String> = ourSent.getOrPut(key) { ArrayList() }
        l.add(text.trim())
        if (l.size > 40) l.removeAt(0)
    }

    private fun isOurOwnText(key: String, text: String): Boolean {
        val t: String = text.trim()
        if (t.isEmpty()) return true
        val l: MutableList<String>? = ourSent[key]
        if (l != null) {
            for (m in l) {
                if (m.equals(t, ignoreCase = true)) return true
            }
        }
        return t.equals("aur batao, kaise ho?", ignoreCase = true)
    }

    private fun lockActive(key: String): Boolean {
        return analyzedKey == key &&
                System.currentTimeMillis() - analyzedAt < 5 * 60 * 1000L
    }

    private fun markAnalyzed(key: String) {
        analyzedKey = key
        analyzedAt = System.currentTimeMillis()
    }

    // ============ BLOCK 2: RADIAL ARC MENU (bubble + arc buttons) ============
    private fun lighten(color: Int): Int {
        val a = android.graphics.Color.alpha(color)
        val r = (android.graphics.Color.red(color) * 0.65 + 255 * 0.35).toInt()
        val g = (android.graphics.Color.green(color) * 0.65 + 255 * 0.35).toInt()
        val b = (android.graphics.Color.blue(color) * 0.65 + 255 * 0.35).toInt()
        return android.graphics.Color.argb(a, r, g, b)
    }

    private fun bgCircle(color: Int): GradientDrawable {
        val d = GradientDrawable()
        d.shape = GradientDrawable.OVAL
        d.setColor(color)
        return d
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun makeMenuButton(text: String, color: Int): Button {
        val b = Button(this)
        b.text = text
        b.textSize = 9f
        b.setTextColor(0xFFFFFFFF.toInt())
        b.elevation = 16f
        b.minWidth = 0
        b.minimumWidth = 0
        b.minHeight = 0
        b.minimumHeight = 0
        b.setPadding(2, 2, 2, 2)
        b.background = bgCircle(color)
        return b
    }

    // tap = action, khinchna = pura overlay move
    private fun attachDragAndClick(v: View, action: () -> Unit) {
        v.setOnTouchListener(object : View.OnTouchListener {
            private var downX: Float = 0f
            private var downY: Float = 0f
            private var moved: Boolean = false
            override fun onTouch(view: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = event.rawX
                        downY = event.rawY
                        moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx: Int = (event.rawX - downX).toInt()
                        val dy: Int = (event.rawY - downY).toInt()
                        if (!moved && kotlin.math.abs(dx) < 14 && kotlin.math.abs(dy) < 14) return true
                        moved = true
                        downX = event.rawX
                        downY = event.rawY
                        val lp: WindowManager.LayoutParams =
                            view.rootView.layoutParams as WindowManager.LayoutParams
                        lp.x = lp.x - dx
                        lp.y = lp.y + dy
                        wm?.updateViewLayout(view.rootView, lp)
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) action()
                        return true
                    }
                }
                return false
            }
        })
    }

    private fun shiftWindow(dx: Int, dy: Int) {
        val box = overlayView ?: return
        try {
            val lp: WindowManager.LayoutParams = box.layoutParams as WindowManager.LayoutParams
            lp.x += dx
            lp.y += dy
            wm?.updateViewLayout(box, lp)
        } catch (e: Exception) { }
    }

    private fun showMenu() {
        if (menuVisible) {
            menuHandler.removeCallbacks(menuHideRunnable)
            menuHandler.postDelayed(menuHideRunnable, 5000)
            return
        }
        // bubble ki absolute jagah same rahe, isliye window ko pehle upar-bayein shift
        shiftWindow(-dp(106), -dp(106))
        menuBg?.visibility = View.VISIBLE
        debugText?.visibility = View.VISIBLE
        for (b in menuButtons) b.visibility = View.VISIBLE
        menuVisible = true
        menuHandler.removeCallbacks(menuHideRunnable)
        menuHandler.postDelayed(menuHideRunnable, 5000)
    }

    private fun hideMenu() {
        if (!menuVisible) return
        menuBg?.visibility = View.GONE
        debugText?.visibility = View.GONE
        for (b in menuButtons) b.visibility = View.GONE
        shiftWindow(dp(106), dp(106))
        menuVisible = false
        menuHandler.removeCallbacks(menuHideRunnable)
    }

    private fun showOverlay() {
        if (overlayView != null) return
        try {
            wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val BOX = dp(260)
            val box = FrameLayout(this)
            box.clipChildren = false
            box.clipToPadding = false
            overlayView = box

            // bada dark circle (menu khulne pe dikhta hai) - reference image jaisa
            val bgc = View(this)
            bgc.background = bgCircle(0xE6263238.toInt())
            menuBg = bgc
            val bgLp = FrameLayout.LayoutParams(dp(215), dp(215))
            bgLp.gravity = Gravity.CENTER
            box.addView(bgc, bgLp)

            // debug text - bubble ke upar
            val dt = TextView(this)
            dt.textSize = 9f
            dt.maxLines = 1
            dt.setTextColor(0xFFFFFFFF.toInt())
            dt.text = "AutoReply"
            dt.setPadding(10, 4, 10, 4)
            dt.setOnLongClickListener {
                val txt: String = ObserverLog.dump(this)
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("logs", txt))
                dbg("Log copied")
                true
            }
            debugText = dt
            val dtLp = FrameLayout.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            dtLp.gravity = Gravity.CENTER
            dtLp.topMargin = -dp(78)
            box.addView(dt, dtLp)

            // ---- BUBBLE (bich mein, hamesha dikhta hai) ----
            val bub = TextView(this)
            bub.text = "AI"
            bub.textSize = 15f
            bub.setTextColor(0xFFFFFFFF.toInt())
            bub.gravity = Gravity.CENTER
            bub.elevation = 18f
            bub.background = bgCircle(0xFF00695C.toInt())
            bubbleView = bub
            attachDragAndClick(bub) {
                if (menuVisible) hideMenu() else showMenu()
            }
            val bubLp = FrameLayout.LayoutParams(dp(48), dp(48))
            bubLp.gravity = Gravity.CENTER
            box.addView(bub, bubLp)

            // ---- ARC BUTTONS (reference image wali arc) ----
            val cx: Int = BOX / 2
            val cy: Int = BOX / 2
            val R: Int = dp(88)
            val BSIZE = dp(40)

            fun arcBtn(text: String, color: Int, angleDeg: Double, action: () -> Unit): Button {
                val b = makeMenuButton(text, color)
                b.setOnClickListener {
                    showMenu()
                    action()
                }
                menuButtons.add(b)
                val rad = Math.toRadians(angleDeg)
                val lp = FrameLayout.LayoutParams(BSIZE, BSIZE)
                lp.leftMargin = cx + (R * Math.cos(rad)).toInt() - BSIZE / 2
                lp.topMargin = cy + (R * Math.sin(rad)).toInt() - BSIZE / 2
                b.visibility = View.GONE
                box.addView(b, lp)
                return b
            }

            pauseBtn = arcBtn("OFF", 0xFFC62828.toInt(), 200.0) {
                if (queueActive) stopQueue() else startQueue()
            }
            arcBtn("REC", 0xFF6A1B9A.toInt(), 245.0) { toggleRec() }
            arcBtn("PLAY", 0xFF2E7D32.toInt(), 290.0) { togglePlay() }
            arcBtn("X", 0xFF455A64.toInt(), 335.0) {
                stopQueue()
                hideMenu()
            }

            // [FIX] window sirf utna bada jitna visible (WRAP_CONTENT)
            // baaki puri screen normally kaam karegi
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            params.x = 10
            params.y = 250
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
        menuBg = null
        bubbleView = null
        menuButtons.clear()
        menuVisible = false
        menuHandler.removeCallbacks(menuHideRunnable)
    }

    private fun updatePauseBtn() {
        val b = pauseBtn ?: return
        if (queueActive) {
            b.text = "ON"
            b.background = bgCircle(0xFF2E7D32.toInt())
        } else {
            b.text = "OFF"
            b.background = bgCircle(0xFFC62828.toInt())
        }
    }
    private fun toggleRec() {
        val r = recorder ?: return
        val pkg: String = Prefs.queuePkg(this)
        if (r.isRecording) {
            val n: Int = r.stopRecording("m1")
            dbg("Saved m1 (" + n + ")")
        } else {
            r.startRecording(pkg)
            dbg("REC...")
        }
    }

    private fun togglePlay() {
        val r = recorder ?: return
        if (r.isPlaying) {
            r.stopPlay()
            dbg("Stop")
            return
        }
        val pkg: String = Prefs.queuePkg(this)
        r.play(pkg, "m1") { ok ->
            dbg(if (ok) "Done" else "REC pehle karo")
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


    // ============ BLOCK 3: BRAIN (QUEUE <-> NAQSH) ============
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
        if (sending || analyzing) return
        val pkg: String = Prefs.queuePkg(this)
        val root: AccessibilityNodeInfo? = rootInActiveWindow
        if (root == null) return
        val currentPkg: String? = root.packageName?.toString()
        if (currentPkg != null && currentPkg != pkg && currentPkg != packageName) {
            wrongPkgCount++
            if (wrongPkgCount >= 3) {
                dbg("PAUSED (other app)")
                return
            }
            return
        }
        wrongPkgCount = 0
        if (currentPkg != pkg) return

        if (recorder?.isPlaying == true) {
            checkPendingDuringObserve(root)
            dbg("OBSERVE run")
            return
        }

        val leaveDialog: AccessibilityNodeInfo? =
            findNodeWithText(root, "are you sure to leave", 0)
        if (leaveDialog != null) {
            dbg("Dialog: Cancel")
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
                sentInChat = 0
                openedAt = System.currentTimeMillis()
                dbg("Chat: " + lastSender)
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
                dbg("Profile -> chat")
                chatBtn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                expectingChat = true
                return
            }
            dbg("Profile -> back")
            performGlobalAction(GLOBAL_ACTION_BACK)
            if (profileBackCount >= 2) {
                profileBackCount = 0
                handler.postDelayed({ performGlobalAction(GLOBAL_ACTION_BACK) }, 800)
            }
            return
        }

        if (isOnList(root)) {
            val work2: List<WorkRow> = findWorkRows(root)
            val badgeSet: HashSet<String> =
                work2.filter { it.isBadge }.map { it.name }.toHashSet()
            val fresh2: List<WorkRow> =
                work2.filter { badgeSet.contains(it.name) || !chatLocked(it.name) }
            if (fresh2.isNotEmpty()) {
                idleSince = 0L
                openRow(fresh2[0])
                return
            }
            if (observeMode) return
            if (idleSince == 0L) idleSince = System.currentTimeMillis()
            val leftSec: Long =
                (OBSERVE_IDLE_MS - (System.currentTimeMillis() - idleSince)) / 1000L
            if (leftSec <= 0L) {
                idleSince = 0L
                startObserve()
            } else {
                dbg("WAIT " + leftSec + "s")
            }
            return
        }

        dbg("Back")
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    fun startQueue() {
        queueActive = true
        expectingChat = false
        wrongPkgCount = 0
        sending = false
        analyzing = false
        observeMode = false
        idleSince = 0L
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
        showMenu()
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
        observeMode = false
        idleSince = 0L
        recorder?.stopPlay()
        recorder?.let { if (it.isRecording) it.stopRecording("m1") }
        qHandler.removeCallbacksAndMessages(null)
        handler.removeCallbacksAndMessages(null)
        try { wakeLock?.release() } catch (_: Exception) { }
        wakeLock = null
        updatePauseBtn()
        dbg("OFF")
        ObserverLog.log(this, "QUEUE OFF")
    }

    private fun openRow(pick: WorkRow) {
        lastSender = pick.name
        sending = false
        analyzing = false
        ourLastCount = 0
        sentInChat = 0
        emptyTries = 0
        dbg("Open: " + pick.name)
        ObserverLog.log(this, "OPEN " + pick.name + " badge=" + pick.isBadge)
        clickRowTextArea(pick.node, pick.name)
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
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ObserverLog.log(this, "ROW CLICK " + name)
            return
        }
        if (nameNode != null) {
            val r = Rect()
            nameNode.getBoundsInScreen(r)
            tap(((r.left + r.right) / 2).toFloat(), ((r.top + r.bottom) / 2).toFloat())
            return
        }
        val r2 = Rect()
        row.getBoundsInScreen(r2)
        tap((r2.left + r2.width() * 0.6f), ((r2.top + r2.bottom) / 2).toFloat())
    }

    // ---- NAQSH auto-play ----
    private fun startObserve() {
        val r = recorder ?: return
        if (r.isPlaying) return
        observeMode = true
        dbg("OBSERVE on")
        ObserverLog.log(this, "OBSERVE START")
        r.play(Prefs.queuePkg(this), "m1", gate = { observeGate() }) { ok ->
            observeMode = false
            dbg(if (ok) "OBSERVE pura" else "OBSERVE ruka")
            scheduleProcess(700)
        }
    }

    private fun observeGate(): Boolean {
        if (!queueActive) return false
        val root = rootInActiveWindow
        if (root != null) {
            if (root.packageName?.toString() != Prefs.queuePkg(this)) return false
            checkPendingDuringObserve(root)
            if (recorder?.isPlaying != true) return false
        }
        return true
    }

    private fun checkPendingDuringObserve(root: AccessibilityNodeInfo) {
        if (!queueActive) return
        if (recorder?.isPlaying != true) return
        if (!isOnList(root)) return
        val work: List<WorkRow> = findWorkRows(root)
        val badgeSet: HashSet<String> =
            work.filter { it.isBadge }.map { it.name }.toHashSet()
        val fresh: List<WorkRow> =
            work.filter { badgeSet.contains(it.name) || !chatLocked(it.name) }
        if (fresh.isNotEmpty()) {
            recorder?.stopPlay()
            observeMode = false
            idleSince = 0L
            dbg("MSG! queue on")
            ObserverLog.log(this, "OBSERVE -> QUEUE")
            scheduleProcess(400)
        }
    }

    // ============ BLOCK 4: CHAT ENGINE ============
    private fun handleChat(root: AccessibilityNodeInfo) {
        if (System.currentTimeMillis() - openedAt < 2000) {
            dbg("Loading...")
            scheduleProcess(1200)
            return
        }
        val key: String = lastSender
        val msgs: List<Pair<String, Boolean>> = scrapeMessages(root)
        if (msgs.isEmpty()) {
            if (sentInChat >= MAX_SENDS || chatLocked(key)) {
                dbg("Empty -> next")
                goNextOrBack()
                return
            }
            emptyTries++
            if (emptyTries < 3) {
                dbg("Loading " + emptyTries + "/3")
                scheduleProcess(2500)
                return
            }
            // 3 baar wait kiya, kuch nahi mila -> fresh chat = 1 greeting
            greetOnce(key)
            return
        }
        if (sentInChat >= MAX_SENDS) {
            dbg("Sent 1 -> next")
            goNextOrBack()
            return
        }
        val last: Pair<String, Boolean>? = msgs.lastOrNull()
        if (last != null && !last.second) {
            if (isOurOwnText(key, last.first)) {
                ourLastCount++
                if (lockActive(key)) { goNextOrBack(); return }
                if (ourLastCount >= 2) {
                    markAnalyzed(key)
                    goNextOrBack()
                    return
                }
                scheduleProcess(3000)
                return
            }
            ourLastCount = 0
            if (analyzing) return
            analyzing = true
            markAnalyzed(key)
            dbg("Reply -> " + last.first.take(15))
            ObserverLog.log(this, "CHAT their: " + last.first.take(30))
            handleTheirMessage(msgs, key)
            return
        }
        if (lockActive(key)) { goNextOrBack(); return }
        val realFromThem: Boolean =
            msgs.any { !it.second && !isOurOwnText(key, it.first) }
        if (!realFromThem) {
            if (chatLocked(key) || sentInChat >= MAX_SENDS) {
                markAnalyzed(key)
                goNextOrBack()
                return
            }
            greetOnce(key)
            return
        }
        markAnalyzed(key)
        goNextOrBack()
    }

    private fun greetOnce(key: String) {
        val sp = getSharedPreferences("intro_flags", Context.MODE_PRIVATE)
        val done: Boolean = sp.getBoolean("i_" + key.lowercase(), false)
        val text: String = if (!done) {
            sp.edit().putBoolean("i_" + key.lowercase(), true).apply()
            "aur batao, kaise ho?"
        } else {
            val b: List<String> = listOf("hii", "hello ji", "heyy", "namaste ji", "hii yrr")
            b[(Math.random() * b.size).toInt()]
        }
        lockAt[key] = System.currentTimeMillis()
        markAnalyzed(key)
        dbg("Greet: " + text)
        ObserverLog.log(this, "GREET " + key + " -> " + text)
        typeAndSend(text)
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
                dbg("No reply -> next")
                ObserverLog.log(this, "REPLY FAIL " + sender)
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
        var inputTop: Int = dh
        try {
            val f: AccessibilityNodeInfo? = findInput(root)
            if (f != null) {
                val fr = Rect()
                f.getBoundsInScreen(fr)
                if (!fr.isEmpty) inputTop = fr.top
            }
        } catch (e: Exception) { }
        if (inputTop >= dh) return emptyList()
        collectMessages(root, out, dw, dh, inputTop, 0)
        val chatName: String = try { readChatName(root).trim().lowercase() } catch (e: Exception) { "" }
        return out.takeLast(12).filter { m ->
            val t: String = m.first.trim().lowercase()
            t.isNotEmpty() && t != chatName
        }
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
        inputTop: Int,
        depth: Int
    ) {
        if (depth > 16) return
        if (!node.isEditable) {
            val t: String? = node.text?.toString()?.trim()
            if (t != null && t.isNotEmpty() && !hasTextChild(node)) {
                val r = Rect()
                node.getBoundsInScreen(r)
                if (!r.isEmpty) {
                    val cy: Int = (r.top + r.bottom) / 2
                    val cx: Int = (r.left + r.right) / 2
                    val tooCloseToInput: Boolean = r.bottom > inputTop - dp(64)
                    if (cy > dh * 0.33 && !tooCloseToInput && !looksLikeMeta(t)) {
                        out.add(Pair(t, cx > dw / 2))
                    }
                }
            }
        }
        for (i in 0 until node.childCount) {
            val c: AccessibilityNodeInfo? = node.getChild(i)
            if (c != null) collectMessages(c, out, dw, dh, inputTop, depth + 1)
        }
    }

    private fun looksLikeMeta(t: String): Boolean {
        if (t.length <= 1) return true
        if (Regex("^.{2,22}: .+").containsMatchIn(t)) return true
        val low: String = t.lowercase()
        if (t.matches(Regex("^\\d{1,3}$"))) return true
        if (low.matches(Regex("^vip\\d*$"))) return true
        if (t.matches(Regex("^\\d{1,2}:\\d{2}.*"))) return true
        if (t.matches(Regex("^\\d{4}/.*"))) return true
        if (t.matches(Regex("^\\d+/\\d+$"))) return true
        if (t.contains("/10")) return true
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
        if (low.contains("disturbance")) return true
        if (low.contains("only 10 messages")) return true
        if (low.contains("mutual following")) return true
        if (low.contains("voice & video")) return true
        if (low.contains("unlocked")) return true
        if (low.contains("view now")) return true
        if (low.contains("reply earns")) return true
        if (low.contains("diamond")) return true
        if (low.contains("in voice chat")) return true
        if (low.contains("voice intro")) return true
        if (low.contains("album")) return true
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
            dbg("Busy")
            return
        }
        sentInChat++
        rememberSent(lastSender, reply)
        lockAt[lastSender] = System.currentTimeMillis()
        sending = true
        pendingReply = reply
        doSetText()
    }

    private fun doSetText() {
        if (!queueActive) { sending = false; return }
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
        dbg("Typing...")
        field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        handler.postDelayed({
            if (!queueActive) { sending = false; return@postDelayed }
            val root2: AccessibilityNodeInfo? = rootInActiveWindow
            val field2: AccessibilityNodeInfo? = if (root2 == null) null else findInput(root2)
            val txt: String = field2?.text?.toString() ?: ""
            if (txt.contains(pendingReply)) {
                sendFlow()
            } else {
                doSetText()
            }
        }, 700)
    }

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

    private fun sendFlow() {
        if (!queueActive) { sending = false; return }
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
            dbg("SEND click")
            btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } else {
            dbg("SEND tap")
            tap(sx, sy)
        }
        handler.postDelayed({
            if (!queueActive) { sending = false; return@postDelayed }
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
            if (!queueActive) return@postDelayed
            try {
                val root: AccessibilityNodeInfo = rootInActiveWindow ?: run {
                    goNextOrBack()
                    return@postDelayed
                }
                if (!isInChat(root)) {
                    goNextOrBack()
                    return@postDelayed
                }
                if (sentInChat >= MAX_SENDS) {
                    goNextOrBack()
                    return@postDelayed
                }
                val msgs: List<Pair<String, Boolean>> = scrapeMessages(root)
                val last: Pair<String, Boolean>? = msgs.lastOrNull()
                if (last != null && !last.second &&
                    !isOurOwnText(lastSender, last.first) &&
                    !looksLikeMeta(last.first)) {
                    analyzing = true
                    markAnalyzed(lastSender)
                    dbg("New msg -> reply")
                    handleTheirMessage(msgs, lastSender)
                    return@postDelayed
                }
            } catch (e: Exception) { }
            goNextOrBack()
        }, 600)
    }

    private fun goNextOrBack() {
        if (!queueActive) return
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
                        tap(((r.left + r.right) / 2).toFloat(), ((r.top + r.bottom) / 2).toFloat())
                        ok = true
                    }
                }
                if (ok) {
                    dbg("Next unread")
                    ObserverLog.log(this, "NEXT UNREAD")
                    expectingChat = true
                    sending = false
                    analyzing = false
                    ourLastCount = 0
                    sentInChat = 0
                    return
                }
            }
        }
        dbg("Back to list")
        ObserverLog.log(this, "BACK")
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

    private class WorkRow(
        var node: AccessibilityNodeInfo,
        var name: String,
        var top: Int,
        var isBadge: Boolean
    )

    private fun findWorkRows(root: AccessibilityNodeInfo): List<WorkRow> {
        val dw: Int = resources.displayMetrics.widthPixels
        val found = ArrayList<WorkRow>()
        findSignalsIn(root, dw, found, 0)
        found.sortBy { it.top }
        val out = ArrayList<WorkRow>()
        val seen = HashSet<String>()
        for (t in found) {
            if (t.name !in seen) {
                seen.add(t.name)
                out.add(t)
            }
        }
        return out
    }

    private fun findSignalsIn(
        node: AccessibilityNodeInfo,
        dw: Int,
        out: MutableList<WorkRow>,
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
                            if (!isSystemRow(name) && out.none { it.name == name }) {
                                out.add(WorkRow(row, name, r.top, isBadge))
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

        ObserverLog.log(this, "EVT " + pkg + " t=" + event.eventType)
        recorder?.onEvent(event)

        if (!queueActive) return
        if (recorder?.isPlaying == true && observeMode) {
            val r2 = rootInActiveWindow
            if (r2 != null) checkPendingDuringObserve(r2)
        }
        if (sending || analyzing) return
        scheduleProcess(600)
    }

    override fun onInterrupt() { }
}
