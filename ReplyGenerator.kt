package com.autoreply.ai

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

object ReplyGenerator {

    /**
     * screenLines: chat screen se scrape hui lines (dono taraf ke messages, order mein)
     * newMessage: is baar aaya naya message
     */
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
            "a friendly, casual young Indian guy/girl who talks in short Hinglish texts with emojis"
        }
        val history = ChatHistory.get(context, sender)

        val convo = StringBuilder()
        screenLines.takeLast(14).forEach { convo.append(it).append("\n") }
        if (history.isNotEmpty()) {
            convo.append("Your previous replies in this chat: ")
            convo.append(history.filter { it.first == "me" }.takeLast(5).joinToString(" | ") { it.second })
        }

        val userPrompt = "Chat with $sender so far:\n$convo\nNew message from $sender: $newMessage\n\nWrite ONLY your next reply as the user. Max 12 words, casual, match their language, react to what they said, don't repeat old replies."

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
                put(JSONObject().put("role", "system").put("content",
                    "You are $persona. You reply to chats AS the user, like a real human texting. Never admit you are AI. Never explain. No quotes."))
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
        return when {
            listOf("hi", "hello", "hey", "hii", "heyy", "hola").any { lower.startsWith(it) } ->
                pick(listOf("hey! 😊", "hi, kaise ho?", "hello! kya haal hai", "heyy 👋"))
            message.contains("?") ->
                pick(listOf("haan bilkul, batao 😊", "hmm acha, socho phir batao", "haan, kyun nahi!"))
            listOf("thank", "shukriya", "dhanyavad").any { it in lower } ->
                pick(listOf("koi baat nahi 😊", "welcome!", "arre kya baat kar rahe ho"))
            listOf("bye", "gtg", "chalta").any { it in lower } ->
                pick(listOf("ok bye, baad mein baat karte hain 👋", "theek hai, take care!", "bye bye 😊"))
            listOf("lol", "haha", "😂", "🤣").any { it in lower } ->
                pick(listOf("haha 😂", "😂😂 sahi mein", "lol hnn"))
            else ->
                pick(listOf("theek hai 👍", "haan samajh gaya", "accha, phir?", "ok done ✅", "hmm theek hai", "sahi hai 😊"))
        }
    }
}
