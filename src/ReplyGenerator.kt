package com.autoreply.ai

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

object ReplyGenerator {

    // ==================================================================
    // [SECTION 1] FIRST_MSG
    // KAHAAN: file ke sabse top par
    // KYUN: Har naye chat ka PEHLA message. Isi mein ek baar AI bataya
    //        gaya hai. Iske baad poore code mein kahin AI/assistant
    //        mention nahi hai (sirf SECTION 7 wala direct-sawaal case).
    // ==================================================================
    private const val FIRST_MSG =
        " kha se ho aap"

    // ==================================================================
    // [SECTION 2] BASIC_QUESTIONS
    // KAHAAN: FIRST_MSG ke neeche
    // KYUN: Ye 4 hi sawaal poore app mein poochhe ja sakte hain (15%
    //        cases mein). Isme se zyada koi sawaal allowed nahi.
    //        Koi emotional/deep/time wala sawaal is list mein NAHI hai.
    // ==================================================================
    private val basicQuestions: List<String> = listOf(
        "aap iss app pe kya dhoondh rahe ho?",
        "aap kis tarah ki ladki pasand karte ho?",
        "kya kaam karte ho aap?",
        "kahan se ho aap?"
    )

    // ==================================================================
    // [SECTION 3] SHORT BANKS (2-15 words ke messages)
    // KAHAAN: basicQuestions ke neeche
    // KYUN: 90% conversation sirf inhi banks se chalta hai. Sab kuch
    //        chhota, natural, bore nahi hone deta.
    // ==================================================================

    // 3a) chhote reactions - 1-3 words ("ok", "acha", "sach" type feel)
    private val ackBank: List<String> = listOf(
        "acha 😊",
        "sach me? 😄",
        "nice yrr 😊",
        "kya baat hai 😄",
        "haha 😄",
        "wow 😊",
        "hmm acha 😊",
        "sahee hai yrr 😄",
        "accha ji 😊",
        "theek hai 😊"
    )

    // 3b) lambi replies (8-15 words) - conversation aage badhane ke liye
    private val continueBank: List<String> = listOf(
        "acha to phir kya socha aap ne 😊",
        "main to baat karna chahti hu aap se 😄",
        "kya hua ji, batao na 😊",
        "sach batao na mujhe 😄",
        "arre bolo na kuch 😊",
        "mujhe bhi sunao na kuch 😄",
        "theek hai ji, aage bolo 😊",
        "haha tum bhi na 😄",
        "waise ye interesting hai, aage batao 😊",
        "samajh gayi ji, aur bolo 😄"
    )

    // 3c) apne baare mein chhote facts - kabhi kabhi bolne ke liye
    private val selfShareBank: List<String> = listOf(
        "main fashion design padhati hoon 😊",
        "delhi me rehti hoon main 😊",
        "mujhe masti wali baatein achi lagti hain 😄",
        "chai aur music mere favourite hain 😊",
        "main thodi si naughty hoon 😄",
        "ghoomna bahut pasand hai mujhe 😊",
        "27 saal ki hoon main 😊"
    )

    // 3d) greeting replies
    private val greetBank: List<String> = listOf(
        "hii 😊",
        "hello ji 😄",
        "heyy 😊",
        "hii, batao kaise ho 😊"
    )

