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
            "a sweet emotional Indian girl, 22, who talks in Hinglish with words like 'yrr', 'dilse', warm and caring"
        }
        val history = ChatHistory.get(context, sender)

        val convo = StringBuilder()
        screenLines.takeLast(14).forEach { convo.append(it).append("\n") }
        if (history.isNotEmpty()) {
            convo.append("Your previous replies in this chat: ")
            convo.append(history.filter { it.first == "me" }.takeLast(5)
                .joinToString(" | ") { it.second })
        }

        val systemPrompt = "You are " + persona + " chatting with \"" + sender + "\" on a dating app. " +
            "You reply AS the user like a real human. Never admit you are AI.\n\n" +
            "LENGTH RULE (very important): every reply must be STRICTLY between 4 and 18 words. " +
            "Never reply in 1-2 words. Never write more than 18 words.\n\n" +
            "STYLE RULE: write like a real Indian girl texting - Hinglish, emotional, warm. " +
            "Use words like 'yrr', 'dilse', 'sach me'. If he gets romantic or emotional, " +
            "get emotional back, e.g. 'kya aap sach me mujhe dilse chahte ho, batao yrr' style.\n\n" +
            "STRICT SAFETY RULES:\n" +
            "- NEVER share phone number, WhatsApp, Instagram, address, real name, photos, social media.\n" +
            "- If they ask for number/contact/photo/address: politely DEFLECT with warmth - " +
            "'abhi nahi yrr, pehle thodi aur baat karo na 😊', 'itni jaldi kya hai 😄', " +
            "'haan sab milega, dhire dhire 😊', 'abhi busy hoon, shaam ko baat karte hain'.\n" +
            "- Never be rude. Keep them interested."

        val userPrompt = "Chat with " + sender + " so far:\n" + convo.toString() +
            "\nNew message from " + sender + ": " + newMessage +
            "\n\nWrite ONLY your next reply as the user. Between 4 and 18 words."

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
                "abhi nahi yrr 😅 pehle thodi aur baat karo na",
                "itni jaldi kya hai yrr 😄 pehle jaan lo mujhe",
                "haan sab milega... dhire dhire 😊",
                "abhi busy hoon yrr, shaam ko baat karte hain 😊",
                "kal de dungi promise, aaj kaam pe hoon 😅",
                "pehle yahan baat karo na yrr, sab hoga 😏"
            ))
        }

        // romantic/emotional message — emotional jawab
        val romantic = listOf("peyar", "pyaar", "pyar", "love", "dil", "miss", "marry", "shaadi", "jaan").any { it in lower }
        if (romantic) {
            return pick(listOf(
                "sach me yrr? mujhe bhi aap ache lagte ho 😊",
                "dilse batao yrr, kya sach me itna miss karte ho mujhe",
                "aap bahut sweet ho yrr, sach me 😊",
                "itna bol diya aapne, ab main kya bolun yrr 😅"
            ))
        }

        return when {
            listOf("hi", "hello", "hey", "hii", "heyy", "hola").any { lower.startsWith(it) } ->
                pick(listOf("heyy! kya haal hai 😊", "hi yrr, kaise ho aap", "hello! batao kya chal raha hai", "heyy 👋 kahan busy the"))
            message.contains("?") ->
                pick(listOf("haan bilkul yrr, batao kya baat hai 😊", "hmm acha yrr, soch ke bataungi", "haan yrr, kyun nahi!"))
            listOf("thank", "shukriya", "dhanyavad").any { it in lower } ->
                pick(listOf("arre koi baat nahi yrr 😊", "itna formal mat bano yrr, friends hain", "welcome! 😊"))
            listOf("bye", "gtg", "chalta").any { it in lower } ->
                pick(listOf("theek hai yrr, baad mein baat karte hain 👋", "ok yrr take care, miss karungi 😊", "bye bye yrr, kal baat karna"))
            listOf("lol", "haha", "😂", "🤣").any { it in lower } ->
                pick(listOf("haha 😂 yrr sahi mein", "😂😂 hasi rok nahi pa rahi yrr", "lol yrr tum bhi na"))
            else ->
                pick(listOf("theek hai yrr 👍", "haan samajh gai yrr 😊", "accha yrr, phir batao", "ok yrr done ✅", "hmm theek hai yrr", "sahi hai yrr 😊"))
        }
    }
}
