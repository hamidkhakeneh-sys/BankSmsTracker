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
import java.security.SecureRandom
import java.text.NumberFormat
import java.util.Locale
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object DatabaseSecurityManager {
    private const val PREFS_NAME = "hamtaraz_secure_vault_v2"
    private const val KEY_USERNAME = "db_username"
    private const val KEY_SALT_HEX = "db_salt_hex"
    private const val KEY_PASS_HASH = "db_pass_hash"
    private const val KEY_FAILED_ATTEMPTS = "db_failed_attempts"
    private const val KEY_LOCK_UNTIL = "db_lock_until_ms"
    private const val KEY_FLAG_SECURE = "flag_secure_enabled"

    fun isCredentialsConfigured(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return !prefs.getString(KEY_USERNAME, null).isNullOrBlank() &&
                !prefs.getString(KEY_PASS_HASH, null).isNullOrBlank() &&
                !prefs.getString(KEY_SALT_HEX, null).isNullOrBlank()
    }

    fun getSavedUsername(context: Context): String {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_USERNAME, "") ?: ""
    }

    fun isScreenPrivacyEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_FLAG_SECURE, false)
    }

    fun setScreenPrivacyEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_FLAG_SECURE, enabled)
            .apply()
    }

    fun getRemainingLockSeconds(context: Context): Long {
        val lockUntil = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_LOCK_UNTIL, 0L)
        val diffMs = lockUntil - System.currentTimeMillis()
        return if (diffMs > 0L) (diffMs / 1000L) + 1L else 0L
    }

    fun setupCredentials(context: Context, username: String, password: String): ByteArray {
        val cleanUser = username.trim()
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val saltHex = salt.joinToString("") { "%02x".format(it) }
        val authVerifier = pbkdf2(password, salt, iterations = 120_000, keyLenBits = 256)
            .joinToString("") { "%02x".format(it) }

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_USERNAME, cleanUser)
            .putString(KEY_SALT_HEX, saltHex)
            .putString(KEY_PASS_HASH, authVerifier)
            .putInt(KEY_FAILED_ATTEMPTS, 0)
            .putLong(KEY_LOCK_UNTIL, 0L)
            .apply()

        return pbkdf2("$cleanUser:$password", salt, iterations = 120_000, keyLenBits = 256)
    }

    fun verifyAndDeriveKey(context: Context, username: String, password: String): Pair<ByteArray?, String> {
        val lockSec = getRemainingLockSeconds(context)
        if (lockSec > 0L) {
            return Pair(null, "تلاش‌های ناموفق زیاد بود. لطفاً $lockSec ثانیه صبر کنید.")
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedUser = prefs.getString(KEY_USERNAME, "") ?: ""
        val savedHash = prefs.getString(KEY_PASS_HASH, "") ?: ""
        val saltHex = prefs.getString(KEY_SALT_HEX, "") ?: ""

        if (username.trim() != savedUser || saltHex.isEmpty()) {
            recordFailedAttempt(context)
            return Pair(null, "نام کاربری یا رمز عبور اشتباه است!")
        }

        val salt = hexToBytes(saltHex)
        val candidateHash = pbkdf2(password, salt, iterations = 120_000, keyLenBits = 256)
            .joinToString("") { "%02x".format(it) }

        return if (constantTimeEquals(candidateHash, savedHash)) {
            prefs.edit().putInt(KEY_FAILED_ATTEMPTS, 0).putLong(KEY_LOCK_UNTIL, 0L).apply()
            val dbKey = pbkdf2("${username.trim()}:$password", salt, iterations = 120_000, keyLenBits = 256)
            Pair(dbKey, "")
        } else {
            recordFailedAttempt(context)
            Pair(null, "نام کاربری یا رمز عبور اشتباه است!")
        }
    }

    private fun recordFailedAttempt(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val attempts = prefs.getInt(KEY_FAILED_ATTEMPTS, 0) + 1
        val editor = prefs.edit().putInt(KEY_FAILED_ATTEMPTS, attempts)
        if (attempts >= 5) {
            editor.putLong(KEY_LOCK_UNTIL, System.currentTimeMillis() + 30_000L)
            editor.putInt(KEY_FAILED_ATTEMPTS, 0)
        }
        editor.apply()
    }

    private fun pbkdf2(secret: String, salt: ByteArray, iterations: Int, keyLenBits: Int): ByteArray {
        val spec = PBEKeySpec(secret.toCharArray(), salt, iterations, keyLenBits)
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val out = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            out[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return out
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }
}

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

fun openSmsInPhoneApp(context: Context, senderAddress: String) {
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
