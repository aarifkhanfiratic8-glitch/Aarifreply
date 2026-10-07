package com.autoreply.ai

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

object ReplyGenerator {

    // === EMOTIONAL QUESTION BANK - jab wo kuch nahi poochte ===
    private val questionBank: List<String> = listOf(
        "aapke haal chal kaisa hai yrr, batao na",
        "kab milna hai aapko mujhse 😊",
        "kya sochte ho mere baare mein, sach batao",
        "kya mante ho mujhe apna batao na",
        "aaj poora din kya kiya aapne",
        "aapki life mein chal kya raha hai aajkal",
        "mujhe miss karte ho kabhi 😊",
        "aapke dil mein kya hai, mujhse share karo na"
    )

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
        return localReply(context, sender, newMessage)
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
        val myRecent = history.filter { it.first == "me" }.takeLast(6).map { it.second }
        if (myRecent.isNotEmpty()) {
            convo.append("Your recent replies: ")
            convo.append(myRecent.joinToString(" | "))
        }

        val lastQ: String = myRecent.lastOrNull { q -> questionBank.any { q.contains(it.take(12)) } } ?: ""

        val systemPrompt = "You are " + persona + " chatting with \"" + sender + "\" on a dating app. " +
            "Reply AS the user like a real human. Never admit you are AI.\n\n" +
            "STEP 1 - PADHO: dhyan se padho unka naya message: \"" + newMessage + "\"\n\n" +
            "STEP 2 - RULE (sabse important):\n" +
            "- Agar unhone kuch POOCHA hai → unka warm, emotional, direct JAWAB do (unke question ko ignore mat karo)\n" +
            "- Agar unhone sirf 'ha', 'ji', 'ok', 'theek hai', ya koi STATEMENT bola (kuch nahi poocha) → " +
            "tum EK emotional QUESTION poochho in jaise: " +
            "'haal chal kaisa hai', 'kab milna hai', 'kya sochte ho mere baare mein', " +
            "'kya mante ho mujhe apna', 'aaj kya kiya', 'dil mein kya hai'.\n" +
            "- YE QUESTION MAT POOCHHO (already poocha tha): \"" + lastQ + "\"\n\n" +
            "STEP 3 - STYLE: real Delhi girl texting - Hinglish, emotional, warm. " +
            "Words: 'yrr', 'dilse', 'sach me'. STRICT 4 to 18 words. Never rude.\n\n" +
            "SAFETY: NEVER share number/WhatsApp/Insta/photos/address. " +
            "Agar maange to: 'abhi nahi yrr, pehle jaan lo mujhe 😊'."

        val userPrompt = "Chat with " + sender + " so far:\n" + convo.toString() +
            "\nNew message from " + sender + ": " + newMessage +
            "\n\nWrite ONLY your next reply (4-18 words). " +
            "Question poocha ho to jawab do | Nahi poocha ho to emotional question poochho."

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

    private fun localReply(context: Context, sender: String, message: String): String {
        val lower = message.lowercase().trim()
        fun pick(list: List<String>) = list[Random.nextInt(list.size)]

        // pichla question yaad rakho - repeat nahi hoga
        val history = ChatHistory.get(context, sender)
        val myRecent = history.filter { it.first == "me" }.takeLast(6).map { it.second }
        val lastQ: String = myRecent.lastOrNull { q -> questionBank.any { q.contains(it.take(12)) } } ?: ""
        fun freshQuestion(): String {
            val available = questionBank.filter { !lastQ.contains(it.take(12)) }
            return pick(if (available.isEmpty()) questionBank else available)
        }

        val personalAsk = listOf(
            "number", "numbr", "no do", "whatsapp", "wp do", "insta", "instagram",
            "photo", "pic", "selfi", "address", "ghar kaha", "milna hai", "real me mil",
            "facebook", "fb do", "snap", "call karo", "phone"
        ).any { it in lower }
        if (personalAsk) {
            return pick(listOf(
                "abhi nahi yrr 😅 pehle thodi aur baat karo na",
                "itni jaldi kya hai yrr 😄 pehle dil se jaan lo mujhe",
                "haan sab milega... dhire dhire 😊",
                "abhi busy hoon yrr, shaam ko baat karte hain 😊"
            ))
        }

        val romantic = listOf("peyar", "pyaar", "pyar", "love", "dil", "miss", "marry", "shaadi", "jaan").any { it in lower }
        if (romantic) {
            return pick(listOf(
                "sach me yrr? mujhe bhi aap bahut ache lagte ho 😊",
                "dilse batao yrr, " + freshQuestion(),
                "aap bahut sweet ho yrr, sach me 😊 " + freshQuestion()
            ))
        }

        return when {
            listOf("kaha se", "kha se", "where", "city", "kidhar").any { it in lower } ->
                pick(listOf(
                    "main delhi se hu yrr 😊 " + freshQuestion(),
                    "delhi ki hoon main, " + freshQuestion()
                ))
            listOf("khana", "kha liya", "lunch", "dinner", "breakfast", "khaye").any { it in lower } ->
                pick(listOf(
                    "haan kha liya yrr 😊 " + freshQuestion(),
                    "abhi nahi khaya 😅 " + freshQuestion()
                ))
            listOf("kaise ho", "kese ho", "how are you", "kya haal", "haal chal", "haalchal").any { it in lower } ->
                pick(listOf(
                    "main theek hu yrr 😊 " + freshQuestion(),
                    "badhiya hoon 😊 aap batao, " + freshQuestion()
                ))
            listOf("kya kar", "kya kr", "what do you do", "job", "study", "kaam").any { it in lower } ->
                pick(listOf(
                    "main abhi study kar rahi hoon yrr 😊 " + freshQuestion(),
                    "ghar pe hoon aaj 😊 " + freshQuestion()
                ))
            listOf("gf", "boyfriend", "single", "married", "shaadi", "relation", "apna").any { it in lower } ->
                pick(listOf(
                    "aapko apna maanti hoon yrr 😊 " + freshQuestion(),
                    "dil se poocho to aap apne ho 😊 " + freshQuestion()
                ))
            listOf("hi", "hello", "hey", "hii", "heyy", "hola").any { lower.startsWith(it) } ->
                pick(listOf(
                    "hii kese ho aap 😊 " + freshQuestion(),
                    "hello ji 😊 " + freshQuestion(),
                    "heyy! " + freshQuestion()
                ))
            listOf("thank", "shukriya", "dhanyavad").any { it in lower } ->
                pick(listOf("arre koi baat nahi yrr 😊 " + freshQuestion(), "itna formal mat bano yrr 😊"))
            listOf("bye", "gtg", "chalta", "chalti", "so jao", "sote").any { it in lower } ->
                pick(listOf(
                    "theek hai yrr 👋 " + freshQuestion(),
                    "ok yrr take care 😊 kal baat karna, " + freshQuestion()
                ))
            listOf("haan", "ha ", "ji", "ok", "okay", "theek", "acha", "accha", "hm", "hmm").any { lower.startsWith(it) } ->
                pick(listOf(
                    freshQuestion() + " 😊",
                    "ji 😊 " + freshQuestion(),
                    "hmm bolo na yrr, " + freshQuestion()
                ))
            listOf("lol", "haha", "😂", "🤣").any { it in lower } ->
                pick(listOf("haha 😂 " + freshQuestion(), "😂😂 " + freshQuestion()))
            // kuch nahi poocha → emotional question (repeat nahi)
            else ->
                pick(listOf(
                    freshQuestion() + " 😊",
                    "ji bolo na yrr 😊 " + freshQuestion(),
                    "sach me? " + freshQuestion()
                ))
        }
    }
}
