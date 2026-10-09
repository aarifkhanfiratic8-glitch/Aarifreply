package com.autoreply.ai

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

object ReplyGenerator {

    // [S1] FIRST_MSG — sirf EK BAAR per sender (intro_done flag). Kabhi repeat nahi.
    private const val FIRST_MSG =
        "aur batao, kaise ho?"

    // [S2] basicQuestions — templates ka hissa (5% cases)
    private val basicQuestions: List<String> = listOf(
        "aap kyu aye iss app per?",
        "kaha se ho aap?",
        "kya kaam karte ho aap?",
        "aap kis tarah ki ladki pasand karte ho?"
    )

    // [S3] banks
    private val ackBank: List<String> = listOf(
        "acha ji 😊", "sach me? 😄", "nice yrr 😊", "kya baat hai 😄",
        "haha 😄", "hmm acha 😊", "sahee hai yrr 😄", "theek hai ji 😊",
        "wow 😊", "accha 😊"
    )
    private val continueBank: List<String> = listOf(
        "acha to phir kya socha aap ne 😊",
        "kya hua ji, batao na 😊",
        "sach batao na mujhe 😄",
        "arre bolo na kuch 😊",
        "haha tum bhi na 😄",
        "samajh gayi ji, aage bolo 😊"
    )
    private val selfShareBank: List<String> = listOf(
        "main delhi se hu ji 😊",
        "main fashion design padhati hoon 😊",
        "mujhe masti wali baatein achi lagti hain 😄",
        "chai aur music mere favourite hain 😊",
        "main thodi si naughty hoon 😄",
        "ghoomna bahut pasand hai mujhe 😊"
    )
    private val greetBank: List<String> = listOf(
        "hii 😊", "hello ji 😄", "heyy 😊", "namaste ji 😊", "hii yrr 😄"
    )
    private val dunnoBank: List<String> = listOf(
        "sach batau? ye mujhse bhi mushkil hai 😄",
        "haha ye to mujhe bhi nahi pata 😊",
        "soch ke batau? 😄"
    )
    private val thinkBank: List<String> = listOf(
        "hmm soch rahi hu 😊",
        "rukko, sochne do 😄",
        "hmm acha 😊",
        "dete hu jawab 😄"
    )
    private val cityAcks: Map<String, String> = mapOf(
        "agra" to "agra? taj wali city 😊 nice",
        "delhi" to "delhi? to hum padosi huye 😄",
        "mumbai" to "mumbai se ho? wah ji 😊",
        "jaipur" to "jaipur? pink city, nice yrr 😊",
        "punjab" to "punjab se? wah ji wah 😄",
        "lucknow" to "lucknow? nawabo wali city 😊",
        "up" to "up se ho? nice 😊",
        "bihar" to "bihar se? acha ji 😊",
        "haryana" to "haryana? nice yrr 😄",
        "mp" to "mp se ho? acha ji 😊",
        "gujarat" to "gujarat se? nice 😊",
        "kolkata" to "kolkata? mishti doi wali city 😄"
    )

    // [S4] BigHistory — history + FIRST_MSG ek-baari flag (intro_done)
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

        // FIRST_MSG kabhi repeat na ho — ye flag hamesha yaad rehta hai
        fun introDone(context: Context, sender: String): Boolean {
            try {
                val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                return sp.getBoolean("intro_" + sender.lowercase(), false)
            } catch (e: Exception) { return false }
        }

