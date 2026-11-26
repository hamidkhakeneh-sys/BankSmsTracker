package com.example.banksmstracker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object ImageReceiptHelper {

    fun compressAndSaveReceipt(context: Context, sourceUri: Uri, smsId: String): String? {
        return try {
            val receiptsDir = File(context.filesDir, "receipts").apply {
                if (!exists()) mkdirs()
            }

            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(sourceUri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }

            val maxDimension = 1024
            var inSampleSize = 1
            if (options.outHeight > maxDimension || options.outWidth > maxDimension) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while ((halfHeight / inSampleSize) >= maxDimension && (halfWidth / inSampleSize) >= maxDimension) {
                    inSampleSize *= 2
                }
            }

            val decodeOptions = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
            val sampledBitmap = context.contentResolver.openInputStream(sourceUri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOptions)
            } ?: return null

            val ratio = minOf(
                maxDimension.toFloat() / sampledBitmap.width.toFloat(),
                maxDimension.toFloat() / sampledBitmap.height.toFloat(),
                1.0f
            )
            val finalBitmap = if (ratio < 1.0f) {
                Bitmap.createScaledBitmap(
                    sampledBitmap,
                    (sampledBitmap.width * ratio).toInt(),
                    (sampledBitmap.height * ratio).toInt(),
                    true
                )
            } else {
                sampledBitmap
            }

            val safeFileName = "receipt_${smsId.hashCode()}_${System.currentTimeMillis()}.jpg"
            val outFile = File(receiptsDir, safeFileName)
            FileOutputStream(outFile).use { out ->
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 65, out)
            }

            if (finalBitmap != sampledBitmap) sampledBitmap.recycle()
            sampledBitmap.recycle()

            outFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    fun getFileSizeKb(path: String): Long {
        val f = File(path)
        return if (f.exists()) (f.length() / 1024L).coerceAtLeast(1L) else 0L
    }
}
