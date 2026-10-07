package com.autoreply.ai

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

object ReplyGenerator {

    // === BIG HISTORY: 500 messages per sender - kabhi repeat nahi hoga ===
    private object BigHistory {
        private const val PREFS = "big_history"
        private const val CAP = 500

        fun add(context: Context, sender: String, who: String, text: String) {
            try {
                val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val key = sender.lowercase()
                val cur = sp.getString(key, "") ?: ""
                val entry = who + "|" + System.currentTimeMillis() + "|" + text.replace("\n", " ")
                val all = if (cur.isEmpty()) entry else cur + "\n" + entry
                val lines = all.split("\n")
                val keep = if (lines.size > CAP) lines.subList(lines.size - CAP, lines.size) else lines
                sp.edit().putString(key, keep.joinToString("\n")).apply()
            } catch (e: Exception) { }
        }

        fun get(context: Context, sender: String): List<Pair<String, String>> {
            try {
                val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val cur = sp.getString(sender.lowercase(), "") ?: ""
                if (cur.isEmpty()) return emptyList()
                return cur.split("\n").mapNotNull { line ->
                    val parts = line.split("|", limit = 3)
                    if (parts.size == 3) Pair(parts[0], parts[2]) else null
                }
            } catch (e: Exception) { return emptyList() }
        }

        fun myMessages(context: Context, sender: String): List<String> =
            get(context, sender).filter { it.first == "me" }.map { it.second }
    }

    private val questionBank: List<String> = listOf(
        "aapke haal chal kaisa hai yrr, batao na",
        "kab milna hai aapko mujhse 😊",
        "kya sochte ho mere baare mein, sach batao",
        "kya mante ho mujhe apna batao na",
        "aaj poora din kya kiya aapne",
        "aapki life mein chal kya raha hai aajkal",
        "mujhe miss karte ho kabhi 😊",
        "aapke dil mein kya hai, mujhse share karo na",
        "aapka bachpan kaisa tha, batao na",
        "kya sapne hain aapke, mujhse batao"
    )

    fun generate(
        context: Context,
        sender: String,
        screenLines: List<String>,
        newMessage: String
    ): String? {
        if (newMessage.isBlank()) return null
        BigHistory.add(context, sender, "them", newMessage)

        val apiKey = Prefs.apiKey(context)
        val reply: String? = if (apiKey.isNotBlank()) {
            try {
                askOpenAI(context, apiKey, sender, screenLines, newMessage)
            } catch (e: Exception) { null }
        } else null

        val final: String = reply ?: localReply(context, sender, newMessage)
        BigHistory.add(context, sender, "me", final)
        return final
    }

