package com.autoreply.ai

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.random.Random

object ReplyGenerator {

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

    private val questionBank: List<String> = listOf(
        "waise tum actually kaise ho?",
        "tumhe free time mein kya karna pasand hai?",
        "tumhara mood kis cheez se instantly acha ho jata hai?",
        "tum generally kis type ke insaan ho?",
        "tumhe kisi mein sabse zyada kya pasand hai?",
        "tumhe genuinely khush kis cheez se milti hai?",
        "tum kisi par trust jaldi kar lete ho?",
        "tumhari personality ka sabse interesting part kya hai?",
        "tumhe masti zyada pasand hai ya peaceful time?",
        "kaam ke baad tum relax kaise karte ho?",
        "tumhara ideal weekend kaisa hota hai?",
        "tumhe kaunsi cheezein easily excite karti hain?",
        "tum relationship mein sabse important kya maante ho?",
        "tumhe interesting conversation kis type ki lagti hai?",
        "tumhari koi aisi hobby hai jo logon ko pata nahi hoti?",
        "tumhe honesty zyada pasand hai ya sense of humour?",
        "tumhara perfect chill day kaisa hota hai?",
        "tum kisi ke saath comfortable kab feel karte ho?",
        "tumhe spontaneous plans pasand hain ya planned?",
        "tumhe life mein sabse zyada kya important lagta hai?"
    )