        fun markIntro(context: Context, sender: String) {
            try {
                val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                sp.edit().putBoolean("intro_" + sender.lowercase(), true).apply()
            } catch (e: Exception) { }
        }
    }

    // [S5] ManualMemory — saved data (95% replies yahan se)
    private object ManualMemory {
        private const val PREFS = "manual_memory"
        private const val CAP = 3000

        fun norm(s: String): String =
            s.lowercase().trim().replace(Regex("\\s+"), " ")

        private fun key(sender: String): String = "p_" + sender.lowercase()

        fun load(context: Context, sender: String): List<Pair<String, String>> {
            try {
                val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val raw = sp.getString(key(sender), "") ?: ""
                if (raw.isEmpty()) return emptyList()
                return raw.split("\n").mapNotNull { line ->
                    val i = line.indexOf("==>")
                    if (i > 0) {
                        Pair(line.substring(0, i).trim(), line.substring(i + 3).trim())
                    } else null
                }
            } catch (e: Exception) { return emptyList() }
        }

        fun addPair(context: Context, sender: String, q: String, a: String) {
            try {
                if (q.length < 2 || a.length < 2) return
                val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val cur = sp.getString(key(sender), "") ?: ""
                val line = q.replace("\n", " ") + "==>" + a.replace("\n", " ")
                val all = if (cur.isEmpty()) line else cur + "\n" + line
                val lines = all.split("\n")
                val keep = if (lines.size > CAP) lines.subList(lines.size - CAP, lines.size) else lines
                sp.edit().putString(key(sender), keep.joinToString("\n")).apply()
            } catch (e: Exception) { }
        }

        // 3-level match — 50% word overlap tak memory pakdi jayegi
        // (zyada saved matches = 95% memory se hi reply)
        fun findReply(context: Context, sender: String, incoming: String): String? {
            val pairs = load(context, sender)
            if (pairs.isEmpty()) return null
            val inc = norm(incoming)
            for (p in pairs) {
                if (norm(p.first) == inc) return p.second
            }
            for (p in pairs) {
                val q = norm(p.first)
                if (q.length >= 5 && (inc.contains(q) || q.contains(inc))) return p.second
            }
            val iw = inc.split(" ").filter { it.length > 2 }.toSet()
            if (iw.size >= 2) {
                var best: Pair<String, String>? = null
                var bestScore = 0
                for (p in pairs) {
                    val qw = norm(p.first).split(" ").filter { it.length > 2 }.toSet()
                    if (qw.isEmpty()) continue
                    val inter = iw.intersect(qw).size
                    val score = inter * 100 / maxOf(iw.size, qw.size)
                    if (score >= 50 && score > bestScore) {
                        best = p
                        bestScore = score
                    }
                }
                if (best != null) return best.second
            }
            return null
        }
    }

    // [S6] generate() — ORDER: pehli baar -> FIRST_MSG (sirf ek baar) ->
    //        1) SAVED DATA (95%) 2) API (agar key ho) 3) TEMPLATE (5%)
    fun generate(
        context: Context,
        sender: String,
        screenLines: List<String>,
        newMessage: String
    ): String? {
        if (newMessage.isBlank()) return null

        BigHistory.add(context, sender, "them", newMessage)

        if (!BigHistory.introDone(context, sender)) {
            BigHistory.markIntro(context, sender)
            BigHistory.add(context, sender, "me", FIRST_MSG)
            return FIRST_MSG
        }

        // 95% — saved data se
        try {
            val learned = ManualMemory.findReply(context, sender, newMessage)
            if (learned != null) {
                BigHistory.add(context, sender, "me", learned)
                return learned
            }
        } catch (e: Exception) { }

        // API (backup, sirf key hone par)
        val apiKey = Prefs.apiKey(context)
        var final: String = ""
        if (apiKey.isNotBlank()) {
            final = try {
                askOpenAI(context, apiKey, sender, screenLines, newMessage) ?: ""
            } catch (e: Exception) { "" }
        }

        // 5% — templates
        if (final.isBlank()) {
            final = try {
                localReply(context, sender, newMessage) ?: ""
            } catch (e: Exception) { "" }
        }

        if (final.isBlank()) {
            final = pick(thinkBank + ackBank)
        }

        BigHistory.add(context, sender, "me", final)
        return final
    }

    // [S8] recordChat — OFF/ON dono mein sirf tumhare manual jawab save
    fun recordChat(
        context: Context,
        sender: String,
        msgs: List<Pair<String, Boolean>>
    ) {
        try {
            if (msgs.size < 2 || sender.isBlank()) return
            val botSends = BigHistory.myMessages(context, sender)
            for (i in 0 until msgs.size - 1) {
                val q = msgs[i]
                val a = msgs[i + 1]
                if (!q.second && a.second) {
                    val isBotSend = botSends.any {
                        it.trim().equals(a.first.trim(), ignoreCase = true)
                    }
                    if (!isBotSend) {
                        ManualMemory.addPair(context, sender, q.first, a.first)
                    }
                }
            }
        } catch (e: Exception) { }
    }

    // === PART 2 ISKE NICHE AAYEGA ===
        // [S9] askOpenAI — API backup
    private fun askOpenAI(
        context: Context,
        apiKey: String,
        sender: String,
        screenLines: List<String>,
        newMessage: String
    ): String {
        val persona = Prefs.persona(context).ifBlank {
            "Khushi, 27 saal, Delhi, fashion design teacher. fun-loving, thodi masti, natural Hinglish"
        }
        val fullHistory = BigHistory.get(context, sender).takeLast(30)
        val histText = fullHistory.joinToString("\n") {
            if (it.first == "me") "You: " + it.second else "Them: " + it.second
        }
        val systemPrompt =
            "You are " + persona + ". Chat as her.\n\n" +
            "STYLE: 2-15 words per reply, short Hinglish, sirf 1 message.\n" +
            "JAWAB PEHLE: unke message ka jawab do, sawaal wapas mat poochho.\n" +
            "Khud ka sawaal sirf tab jab conversation ruk jaye - sirf ye 4: " +
            basicQuestions.joinToString(" / ") + "\n" +
            "IDENTITY: 'bot ho?' pooche to 'haan 😊'. Iske ilawa kabhi AI mention mat karo.\n" +
            "SAFETY: number/whatsapp/insta/photos/paise/milna - nahi. " +
            "Explicit ho to 'aise baat nahi karte yrr 😄'.\n\n" +
            "Chat history:\n" + histText
        val userPrompt =
            "New message: " + newMessage +
            "\n\nReply 2-15 words. Jawab pehle."
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
                    put(JSONObject().put("role", "system").put("content", systemPrompt))
                    put(JSONObject().put("role", "user").put("content", userPrompt))
                }
            )
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        if (conn.responseCode != 200) throw RuntimeException("HTTP " + conn.responseCode)
        val resp = JSONObject(conn.inputStream.bufferedReader().readText())
        val content = resp.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
        val cleaned = content.trim().trim('"').trim()
        return if (cleaned.isBlank()) throw RuntimeException("empty") else cleaned
    }

    fun pick(list: List<String>): String {
        return list[Random.nextInt(list.size)]
    }

    // [S10] localReply() — TEMPLATE (sirf 5% cases, jab saved data mein match na ho)
    private fun localReply(
        context: Context,
        sender: String,
        message: String
    ): String {
        val lower = message.lowercase().trim()
        val myMsgs = BigHistory.myMessages(context, sender)

        fun askedRecently(): Boolean {
            return myMsgs.takeLast(12).any { m ->
                basicQuestions.any { q -> m.contains(q.take(15)) }
            }
        }
        fun shouldAskBasic(): Boolean {
            if (askedRecently()) return false
            if (myMsgs.size >= 12) return true
            return Random.nextInt(100) < 5
        }
        fun freshBasic(): String {
            val fresh = basicQuestions.filter { q ->
                myMsgs.none { m -> m.contains(q.take(15)) }
            }
            return pick(if (fresh.isEmpty()) basicQuestions else fresh)
        }
        fun replyOrAsk(bank: List<String>): String {
            return if (shouldAskBasic()) freshBasic() else pick(bank)
        }

        // L1 SAFETY
        if (listOf(
                "bot ho", "robot ho", "ai ho", "real ho", "khushi ho",
                "insaan ho", "ladki ho", "real ladki", "sach me ho"
            ).any { it in lower }) {
            return pick(listOf("haan 😊", "haan ji 😊", "haan, AI hoon 😊"))
        }
        if (listOf(
                "sex", "nude", "naked", "boob", "gand", "chut", "fuck",
                "blow", "condom", "khada", "hila", "andar dal",
                "pura andar", "raat sath", "hotel me"
            ).any { it in lower }) {
            return pick(
                listOf("aise baat nahi karte yrr 😄", "nahi nahi, decent raho 😊", "ye topic chhodo 😄")
            )
        }
        if (listOf("pic", "photo", "selfi", "selfie").any { it in lower }) {
            return replyOrAsk(listOf("abhi nahi yrr 😄", "haha pehle baat karo na 😊"))
        }
        if (listOf(
                "number", "numbr", "no do", "whatsapp", "wp do", "insta",
                "instagram", "facebook", "fb do", "snap", "call karo", "call kr",
                "call", "phone", "vc", "video call"
            ).any { it in lower }) {
            return pick(
                listOf("nahi yrr, yahin baat karte hain 😊", "itni jaldi nahi 😄 yahin acha hai")
            )
        }
        if (listOf("paise", "money", "hotel", "booking", "milna", "milte", "address")
            .any { it in lower }) {
            return pick(
                listOf(
                    "mujhe ye sab nahi chahiye 😊 bas baat achi lagti hai",
                    "ye sab baad me, pehle baat karte hain 😊"
                )
            )
        }

        // L2 — HUMSE SAWAAL -> FACT jawab
        if (listOf(
                "you are from", "where are you from", "where r u from",
                "from where", "which city", "where do you live", "your city",
                "kahan se", "kaha se", "kidhar se", "kaha ki ho",
                "kaha rehti", "kidhar rehti"
            ).any { it in lower }) {
            return replyOrAsk(listOf("main delhi se hu ji 😊", "delhi se hu 😊"))
        }
        if ((lower.contains("age") || lower.contains("umar") || lower.contains("saal")) &&
            listOf(
                "aapke", "aapki", "aap ki", "apki", "apke", "teri",
                "tumhari", "tumhara", "tumari", "tumri", "your", "kitne", "kitni"
            ).any { it in lower }) {
            return replyOrAsk(listOf("27 ki hu ji 😊", "27 saal ki hu 😊"))
        }
        if (listOf(
                "what do you do", "your job", "what's your job", "what work",
                "what are you doing", "what are you up to",
                "kya kar rahi", "kya kr rhi", "kya karte", "kya krte",
                "kya karta", "kya kar rahe", "kya ho raha",
                "kya krti", "kaam kya", "job", "teacher", "work"
            ).any { it in lower }) {
            return replyOrAsk(
                listOf("fashion design teacher hu 😊", "fashion design padhati hu ji 😊")
            )
        }
        if (listOf(
                "your name", "what's your name", "what is your name", "who are you",
                "naam kya", "kaun ho", "pehchana", "your good name"
            ).any { it in lower }) {
            return replyOrAsk(listOf("khushi naam hai mera 😊", "main khushi 😊"))
        }
        if (listOf("how are you", "how r u", "kaise ho", "kese ho", "kya haal", "haal chal", "kaisi ho")
            .any { it in lower }) {
            return replyOrAsk(listOf("main mast hu ji 😊", "badhiya hu 😊"))
        }
        if (listOf("kya dhoondh", "kya chahti", "kya chahiye", "kya dhundh", "what are you looking", "why are you here")
            .any { it in lower }) {
            return replyOrAsk(
                listOf("mujhse baat karna acha lagta hai 😊", "ache dost banna acha lagta hai 😄")
            )
        }
        if (listOf("dost", "friend banoge", "friendship", "dosti")
            .any { it in lower }) {
            return replyOrAsk(listOf("haan ji kyun nahi 😊", "zaroor ji 😄"))
        }

        // L3 — humara sawaal tha, ye unka jawab hai -> ack
        if (askedRecently() && lower.length <= 20) {
            for ((city, ack) in cityAcks) {
                if (city in lower) return ack
            }
            return pick(listOf("nice yrr 😊", "acha ji 😊", "wah acha hai 😄", "kya baat hai 😊"))
        }

        // L4 — chhota jawab
        val isShort = lower.length <= 4 ||
            listOf(
                "ha", "haan", "han", "ok", "okay", "ji", "hmm", "acha",
                "accha", "theek", "thik", "good", "fine", "nice"
            ).any { lower == it || lower.startsWith(it + " ") }
        if (isShort) {
            return replyOrAsk(listOf("theek hai ji 😊", "acha ji 😊", "hmm 😊", "ji ji 😄"))
        }

        // L5 — wo kuch aur pooche
        val theyAsked = lower.contains("?") ||
            listOf(
                "kya", "kaise", "kese", "kahan", "kha ", "kidhar", "kyu",
                "kyun", "kab", "kitna", "kitni", "kaun", "kaisi", "batao",
                "bolo", "why", "when", "which", "how", "who"
            ).any { lower.startsWith(it) }
        if (theyAsked) {
            return replyOrAsk(dunnoBank + thinkBank)
        }

        // L6 — greeting / compliment / romantic
        if (listOf("hi", "hello", "hey", "hii", "heyy", "hola", "namaste").any { lower.startsWith(it) }) {
            return replyOrAsk(greetBank)
        }
        if (listOf(
                "cute", "pretty", "beautiful", "hot", "sweet", "achhi ho",
                "mast ho", "badhiya ho", "nice ho", "love you", "miss you"
            ).any { it in lower }) {
            return replyOrAsk(listOf("haha acha ji 😄", "thank you yrr 😊", "tum bhi ache ho 😄"))
        }
        if (listOf("marry", "shaadi", "propose").any { it in lower }) {
            return replyOrAsk(listOf("arre itni jaldi? 😄", "haha pehle jaan toh lo mujhe 😄"))
        }

        // L7 — baki sab
        return replyOrAsk(
            listOf(
                pick(ackBank), pick(continueBank), pick(selfShareBank),
                pick(ackBank), pick(ackBank), pick(selfShareBank)
            )
        )
    }
}
