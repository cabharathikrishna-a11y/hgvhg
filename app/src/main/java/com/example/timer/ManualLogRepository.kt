package com.example.timer

import androidx.room.withTransaction
import com.example.data.AppDatabase
import com.example.data.LocalHistoryVault
import com.example.util.TimeEngine
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class ManualLogRepository(
    private val database: AppDatabase,
    private val timerDao: TimerDao,
    private val gson: Gson
) {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    /**
     * LOG MANUAL FOCUS STUDY SESSION WITH NEW RULES:
     * 1. Max time recorded in a single session is 6 hours.
     * 2. Cannot record more time than the current total time wasted today.
     * @return Pair<Boolean, String> -> (Success status, UI Message/Reason)
     */
    suspend fun logManualStudySession(
        taskTitle: String,
        subjectTag: String,
        durationMinutes: Int
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (durationMinutes <= 0) return@withContext Pair(false, "Duration must be greater than 0 minutes.")

        // Rule 1: Max time that can be recorded is 6 hours
        if (durationMinutes > 360) {
            return@withContext Pair(
                false,
                "The maximum focus time you can record in a single session is 6 hours (360 minutes)."
            )
        }

        val nowMs = System.currentTimeMillis()
        val durationMs = durationMinutes * 60 * 1000L
        val dateStr = dateFormat.format(Date(nowMs))

        // Rule 2: Cannot record more time than the current total time wasted today
        val cal = Calendar.getInstance()
        val elapsedMins = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)

        val todayTotalFocusMs = database.localHistoryVaultDao().getTodayTotalFocusTimeMs(dateStr)
        val focusedMins = (todayTotalFocusMs / 1000 / 60).toInt()

        val sleepRecord = database.healthRecordDao().getHealthRecordDirect(dateStr)
        val sleepMinutes = sleepRecord?.sleepMinutes ?: 0

        val wastedMins = (elapsedMins - focusedMins - sleepMinutes).coerceAtLeast(0)

        if (durationMinutes > wastedMins) {
            return@withContext Pair(
                false,
                "You cannot record more focus time than your current total time wasted today ($wastedMins minutes available)."
            )
        }

        // --- STEP 2: PREPARE RECORD WITH MANUAL_LOG MODE ---
        val approximatedStartMs = nowMs - durationMs
        val recordId = "manual_${nowMs}_${subjectTag.lowercase()}"

        val syntheticTimeline = listOf(
            com.example.api.TimelineEvent(deviceId = "manual", event = "start", timestamp = approximatedStartMs),
            com.example.api.TimelineEvent(deviceId = "manual", event = "session_end", timestamp = nowMs)
        )
        val syntheticTimelineJson = gson.toJson(syntheticTimeline)

        val manualVaultRecord = LocalHistoryVault(
            record_id = recordId,
            date_string = dateStr,
            subject = subjectTag,
            task_title = taskTitle, // Raw title preserved; mode handles the display badge
            start_time_ms = approximatedStartMs,
            end_time_ms = nowMs,
            total_focus_ms = durationMs,
            duration_formatted = TimeEngine.formatDuration(durationMs),
            start_time_formatted = TimeEngine.formatTimestamp(approximatedStartMs),
            end_time_formatted = TimeEngine.formatTimestamp(nowMs),
            is_synced_to_firestore = 0,
            mode = "MANUAL_LOG",
            lastModifiedMs = nowMs,
            isManualEntry = true,
            timeline_json = syntheticTimelineJson,
            timeline = syntheticTimeline
        )

        // --- STEP 3: ATOMIC ROOM TRANSACTION & DIRECT-TO-VAULT ROUTING ---
        database.withTransaction {
            // Save to local SQLite Vault
            timerDao.archiveToVault(manualVaultRecord)

            // Enqueue Outbox payload with explicit "MANUAL_LOG" mode stamp
            val cloudPayload = gson.toJson(mapOf(
                "recordId" to recordId,
                "dateString" to dateStr,
                "subject" to subjectTag,
                "taskTitle" to taskTitle,
                "mode" to "MANUAL_LOG", // Explicitly replaces Pomodoro/Stopwatch
                "metrics" to mapOf(
                    "totalFocusMs" to durationMs,
                    "durationFormatted" to TimeEngine.formatDuration(durationMs),
                    "startTimeFormatted" to TimeEngine.formatTimestamp(approximatedStartMs),
                    "endTimeFormatted" to TimeEngine.formatTimestamp(nowMs)
                ),
                "loggedByDevice" to "android_mobile_apk",
                "isManualEntry" to true
            ))

            timerDao.enqueueOutboxMutation(
                OutboxMutation(
                    mutationId = "mut_manual_$nowMs",
                    createdAtMs = nowMs,
                    routingTarget = "FIRESTORE_DIRECT_VAULT",
                    actionType = "ARCHIVE_SESSION",
                    payloadJson = cloudPayload
                )
            )
        }

        return@withContext Pair(true, "Successfully logged ${durationMinutes}m of manual study time!")
    }
}