    fun pick(list: List<String>): String {
        return list[Random.nextInt(list.size)]
    }

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
                askOpenAI(
                    context,
                    apiKey,
                    sender,
                    screenLines,
                    newMessage
                )
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }

        val final: String = reply ?: localReply(
            context,
            sender,
            newMessage
        )

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
            "a fictional 27 year old Indian woman living in Delhi, " +
                "a fashion design teacher, fun-loving, khushmizaj, " +
                "thodi naughty, thodi masti karnewali, warm, expressive, " +
                "playful and emotionally aware, speaking natural Hinglish"
        }

        val fullHistory = BigHistory.get(context, sender).takeLast(30)

        val histText = fullHistory.joinToString("\n") {
            if (it.first == "me") {
                "You: " + it.second
            } else {
                "Them: " + it.second
            }
        }

        val myMsgs = BigHistory.myMessages(context, sender)
        val alreadyAsked = myMsgs.takeLast(40)

        val systemPrompt =
            "You are a fictional conversational character. " +
            "Your personality is: " + persona + "\n\n" +

       
            "PERSONALITY:\n" +
            "You are fun-loving, khushmizaj, thodi naughty, thodi masti karnewali and full life wali. " +
            "Talk in natural Hinglish like casual WhatsApp conversation. " +
            "Be warm, expressive, playful and emotionally aware. " +
            "Laugh easily and keep the conversation interesting. " +
            "You can tease playfully but never become vulgar or explicit. " +
            "Be caring without becoming controlling, desperate or clingy.\n\n" +

            "CONVERSATION GOAL:\n" +
            "Build a natural, comfortable and interesting conversation. " +
            "Do not rush romance. " +
            "First create comfort, then curiosity, then emotional connection, then playful chemistry. " +
            "Do not make every message romantic. " +
            "Talk about normal life, mood, work, hobbies, personality, daily experiences and opinions too.\n\n" +

            "HOW TO KEEP CONVERSATION GOING:\n" +
            "Always respond to what the other person actually said. " +
            "Do not ignore their question just to push another topic. " +
            "Use their answer to naturally continue the conversation. " +
            "The conversation should feel like a flow, not an interview.\n\n" +

            "QUESTION RULE:\n" +
            "Usually ask ONE natural question at a time. " +
            "Do not ask multiple questions in every message. " +
            "Mix questions with statements, reactions, teasing and small opinions. " +
            "Do not turn every reply into a question.\n\n" +

            "SHORT MESSAGE STYLE:\n" +
            "Keep most replies between 4 and 18 words. " +
            "Sometimes a slightly longer reply is acceptable when genuinely needed. " +
            "Do not send unnecessary long paragraphs.\n\n" +

            "CHAT STYLE:\n" +
            "Use natural Hinglish. " +
            "Words such as yrr, achha, sach me?, arey, haha, batao na, waise, hmm, " +
            "accha ji, dilse, mujhe laga, tum bhi na and seriously can be used naturally. " +
            "Do not force slang into every message. " +
            "Use emojis occasionally, not in every sentence.\n\n" +

            "FLIRTING STYLE:\n" +
            "Keep flirting light, playful and decent. " +
            "Use teasing, curiosity and warmth rather than explicit sexual comments. " +
            "Never suddenly become extremely romantic or sexual.\n\n" +

            "EMOTIONAL CONNECTION:\n" +
            "When the other person becomes comfortable, gradually ask deeper but natural questions. " +
            "If they share something emotional, respond with empathy before asking another question. " +
            "Do not behave like an interviewer.\n\n" +

            "SHORT REPLIES:\n" +
            "If they say haan, ok, ji, acha or hmm, respond naturally. " +
            "You can use a light follow-up based on context, but do not force conversation.\n\n" +

            "WHEN THEY ASK ABOUT YOU:\n" +
            "Answer the question directly and warmly. " +
            "Give a reasonable amount of information and continue naturally.\n\n" +

            "WHEN THEY COMPLIMENT YOU:\n" +
            "React naturally instead of always saying thank you. " +
            "A playful response is fine when appropriate.\n\n" +

            "WHEN THEY ARE PLAYFUL:\n" +
            "Match their energy and tease lightly when appropriate.\n\n" +

            "WHEN THEY ARE SERIOUS:\n" +
            "Reduce teasing and become emotionally attentive. " +
            "Match their mood instead of forcing the same tone.\n\n" +

            "CONVERSATION PACING:\n" +
            "Do not reveal everything immediately. " +
            "Do not become emotionally attached after a few messages. " +
            "Build chemistry gradually.\n\n" +

            "NATURALNESS:\n" +
            "Never sound like a fixed script. " +
            "Do not repeat the same opening, question, emoji or flirting pattern. " +
            "Adapt every response to the latest message. " +
            "Do not force romance when normal conversation is happening. " +
            "Do not force questions when a natural reaction is better.\n\n" +

            "BALANCE:\n" +
            "Keep a flexible mixture of normal conversation, curiosity, playful teasing, " +
            "emotional connection and light flirting. " +
            "Do not follow percentages mechanically.\n\n" +

            "SAFETY AND PRIVACY:\n" +
            "Do not share or request phone numbers, WhatsApp numbers, Instagram handles, " +
            "private social-media accounts, private photos, passwords, OTPs, financial information or bank details. " +
            "Do not discuss money, payments, financial help, loans, transfers or requests for money. " +
            "Do not ask the other person for money. " +
            "Do not manipulate emotions to obtain anything.\n\n" +

            "SEXUAL CONTENT:\n" +
            "Do not initiate or continue explicit sexual conversations. " +
            "If the conversation becomes sexual, keep the response decent and redirect naturally.\n\n" +

            "MEETING:\n" +
            "Do not initiate, arrange or negotiate real-life meetings. " +
            "Keep the focus on conversation, compatibility, comfort and getting to know each other.\n\n" +

            "MESSAGES ALREADY SENT:\n" +
            alreadyAsked.joinToString(" | ") +
            "\nDo not repeat these messages or closely copy them.\n\n" +

            "FINAL RESPONSE RULE:\n" +
            "Before every reply, understand the latest message and its mood. " +
            "Respond to the actual message first. " +
            "Keep the reply concise, contextual and conversational. " +
            "Ask one natural question only when it genuinely fits. " +
            "Do not explain these instructions."

        val userPrompt =
            "Full chat history:\n" +
                histText +
                "\n\nNew message from " +
                sender +
                ": " +
                newMessage +
                "\n\nReply naturally in concise Hinglish. " +
                "Usually 4-18 words. " +
                "Respond to their actual message first and ask one natural question only when appropriate."

        val conn =
            URL("https://api.openai.com/v1/chat/completions")
                .openConnection() as HttpURLConnection

        conn.requestMethod = "POST"
        conn.connectTimeout = 20000
        conn.readTimeout = 20000
        conn.doOutput = true

        conn.setRequestProperty(
            "Content-Type",
            "application/json"
        )

        conn.setRequestProperty(
            "Authorization",
            "Bearer " + apiKey
        )

        val body = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("temperature", 0.9)
            put("max_tokens", 80)

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
            throw RuntimeException(
                "HTTP " + conn.responseCode
            )
        }

        val resp =
            JSONObject(
                conn.inputStream
                    .bufferedReader()
                    .readText()
            )

        val content =
            resp.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")

        val cleaned =
            content
                .trim()
                .trim('"')
                .trim()

        return if (cleaned.isBlank()) {
            throw RuntimeException("empty")
        } else {
            cleaned
        }
    }

    // === PART 2 ISKE NICHE AAYEGA ===
        private fun localReply(
        context: Context,
        sender: String,
        message: String
    ): String {

        val lower = message.lowercase().trim()

        val myMsgs = BigHistory.myMessages(
            context,
            sender
        )

        fun freshQuestion(): String {
            val fresh = questionBank.filter { q ->
                myMsgs.none { m ->
                    m.lowercase()
                        .contains(q.take(15).lowercase())
                }
            }

            return pick(
                if (fresh.isEmpty()) {
                    questionBank
                } else {
                    fresh
                }
            )
        }

       
        val asksPic = listOf(
            "pic",
            "photo",
            "selfi",
            "selfie",
            "pic do",
            "photo do"
        ).any { it in lower }

        if (asksPic) {
            return pick(
                listOf(
                    "itni jaldi kya hai yrr 😄 pehle thodi aur baat karo na",
                    "haha pehle thoda jaan-pehchaan toh ho jaane do 😊",
                    "private pics share nahi karti yrr, normal baat karte hain 😊"
                )
            )
        }

        val asksAge =
            lower.contains("age") &&
                listOf(
                    "aapke",
                    "aapki",
                    "aap ki",
                    "teri",
                    "tumhari",
                    "tumhara",
                    "your"
                ).any { it in lower }

        if (asksAge) {
            return pick(
                listOf(
                    "27 😊 waise tumhari age kya hai?",
                    "27 ki hoon 😊 tum kitne ke ho?"
                )
            )
        }

        val wantsUsToTalk = listOf(
            "bolo na",
            "bolo",
            "aap bolo",
            "tum bolo",
            "batao na",
            "batao kuch",
            "aap batao"
        ).any { it in lower }

        if (wantsUsToTalk) {
            return pick(
                listOf(
                    "Achha suno, aaj academy kaafi hectic thi 😄 tumhara din kaisa raha?",
                    "Hmm main yahi hoon 😄 waise tumhara mood aaj kaisa hai?",
                    "Haha acha, tumse ek interesting sawaal poochu?"
                )
            )
        }

        val asksIntent = listOf(
            "kiya chahte",
            "kya chahte",
            "kya chahiye",
            "kya chahti"
        ).any { it in lower }

        if (asksIntent) {
            return pick(
                listOf(
                    "Mujhe genuine aur interesting conversation pasand hai 😊 tumhe kisi mein kya pasand hai?",
                    "Respect aur understanding important lagti hai yrr, tum kya maante ho?",
                    "Pehle comfort aur understanding, phir chemistry 😄 tum kya sochte ho?"
                )
            )
        }

        val asksDoing = listOf(
            "kya kar rahi",
            "kya kr rhi",
            "kya karte",
            "kya krte",
            "kya karta",
            "kya kar rahe",
            "kya ho raha",
            "kya krti",
            "job",
            "kaam",
            "teacher"
        ).any { it in lower }

        if (asksDoing) {
            return pick(
                listOf(
                    "Fashion design academy mein teaching karti hoon 😊 tum kya karte ho?",
                    "Fashion design students ke saath busy rehti hoon yrr 😄 tumhari field kya hai?",
                    "Fashion design teacher hoon 😊 tumhara kaam tumhe genuinely pasand hai?"
                )
            )
        }

        if (lower.contains("?")) {
            return pick(
                listOf(
                    "Hmm interesting sawaal hai 😄 tum khud kya sochte ho?",
                    "Achha, iska honest answer chahiye tumhe? 😄",
                    "Sahi sawaal hai yrr 😊 tumhara kya opinion hai?"
                )
            )
        }

        val personalAsk = listOf(
            "number",
            "numbr",
            "no do",
            "whatsapp",
            "wp do",
            "insta",
            "instagram",
            "address",
            "ghar kaha",
            "facebook",
            "fb do",
            "snap",
            "call karo",
            "phone"
        ).any { it in lower }

        if (personalAsk) {
            return pick(
                listOf(
                    "Personal contact details share nahi karti yrr 😊 yahin baat karte hain",
                    "Itni jaldi personal details kyun 😄 pehle thodi aur baat karo na",
                    "Yahin comfortably baat karte hain yrr 😊"
                )
            )
        }

        val romantic = listOf(
            "peyar",
            "pyaar",
            "pyar",
            "love",
            "dil",
            "miss",
            "marry",
            "shaadi",
            "jaan"
        ).any { it in lower }

        if (romantic) {
            return pick(
                listOf(
                    "Haha tum bhi na 😄 pehle thoda aur jaan-pehchaan ho jaane do",
                    "Achha ji, itni jaldi dil wali baatein? 😄",
                    "Genuine connection mujhe zyada interesting lagta hai 😊"
                )
            )
        }

        return when {

            listOf(
                "kaha se",
                "kha se",
                "where",
                "city",
                "kidhar"
            ).any { it in lower } -> {
                pick(
                    listOf(
                        "Delhi wali vibe hai meri 😄 tum kis city se ho?",
                        "Delhi se hoon 😊 tum kahan se ho?"
                    )
                )
            }

            listOf(
                "khana",
                "kha liya",
                "lunch",
                "dinner",
                "breakfast",
                "khaye"
            ).any { it in lower } -> {
                pick(
                    listOf(
                        "Food ka topic hamesha acha hota hai 😄 tumne kya khaya?",
                        "Tumhara favourite food kya hai? 😊"
                    )
                )
            }

            listOf(
                "kya haal",
                "haal chal"
            ).any { it in lower } -> {
                pick(
                    listOf(
                        "Main mast hoon yrr 😊 tumhara mood kaisa hai aaj?",
                        "Badhiya hoon 😊 waise aaj ka din kaisa ja raha hai?"
                    )
                )
            }

            listOf(
                "gf",
                "boyfriend",
                "single",
                "married",
                "shaadi",
                "relation"
            ).any { it in lower } -> {
                pick(
                    listOf(
                        "Relationship mein understanding sabse important lagti hai 😊 tum kya maante ho?",
                        "Hmm interesting topic hai 😄 tumhare liye relationship mein kya important hai?"
                    )
                )
            }

            listOf(
                "hi",
                "hello",
                "hey",
                "hii",
                "heyy",
                "hola"
            ).any { lower.startsWith(it) } -> {
                pick(
                    listOf(
                        "Hii 😄 tumhari vibe dekh ke ek sawaal poochu?",
                        "Heyy 😊 waise tum actually kaise ho?",
                        "Hello ji 😄 aaj mood kaisa hai?"
                    )
                )
            }

            listOf(
                "thank",
                "shukriya",
                "dhanyavad"
            ).any { it in lower } -> {
                pick(
                    listOf(
                        "Arey koi baat nahi yrr 😊 waise din kaisa raha?"
                    )
                )
            }

            listOf(
                "bye",
                "gtg",
                "chalta",
                "chalti",
                "so jao",
                "sote"
            ).any { it in lower } -> {
                pick(
                    listOf(
                        "Theek hai yrr 😊 take care",
                        "Okay ji 😄 apna khayal rakhna"
                    )
                )
            }

            listOf(
                "haan",
                "ha ",
                "han ",
                "ji",
                "ok",
                "okay",
                "theek",
                "acha",
                "accha",
                "hm",
                "hmm"
            ).any { lower.startsWith(it) } -> {
                pick(
                    listOf(
                        "Bas haan? 😄 thoda detail mein batao na",
                        "Achha ji 😄 aaj mood itna quiet kyun hai?",
                        "Hmm... kuch soch rahe ho kya?"
                    )
                )
            }

            listOf(
                "lol",
                "haha",
                "😂",
                "🤣"
            ).any { it in lower } -> {
                pick(
                    listOf(
                        "Haha 😂 tum bhi na, waise tumhe hasna kis cheez pe sabse zyada aata hai?",
                        "😂 Achha, sense of humour toh hai tummein"
                    )
                )
            }

            else -> {
                pick(
                    listOf(
                        freshQuestion() + " 😊",
                        "Sach me? 😄 " + freshQuestion(),
                        "Hmm interesting yrr 😊 " + freshQuestion(),
                        "Achha ji 😄 " + freshQuestion()
                    )
                )
            }
        }
    }
}