    private fun askOpenAI(
        context: Context,
        apiKey: String,
        sender: String,
        screenLines: List<String>,
        newMessage: String
    ): String {
        val persona = Prefs.persona(context).ifBlank {
            "a sweet Indian girl, 27, from Delhi, fashion design teacher, divorced, fun-loving, thodi naughty, Hinglish"
        }

        val fullHistory = BigHistory.get(context, sender).takeLast(30)
        val histText = fullHistory.joinToString("\n") {
            if (it.first == "me") "You: " + it.second else "Them: " + it.second
        }
        val myMsgs = BigHistory.myMessages(context, sender)
        val alreadyAsked = myMsgs.takeLast(40)

        val systemPrompt = "You are " + persona + " chatting with \"" + sender + "\". " +
            "Reply AS her like a real human. Never admit you are AI.\n\n" +
            "RULE 1 - PEHLE JAWAB, PHIR SAWAAL: unka message dhyan se padho: \"" + newMessage + "\"\n" +
            "- Agar unhone sawaal poocha ya kuch maanga (jaise 'bolo na', 'kya chahte ho', 'kya kar rahi ho', 'kahan ho', '?') → " +
            "PEHLE uska warm jawab do, phir ek chhota question\n" +
            "Jawab examples: 'bolo na' → pehle apne baare me kuch batao (academy, din kaisa gaya) phir question | " +
            "'kya chahte ho' → 'bas tumse dosti karni hai yrr, tum ache lagte ho' | " +
            "'kya kar rahi ho' → 'tumse hi baat kar rahi hoon 😊'\n" +
            "- Agar kuch nahi poocha → emotional question poochho (haal chal, kab milna, kya sochte ho mere baare me)\n\n" +
            "RULE 2 - KABHI REPEAT MAT KARO. Ye messages tum pehle bhej chuki ho:\n" +
            alreadyAsked.joinToString(" | ") + "\n" +
            "In jaise messages MAT bhejo. Naya aur fresh bolo.\n\n" +
            "RULE 3 - STYLE: Hinglish, warm, emotional, thodi naughty but decent. " +
            "Words: 'yrr', 'dilse', 'sach me'. STRICT 4 to 18 words. Unka naam kabhi-kabhi bulaao.\n\n" +
            "SAFETY: number/WhatsApp/Insta/photos kabhi share mat karo."

        val userPrompt = "Full chat history:\n" + histText +
            "\n\nNew message from " + sender + ": " + newMessage +
            "\n\nReply 4-18 words ONLY. Jawab pehle, question baad me."

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

        val myMsgs = BigHistory.myMessages(context, sender)
        fun freshQuestion(): String {
            val fresh = questionBank.filter { q -> myMsgs.none { m -> m.contains(q.take(12)) } }
            return pick(if (fresh.isEmpty()) questionBank else fresh)
        }

        // === RULE 1: PEHLE JAWAB - sawaal/maang wale messages ===
        val wantsUsToTalk = listOf("bolo na", "bolo", "aap bolo", "tum bolo", "batao na", "batao kuch", "aap batao").any { it in lower }
        if (wantsUsToTalk) {
            return pick(listOf(
                "acha suno, aaj academy me thodi masti hui 😊 " + freshQuestion(),
                "main yahi hoon na tumhare liye yrr 😊 " + freshQuestion(),
                "hmm tumse baat karke din acha jata hai mera 😊 " + freshQuestion()
            ))
        }

        val asksIntent = listOf("kiya chahte", "kya chahte", "kya chahiye", "kya chahti").any { it in lower }
        if (asksIntent) {
            return pick(listOf(
                "bas tumse baat karke dil khush hota hai mera 😊 tum ache lagte ho, " + freshQuestion(),
                "kuch nahi yrr, bas tum jaise insaan se dosti karni hai 😊 " + freshQuestion(),
                "tumse milna chahti hoon kabhi, sach me 😊 " + freshQuestion()
            ))
        }

        val asksDoing = listOf("kya kar rahi", "kya kr rhi", "kya kar rahe ho aap", "kya ho raha", "kya kr rhi ho").any { it in lower }
        if (asksDoing) {
            return pick(listOf(
                "bas tumse hi baat kar rahi hoon yrr 😊 " + freshQuestion(),
                "abhi free thi, tumhari yaad aa gayi 😊 " + freshQuestion(),
                "kuch nahi, tumhara wait kar rahi thi 😊 " + freshQuestion()
            ))
        }

        val hasQuestionMark = lower.contains("?")
        if (hasQuestionMark) {
            return pick(listOf(
                "sach batau? tumse baat karna hi sabse acha lagta hai 😊 " + freshQuestion(),
                "hmm soch rahi hoon... pehle tum apna batao na 😊 " + freshQuestion(),
                "tum jo pooch rahe ho, dilse jawab dungi 😊 " + freshQuestion()
            ))
        }

        // === existing intents ===
        val personalAsk = listOf(
            "number", "numbr", "no do", "whatsapp", "wp do", "insta", "instagram",
            "photo", "pic", "selfi", "address", "ghar kaha", "real me mil",
            "facebook", "fb do", "snap", "call karo", "phone"
        ).any { it in lower }
        if (personalAsk) {
            return pick(listOf(
                "abhi nahi yrr 😅 pehle dil se jaan lo mujhe",
                "itni jaldi kya hai yrr 😄 " + freshQuestion(),
                "haan sab milega... dhire dhire 😊 " + freshQuestion()
            ))
        }

        val romantic = listOf("peyar", "pyaar", "pyar", "love", "dil", "miss", "marry", "shaadi", "jaan").any { it in lower }
        if (romantic) {
            return pick(listOf(
                "sach me yrr? mujhe bhi aap bahut ache lagte ho 😊 " + freshQuestion(),
                "dilse batau to tum special ho mere liye 😊 " + freshQuestion()
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
            listOf("kaise ho", "kese ho", "how are you", "kya haal", "haal chal").any { it in lower } ->
                pick(listOf(
                    "main theek hu yrr 😊 " + freshQuestion(),
                    "badhiya hoon 😊 " + freshQuestion()
                ))
            listOf("job", "study", "kaam kya", "teacher", "academy").any { it in lower } ->
                pick(listOf(
                    "main fashion design teacher hoon yrr, academy me 😊 " + freshQuestion(),
                    "teacher hoon, fashion design ki 😊 " + freshQuestion()
                ))
            listOf("gf", "boyfriend", "single", "married", "shaadi", "relation").any { it in lower } ->
                pick(listOf(
                    "dil se poocho to aap apne lagte ho 😊 " + freshQuestion(),
                    "abhi sirf tumse baat karna chahti hoon 😊 " + freshQuestion()
                ))
            listOf("hi", "hello", "hey", "hii", "heyy", "hola").any { lower.startsWith(it) } ->
                pick(listOf(
                    "hii kese ho aap 😊 " + freshQuestion(),
                    "hello ji 😊 " + freshQuestion(),
                    "heyy! " + freshQuestion()
                ))
            listOf("thank", "shukriya", "dhanyavad").any { it in lower } ->
                pick(listOf("arre koi baat nahi yrr 😊 " + freshQuestion()))
            listOf("bye", "gtg", "chalta", "chalti", "so jao", "sote").any { it in lower } ->
                pick(listOf(
                    "theek hai yrr 👋 " + freshQuestion(),
                    "ok yrr take care 😊 kal baat karna"
                ))
            listOf("haan", "ha ", "han ", "ji", "ok", "okay", "theek", "acha", "accha", "hm", "hmm").any { lower.startsWith(it) } ->
                pick(listOf(
                    freshQuestion() + " 😊",
                    "ji 😊 " + freshQuestion(),
                    "hmm bolo na yrr, " + freshQuestion()
                ))
            listOf("lol", "haha", "😂", "🤣").any { it in lower } ->
                pick(listOf("haha 😂 " + freshQuestion(), "😂😂 " + freshQuestion()))
            else ->
                pick(listOf(
                    freshQuestion() + " 😊",
                    "ji bolo na yrr 😊 " + freshQuestion(),
                    "sach me? " + freshQuestion()
                ))
        }
    }
}
