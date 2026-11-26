package com.example.banksmstracker

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import java.security.MessageDigest
import java.text.NumberFormat
import java.util.Locale

// ============================================================================
// ۱. مدیریت امنیت دیتابیس با نام کاربری و رمز عبور (SQLCipher Passphrase)
// ============================================================================
object DatabaseSecurityManager {
    private const val PREFS_NAME = "hamtaraz_secure_vault"
    private const val KEY_USERNAME = "db_username"
    private const val KEY_PASS_HASH = "db_pass_hash"

    fun isCredentialsConfigured(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return !prefs.getString(KEY_USERNAME, null).isNullOrBlank() &&
                !prefs.getString(KEY_PASS_HASH, null).isNullOrBlank()
    }

    fun getSavedUsername(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_USERNAME, "") ?: ""
    }

    fun setupCredentials(context: Context, username: String, password: String): ByteArray {
        val cleanUser = username.trim()
        val hash = sha256("$cleanUser:$password:HamTarazVault2026")
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_USERNAME, cleanUser)
            .putString(KEY_PASS_HASH, hash)
            .apply()
        return deriveSqlCipherKey(cleanUser, password)
    }

    fun verifyAndDeriveKey(context: Context, username: String, password: String): ByteArray? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedUser = prefs.getString(KEY_USERNAME, "") ?: ""
        val savedHash = prefs.getString(KEY_PASS_HASH, "") ?: ""
        val cleanUser = username.trim()
        if (cleanUser != savedUser) return null
        val candidateHash = sha256("$cleanUser:$password:HamTarazVault2026")
        return if (candidateHash == savedHash) {
            deriveSqlCipherKey(cleanUser, password)
        } else {
            null
        }
    }

    private fun deriveSqlCipherKey(username: String, password: String): ByteArray {
        return sha256("SQLCIPHER_KEY_${username}_$password").toByteArray(Charsets.UTF_8)
    }

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

// ============================================================================
// ۲. تبدیل عدد به حروف فارسی و معادل تومان (برای نمایش زیر فیلدهای مبلغی)
// ============================================================================
object PersianNumberToWords {
    private val ones = listOf("", "یک", "دو", "سه", "چهار", "پنج", "شش", "هفت", "هشت", "نه")
    private val teens = listOf("ده", "یازده", "دوازده", "سیزده", "چهارده", "پانزده", "شانزده", "هفده", "هجده", "نوزده")
    private val tens = listOf("", "", "بیست", "سی", "چهل", "پنجاه", "شصت", "هفتاد", "هشتاد", "نود")
    private val hundreds = listOf("", "یکصد", "دویست", "سیصد", "چهارصد", "پانصد", "ششصد", "هفتصد", "هشتصد", "نهصد")
    private val scales = listOf("", "هزار", "میلیون", "میلیارد", "تریلیون")

    fun rialToTomanWords(rialAmount: Long): String {
        if (rialAmount <= 0L) return "صفر تومان"
        val toman = rialAmount / 10L
        val remainderRial = rialAmount % 10L
        val formattedToman = NumberFormat.getNumberInstance(Locale.US).format(toman)
        val words = convertNumberToWords(toman)
        return buildString {
            append("معادل: $formattedToman تومان ($words تومان")
            if (remainderRial > 0L) {
                append(" و ${ones[remainderRial.toInt()]} ریال")
            }
            append(")")
        }
    }

    fun convertNumberToWords(number: Long): String {
        if (number == 0L) return "صفر"
        var n = number
        val parts = mutableListOf<String>()
        var scaleIdx = 0

        while (n > 0 && scaleIdx < scales.size) {
            val chunk = (n % 1000L).toInt()
            if (chunk > 0) {
                val chunkWords = threeDigitsToWords(chunk)
                val scaleName = scales[scaleIdx]
                parts.add(if (scaleName.isNotEmpty()) "$chunkWords $scaleName" else chunkWords)
            }
            n /= 1000L
            scaleIdx++
        }
        return parts.reversed().joinToString(" و ")
    }

    private fun threeDigitsToWords(num: Int): String {
        val h = num / 100
        val rem = num % 100
        val t = rem / 10
        val o = rem % 10

        val list = mutableListOf<String>()
        if (h > 0) list.add(hundreds[h])
        if (rem in 10..19) {
            list.add(teens[rem - 10])
        } else {
            if (t > 0) list.add(tens[t])
            if (o > 0) list.add(ones[o])
        }
        return list.joinToString(" و ")
    }
}

