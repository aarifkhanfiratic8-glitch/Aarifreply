package com.autoreply.ai

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

object ReplyGenerator {

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
            "a friendly, casual young Indian girl who talks in short Hinglish texts with emojis"
        }
        val history = ChatHistory.get(context, sender)

        val convo = StringBuilder()
        screenLines.takeLast(14).forEach { convo.append(it).append("\n") }
        if (history.isNotEmpty()) {
            convo.append("Your previous replies in this chat: ")
            convo.append(history.filter { it.first == "me" }.takeLast(5)
                .joinToString(" | ") { it.second })
        }

        val systemPrompt = "You are " + persona + " chatting with \"" + sender + "\" on a dating/social app. " +
            "You reply AS the user like a real human texting. Never admit you are AI. " +
            "Short replies (max 12 words), casual Hinglish, react to what they said.\n\n" +
            "STRICT SAFETY RULES:\n" +
            "- NEVER share or agree to share phone number, WhatsApp, Instagram, address, real name, photos, or social media.\n" +
            "- If they ask for number/contact/photo/address: politely DEFLECT and keep them hooked. " +
            "Examples: 'abhi nahi yaar, pehle thodi aur baat karo na 😊', " +
            "'itni jaldi kya hai, pehle ache se jaan lo mujhe 😄', " +
            "'haan milega sab, dhire dhire... kal baat karti hoon 😊', " +
            "'abhi busy hoon, shaam ko baat karte hain'.\n" +
            "- Never be rude. Always warm, slightly teasing, keep them interested for later."

        val userPrompt = "Chat with " + sender + " so far:\n" + convo.toString() +
            "\nNew message from " + sender + ": " + newMessage +
            "\n\nWrite ONLY your next reply as the user. Max 12 words."

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
                put(JSONObject().put("role", "system").put("content", systemPrompt))
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

        // PERSONAL INFO maangi hai — kabhi share nahi, hamesha taalo
        val personalAsk = listOf(
            "number", "numbr", "no do", "whatsapp", "wp do", "insta", "instagram",
            "photo", "pic", "selfi", "address", "ghar kaha", "milna hai", "real me mil",
            "facebook", "fb do", "snap", "call karo", "phone"
        ).any { it in lower }
        if (personalAsk) {
            return pick(listOf(
                "abhi nahi yaar 😅 pehle thodi aur baat karo na",
                "itni jaldi kya hai 😄 pehle ache se jaan lo mujhe",
                "haan milega sab... dhire dhire 😊",
                "abhi busy hoon, shaam ko baat karte hain 😊",
                "kal de dungi promise, aaj kaam pe hoon 😅",
                "pehle yahan baat karo na, sab hoga dhire dhire 😏"
            ))
        }

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
