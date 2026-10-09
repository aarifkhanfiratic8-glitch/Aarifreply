package com.autoreply.ai

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val sp by lazy { getSharedPreferences(Prefs.FILE, MODE_PRIVATE) }
    private lateinit var tvStatus: TextView
    private lateinit var chipCount: TextView
    private val appsSet = mutableSetOf<String>()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    // ===== theme colors =====
    private val C_BG = Color.parseColor("#0B1220")
    private val C_CARD = Color.parseColor("#141D31")
    private val C_CARD2 = Color.parseColor("#1A2440")
    private val C_STROKE = Color.parseColor("#263350")
    private val C_TEXT = Color.parseColor("#F1F5F9")
    private val C_SUB = Color.parseColor("#8CA3C3")
    private val C_GREEN = Color.parseColor("#22C55E")
    private val C_GREEN2 = Color.parseColor("#16A34A")
    private val C_PURPLE = Color.parseColor("#7C3AED")
    private val C_PURPLE2 = Color.parseColor("#4C1D95")
    private val C_GOLD = Color.parseColor("#FACC15")

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(color)
        d.cornerRadius = dp(radiusDp).toFloat()
        strokeColor?.let { d.setStroke(dp(1), it) }
        return d
    }

    private fun gradient(c1: Int, c2: Int, radiusDp: Int): GradientDrawable {
        val d = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(c1, c2))
        d.cornerRadius = dp(radiusDp).toFloat()
        return d
    }

    private fun card(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(C_CARD, 20, C_STROKE)
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.setMargins(0, dp(10), 0, 0)
            layoutParams = lp
        }
    }

    private fun tv(text: String, size: Float, color: Int, bold: Boolean = false): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
    }

    private fun field(hint: String, value: String, password: Boolean = false): EditText {
        return EditText(this).apply {
            this.hint = hint
            setHintTextColor(C_SUB)
            setTextColor(C_TEXT)
            textSize = 14f
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = rounded(C_CARD2, 14, C_STROKE)
            setText(value)
            inputType = if (password) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT
            }
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.setMargins(0, dp(8), 0, 0)
            layoutParams = lp
        }
    }

    private fun btn(text: String, bg: GradientDrawable, txtColor: Int, bold: Boolean = true): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 14f
            setTextColor(txtColor)
            if (bold) setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = bg
        }
    }

    private fun toggle(text: String, key: String, def: Boolean): Switch {
        val s = Switch(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(C_TEXT)
            isChecked = sp.getBoolean(key, def)
            val states = arrayOf(
                intArrayOf(android.R.attr.state_checked),
                intArrayOf(-android.R.attr.state_checked)
            )
            thumbTintList = ColorStateList(states, intArrayOf(C_GREEN, Color.parseColor("#475569")))
            trackTintList = ColorStateList(states, intArrayOf(0x5522C55E, 0x33FFFFFF))
            setOnCheckedChangeListener { _, c ->
                sp.edit().putBoolean(key, c).apply()
            }
        }
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.setMargins(0, dp(6), 0, 0)
        s.layoutParams = lp
        return s
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.statusBarColor = C_BG

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(24))
            setBackgroundColor(C_BG)
        }

        // ===== HEADER =====
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val robotBox = TextView(this).apply {
            text = "🤖"
            textSize = 26f
            gravity = Gravity.CENTER
            background = gradient(C_GREEN, C_GREEN2, 16)
            val lp = LinearLayout.LayoutParams(dp(52), dp(52))
            layoutParams = lp
        }
        header.addView(robotBox)
        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(0, -2, 1f)
            lp.setMargins(dp(12), 0, dp(8), 0)
            layoutParams = lp
        }
        titleBlock.addView(tv("AutoReply AI", 22f, C_TEXT, true))
        titleBlock.addView(tv("Smart Replies, Always On", 12f, C_SUB))
        header.addView(titleBlock)
        val proPill = TextView(this).apply {
            text = "👑 Pro"
            textSize = 11f
            setTextColor(C_GOLD)
            setPadding(dp(10), dp(5), dp(10), dp(5))
            background = rounded(Color.parseColor("#1E293B"), 20, C_GOLD)
        }
        header.addView(proPill)
        root.addView(header)

        tvStatus = tv("", 12f, C_SUB)
        val statusLp = LinearLayout.LayoutParams(-1, -2)
        statusLp.setMargins(dp(2), dp(8), 0, 0)
        tvStatus.layoutParams = statusLp
        root.addView(tvStatus)

        // ===== STATUS CARD (notification access) =====
        val statusCard = card()
        statusCard.orientation = LinearLayout.HORIZONTAL
        statusCard.gravity = Gravity.CENTER_VERTICAL
        val bell = TextView(this).apply {
            text = "🔔"
            textSize = 22f
            gravity = Gravity.CENTER
            background = rounded(0x3322C55E, 14)
            val lp = LinearLayout.LayoutParams(dp(44), dp(44))
            layoutParams = lp
        }
        statusCard.addView(bell)
        val statusTxt = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val lp = LinearLayout.LayoutParams(0, -2, 1f)
            lp.setMargins(dp(12), 0, dp(8), 0)
            layoutParams = lp
        }
        val tvNotifState = tv("Notification Access", 15f, C_TEXT, true)
        statusTxt.addView(tvNotifState)
        val tvSubState = tv("Accessibility: — | Queue Mode: —", 12f, C_SUB)
        statusTxt.addView(tvSubState)
        statusCard.addView(statusTxt)
        statusCard.addView(tv("›", 22f, C_SUB))
        statusCard.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        root.addView(statusCard)
        tvStatus = tvSubState // reuse for refreshStatus

        // ===== ENABLE BUTTONS =====
        val enableRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.setMargins(0, dp(10), 0, 0)
            layoutParams = lp
        }
        val bNotif = btn("1. Enable\nNotification Access", gradient(C_GREEN, C_GREEN2, 18), Color.WHITE)
        bNotif.setOnClickListener {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        val lp1 = LinearLayout.LayoutParams(0, -2, 1f)
        lp1.setMargins(0, 0, dp(6), 0)
        bNotif.layoutParams = lp1
        enableRow.addView(bNotif)
        val bAcc = btn("2. Enable\nAccessibility", gradient(C_PURPLE, C_PURPLE2, 18), Color.WHITE)
        bAcc.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        val lp2 = LinearLayout.LayoutParams(0, -2, 1f)
        lp2.setMargins(dp(6), 0, 0, 0)
        bAcc.layoutParams = lp2
        enableRow.addView(bAcc)
        root.addView(enableRow)

        // ===== APPS CARD =====
        val appsCard = card()
        val appsTitleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        appsTitleRow.addView(tv("🎛  Auto-reply ON these apps", 15f, C_TEXT, true).apply {
            val lp = LinearLayout.LayoutParams(0, -2, 1f)
            layoutParams = lp
        })
        chipCount = TextView(this).apply {
            textSize = 11f
            setTextColor(C_GREEN)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = rounded(0x3322C55E, 20)
        }
        appsTitleRow.addView(chipCount)
        appsCard.addView(appsTitleRow)

        appsSet.addAll(sp.getStringSet("apps", setOf("com.whatsapp"))!!)
        val allApps = Prefs.SUPPORTED.toMutableMap()
        (sp.getString("customApps", "") ?: "").split(",")
            .filter { it.isNotBlank() }
            .forEach { allApps[it] = it }

        val entries = allApps.entries.toList()
        var row: LinearLayout? = null
        entries.forEachIndexed { i, e ->
            if (i % 3 == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val lp = LinearLayout.LayoutParams(-1, -2)
                    lp.setMargins(0, dp(10), 0, 0)
                    layoutParams = lp
                }
                appsCard.addView(row)
            }
            val pkg = e.key
            val name = if (e.value == pkg) pkg else e.value
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val lp = LinearLayout.LayoutParams(0, -2, 1f)
                layoutParams = lp
            }
            val badge = TextView(this).apply {
                text = "✓"
                textSize = 10f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                background = rounded(C_GREEN, 10)
                val slp = LinearLayout.LayoutParams(dp(20), dp(20))
                layoutParams = slp
                visibility = if (pkg in appsSet) View.VISIBLE else View.GONE
            }
            val icon = TextView(this).apply {
                text = name.take(1).uppercase()
                textSize = 20f
                setTextColor(Color.WHITE)
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
                background = gradient(
                    if (pkg in appsSet) C_GREEN2 else Color.parseColor("#334155"),
                    if (pkg in appsSet) C_GREEN else Color.parseColor("#1E293B"),
                    16
                )
                val ilp = LinearLayout.LayoutParams(dp(52), dp(52))
                ilp.setMargins(0, 0, 0, dp(4))
                layoutParams = ilp
            }
            val nameTv = tv(shortName(pkg, name), 10f, C_SUB)
            nameTv.gravity = Gravity.CENTER
            cell.setOnClickListener {
                if (pkg in appsSet) {
                    appsSet.remove(pkg)
                } else {
                    appsSet.add(pkg)
                }
                sp.edit().putStringSet("apps", HashSet(appsSet)).apply()
                icon.background = gradient(
                    if (pkg in appsSet) C_GREEN2 else Color.parseColor("#334155"),
                    if (pkg in appsSet) C_GREEN else Color.parseColor("#1E293B"),
                    16
                )
                badge.visibility = if (pkg in appsSet) View.VISIBLE else View.GONE
                updateCount()
            }
            val iconWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            iconWrap.addView(badge)
            iconWrap.addView(icon)
            cell.addView(iconWrap)
            cell.addView(nameTv)
            row!!.addView(cell)
        }
        updateCount()

        val etCustom = field("Custom package (e.g. com.example.app)", "")
        appsCard.addView(etCustom)
        val addCustom = btn(
            "ADD CUSTOM APP",
            gradient(Color.parseColor("#334155"), Color.parseColor("#1E293B"), 14),
            C_TEXT
        )
        addCustom.setOnClickListener {
            val p = etCustom.text.toString().trim()
            if (p.isNotEmpty()) {
                sp.edit().putString(
                    "customApps",
                    (sp.getString("customApps", "") ?: "") + "," + p
                ).apply()
                Toast.makeText(this, "Added: $p", Toast.LENGTH_SHORT).show()
                recreate()
            }
        }
        val aclp = LinearLayout.LayoutParams(-1, -2)
        aclp.setMargins(0, dp(8), 0, 0)
        addCustom.layoutParams = aclp
        appsCard.addView(addCustom)
        root.addView(appsCard)

        // ===== AI SETTINGS CARD =====
        val aiCard = card()
        aiCard.addView(tv("✨  AI Settings (optional)", 15f, C_TEXT, true))
        val etApiKey = field("OpenAI API key (khali = templates use)", sp.getString("apiKey", "") ?: "", true)
        aiCard.addView(etApiKey)
        val etPersona = field("Persona (e.g. 22 saal ka ladka, casual Hinglish)", sp.getString("persona", "") ?: "")
        aiCard.addView(etPersona)
        val rowDelay = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val etMin = field("Min delay (ms)", sp.getInt("minDelay", 2000).toString())
        etMin.inputType = InputType.TYPE_CLASS_NUMBER
        val lpmin = LinearLayout.LayoutParams(0, -2, 1f)
        lpmin.setMargins(0, dp(8), dp(4), 0)
        etMin.layoutParams = lpmin
        rowDelay.addView(etMin)
        val etMax = field("Max delay (ms)", sp.getInt("maxDelay", 8000).toString())
        etMax.inputType = InputType.TYPE_CLASS_NUMBER
        val lpmax = LinearLayout.LayoutParams(0, -2, 1f)
        lpmax.setMargins(dp(4), dp(8), 0, 0)
        etMax.layoutParams = lpmax
        rowDelay.addView(etMax)
        aiCard.addView(rowDelay)
        val saveAi = btn("SAVE SETTINGS", gradient(C_GREEN, C_GREEN2, 14), Color.WHITE)
        saveAi.setOnClickListener {
            sp.edit()
                .putString("apiKey", etApiKey.text.toString().trim())
                .putString("persona", etPersona.text.toString().trim())
                .putInt("minDelay", etMin.text.toString().toIntOrNull() ?: 2000)
                .putInt("maxDelay", etMax.text.toString().toIntOrNull() ?: 8000)
                .apply()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }
        val salp = LinearLayout.LayoutParams(-1, -2)
        salp.setMargins(0, dp(10), 0, 0)
        saveAi.layoutParams = salp
        aiCard.addView(saveAi)
        root.addView(aiCard)

        // ===== TOGGLES CARD =====
        val togCard = card()
        togCard.addView(toggle("Master switch (auto-reply ON/OFF)", "master", true))
        togCard.addView(toggle("Smart memory (chat khol kar context padhe)", "context", true))
        togCard.addView(toggle("Human-like random delay", "human", true))
        root.addView(togCard)

        // ===== QUEUE CARD =====
        val qCard = card()
        qCard.addView(tv("📋  QUEUE MODE", 15f, C_TEXT, true))
        qCard.addView(tv("list se sabko ek-ek karke reply", 11f, C_SUB))
        val etQueuePkg = field("Queue package", sp.getString("queuePkg", "com.toki.android") ?: "com.toki.android")
        qCard.addView(etQueuePkg)
        val etQueueSec = field("Interval seconds", sp.getInt("queueSec", 12).toString())
        etQueueSec.inputType = InputType.TYPE_CLASS_NUMBER
        qCard.addView(etQueueSec)
        val saveQ = btn("SAVE QUEUE SETTINGS", gradient(Color.parseColor("#334155"), Color.parseColor("#1E293B"), 14), C_TEXT)
        saveQ.setOnClickListener {
            sp.edit()
                .putString("queuePkg", etQueuePkg.text.toString().trim().ifBlank { "com.toki.android" })
                .putInt("queueSec", etQueueSec.text.toString().toIntOrNull() ?: 12)
                .apply()
            Toast.makeText(this, "Queue settings saved", Toast.LENGTH_SHORT).show()
        }
        val sql = LinearLayout.LayoutParams(-1, -2)
        sql.setMargins(0, dp(10), 0, 0)
        saveQ.layoutParams = sql
        qCard.addView(saveQ)
        root.addView(qCard)

        // ===== BOTTOM BUTTONS =====
        val bStart = btn("▶  START QUEUE", gradient(C_GREEN, C_GREEN2, 16), Color.WHITE)
        bStart.setOnClickListener {
            sp.edit()
                .putString("queuePkg", etQueuePkg.text.toString().trim().ifBlank { "com.toki.android" })
                .putInt("queueSec", etQueueSec.text.toString().toIntOrNull() ?: 12)
                .apply()
            AutoAccessibilityService.instance?.startQueue()
            Toast.makeText(this, "QUEUE STARTED", Toast.LENGTH_LONG).show()
            refreshStatus()
        }
        val bstlp = LinearLayout.LayoutParams(-1, -2)
        bstlp.setMargins(0, dp(12), 0, 0)
        bStart.layoutParams = bstlp
        root.addView(bStart)

        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(-1, -2)
            lp.setMargins(0, dp(8), 0, 0)
            layoutParams = lp
        }
        val bStop = btn("STOP", gradient(C_PURPLE2, Color.parseColor("#312E81"), 14), Color.WHITE)
        bStop.setOnClickListener {
            AutoAccessibilityService.instance?.stopQueue()
            Toast.makeText(this, "Queue stopped", Toast.LENGTH_SHORT).show()
            refreshStatus()
        }
        val bslp = LinearLayout.LayoutParams(0, -2, 1f)
        bslp.setMargins(0, 0, dp(4), 0)
        bStop.layoutParams = bslp
        row2.addView(bStop)
        val bTest = btn("TEST REPLY", gradient(C_PURPLE, C_PURPLE2, 14), Color.WHITE)
        bTest.setOnClickListener {
            val reply = ReplyGenerator.generate(
                this, "Test User",
                listOf("Them: hi", "You: hey!"), "kaise ho?"
            )
            Toast.makeText(this, "Test reply: " + (reply ?: "none"), Toast.LENGTH_LONG).show()
        }
        val btlp = LinearLayout.LayoutParams(0, -2, 1f)
        btlp.setMargins(dp(4), 0, 0, 0)
        bTest.layoutParams = btlp
        row2.addView(bTest)
        root.addView(row2)

        val bHow = btn("HOW TO SETUP", gradient(Color.parseColor("#334155"), Color.parseColor("#1E293B"), 14), C_SUB, false)
        bHow.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Setup Steps")
                .setMessage(
                    "1. Notification Access ON + Accessibility ON\n" +
                    "2. API key + persona dalo, SAVE SETTINGS\n" +
                    "3. Queue package = com.toki.android\n" +
                    "4. Master switch ON\n" +
                    "5. Screen ON rakho (charge pe laga do)\n" +
                    "6. START QUEUE dabao\n\n" +
                    "OFF mode mein khud chat karo — app seekhta jayega.\n" +
                    "Rokna ho toh STOP."
                )
                .setPositiveButton("OK", null)
                .show()
        }
        val bhlp = LinearLayout.LayoutParams(-1, -2)
        bhlp.setMargins(0, dp(8), 0, 0)
        bHow.layoutParams = bhlp
        root.addView(bHow)

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun shortName(pkg: String, name: String): String {
        return when (pkg) {
            "com.whatsapp" -> "WhatsApp"
            "com.whatsapp.w4b" -> "WA Business"
            "org.telegram.messenger" -> "Telegram"
            "com.instagram.android" -> "Instagram"
            "com.facebook.orca" -> "Messenger"
            else -> if (name.length > 12) name.take(11) + "…" else name
        }
    }

    private fun updateCount() {
        chipCount.text = appsSet.size.toString() + " selected"
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        if (!::tvStatus.isInitialized) return
        val notifOn = isNotificationServiceEnabled()
        val accOn = AutoAccessibilityService.instance != null
        val queue = AutoAccessibilityService.queueActive
        tvStatus.text =
            "Accessibility: " + (if (accOn) "ON" else "OFF") +
            "  |  Queue Mode: " + (if (queue) "RUNNING" else "OFF") +
            (if (notifOn) "" else "  |  Notification: OFF")
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(packageName)
    }
}
