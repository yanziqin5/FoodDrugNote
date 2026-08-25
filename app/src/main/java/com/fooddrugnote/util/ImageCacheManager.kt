package com.fooddrugnote.util

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 选图缓存管理：
 * - 将 content:// 相册图片拷贝为应用私有文件（getExternalFilesDir/image_cache），
 *   生成唯一文件名。该目录随应用卸载删除、系统不会自动回收，适合跨进程重建读取。
 * - 提供过期清理（默认 7 天）与容量上限（LRU）能力，且仅清理“无数据库引用”的孤立文件，
 *   不会误删历史记录引用的图片。
 */
object ImageCacheManager {

    private const val EXPIRE_DAYS = 7
    private const val EXPIRE_MS = EXPIRE_DAYS * 24L * 60 * 60 * 1000
    private const val MAX_CACHE_FILES = 50

    private fun cacheDir(context: Context): File {
        val base = context.getExternalFilesDir(null) ?: context.filesDir
        val dir = File(base, "image_cache")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /** 将 content:// Uri 拷贝到缓存目录，返回本地绝对路径；失败返回 null */
    fun copyUriToCache(context: Context, uri: Uri): String? {
        val dest = File(
            cacheDir(context),
            "img_${System.currentTimeMillis()}_${UUID.randomUUID()}.jpg"
        )
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { out -> input.copyTo(out) }
            }
            dest.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 清理超期且无引用的孤立文件。
     * @param retainPaths 不应被删除的路径集合（历史记录引用的图片 + 当前预览等）
     */
    fun cleanupExpired(
        context: Context,
        retainPaths: Set<String>,
        now: Long = System.currentTimeMillis()
    ) {
        val expireBefore = now - EXPIRE_MS
        cacheDir(context).listFiles()?.forEach { file ->
            if (file.isFile &&
                !retainPaths.contains(file.absolutePath) &&
                file.lastModified() < expireBefore
            ) {
                file.delete()
            }
        }
    }

    /** 按修改时间 LRU 控制容量上限，排除 retainPaths */
    fun enforceCapacity(
        context: Context,
        retainPaths: Set<String>,
        maxFiles: Int = MAX_CACHE_FILES
    ) {
        val files = cacheDir(context).listFiles()
            ?.filter { it.isFile && !retainPaths.contains(it.absolutePath) }
            ?: return
        if (files.size <= maxFiles) return
        files.sortedBy { it.lastModified() }
            .take(files.size - maxFiles)
            .forEach { it.delete() }
    }
}
