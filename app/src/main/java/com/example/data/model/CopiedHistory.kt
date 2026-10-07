package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Stores history of numbers that have been copied and removed from queue.
 */
@Entity(tableName = "copied_history")
data class CopiedHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val number: String,
    val note: String = "",
    val copiedAt: Long = System.currentTimeMillis()
)
