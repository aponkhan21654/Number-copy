package com.example.data.repository

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.example.data.dao.NumberDao
import com.example.data.model.CopiedHistory
import com.example.data.model.QueueNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class CopyPopResult(
    val success: Boolean,
    val copiedNumber: String?,
    val remainingCount: Int,
    val message: String
)

enum class ImportMode {
    APPEND,
    REPLACE
}

data class ImportSummary(
    val success: Boolean,
    val activeImported: Int,
    val historyImported: Int,
    val errorMessage: String? = null
)

class NumberRepository(private val dao: NumberDao) {

    val allQueueNumbers: Flow<List<QueueNumber>> = dao.getAllQueueNumbers()
    val allHistory: Flow<List<CopiedHistory>> = dao.getAllHistory()
    val queueCountFlow: Flow<Int> = dao.getQueueCountFlow()

    suspend fun getNextNumber(): QueueNumber? = withContext(Dispatchers.IO) {
        dao.getNextNumber()
    }

    suspend fun getQueueCount(): Int = withContext(Dispatchers.IO) {
        dao.getQueueCount()
    }

    /**
     * Copies the top number to system clipboard, removes it from the active queue,
     * archives it into history, and triggers haptic feedback.
     */
    suspend fun copyAndPopNext(context: Context): CopyPopResult = withContext(Dispatchers.IO) {
        val next = dao.getNextNumber()
        if (next == null) {
            return@withContext CopyPopResult(
                success = false,
                copiedNumber = null,
                remainingCount = 0,
                message = "সারি খালি! কোনো নম্বর নেই।"
            )
        }

        // Copy to system clipboard
        withContext(Dispatchers.Main) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("Copied Number", next.number)
            clipboard?.setPrimaryClip(clip)
        }

        // Haptic feedback
        vibratePhone(context)

        // Delete from queue
        dao.deleteNumber(next)

        // Insert into history
        dao.insertHistory(
            CopiedHistory(
                number = next.number,
                note = next.note,
                copiedAt = System.currentTimeMillis()
            )
        )

