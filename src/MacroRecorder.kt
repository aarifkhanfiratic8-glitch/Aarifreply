package com.autoreply.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

// [MACRO RECORDER v5]
// REC ON -> sirf TAPS record hote hain (type/typing KABHI record nahi hota).
// Replies hamesha Queue+Memory se aate hain — naqsh sirf "rasta" hai.
// PLAY = naqsh chalao. Observe mode mein har step pe gate check:
// naya msg / app chhodi / OFF -> turant ruk.
class MacroRecorder(private val service: AccessibilityService) {

    private val PREFS = "macros"

    @Volatile var isRecording: Boolean = false
        private set
    @Volatile var isPlaying: Boolean = false
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var steps = JSONArray()
    private var lastActionAt: Long = 0L
    private var currentPkg: String = ""

    // ---------------- RECORD ----------------

    fun startRecording(pkg: String) {
        steps = JSONArray()
        lastActionAt = 0L
        currentPkg = pkg
        isRecording = true
        ObserverLog.log(service, "REC START " + pkg)
    }

    fun stopRecording(name: String): Int {
        isRecording = false
        val count = steps.length()
        if (count > 0 && name.isNotBlank()) {
            try {
                val sp = service.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val e = sp.edit()
                e.putString("macro_" + currentPkg + "_" + name, steps.toString())
                e.putString("macro_" + name, steps.toString())
                e.putString("macro_last_pkg", currentPkg)
                e.commit()
                ObserverLog.log(service, "REC SAVE " + name + " steps=" + count + " pkg=" + currentPkg)
            } catch (ex: Exception) {
                ObserverLog.log(service, "REC SAVE ERR " + ex.message)
            }
        } else {
            ObserverLog.log(service, "REC SAVE SKIP steps=" + count)
        }
        return count
    }

    fun stepCount(): Int = steps.length()

    fun hasAnyMacro(): Boolean {
        val sp = service.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sp.all.keys.any { it.startsWith("macro_") && it != "macro_last_pkg" }
    }

    fun onEvent(event: AccessibilityEvent) {
        if (!isRecording) return
        if (isPlaying) return
        if (event.packageName?.toString() == service.packageName) return
        // SIRF target app ke events yahan tak pahunchte hain (service filter)

        val src: AccessibilityNodeInfo = event.source ?: return
        val now = System.currentTimeMillis()
        val gap = now - lastActionAt
        lastActionAt = now

        if (gap > 400 && steps.length() > 0) {
            steps.put(JSONObject().put("t", "wait").put("ms", gap))
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val r = Rect()
                src.getBoundsInScreen(r)
                steps.put(
                    JSONObject()
                        .put("t", "tap")
                        .put("x", (r.left + r.right) / 2)
                        .put("y", (r.top + r.bottom) / 2)
                        .put("desc", src.contentDescription?.toString() ?: "")
                        .put("text", src.text?.toString() ?: "")
                )
            }
            // TYPE_VIEW_TEXT_CHANGED jaan-boojh kar skip: typing naqsh mein nahi jati
        }
    }

    // ---------------- PLAY (interruptible) ----------------

    fun play(
        pkg: String,
        name: String,
        gate: (() -> Boolean)? = null,
        onDone: ((Boolean) -> Unit)? = null
    ) {
        if (isPlaying) return
        val sp = service.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        var raw: String = sp.getString("macro_" + pkg + "_" + name, "") ?: ""
        var usedKey = "macro_" + pkg + "_" + name

        if (raw.isBlank()) {
            raw = sp.getString("macro_" + name, "") ?: ""
            if (raw.isNotBlank()) usedKey = "macro_" + name
        }

        if (raw.isBlank()) {
            try {
                for (k in sp.all.keys) {
                    if (k.endsWith("_" + name) && k.startsWith("macro_")) {
                        raw = sp.getString(k, "") ?: ""
                        if (raw.isNotBlank()) { usedKey = k; break }
                    }
                }
            } catch (e: Exception) { }
        }

        if (raw.isBlank()) {
            val saved = sp.all.keys.filter { it.startsWith("macro_") }.joinToString(",")
            ObserverLog.log(service, "PLAY NONE name=" + name + " saved=[" + saved + "]")
            onDone?.invoke(false)
            return
        }
        ObserverLog.log(service, "PLAY " + usedKey + " len=" + raw.length)
        isPlaying = true
        runSteps(JSONArray(raw), 0, gate, onDone)
    }

    fun stopPlay() {
        isPlaying = false
        ObserverLog.log(service, "PLAY STOP")
    }

    private fun runSteps(
        arr: JSONArray,
        index: Int,
        gate: (() -> Boolean)?,
        onDone: ((Boolean) -> Unit)?
    ) {
        if (!isPlaying) { onDone?.invoke(false); return }

        if (gate != null) {
            val ok = try { gate() } catch (e: Exception) { false }
            if (!ok) {
                isPlaying = false
                ObserverLog.log(service, "PLAY GATE-PAUSE at " + index)
                onDone?.invoke(false)
                return
            }
        }

        if (index >= arr.length()) {
            isPlaying = false
            ObserverLog.log(service, "PLAY DONE")
            onDone?.invoke(true)
            return
        }
        val s = arr.getJSONObject(index)
        when (s.optString("t")) {
            "wait" -> {
                val ms = s.optLong("ms", 500).coerceIn(100, 5000)
                handler.postDelayed({ runSteps(arr, index + 1, gate, onDone) }, ms)
            }
            "tap" -> {
                tapStep(s)
                handler.postDelayed({ runSteps(arr, index + 1, gate, onDone) }, 450)
            }
            else -> runSteps(arr, index + 1, gate, onDone)
        }
    }

    private fun tapStep(s: JSONObject) {
        try {
            val desc = s.optString("desc")
            val txt = s.optString("text")
            if (desc.isNotBlank() || txt.isNotBlank()) {
                val root = service.rootInActiveWindow
                val n = root?.let { findMatch(it, desc, txt, 0) }
                if (n != null) {
                    if (n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
                }
            }
        } catch (e: Exception) { }
        tap(s.optInt("x"), s.optInt("y"))
    }

    private fun findMatch(
        node: AccessibilityNodeInfo,
        desc: String,
        txt: String,
        depth: Int
    ): AccessibilityNodeInfo? {
        if (depth > 16) return null
        try {
            if (desc.isNotBlank() && node.contentDescription?.toString() == desc) return node
            if (txt.isNotBlank() && node.text?.toString() == txt) return node
        } catch (e: Exception) { }
        for (i in 0 until node.childCount) {
            val c: AccessibilityNodeInfo? = node.getChild(i)
            if (c != null) {
                val f = findMatch(c, desc, txt, depth + 1)
                if (f != null) return f
            }
        }
        return null
    }

    private fun tap(x: Int, y: Int) {
        try {
            val p = Path()
            p.moveTo(x.toFloat(), y.toFloat())
            val g: GestureDescription = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(p, 0, 80))
                .build()
            service.dispatchGesture(g, null, null)
        } catch (e: Exception) { }
    }
}
