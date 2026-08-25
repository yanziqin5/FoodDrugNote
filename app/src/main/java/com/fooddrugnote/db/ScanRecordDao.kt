package com.fooddrugnote.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ScanRecordDao {

    /** 插入一条扫描记录 */
    @Insert
    suspend fun insert(record: ScanRecord): Long

    /** 删除一条记录 */
    @Delete
    suspend fun delete(record: ScanRecord)

    /** 查询全部历史记录，按时间倒序 */
    @Query("SELECT * FROM scan_records ORDER BY scanTime DESC")
    fun getAllHistory(): Flow<List<ScanRecord>>

    /** 查询指定时间之后的所有记录（用于图片感知哈希缓存去重） */
    @Query("SELECT * FROM scan_records WHERE scanTime > :expireTime")
    suspend fun getRecent(expireTime: Long): List<ScanRecord>

    /** 一次性取出全部记录（用于清空前收集待删除的图片路径） */
    @Query("SELECT * FROM scan_records ORDER BY scanTime DESC")
    suspend fun getAllRecords(): List<ScanRecord>

    /** 清空全部历史 */
    @Query("DELETE FROM scan_records")
    suspend fun clearAll()
}