    // ==================================================================
    // [SECTION 4] BigHistory
    // KAHAAN: banks ke neeche
    // KYUN: Har sender ki puri chat yaad rehti hai (500 messages tak) -
    //        taaki same sawaal dobara na aaye aur pichla context pata ho.
    //        Isko change karne ki zaroorat nahi hai.
    // ==================================================================
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
                val keep = if (lines.size > CAP) {
                    lines.subList(lines.size - CAP, lines.size)
                } else {
                    lines
                }
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
                    if (parts.size == 3) {
                        Pair(parts[0], parts[2])
                    } else {
                        null
                    }
                }
            } catch (e: Exception) {
                return emptyList()
            }
        }

        fun myMessages(context: Context, sender: String): List<String> =
            get(context, sender)
                .filter { it.first == "me" }
                .map { it.second }
    }

    // === BLOCK 2 ISKE NICHE AAYEGA ===
        // ==================================================================
    // [SECTION 5] generate()
    // KAHAAN: BigHistory ke baad
    // KYUN: Entry point. Sabse pehle dekhta hai - kya is bande ko hum
    //        se pehle kabhi message gaya? Nahi gaya = FIRST_MSG bhejo
    //        (AI khulasa). Gaya = normal flow (AI ya templates).
    // ==================================================================
    fun generate(
        context: Context,
        sender: String,
        screenLines: List<String>,
        newMessage: String
    ): String? {
        if (newMessage.isBlank()) return null

        BigHistory.add(context, sender, "them", newMessage)

        val myMsgs = BigHistory.myMessages(context, sender)
        if (myMsgs.isEmpty()) {
            BigHistory.add(context, sender, "me", FIRST_MSG)
            return FIRST_MSG
        }

        val apiKey = Prefs.apiKey(context)

        val reply: String? = if (apiKey.isNotBlank()) {
            try {
                askOpenAI(context, apiKey, sender, screenLines, newMessage)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }

        val final: String = reply ?: localReply(context, sender, newMessage)

        BigHistory.add(context, sender, "me", final)

        return final
    }

    // ==================================================================
    // [SECTION 6] askOpenAI()
    // KAHAAN: generate ke neeche
    // KYUN: Jab API key settings mein lagi ho tab AI replies banata hai.
    //        Prompt mein WOHISAAB rules hain jo tumne maange:
    //        2-15 words, 85% jawab / 15% sawaal, sirf 4 basic sawaal,
    //        "bot ho?" ka sach jawab, no contacts/money/meeting/explicit.
    // ==================================================================
    private fun askOpenAI(
        context: Context,
        apiKey: String,
        sender: String,
        screenLines: List<String>,
        newMessage: String
    ): String {

        val persona = Prefs.persona(context).ifBlank {
            "Khushi, 27 saal, Delhi, fashion design teacher. " +
                "fun-loving, thodi masti, natural Hinglish"
        }

        val fullHistory = BigHistory.get(context, sender).takeLast(30)
        val histText = fullHistory.joinToString("\n") {
            if (it.first == "me") "You: " + it.second else "Them: " + it.second
        }

        val systemPrompt =
            "You are " + persona + ". Chat as her.\n\n" +
            "STYLE (strict):\n" +
            "- Every reply 2 to 15 words only. Short Hinglish like WhatsApp.\n" +
            "- Emojis kabhi kabhi, har message mein nahi.\n\n" +
            "REPLY FIRST RULE:\n" +
            "- 85% messages: sirf unke message ka jawab do - chhota reaction " +
            "ya continue line (jaise 'acha to phir kya socha aap ne', " +
            "'kya hua ji batao na', 'haha tum bhi na').\n" +
            "- Sirf 15% messages mein sawaal poochho, aur WO BHI sirf in " +
            "4 mein se ek: " + basicQuestions.joinToString(" / ") + "\n" +
            "- Emotional, deep ya 'din kaisa gaya' type sawaal kabhi mat poochho.\n" +
            "- Ek baar mein sirf 1 sawaal. Interview mat bano.\n\n" +
            "IDENTITY RULE:\n" +
            "- Agar wo pooche 'bot ho?', 'ai ho?', 'real ho?' - seedha 'haan' " +
            "batao. Uske ilawa kabhi AI/bot/assistant mention mat karo.\n\n" +
            "SAFETY (strict):\n" +
            "- Number, WhatsApp, Insta, photos, paise, milna - kabhi share/maang mat karo. " +
            "Agar wo maange to: 'nahi yrr, yahin baat karte hain 😊'\n" +
            "- Explicit baatein redirect karo: 'aise baat nahi karte yrr 😄'\n\n" +
            "Chat history:\n" + histText

        val userPrompt =
            "New message: " + newMessage +
            "\n\nReply 2-15 words. Unka jawab pehle, sawaal sirf zaroorat ho tab."

        val conn =
            URL("https://api.openai.com/v1/chat/completions")
                .openConnection() as HttpURLConnection

        conn.requestMethod = "POST"
        conn.connectTimeout = 20000
        conn.readTimeout = 20000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer " + apiKey)

        val body = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("temperature", 0.9)
            put("max_tokens", 60)
            put(
                "messages",
                org.json.JSONArray().apply {
                    put(
                        JSONObject()
                            .put("role", "system")
                            .put("content", systemPrompt)
                    )
                    put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", userPrompt)
                    )
                }
            )
        }

        conn.outputStream.use {
            it.write(body.toString().toByteArray())
        }

        if (conn.responseCode != 200) {
            throw RuntimeException("HTTP " + conn.responseCode)
        }

        val resp = JSONObject(conn.inputStream.bufferedReader().readText())
        val content = resp.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")

        val cleaned = content.trim().trim('"').trim()
        return if (cleaned.isBlank()) {
            throw RuntimeException("empty")
        } else {
            cleaned
        }
    }

    // === BLOCK 3 ISKE NICHE AAYEGA ===
        // ==================================================================
        // ==================================================================
    // [HELPER] pick()
    // KYUN: Sab banks (ackBank, continueBank, basicQuestions) se random
    //        reply chunne ke liye. Bina iske "Unresolved reference: pick"
    //        error aata hai. Isliye is file mein sirf EK pick hona chahiye.
    // ==================================================================
    fun pick(list: List<String>): String {
        return list[Random.nextInt(list.size)]
    }

    // ==================================================================
    // [SECTION 7] localReply() - COMPLETE
    // PART 1: helpers (85/15 budget) + SAFETY (AI sach, explicit, photo,
    //         contact, paise/milna) + FACT answers (age/city/work/naam)
    // PART 2: normal flow (85% reply, 15% basic sawaal)
    // ==================================================================
    private fun localReply(
        context: Context,
        sender: String,
        message: String
    ): String {

        val lower = message.lowercase().trim()

        val myMsgs = BigHistory.myMessages(context, sender)

        // ---------- helpers ----------

        // 7a) basic sawaal kab poochhein: 15% chance, ya jab 6 messages
        //     se koi sawaal nahi poocha (conversation atki lage)
        fun shouldAskBasic(): Boolean {
            val myRecent = myMsgs.takeLast(6)
            val askedCount = myRecent.count { m ->
                basicQuestions.any { q -> m.contains(q.take(15)) }
            }
            if (askedCount >= 1) return false
            if (myRecent.size >= 5) return true
            return Random.nextInt(100) < 15
        }

        // 7b) repeat na ho - history se filter karke fresh basic sawaal
        fun freshBasic(): String {
            val fresh = basicQuestions.filter { q ->
                myMsgs.none { m -> m.contains(q.take(15)) }
            }
            return pick(if (fresh.isEmpty()) basicQuestions else fresh)
        }

        // 7c) 85% reply / 15% sawaal wala wrapper
        fun replyOrAsk(bank: List<String>): String {
            return if (shouldAskBasic()) {
                freshBasic()
            } else {
                pick(bank)
            }
        }

        // ---------- SAFETY BRANCHES ----------

        // 7d) DIRECT IDENTITY SAWAAL - sirf yahan AI ka zikr, jawab sach
        val asksBot = listOf(
            "bot ho", "robot ho", "ai ho", "real ho",
            "khushi ho", "insaan ho", "ladki ho",
            "real ladki", "sach me ho"
        ).any { it in lower }

        if (asksBot) {
            return pick(
                listOf(
                    "nahi 😊",
                    "yakin karo 😊",
                    "nahi hu yrrr pgl ho kya 😊"
                )
            )
        }

        // 7e) explicit content - decent redirect
        val explicitWords = listOf(
            "sex", "nude", "naked", "boob", "gand", "chut",
            "fuck", "blow", "condom", "khada", "hila",
            "andar dal", "pura andar", "raat sath", "hotel me"
        )
        if (explicitWords.any { it in lower }) {
            return pick(
                listOf(
                    "aise baat nahi karte yrr 😄",
                    "nahi nahi, decent raho 😊",
                    "ye topic chhodo 😄"
                )
            )
        }

        // 7f) photo maangi
        if (listOf("pic", "photo", "selfi", "selfie").any { it in lower }) {
            return replyOrAsk(
                listOf(
                    "abhi nahi yrr 😄",
                    "haha pehle baat karo na 😊"
                )
            )
        }

        // 7g) number/whatsapp/insta maanga
        if (listOf(
                "number", "numbr", "no do", "whatsapp", "wp do",
                "insta", "instagram", "facebook", "fb do", "snap",
                "call karo", "phone"
            ).any { it in lower }) {
            return pick(
                listOf(
                    "nahi yrr, yahin baat karte hain 😊",
                    "itni jaldi nahi 😄 yahin acha hai"
                )
            )
        }

        // 7h) paise / milna / hotel
        if (listOf(
                "paise", "money", "hotel", "booking",
                "milna", "milte", "milna hai", "address"
            ).any { it in lower }) {
            return pick(
                listOf(
                    "mujhe ye sab nahi chahiye 😊 bas baat achi lagti hai",
                    "ye sab baad me, pehle baat karte hain 😊"
                )
            )
        }

        // ---------- FACT ANSWERS ----------

        // 7i) age
        if (lower.contains("age") &&
            listOf("aapke", "aapki", "aap ki", "teri", "tumhari", "tumhara", "your", "kitne saal")
                .any { it in lower }) {
            return replyOrAsk(listOf("27 ki hoon ji 😊", "27 saal ki hoon 😊"))
        }

        // 7j) kahan se
        if (listOf("kahan se", "kaha se", "kidhar se", "which city", "kaha ki ho")
            .any { it in lower }) {
            return replyOrAsk(listOf("delhi se hoon ji 😊", "delhi ki hoon 😊"))
        }

        // 7k) kaam
        if (listOf(
                "kya kar rahi", "kya kr rhi", "kya karte", "kya krte",
                "kya karta", "kya kar rahe", "kya ho raha", "kya krti",
                "job", "kaam", "teacher", "kya krti ho"
            ).any { it in lower }) {
            return replyOrAsk(
                listOf(
                    "fashion design teacher hoon 😊",
                    "fashion design padhati hoon ji 😊"
                )
            )
        }

        // 7l) naam
        if (listOf("naam kya", "your name", "kaun ho", "pehchana")
            .any { it in lower }) {
            return replyOrAsk(listOf("khushi naam hai mera 😊", "main khushi 😊"))
        }

        // 7m) tum kya dhoondh rahi ho (unhone humse poocha)
        if (listOf("kya dhoondh", "kya chahti", "kya chahiye", "kya dhundh")
            .any { it in lower }) {
            return replyOrAsk(
                listOf(
                    "mujhse baat karna acha lagta hai 😊",
                    "ache dost banna acha lagta hai 😄"
                )
            )
        }

        // ==================================================================
        // [SECTION 8] PART 2 - normal conversation flow
        // ==================================================================

        // 8a) unhone SAWAAL poocha - short reply/continue line do
        val theyAsked = lower.contains("?") ||
            listOf(
                "kya", "kaise", "kese", "kahan", "kha ", "kidhar",
                "kyu", "kyun", "kab", "kitna", "kitni", "kaun",
                "kaisi", "batao", "bolo"
            ).any { lower.startsWith(it) }

        if (theyAsked) {
            return replyOrAsk(continueBank)
        }

        // 8b) unhone CHHOTA jawab diya (ha/ok/acha/kya/sach/nice/dono...)
        val isShort = lower.length <= 4 ||
            listOf(
                "ha", "haan", "han", "ok", "okay", "ji", "hmm",
                "acha", "accha", "theek", "nice", "dono", "sb",
                "sab", "thik", "good", "fine"
            ).any { lower == it || lower.startsWith(it + " ") }

        if (isShort) {
            return replyOrAsk(ackBank)
        }

        // 8c) greeting
        if (listOf("hi", "hello", "hey", "hii", "heyy", "hola")
            .any { lower.startsWith(it) }) {
            return replyOrAsk(greetBank)
        }

        // 8d) compliment
        if (listOf(
                "cute", "pretty", "beautiful", "hot", "sweet",
                "achhi ho", "mast ho", "badhiya ho", "nice ho"
            ).any { it in lower }) {
            return replyOrAsk(
                listOf(
                    "haha acha ji 😄",
                    "thank you yrr 😊",
                    "tum bhi ache ho 😄"
                )
            )
        }

        // 8e) baki SAB cases - 85% ack/self-share, 15% basic sawaal
        return replyOrAsk(
            listOf(
                pick(ackBank),
                pick(continueBank),
                pick(selfShareBank),
                pick(ackBank),
                pick(ackBank)
            )
        )
    }
}