/**
 * تبدیل ظاهری ارقام به فرمت سه‌رقمی جداشده با کاما (1,250,000) هنگام تایپ کاربر
 */
class ThousandSeparatorTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        if (raw.isEmpty()) return TransformedText(text, OffsetMapping.Identity)

        val formatted = buildString {
            var count = 0
            for (i in raw.length - 1 downTo 0) {
                append(raw[i])
                count++
                if (count % 3 == 0 && i > 0 && raw[i - 1] != '-') {
                    append(',')
                }
            }
        }.reversed()

        val offsetMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                if (offset <= 0) return 0
                val digitsBefore = offset.coerceAtMost(raw.length)
                var transformedIdx = 0
                var seenDigits = 0
                while (transformedIdx < formatted.length && seenDigits < digitsBefore) {
                    if (formatted[transformedIdx] != ',') seenDigits++
                    transformedIdx++
                }
                return transformedIdx
            }

            override fun transformedToOriginal(offset: Int): Int {
                if (offset <= 0) return 0
                val bounded = offset.coerceAtMost(formatted.length)
                var digitsCount = 0
                for (i in 0 until bounded) {
                    if (formatted[i] != ',') digitsCount++
                }
                return digitsCount.coerceAtMost(raw.length)
            }
        }

        return TransformedText(AnnotatedString(formatted), offsetMapping)
    }
}

/**
 * کامپوننت استاندارد فیلد مبلغی (ریال):
 * دارای جداکننده هزارگان حین تایپ + باکس نمایش لحظه‌ای مبلغ به حروف و معادل تومان در زیر فیلد
 */
@Composable
fun RialAmountTextField(
    rawDigitsValue: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    allowNegative: Boolean = false
) {
    val numericAmount = remember(rawDigitsValue) {
        rawDigitsValue.replace("-", "").toLongOrNull() ?: 0L
    }

    Column(modifier = modifier) {
        OutlinedTextField(
            value = rawDigitsValue,
            onValueChange = { input ->
                val cleaned = input.filterIndexed { index, c ->
                    c.isDigit() || (allowNegative && index == 0 && c == '-')
                }
                if (cleaned.length <= 15) {
                    onValueChange(cleaned)
                }
            },
            label = { Text(label) },
            visualTransformation = ThousandSeparatorTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            trailingIcon = {
                Text(
                    text = "ریال",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray,
                    modifier = Modifier.padding(end = 8.dp)
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (numericAmount > 0L) {
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                color = Color(0xFFE0F2F1),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Payments,
                        contentDescription = null,
                        tint = Color(0xFF00695C),
                        modifier = Modifier.size(15.dp)
                    )
                    Text(
                        text = PersianNumberToWords.rialToTomanWords(numericAmount),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF004D40)
                    )
                }
            }
        }
    }
}

// ============================================================================
// ۳. باز کردن مستقیم پیامک در اپلیکیشن پیام‌رسان پیش‌فرض گوشی
// ============================================================================
fun openSmsInPhoneApp(context: Context, smsId: String, senderAddress: String) {
    try {
        val cleanSender = senderAddress.trim()
        if (cleanSender.isNotEmpty() && cleanSender != "نامشخص" && !cleanSender.contains("فایل")) {
            val smsUri = Uri.parse("smsto:${Uri.encode(cleanSender)}")
            val intent = Intent(Intent.ACTION_SENDTO, smsUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                return
            }
        }
        val defaultSmsPkg = Telephony.Sms.getDefaultSmsPackage(context)
        if (defaultSmsPkg != null) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(defaultSmsPkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launchIntent)
            }
        }
    } catch (_: Exception) { }
}

private fun getSmsThreadId(context: Context, smsId: Long): Long? {
    return try {
        val cursor = context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.THREAD_ID),
            "${Telephony.Sms._ID} = ?",
            arrayOf(smsId.toString()),
            null
        )
        cursor?.use {
            if (it.moveToFirst()) {
                val idx = it.getColumnIndex(Telephony.Sms.THREAD_ID)
                if (idx != -1) return it.getLong(idx)
            }
        }
        null
    } catch (e: Exception) {
        null
    }
}
