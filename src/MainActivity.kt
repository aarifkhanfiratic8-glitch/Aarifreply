package com.autoreply.ai

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val sp by lazy { getSharedPreferences(Prefs.FILE, MODE_PRIVATE) }
    private lateinit var tvStatus: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        fun label(text: String) = TextView(this).apply {
            this.text = text
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(14), 0, dp(6))
        }

        fun button(text: String, onClick: (View) -> Unit) = Button(this).apply {
            this.text = text
            setOnClickListener(onClick)
        }

        fun editText(hint: String, inputType: Int? = null) = EditText(this).apply {
            this.hint = hint
            inputType?.let { this.inputType = it }
        }

        fun switchW(text: String, key: String, def: Boolean) = Switch(this).apply {
            this.text = text
            isChecked = sp.getBoolean(key, def)
            setOnCheckedChangeListener { _: CompoundButton, c: Boolean ->
                sp.edit().putBoolean(key, c).apply()
            }
        }

        root.addView(TextView(this).apply {
            text = "AutoReply AI"
            textSize = 26f
            setTypeface(typeface, Typeface.BOLD)
        })

        tvStatus = TextView(this).apply { textSize = 15f }
        root.addView(tvStatus)

        root.addView(button("1. Enable Notification Access") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        root.addView(button("2. Enable Accessibility") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        root.addView(label("Auto-reply ON these apps:"))

        val appContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(appContainer)

        val appsSet = sp.getStringSet("apps", setOf("com.whatsapp"))!!.toMutableSet()
        val allApps = Prefs.SUPPORTED.toMutableMap()
        (sp.getString("customApps", "") ?: "").split(",")
            .filter { it.isNotBlank() }
            .forEach { allApps[it] = it }

        allApps.forEach { (pkg, name) ->
            val cb = CheckBox(this).apply {
                this.text = if (name == pkg) pkg else "$name ($pkg)"
                isChecked = pkg in appsSet
                setOnCheckedChangeListener { _: CompoundButton, c: Boolean ->
                    if (c) appsSet.add(pkg) else appsSet.remove(pkg)
                    sp.edit().putStringSet("apps", appsSet).apply()
                }
            }
            appContainer.addView(cb)
        }

        val etCustom = editText("Custom package (e.g. com.example.app)")
        root.addView(etCustom)
        root.addView(button("Add Custom App") {
            val p = etCustom.text.toString().trim()
            if (p.isNotEmpty()) {
                sp.edit().putString("customApps", (sp.getString("customApps", "") ?: "") + "," + p).apply()
                Toast.makeText(this, "Added: $p", Toast.LENGTH_SHORT).show()
                etCustom.setText("")
                recreate()
            }
        })

        root.addView(label("AI Settings (optional):"))

        val etApiKey = editText("OpenAI API key (khali = templates use honge)",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        etApiKey.setText(sp.getString("apiKey", ""))
        root.addView(etApiKey)

        val etPersona = editText("Tera persona (e.g. 22 saal ka ladka, casual Hinglish)")
        etPersona.setText(sp.getString("persona", ""))
        root.addView(etPersona)

        val etMin = editText("Min delay (ms) - default 2000", InputType.TYPE_CLASS_NUMBER)
        etMin.setText(sp.getInt("minDelay", 2000).toString())
        root.addView(etMin)

        val etMax = editText("Max delay (ms) - default 8000", InputType.TYPE_CLASS_NUMBER)
        etMax.setText(sp.getInt("maxDelay", 8000).toString())
        root.addView(etMax)

        root.addView(switchW("Master switch (auto-reply ON/OFF)", "master", true))
        root.addView(switchW("Smart memory (chat khol kar context padhe)", "context", true))
        root.addView(switchW("Human-like random delay", "human", true))

        root.addView(button("Save Settings") {
            sp.edit()
                .putString("apiKey", etApiKey.text.toString().trim())
                .putString("persona", etPersona.text.toString().trim())
                .putInt("minDelay", etMin.text.toString().toIntOrNull() ?: 2000)
                .putInt("maxDelay", etMax.text.toString().toIntOrNull() ?: 8000)
                .apply()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        })

        root.addView(label("QUEUE MODE (list se sabko ek-ek karke reply):"))

        val etQueuePkg = editText("Queue package (default com.toki.android)")
        etQueuePkg.setText(sp.getString("queuePkg", "com.toki.android"))
        root.addView(etQueuePkg)

        val etQueueSec = editText("Interval seconds (default 12)", InputType.TYPE_CLASS_NUMBER)
        etQueueSec.setText(sp.getInt("queueSec", 12).toString())
        root.addView(etQueueSec)

        root.addView(button("SAVE QUEUE SETTINGS") {
            sp.edit()
                .putString("queuePkg", etQueuePkg.text.toString().trim().ifBlank { "com.toki.android" })
                .putInt("queueSec", etQueueSec.text.toString().toIntOrNull() ?: 12)
                .apply()
            Toast.makeText(this, "Queue settings saved", Toast.LENGTH_SHORT).show()
        })

        root.addView(button("START QUEUE (screen ON rakhna, app khud sab handle karega)") {
            sp.edit()
                .putString("queuePkg", etQueuePkg.text.toString().trim().ifBlank { "com.toki.android" })
                .putInt("queueSec", etQueueSec.text.toString().toIntOrNull() ?: 12)
                .apply()
            AutoAccessibilityService.instance?.startQueue()
            Toast.makeText(this, "QUEUE STARTED - screen ON rakhna!", Toast.LENGTH_LONG).show()
            refreshStatus()
        })

        root.addView(button("STOP QUEUE") {
            AutoAccessibilityService.instance?.stopQueue()
            Toast.makeText(this, "Queue stopped", Toast.LENGTH_SHORT).show()
            refreshStatus()
        })

        root.addView(button("Test Reply Generator") {
            val reply = ReplyGenerator.generate(this, "Test User",
                listOf("Them: hi", "You: hey!"), "kaise ho?")
            Toast.makeText(this, "Test reply: " + (reply ?: "none"), Toast.LENGTH_LONG).show()
        })

        root.addView(button("How to Setup") {
            AlertDialog.Builder(this)
                .setTitle("Setup Steps")
                .setMessage(
                    "QUEUE MODE (sabse asaan):\n" +
                    "1. Notification Access ON + Accessibility ON\n" +
                    "2. API key + persona dalo, Save\n" +
                    "3. Queue package = com.toki.android confirm karo\n" +
                    "4. Master switch ON\n" +
                    "5. Phone ki screen ON rakho (charge pe laga do)\n" +
                    "6. START QUEUE dabao\n" +
                    "7. App khud Toki khole gi, har pending chat ka\n" +
                    "   reply degi, back ayegi, agle ka karegi!\n\n" +
                    "Rokna ho toh STOP QUEUE dabao."
                )
                .setPositiveButton("OK", null)
                .show()
        })

        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
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
            "Notification Access: " + (if (notifOn) "ON" else "OFF") + "\n" +
            "Accessibility: " + (if (accOn) "ON" else "OFF") + "\n" +
            "Queue Mode: " + (if (queue) "RUNNING" else "OFF")
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return flat != null && flat.contains(packageName)
    }
}
