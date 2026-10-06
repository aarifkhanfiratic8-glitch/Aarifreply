package com.autoreply.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

object ChatHistory {
    private const val FILE = "chat_history"
    private const val MAX_TURNS = 40

    private fun store(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    @Synchronized
    fun get(c: Context, sender: String): MutableList<Pair<String, String>> {
        val raw = store(c).getString("data", "{}") ?: "{}"
        val root = JSONObject(raw)
        if (!root.has(sender)) return mutableListOf()
        val arr = root.getJSONArray(sender)
        val list = mutableListOf<Pair<String, String>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(o.getString("role") to o.getString("text"))
        }
        return list
    }

    @Synchronized
    fun add(c: Context, sender: String, role: String, text: String) {
        if (text.isBlank()) return
        val list = get(c, sender)
        if (list.lastOrNull()?.let { it.first == role && it.second == text } == true) return
        list.add(role to text)
        while (list.size > MAX_TURNS) list.removeAt(0)
        save(c, sender, list)
    }

    @Synchronized
    fun lastMeText(c: Context, sender: String): String? =
        get(c, sender).lastOrNull { it.first == "me" }?.second

    @Synchronized
    private fun save(c: Context, sender: String, list: List<Pair<String, String>>) {
        val raw = store(c).getString("data", "{}") ?: "{}"
        val root = JSONObject(raw)
        val arr = JSONArray()
        for ((role, text) in list) arr.put(JSONObject().put("role", role).put("text", text))
        root.put(sender, arr)
        store(c).edit().putString("data", root.toString()).apply()
    }
}
