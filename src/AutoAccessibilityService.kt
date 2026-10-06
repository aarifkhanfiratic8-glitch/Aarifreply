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

    private fun cleanForKeyboard(text: String): String {
        val sb = StringBuilder()
        for (ch in text) {
            val c = ch.lowercaseChar()
            if ((c in 'a'..'z') || (c in '0'..'9')) sb.append(c)
            else if (ch == ' ' || ch == '?' || ch == '.' || ch == ',') sb.append(ch)
        }
        return sb.toString()
    }

    private fun typeAndSend(reply: String) {
        lastReply = reply
        handler.postDelayed({
            val root: AccessibilityNodeInfo? = rootInActiveWindow
            if (root == null) return@postDelayed
            val field: AccessibilityNodeInfo? = findInput(root)
            if (field == null) {
                dbg("No input field found")
                goNextOrBack()
                return@postDelayed
            }
            dbg("Opening keyboard...")
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val fr = Rect()
            field.getBoundsInScreen(fr)
            tap(((fr.left + fr.right) / 2).toFloat(), ((fr.top + fr.bottom) / 2).toFloat())
            handler.postDelayed({ typeWithKeyboard(cleanForKeyboard(lastReply)) }, 1300)
        }, 800)
    }

    private fun typeWithKeyboard(text: String) {
        thread {
            var i = 0
            while (i < text.length) {
                val ch = text[i]
                val keyName: String = if (ch == ' ') "space" else ch.toString()
                val key: AccessibilityNodeInfo? = waitForKey(keyName)
                if (key == null) {
                    dbg("Key not found: " + ch)
                } else {
                    val r = Rect()
                    key.getBoundsInScreen(r)
                    tap(((r.left + r.right) / 2).toFloat(), ((r.top + r.bottom) / 2).toFloat())
                    Thread.sleep(120L + kotlin.random.Random.nextLong(0, 100))
                }
                i++
            }
            dbg("Typed, clicking SEND")
            handler.post { trySendClick(0) }
        }
    }

    private fun waitForKey(key: String): AccessibilityNodeInfo? {
        var tries = 0
        while (tries < 6) {
            val root: AccessibilityNodeInfo = rootInActiveWindow ?: return null
            val node: AccessibilityNodeInfo? = findKeyOnKeyboard(root, key, 0)
            if (node != null) return node
            tries++
            Thread.sleep(250)
        }
        return null
    }

    private fun findKeyOnKeyboard(
        node: AccessibilityNodeInfo,
        key: String,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (depth > 20) return null
        val dh: Int = resources.displayMetrics.heightPixels
        val r = Rect()
        node.getBoundsInScreen(r)
        val cy: Int = (r.top + r.bottom) / 2
        if (cy > dh * 0.5) {
            val desc: String? = node.contentDescription?.toString()?.lowercase()?.trim()
            val txt: String? = node.text?.toString()?.lowercase()?.trim()
            if ((desc != null && desc == key.lowercase()) ||
                (txt != null && txt == key.lowercase())) {
                return node
            }
        }
        for (i in 0 until node.childCount) {
            val c: AccessibilityNodeInfo? = node.getChild(i)
            if (c != null) {
                val f: AccessibilityNodeInfo? = findKeyOnKeyboard(c, key, depth + 1)
                if (f != null) return f
            }
        }
        return null
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

    private fun findBadgedRows(
        root: AccessibilityNodeInfo
    ): List<Pair<AccessibilityNodeInfo, String>> {
        val candidates = ArrayList<Triple<AccessibilityNodeInfo, String, String>>()
        gatherRows(root, candidates, 0)
        candidates.sortBy { rowTop(it.first) }
        val dw: Int = resources.displayMetrics.widthPixels
        val out = ArrayList<Pair<AccessibilityNodeInfo, String>>()
        for (entry in candidates) {
            if (isSystemRow(entry.second)) continue
            if (scanBadge(entry.first, dw, 0)) {
                out.add(Pair(entry.first, entry.second))
            }
        }
        return out
    }

    private fun scanBadge(node: AccessibilityNodeInfo, dw: Int, depth: Int): Boolean {
        if (depth > 10) return false
        val t: String? = node.text?.toString()?.trim()
        if (t != null && t.isNotEmpty() && node.childCount == 0 &&
            t.matches(Regex("^\\d{1,2}$"))
        ) {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (((r.left + r.right) / 2) > dw * 0.65) return true
        }
        for (i in 0 until node.childCount) {
            val c: AccessibilityNodeInfo? = node.getChild(i)
            if (c != null && scanBadge(c, dw, depth + 1)) return true
        }
        return false
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
                if (name.length in 2..30 && !name.contains(":")) {
                    candidates.add(Triple(node, name, texts[1]))
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

    private fun clickRowTextArea(row: AccessibilityNodeInfo, name: String) {
        val nameNode: AccessibilityNodeInfo? = findLeafWithText(row, name, 0)
        if (nameNode != null) {
            val r = Rect()
            nameNode.getBoundsInScreen(r)
            dbg("Tapped: " + name)
            tap(((r.left + r.right) / 2).toFloat(), ((r.top + r.bottom) / 2).toFloat())
            return
        }
        val r2 = Rect()
        row.getBoundsInScreen(r2)
        dbg("Tapped (fallback)")
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
            qHandler.removeCallbacks(queueStep)
            qHandler.postDelayed(queueStep, 800)
        }
    }

    override fun onInterrupt() { }
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

    private fun cleanForKeyboard(text: String): String {
        val sb = StringBuilder()
        for (ch in text) {
            val c = ch.lowercaseChar()
            if ((c in 'a'..'z') || (c in '0'..'9')) sb.append(c)
            else if (ch == ' ' || ch == '?' || ch == '.' || ch == ',') sb.append(ch)
        }
        return sb.toString()
    }

    private fun typeAndSend(reply: String) {
        lastReply = reply
        handler.postDelayed({
            val root: AccessibilityNodeInfo? = rootInActiveWindow
            if (root == null) return@postDelayed
            val field: AccessibilityNodeInfo? = findInput(root)
            if (field == null) {
                dbg("No input field found")
                goNextOrBack()
                return@postDelayed
            }
            dbg("Opening keyboard...")
            field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val fr = Rect()
            field.getBoundsInScreen(fr)
            tap(((fr.left + fr.right) / 2).toFloat(), ((fr.top + fr.bottom) / 2).toFloat())
            handler.postDelayed({ typeWithKeyboard(cleanForKeyboard(lastReply)) }, 1300)
        }, 800)
    }

    private fun typeWithKeyboard(text: String) {
        thread {
            var i = 0
            while (i < text.length) {
                val ch = text[i]
                val keyName: String = if (ch == ' ') "space" else ch.toString()
                val key: AccessibilityNodeInfo? = waitForKey(keyName)
                if (key == null) {
                    dbg("Key not found: " + ch)
                } else {
                    val r = Rect()
                    key.getBoundsInScreen(r)
                    tap(((r.left + r.right) / 2).toFloat(), ((r.top + r.bottom) / 2).toFloat())
                    Thread.sleep(120L + kotlin.random.Random.nextLong(0, 100))
                }
                i++
            }
            dbg("Typed, clicking SEND")
            handler.post { trySendClick(0) }
        }
    }

    private fun waitForKey(key: String): AccessibilityNodeInfo? {
        var tries = 0
        while (tries < 6) {
            val root: AccessibilityNodeInfo = rootInActiveWindow ?: return null
            val node: AccessibilityNodeInfo? = findKeyOnKeyboard(root, key, 0)
            if (node != null) return node
            tries++
            Thread.sleep(250)
        }
        return null
    }

    private fun findKeyOnKeyboard(
        node: AccessibilityNodeInfo,
        key: String,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (depth > 20) return null
        val dh: Int = resources.displayMetrics.heightPixels
        val r = Rect()
        node.getBoundsInScreen(r)
        val cy: Int = (r.top + r.bottom) / 2
        if (cy > dh * 0.5) {
            val desc: String? = node.contentDescription?.toString()?.lowercase()?.trim()
            val txt: String? = node.text?.toString()?.lowercase()?.trim()
            if ((desc != null && desc == key.lowercase()) ||
                (txt != null && txt == key.lowercase())) {
                return node
            }
        }
        for (i in 0 until node.childCount) {
            val c: AccessibilityNodeInfo? = node.getChild(i)
            if (c != null) {
                val f: AccessibilityNodeInfo? = findKeyOnKeyboard(c, key, depth + 1)
                if (f != null) return f
            }
        }
        return null
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

    private fun findBadgedRows(
        root: AccessibilityNodeInfo
    ): List<Pair<AccessibilityNodeInfo, String>> {
        val candidates = ArrayList<Triple<AccessibilityNodeInfo, String, String>>()
        gatherRows(root, candidates, 0)
        candidates.sortBy { rowTop(it.first) }
        val dw: Int = resources.displayMetrics.widthPixels
        val out = ArrayList<Pair<AccessibilityNodeInfo, String>>()
        for (entry in candidates) {
            if (isSystemRow(entry.second)) continue
            if (scanBadge(entry.first, dw, 0)) {
                out.add(Pair(entry.first, entry.second))
            }
        }
        return out
    }

    private fun scanBadge(node: AccessibilityNodeInfo, dw: Int, depth: Int): Boolean {
        if (depth > 10) return false
        val t: String? = node.text?.toString()?.trim()
        if (t != null && t.isNotEmpty() && node.childCount == 0 &&
            t.matches(Regex("^\\d{1,2}$"))
        ) {
            val r = Rect()
            node.getBoundsInScreen(r)
            if (((r.left + r.right) / 2) > dw * 0.65) return true
        }
        for (i in 0 until node.childCount) {
            val c: AccessibilityNodeInfo? = node.getChild(i)
            if (c != null && scanBadge(c, dw, depth + 1)) return true
        }
        return false
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
                if (name.length in 2..30 && !name.contains(":")) {
                    candidates.add(Triple(node, name, texts[1]))
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

    private fun clickRowTextArea(row: AccessibilityNodeInfo, name: String) {
        val nameNode: AccessibilityNodeInfo? = findLeafWithText(row, name, 0)
        if (nameNode != null) {
            val r = Rect()
            nameNode.getBoundsInScreen(r)
            dbg("Tapped: " + name)
            tap(((r.left + r.right) / 2).toFloat(), ((r.top + r.bottom) / 2).toFloat())
            return
        }
        val r2 = Rect()
        row.getBoundsInScreen(r2)
        dbg("Tapped (fallback)")
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
            qHandler.removeCallbacks(queueStep)
            qHandler.postDelayed(queueStep, 800)
        }
    }

    override fun onInterrupt() { }
}
