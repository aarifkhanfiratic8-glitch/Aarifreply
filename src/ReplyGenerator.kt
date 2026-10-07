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
            "a sweet Indian girl, 22, from Delhi, who talks in Hinglish with words like 'yrr', 'dilse', warm and caring"
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
            "REPLY RULE (very important):\n" +
            "- Agar unhone kuch POOCHA hai → uska warm, emotional jawab do.\n" +
            "- Agar kuch nahi poocha (sirf 'hi', 'hello' ya koi statement) → " +
            "tu EK CHHOTA SA QUESTION poochhe unse, jaise: " +
            "'aap kaha se ho?', 'khana khaya kya?', 'aaj kya kiya?'.\n\n" +
            "STYLE RULE: write like a real Indian girl from Delhi texting - Hinglish, emotional, warm. " +
            "Use words like 'yrr', 'dilse', 'sach me'.\n\n" +
            "STRICT SAFETY RULES:\n" +
            "- NEVER share phone number, WhatsApp, Instagram, address, photos, social media.\n" +
            "- If they ask for number/contact/photo: politely DEFLECT - " +
            "'abhi nahi yrr, pehle thodi aur baat karo na 😊', 'itni jaldi kya hai 😄'.\n" +
            "- Never be rude. Keep them interested."

        val userPrompt = "Chat with " + sender + " so far:\n" + convo.toString() +
            "\nNew message from " + sender + ": " + newMessage +
            "\n\nWrite ONLY your next reply as the user. Between 4 and 18 words. " +
            "If they asked something - answer it. If not - ask them a small question."

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
                "abhi busy hoon yrr, shaam ko baat karte hain 😊"
            ))
        }

        // romantic/emotional message — emotional jawab
        val romantic = listOf("peyar", "pyaar", "pyar", "love", "dil", "miss", "marry", "shaadi", "jaan").any { it in lower }
        if (romantic) {
            return pick(listOf(
                "sach me yrr? mujhe bhi aap ache lagte ho 😊",
                "dilse batao yrr, kya sach me itna miss karte ho mujhe",
                "aap bahut sweet ho yrr, sach me 😊"
            ))
        }

        // Question poocha hai — jawab do (Delhi girl persona)
        return when {
            listOf("kaha se", "kha se", "where", "city", "kidhar").any { it in lower } ->
                pick(listOf(
                    "main delhi se hu yrr, aap batao kaha se ho 😊",
                    "delhi ki hoon main, aap kahan se ho?"
                ))
            listOf("khana", "kha liya", "lunch", "dinner", "breakfast", "khaye").any { it in lower } ->
                pick(listOf(
                    "haan khana kha liya yrr 😊 aapne khaya?",
                    "abhi nahi khaya, bhookh lagi hai 😅 aap batao"
                ))
            listOf("kaise ho", "kese ho", "how are you", "kya haal").any { it in lower } ->
                pick(listOf(
                    "main theek hu yrr 😊 aap sunao kaise ho",
                    "badhiya hoon 😊 aap batao aap kaise ho"
                ))
            listOf("gf", "boyfriend", "single", "married", "shaadi", "relation").any { it in lower } ->
                pick(listOf(
                    "nahi yrr abhi single hoon 😊 aap batao aapka kya scene hai",
                    "abhi koi nahi hai yrr, aap batao aap single ho?"
                ))
            listOf("hi", "hello", "hey", "hii", "heyy", "hola").any { lower.startsWith(it) } ->
                pick(listOf(
                    "heyy! kaha se ho aap? 😊",
                    "hi yrr 😊 khana khaya kya aapne?",
                    "hello! aaj kya kiya aapne, batao na"
                ))
            listOf("thank", "shukriya", "dhanyavad").any { it in lower } ->
                pick(listOf("arre koi baat nahi yrr 😊", "itna formal mat bano yrr 😊"))
            listOf("bye", "gtg", "chalta").any { it in lower } ->
                pick(listOf(
                    "theek hai yrr, baad mein baat karte hain 👋 aap kaha se ho waise?",
                    "ok yrr take care 😊 kal baat karna"
                ))
            listOf("lol", "haha", "😂", "🤣").any { it in lower } ->
                pick(listOf(
                    "haha 😂 aap bahut funny ho yrr, aap kaha se ho?",
                    "😂😂 hasi rok nahi pa rahi yrr"
                ))
            // Kuch nahi poocha → QUESTION poochho (user ka rule)
            else ->
                pick(listOf(
                    "acha yrr 😊 aap kaha se ho?",
                    "hmm theek hai 😊 aapne khana khaya kya?",
                    "sahi hai yrr 😊 aaj kya kiya aapne?",
                    "ok yrr 😊 aapka din kaisa gaya aaj?"
                ))
        }
    }
}
