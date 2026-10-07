package com.example.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.model.CopiedHistory
import com.example.data.model.QueueNumber
import kotlinx.coroutines.flow.Flow

@Dao
interface NumberDao {

    // --- Active Queue operations ---

    @Query("SELECT * FROM queue_numbers ORDER BY sortOrder ASC, id ASC")
    fun getAllQueueNumbers(): Flow<List<QueueNumber>>

    @Query("SELECT * FROM queue_numbers ORDER BY sortOrder ASC, id ASC")
    suspend fun getAllQueueNumbersList(): List<QueueNumber>

    @Query("SELECT * FROM queue_numbers ORDER BY sortOrder ASC, id ASC LIMIT 1")
    suspend fun getNextNumber(): QueueNumber?

    @Query("SELECT COUNT(*) FROM queue_numbers")
    fun getQueueCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM queue_numbers")
    suspend fun getQueueCount(): Int

    @Query("SELECT MAX(sortOrder) FROM queue_numbers")
    suspend fun getMaxSortOrder(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNumber(number: QueueNumber): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNumbers(numbers: List<QueueNumber>): List<Long>

    @Update
    suspend fun updateNumber(number: QueueNumber)

    @Delete
    suspend fun deleteNumber(number: QueueNumber)

    @Query("DELETE FROM queue_numbers WHERE id = :id")
    suspend fun deleteNumberById(id: Long)

    @Query("DELETE FROM queue_numbers")
    suspend fun clearAllQueue()

    // --- History operations ---

    @Query("SELECT * FROM copied_history ORDER BY copiedAt DESC")
    fun getAllHistory(): Flow<List<CopiedHistory>>

    @Query("SELECT * FROM copied_history ORDER BY copiedAt DESC")
    suspend fun getAllHistoryList(): List<CopiedHistory>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistory(history: CopiedHistory): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertHistoryList(list: List<CopiedHistory>): List<Long>

    @Delete
    suspend fun deleteHistory(history: CopiedHistory)

    @Query("DELETE FROM copied_history")
    suspend fun clearAllHistory()
}
