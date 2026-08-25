package com.fooddrugnote.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.min

/**
 * 图片工具类
 * 功能：创建拍照临时文件、Uri转Bitmap、图片压缩、保存图片
 */
object ImageUtil {

    /**
     * 创建拍照用的临时图片文件（兼容FileProvider）
     */
    fun createImageFile(context: Context): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "image_cache")
        if (!storageDir.exists()) storageDir.mkdirs()
        return File.createTempFile("IMG_${timeStamp}_", ".jpg", storageDir)
    }

    /**
     * 从 Uri 读取 Bitmap，并按目标最大尺寸等比压缩
     * @param maxSize 目标最长边像素，默认1280，减小base64体积、加速上传
     */
    fun uriToCompressedBitmap(context: Context, uri: Uri, maxSize: Int = 1280): Bitmap? {
        return try {
            // 第一步：只读取尺寸，不加载全图
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }

            var sampleSize = 1
            while (options.outWidth / sampleSize > maxSize || options.outHeight / sampleSize > maxSize) {
                sampleSize *= 2
            }

            // 第二步：按采样率加载
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                // RGB_565 内存更小，药盒识别在云端完成，本地轻量解码不影响识别精度（预览可能有极轻微偏色）
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            context.contentResolver.openInputStream(uri)?.use { input ->
                BitmapFactory.decodeStream(input, null, decodeOptions)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 将 Bitmap 保存为本地文件（用于历史记录回显）
     */
    fun saveBitmapToFile(context: Context, bitmap: Bitmap, fileName: String): String {
        val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "image_cache")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, fileName)
        FileOutputStream(file).use { fos ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, fos)
        }
        return file.absolutePath
    }

    /**
     * 从文件路径读取Bitmap
     */
    fun loadBitmapFromPath(path: String, maxSize: Int = 800): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, options)

            var sampleSize = 1
            while (options.outWidth / sampleSize > maxSize || options.outHeight / sampleSize > maxSize) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                // RGB_565 内存更小，药盒识别在云端完成，本地轻量解码不影响识别精度（预览可能有极轻微偏色）
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeFile(path, decodeOptions)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 计算图片的感知哈希（aHash，8x8 = 64bit）
     * 将图片缩放为灰度 8x8，按平均灰度二值化，得到可比较的指纹。
     * 同一药盒不同角度/光线下拍摄，哈希汉明距离很小，可用于缓存去重。
     */
    fun perceptualHash(bitmap: Bitmap, size: Int = 8): String {
        val small = Bitmap.createScaledBitmap(bitmap, size, size, true)
        val pixels = IntArray(size * size)
        small.getPixels(pixels, 0, size, 0, 0, size, size)
        val gray = DoubleArray(size * size)
        var sum = 0.0
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val v = 0.299 * r + 0.587 * g + 0.114 * b
            gray[i] = v
            sum += v
        }
        val avg = sum / gray.size
        val sb = StringBuilder(size * size)
        for (v in gray) {
            sb.append(if (v >= avg) '1' else '0')
        }
        // 仅当缩略图与原始 Bitmap 不是同一对象时才回收，
        // 否则（极端情况：源图恰好 8x8）会误回收调用方仍在使用的 Bitmap
        if (small !== bitmap && !small.isRecycled) small.recycle()
        return sb.toString()
    }

    /**
     * 计算两个等长感知哈希串的汉明距离（不同位数）
     */
    fun hammingDistance(a: String, b: String): Int {
        var distance = 0
        val len = minOf(a.length, b.length)
        for (i in 0 until len) {
            if (a[i] != b[i]) distance++
        }
        return distance
    }
}
