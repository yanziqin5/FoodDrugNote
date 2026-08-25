package com.fooddrugnote.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 扫描历史记录实体
 * 缓存每次扫描结果，相同药品7天内不重复调用API
 */
@Entity(tableName = "scan_records")
data class ScanRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 药品名称（从结果中提取的关键词，用于展示） */
    val drugKey: String,
    /** 扫描图片本地路径 */
    val imagePath: String,
    /** AI 分析结果文本 */
    val resultText: String,
    /** 图片感知哈希，用于 7 天内同药盒缓存去重 */
    val imageHash: String = "",
    /** 扫描时间戳 */
    val scanTime: Long = System.currentTimeMillis()
)
