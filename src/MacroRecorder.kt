package com.autoreply.ai

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

// [MACRO RECORDER]
// REC ON karo -> tum Toki/WhatsApp pe jo bhi karo (click, type, wait) record hoga.
// REC dubara dabao -> macro "m1" save ho jayega (app ke hisaab se alag).
// PLAY dabao -> app wahi steps khud repeat karega.
//
// IMPORTANT: Record karte waqt queue (ON/OFF) OFF rakho,
// warna bot ke clicks bhi record ho jayenge.
class MacroRecorder(private val service: AccessibilityService) {

    companion object {
        private const val PREFS = "macros"
        @Volatile var isRecording: Boolean = false
            private set
        @Volatile var isPlaying: Boolean = false
            private set
    }

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
        ObserverLog.log(service, "REC START pkg=" + pkg)
    }

    fun stopRecording(name: String): Int {
        isRecording = false
        val count = steps.length()
        if (count > 0 && name.isNotBlank()) {
            try {
                val sp = service.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                sp.edit().putString("macro_" + currentPkg + "_" + name, steps.toString()).apply()
                ObserverLog.log(service, "REC SAVE " + name + " steps=" + count + " pkg=" + currentPkg)
            } catch (e: Exception) { }
        }
        return count
    }

    fun stepCount(): Int = steps.length()

    fun onEvent(event: AccessibilityEvent) {
        if (!isRecording) return
        if (isPlaying) return
        // apne app (overlay buttons) ke clicks record nahi karte
        if (event.packageName?.toString() == service.packageName) return

        val src: AccessibilityNodeInfo = event.source ?: return
        val now = System.currentTimeMillis()
        val gap = now - lastActionAt
        lastActionAt = now

        // do actions ke beech ka gap = wait step
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
                ObserverLog.log(service, "REC tap x=" + ((r.left + r.right) / 2) + " y=" + ((r.top + r.bottom) / 2))
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                if (src.isEditable) {
                    val txt = src.text?.toString() ?: return
                    // same typing ka har letter alag step na ban jaye - last update karo
                    if (steps.length() > 0) {
                        val last = steps.getJSONObject(steps.length() - 1)
                        if (last.optString("t") == "type") {
                            last.put("text", txt)
                            return
                        }
                    }
                    steps.put(JSONObject().put("t", "type").put("text", txt))
                    ObserverLog.log(service, "REC type: " + txt.take(30))
                }
            }
        }
    }

    // ---------------- PLAY ----------------

    fun play(pkg: String, name: String, onDone: ((Boolean) -> Unit)? = null) {
        if (isPlaying) return
        val raw = try {
            val sp = service.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            sp.getString("macro_" + pkg + "_" + name, "") ?: ""
        } catch (e: Exception) { "" }
        if (raw.isBlank()) {
            ObserverLog.log(service, "PLAY macro nahi mila: " + name + " pkg=" + pkg)
            onDone?.invoke(false)
            return
        }
        ObserverLog.log(service, "PLAY START " + name + " pkg=" + pkg)
        isPlaying = true
        runSteps(JSONArray(raw), 0, onDone)
    }

    fun stopPlay() {
        isPlaying = false
        ObserverLog.log(service, "PLAY STOP")
    }

    private fun runSteps(arr: JSONArray, index: Int, onDone: ((Boolean) -> Unit)?) {
        if (!isPlaying) { onDone?.invoke(false); return }
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
                handler.postDelayed({ runSteps(arr, index + 1, onDone) }, ms)
            }
            "tap" -> {
                tapStep(s)
                handler.postDelayed({ runSteps(arr, index + 1, onDone) }, 450)
            }
            "type" -> {
                typeStep(s.optString("text"))
                handler.postDelayed({ runSteps(arr, index + 1, onDone) }, 450)
            }
            else -> runSteps(arr, index + 1, onDone)
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

    private fun typeStep(text: String) {
        try {
            val root = service.rootInActiveWindow ?: return
            val field = findEditable(root) ?: return
            val args = Bundle()
            args.putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                text
            )
            field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) { }
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        for (i in 0 until node.childCount) {
            val c: AccessibilityNodeInfo? = node.getChild(i)
            if (c != null) {
                val f = findEditable(c)
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
