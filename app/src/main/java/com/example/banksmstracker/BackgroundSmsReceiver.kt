package com.example.banksmstracker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class BackgroundSmsReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "bank_sms_channel"
        const val EXTRA_SMS_ID = "extra_sms_id"
        private const val BLOCKED_PREFS = "hamtaraz_blocked_senders"
        private const val KEY_BLOCKED = "senders"

        fun syncBlockedSenders(context: Context, blocked: Set<String>) {
            context.getSharedPreferences(BLOCKED_PREFS, Context.MODE_PRIVATE)
                .edit()
                .putStringSet(KEY_BLOCKED, blocked)
                .apply()
        }

        fun isSenderBlockedFast(context: Context, sender: String): Boolean {
            val set = context.getSharedPreferences(BLOCKED_PREFS, Context.MODE_PRIVATE)
                .getStringSet(KEY_BLOCKED, emptySet()) ?: emptySet()
            return set.contains(sender)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val sender = messages[0].originatingAddress ?: "نامشخص"
        if (isSenderBlockedFast(context, sender)) return

        val timestamp = messages[0].timestampMillis
        val fullBody = messages.joinToString("") { it.messageBody ?: "" }

        val quickCheck = IranBankSmsParser.parseSms("temp", sender, fullBody, timestamp) ?: return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                delay(1200L)
                val realSmsId = findLatestSmsId(context, fullBody) ?: timestamp.toString()
                showTransactionNotification(context, quickCheck.copy(id = realSmsId))
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun findLatestSmsId(context: Context, body: String): String? {
        val cursor = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms._ID, Telephony.Sms.BODY),
            null,
            null,
            "${Telephony.Sms.DATE} DESC LIMIT 2"
        )
        cursor?.use {
            val idIdx = it.getColumnIndex(Telephony.Sms._ID)
            val bodyIdx = it.getColumnIndex(Telephony.Sms.BODY)
            while (it.moveToNext()) {
                if (it.getString(bodyIdx) == body) {
                    return it.getString(idIdx)
                }
            }
        }
        return null
    }

    private fun showTransactionNotification(context: Context, tx: BankTransaction) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) return
        }

        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "تراکنش‌های بانکی",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "اعلان دریافت پیامک بانکی"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            notificationManager.createNotificationChannel(channel)
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_SMS_ID, tx.id)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            tx.id.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val typeTitle = if (tx.type == TransactionType.DEPOSIT) "واریز جدید" else "برداشت جدید"
        val title = "هم‌تراز • $typeTitle"
        val content = "تراکنش جدید در ${tx.bankName} ثبت شد • برای بررسی لمس کنید"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(tx.id.hashCode(), notification)
    }
}