        val remaining = dao.getQueueCount()
        CopyPopResult(
            success = true,
            copiedNumber = next.number,
            remainingCount = remaining,
            message = "কপি করা হয়েছে: ${next.number} ($remaining টি বাকি)"
        )
    }

    /**
     * Parses raw multi-line or delimited text and inserts numbers into the queue in order.
     */
    suspend fun addBatchNumbers(rawInput: String, defaultNote: String = ""): Int = withContext(Dispatchers.IO) {
        val lines = rawInput.split("\n", "\r", ",", ";")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (lines.isEmpty()) return@withContext 0

        val currentMaxSort = dao.getMaxSortOrder() ?: 0L
        var sortCounter = currentMaxSort + 1L

        val listToInsert = lines.map { num ->
            QueueNumber(
                number = num,
                note = defaultNote,
                sortOrder = sortCounter++,
                createdAt = System.currentTimeMillis()
            )
        }

        dao.insertNumbers(listToInsert)
        listToInsert.size
    }

    suspend fun addSingleNumber(number: String, note: String = ""): Long = withContext(Dispatchers.IO) {
        val clean = number.trim()
        if (clean.isBlank()) return@withContext -1L
        val currentMaxSort = dao.getMaxSortOrder() ?: 0L
        dao.insertNumber(
            QueueNumber(
                number = clean,
                note = note.trim(),
                sortOrder = currentMaxSort + 1L
            )
        )
    }

    suspend fun deleteQueueNumber(number: QueueNumber) = withContext(Dispatchers.IO) {
        dao.deleteNumber(number)
    }

    suspend fun clearQueue() = withContext(Dispatchers.IO) {
        dao.clearAllQueue()
    }

    suspend fun restoreHistoryItem(history: CopiedHistory) = withContext(Dispatchers.IO) {
        val currentMaxSort = dao.getMaxSortOrder() ?: 0L
        dao.insertNumber(
            QueueNumber(
                number = history.number,
                note = history.note,
                sortOrder = currentMaxSort + 1L
            )
        )
        dao.deleteHistory(history)
    }

    suspend fun deleteHistory(history: CopiedHistory) = withContext(Dispatchers.IO) {
        dao.deleteHistory(history)
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        dao.clearAllHistory()
    }

    // --- Backup & Export ---

    suspend fun exportDatabaseToJson(): String = withContext(Dispatchers.IO) {
        val queueList = dao.getAllQueueNumbersList()
        val historyList = dao.getAllHistoryList()

        val root = JSONObject()
        root.put("version", 1)
        root.put("app", "NumQueue")
        root.put("exportedAt", System.currentTimeMillis())
        root.put("exportedDate", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))

        val queueArray = JSONArray()
        queueList.forEach { item ->
            val obj = JSONObject().apply {
                put("number", item.number)
                put("note", item.note)
                put("sortOrder", item.sortOrder)
                put("createdAt", item.createdAt)
            }
            queueArray.put(obj)
        }
        root.put("activeQueue", queueArray)

        val historyArray = JSONArray()
        historyList.forEach { hist ->
            val obj = JSONObject().apply {
                put("number", hist.number)
                put("note", hist.note)
                put("copiedAt", hist.copiedAt)
            }
            historyArray.put(obj)
        }
        root.put("copiedHistory", historyArray)

        root.toString(2)
    }

    suspend fun exportQueueToPlainText(): String = withContext(Dispatchers.IO) {
        val list = dao.getAllQueueNumbersList()
        list.joinToString("\n") { it.number }
    }

    suspend fun importDatabaseFromJson(jsonContent: String, mode: ImportMode): ImportSummary = withContext(Dispatchers.IO) {
        try {
            val root = JSONObject(jsonContent)
            val queueArray = root.optJSONArray("activeQueue")
            val historyArray = root.optJSONArray("copiedHistory")

            if (mode == ImportMode.REPLACE) {
                dao.clearAllQueue()
                dao.clearAllHistory()
            }

            var currentMaxSort = dao.getMaxSortOrder() ?: 0L
            val queueItemsToInsert = mutableListOf<QueueNumber>()

            if (queueArray != null) {
                for (i in 0 until queueArray.length()) {
                    val obj = queueArray.getJSONObject(i)
                    val num = obj.optString("number", "").trim()
                    if (num.isNotBlank()) {
                        val note = obj.optString("note", "")
                        val createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                        currentMaxSort++
                        queueItemsToInsert.add(
                            QueueNumber(
                                number = num,
                                note = note,
                                sortOrder = currentMaxSort,
                                createdAt = createdAt
                            )
                        )
                    }
                }
            }

            val historyItemsToInsert = mutableListOf<CopiedHistory>()
            if (historyArray != null) {
                for (i in 0 until historyArray.length()) {
                    val obj = historyArray.getJSONObject(i)
                    val num = obj.optString("number", "").trim()
                    if (num.isNotBlank()) {
                        val note = obj.optString("note", "")
                        val copiedAt = obj.optLong("copiedAt", System.currentTimeMillis())
                        historyItemsToInsert.add(
                            CopiedHistory(
                                number = num,
                                note = note,
                                copiedAt = copiedAt
                            )
                        )
                    }
                }
            }

            if (queueItemsToInsert.isNotEmpty()) {
                dao.insertNumbers(queueItemsToInsert)
            }
            if (historyItemsToInsert.isNotEmpty()) {
                dao.insertHistoryList(historyItemsToInsert)
            }

            ImportSummary(
                success = true,
                activeImported = queueItemsToInsert.size,
                historyImported = historyItemsToInsert.size
            )
        } catch (e: Exception) {
            ImportSummary(
                success = false,
                activeImported = 0,
                historyImported = 0,
                errorMessage = e.localizedMessage ?: "Invalid JSON backup format"
            )
        }
    }

    private fun vibratePhone(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(
                    VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(50)
                }
            }
        } catch (_: Exception) {
            // Ignore if vibration unsupported
        }
    }
}
