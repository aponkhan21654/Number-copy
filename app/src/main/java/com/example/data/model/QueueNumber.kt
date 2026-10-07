package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents an ordered number in the active queue.
 */
@Entity(tableName = "queue_numbers")
data class QueueNumber(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val number: String,
    val note: String = "",
    val sortOrder: Long = 0,
    val createdAt: Long = System.currentTimeMillis()
)
