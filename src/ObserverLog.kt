package com.autoreply.ai

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// [OBSERVER] Har accessibility event yahan save hota hai.
// File: filesDir/observer.log (400KB ke baad purana auto-delete)
// Overlay ke LOGS button se copy karke kahin bhi paste karke padh sakte ho.
object ObserverLog {

    private const val FILE = "observer.log"
    private const val MAX_BYTES = 400 * 1024
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun log(context: Context, line: String) {
        try {
            val f = File(context.filesDir, FILE)
            f.appendText(fmt.format(Date()) + " | " + line + "\n")
            if (f.length() > MAX_BYTES) {
                val tail = f.readText().takeLast(MAX_BYTES / 2)
                f.writeText(tail)
            }
        } catch (e: Exception) { }
    }

    fun dump(context: Context, maxLines: Int = 200): String {
        return try {
            val f = File(context.filesDir, FILE)
            if (!f.exists()) return "(log khali hai - abhi koi event nahi aaya)"
            f.readText().lines().filter { it.isNotBlank() }.takeLast(maxLines).joinToString("\n")
        } catch (e: Exception) { "(log read error)" }
    }

    fun clear(context: Context) {
        try { File(context.filesDir, FILE).delete() } catch (e: Exception) { }
    }
}
