package com.example.banksmstracker

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

object ImageReceiptHelper {

    /**
     * فشرده‌سازی هوشمند تصویر فاکتور با اصلاح خودکار زاویه چرخش دوربین (EXIF)
     * و ذخیره با مسیر نسبی در پوشه داخلی برنامه
     */
    fun compressAndSaveReceipt(context: Context, sourceUri: Uri, txKey: String): String? {
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

            val rotationDegrees = readExifRotation(context, sourceUri)
            val rotatedBitmap = if (rotationDegrees != 0f) {
                val matrix = Matrix().apply { postRotate(rotationDegrees) }
                Bitmap.createBitmap(sampledBitmap, 0, 0, sampledBitmap.width, sampledBitmap.height, matrix, true).also {
                    if (it != sampledBitmap) sampledBitmap.recycle()
                }
            } else {
                sampledBitmap
            }

            val ratio = minOf(
                maxDimension.toFloat() / rotatedBitmap.width.toFloat(),
                maxDimension.toFloat() / rotatedBitmap.height.toFloat(),
                1.0f
            )
            val finalBitmap = if (ratio < 1.0f) {
                Bitmap.createScaledBitmap(
                    rotatedBitmap,
                    (rotatedBitmap.width * ratio).toInt(),
                    (rotatedBitmap.height * ratio).toInt(),
                    true
                ).also {
                    if (it != rotatedBitmap) rotatedBitmap.recycle()
                }
            } else {
                rotatedBitmap
            }

            val fileName = "receipt_${txKey.hashCode()}_${System.currentTimeMillis()}.jpg"
            val outFile = File(receiptsDir, fileName)
            FileOutputStream(outFile).use { out ->
                finalBitmap.compress(Bitmap.CompressFormat.JPEG, 68, out)
            }
            finalBitmap.recycle()

            "receipts/$fileName"
        } catch (_: Exception) {
            null
        }
    }

    fun resolveReceiptFile(context: Context, storedPath: String): File? {
        if (storedPath.isBlank()) return null
        val file = if (storedPath.startsWith("/")) File(storedPath) else File(context.filesDir, storedPath)
        return if (file.exists()) file else null
    }

    fun deleteReceiptFile(context: Context, storedPath: String) {
        resolveReceiptFile(context, storedPath)?.delete()
    }

    fun getFileSizeKb(context: Context, storedPath: String): Long {
        val f = resolveReceiptFile(context, storedPath) ?: return 0L
        return (f.length() / 1024L).coerceAtLeast(1L)
    }

    private fun readExifRotation(context: Context, uri: Uri): Float {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val exif = ExifInterface(input)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
            } ?: 0f
        } catch (_: Exception) {
            0f
        }
    }
}
