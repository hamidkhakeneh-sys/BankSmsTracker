package com.example.banksmstracker

import android.Manifest
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

/**
 * گیرنده رویدادمحور با مصرف باتری نزدیک به صفر:
 * بدون هیچ سرویس پس‌زمینه دائم یا بیدار نگه داشتن CPU؛
 * سیستم‌عامل فقط در لحظه دریافت پیامک بانکی این گیرنده را بیدار می‌کند،
 * ظرف ۱.۵ ثانیه نوتیفیکیشن را صادر کرده و بلافاصله به خواب عمیق (Doze) بازمی‌گردد.
 */
class BackgroundSmsReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "bank_sms_channel"
        const val EXTRA_SMS_ID = "extra_sms_id"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isNullOrEmpty()) return

        val sender = messages[0].originatingAddress ?: "نامشخص"
        val timestamp = messages[0].timestampMillis
        val fullBody = messages.joinToString("") { it.messageBody ?: "" }

        // فیلتر سریع در حافظه قبل از هرگونه دسترسی به دیتابیس یا I/O برای حفظ حداکثری شارژ باتری
        val quickCheck = IranBankSmsParser.parseSms("temp", sender, fullBody, timestamp) ?: return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getDatabase(context).appDao()
                val blockedSenders = dao.getAllIgnored().filter { it.isSenderBlocked }.map { it.keyId }.toSet()
                if (blockedSenders.contains(sender)) return@launch

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
                description = "اعلان دریافت پیامک واریز و برداشت بانکی"
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

        val typeTitle = if (tx.type == TransactionType.DEPOSIT) "واریز به حساب" else "برداشت از حساب"
        val title = "$typeTitle (${tx.accountKey})"
        val content = "مبلغ: ${formatCurrency(tx.amount)} ریال • برای دسته‌بندی لمس کنید"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(tx.id.hashCode(), notification)
    }
}
