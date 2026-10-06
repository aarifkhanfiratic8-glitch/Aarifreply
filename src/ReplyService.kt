package com.autoreply.ai

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class ReplyService : NotificationListenerService() {

    companion object {
        private const val TAG = "AutoReply"
        var pendingChat: Triple<String, PendingIntent, String>? = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try { handle(sbn) } catch (e: Exception) { Log.e(TAG, "error", e) }
    }

    private fun handle(sbn: StatusBarNotification) {
        val pkg = sbn.packageName
        if (!Prefs.masterEnabled(this)) return
        if (AutoAccessibilityService.queueActive) return // queue mode khud sambhalega
        if (!Prefs.isPackageEnabled(this, pkg)) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() } ?: return
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))?.toString()?.takeIf { it.isNotBlank() } ?: return

        if (title.contains("TOKI", ignoreCase = true) && title.contains("TEAM", ignoreCase = true)) return
        if (text.contains("[Match]", ignoreCase = true)) return
        if (ChatHistory.lastMeText(this, title) == text) return

        if (Prefs.contextMode(this)) {
            val contentIntent = sbn.notification.contentIntent ?: return
            Log.d(TAG, "smart mode: opening chat with $title")
            Thread {
                try { Thread.sleep(Prefs.humanDelayMs(this)) } catch (_: Exception) {}
                try {
                    pendingChat = Triple(text, contentIntent, title)
                    contentIntent.send()
                } catch (e: Exception) {
                    Log.e(TAG, "open chat failed", e)
                    pendingChat = null
                }
            }.start()
        } else {
            Thread {
                try { Thread.sleep(Prefs.humanDelayMs(this)) } catch (_: Exception) {}
                if (tryInlineReply(sbn, text, title)) {
                    ChatHistory.add(this, title, "them", text)
                }
            }.start()
        }
    }

    private fun tryInlineReply(sbn: StatusBarNotification, text: String, title: String): Boolean {
        val actions = sbn.notification.actions ?: return false
        for (action in actions) {
            val remoteInputs = action.remoteInputs ?: continue
            if (remoteInputs.isEmpty()) continue
            try {
                val reply = ReplyGenerator.generate(this, title, emptyList(), text) ?: continue
                val fillIn = Intent()
                val results = Bundle()
                for (ri in remoteInputs) results.putCharSequence(ri.resultKey, reply)
                RemoteInput.addResultsToIntent(remoteInputs, fillIn, results)
                action.actionIntent.send(this, 0, fillIn)
                ChatHistory.add(this, title, "me", reply)
                return true
            } catch (e: Exception) {
                Log.e(TAG, "inline reply failed", e)
            }
        }
        return false
    }
}
