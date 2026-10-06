package com.autoreply.ai

import android.content.Context
import kotlin.random.Random

object Prefs {
    const val FILE = "prefs"

    val SUPPORTED = linkedMapOf(
        "com.whatsapp" to "WhatsApp",
        "com.whatsapp.w4b" to "WhatsApp Business",
        "org.telegram.messenger" to "Telegram",
        "com.instagram.android" to "Instagram",
        "com.facebook.orca" to "Messenger"
    )

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun masterEnabled(c: Context) = sp(c).getBoolean("master", true)

    fun contextMode(c: Context) = sp(c).getBoolean("context", true)

    fun persona(c: Context) = sp(c).getString("persona", "") ?: ""

    fun isPackageEnabled(c: Context, pkg: String): Boolean {
        val set = sp(c).getStringSet("apps", setOf("com.whatsapp")) ?: return false
        if (pkg in set) return true
        val custom = sp(c).getString("customApps", "") ?: ""
        return custom.split(",").any { it.trim() == pkg }
    }

    fun humanDelayMs(c: Context): Long {
        if (!sp(c).getBoolean("human", true)) return 500
        val min = sp(c).getInt("minDelay", 2000).toLong()
        val max = sp(c).getInt("maxDelay", 8000).toLong()
        val lo = min.coerceAtMost(max)
        val hi = max.coerceAtLeast(min)
        return Random.nextLong(lo, hi + 1)
    }

    fun apiKey(c: Context) = sp(c).getString("apiKey", "") ?: ""

    fun queuePkg(c: Context) = sp(c).getString("queuePkg", "com.toki.android") ?: "com.toki.android"

    fun queueIntervalMs(c: Context): Long =
        (sp(c).getInt("queueSec", 12).coerceAtLeast(5)) * 1000L
}
