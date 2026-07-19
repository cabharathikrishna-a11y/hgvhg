package com.example.api

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.util.TimeEngine
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DevicePresenceManager {
    private const val TAG = "DevicePresenceManager"

    // Sanitize email by replacing all dots '.' with underscores '_'
    fun sanitizeEmail(email: String): String {
        return email.replace(".", "_")
    }

    fun getDeviceKey(context: Context): String {
        return com.example.util.DeviceIdProvider.getDeviceId(context)
    }

    /**
     * Engine that runs on app launch/login to write presence data to RTDB.
     */
    fun registerPresence(context: Context, email: String) {
        if (email.isBlank()) return
        
        try {
            val dbUrl = FirebaseConfig.getDatabaseUrl(context)
            if (dbUrl.isEmpty()) {
                Log.e(TAG, "Firebase DB URL is empty. Cannot register presence.")
                return
            }
            val database = FirebaseDatabase.getInstance(dbUrl)
            val sanitizedEmail = sanitizeEmail(email)
            val deviceKey = getDeviceKey(context)
            
            // Database Path: FOCUS_TIMMER/USER/{user_gmail_com}/DEVICES_LOGGED_IN/{DeviceKey}
            val presenceRef = database.getReference("FOCUS_TIMMER")
                .child("USER")
                .child(sanitizedEmail)
                .child("DEVICES_LOGGED_IN")
                .child(deviceKey)

            val trueTime = TimeEngine.getTrueTimeMs()
            val formattedDate = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(trueTime))

            try {
                com.google.firebase.messaging.FirebaseMessaging.getInstance().token.addOnCompleteListener { fcmTask ->
                    val fcmToken = if (fcmTask.isSuccessful) fcmTask.result else "null_or_failed"
                    val payload = mapOf(
                        "Login_status" to true,
                        "Upload_Status" to "Completed",
                        "Last_Update_Time_and_Date" to formattedDate,
                        "fcm token number" to fcmToken
                    )

                    presenceRef.setValue(payload).addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            Log.d(TAG, "Successfully registered presence payload with FCM token for $deviceKey")
                            // Also update the focus timings for this device
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                try {
                                    adoptHighestTodayFocusMsFromOtherDevices(context, email)
                                    updateDeviceFocusStats(context, email)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to run adopt/updateDeviceFocusStats inside registerPresence", e)
                                }
                            }
                        } else {
                            Log.e(TAG, "Failed to register presence for $deviceKey", task.exception)
                        }
                    }
                }
            } catch (fcmEx: Exception) {
                Log.e(TAG, "Error getting FCM token inside registerPresence, continuing with fallback", fcmEx)
                val payload = mapOf(
                    "Login_status" to true,
                    "Upload_Status" to "Completed",
                    "Last_Update_Time_and_Date" to formattedDate,
                    "fcm token number" to "error_or_not_initialized"
                )
                presenceRef.setValue(payload).addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Log.d(TAG, "Successfully registered fallback presence payload for $deviceKey")
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                            try {
                                adoptHighestTodayFocusMsFromOtherDevices(context, email)
                                updateDeviceFocusStats(context, email)
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to run adopt/updateDeviceFocusStats inside registerPresence fallback", e)
                            }
                        }
                    }
                }
            }

            // Disconnection Rule: Attach .onDisconnect().setValue(false) to the Login_status node.
            presenceRef.child("Login_status").onDisconnect().setValue(false)

        } catch (e: Exception) {
            Log.e(TAG, "Error registering presence", e)
        }
    }

    suspend fun adoptHighestTodayFocusMsFromOtherDevices(context: Context, email: String) {
        if (email.isBlank()) return
        val dbUrl = FirebaseConfig.getDatabaseUrl(context)
        if (dbUrl.isEmpty()) return

        try {
            val database = FirebaseDatabase.getInstance(dbUrl)
            val sanitizedEmail = sanitizeEmail(email)
            val myDeviceKey = getDeviceKey(context)
            
            val devicesRef = database.getReference("FOCUS_TIMMER")
                .child("USER")
                .child(sanitizedEmail)
                .child("DEVICES_LOGGED_IN")

            val snapshot = suspendCancellableCoroutine<com.google.firebase.database.DataSnapshot?> { cont ->
                devicesRef.get().addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        cont.resume(task.result)
                    } else {
                        cont.resumeWithException(task.exception ?: Exception("Failed to fetch devices logged in"))
                    }
                }
            }

            if (snapshot != null && snapshot.exists()) {
                val nowMs = System.currentTimeMillis()
                val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                val todayStr = sdf.format(Date(nowMs))

                var maxOtherTodayFocusMs = 0L

                for (deviceSnapshot in snapshot.children) {
                    val deviceKey = deviceSnapshot.key ?: continue
                    if (deviceKey == myDeviceKey) continue // skip ourselves

                    val lastUpdateStr = deviceSnapshot.child("Last_Update_Time_and_Date").getValue(String::class.java) ?: ""
                    val lastUpdateDate = if (lastUpdateStr.isNotEmpty()) lastUpdateStr.substringBefore(" ") else ""
                    if (lastUpdateDate == todayStr) {
                        val otherTodayMs = deviceSnapshot.child("Todays_Focus_Ms").getValue(Long::class.java) ?: 0L
                        if (otherTodayMs > maxOtherTodayFocusMs) {
                            maxOtherTodayFocusMs = otherTodayMs
                        }
                    }
                }

                val db = com.example.data.AppDatabase.getInstance(context)
                val allHistory = db.localHistoryVaultDao().getAllHistoryDirect()
                var localTodayFocusMs = 0L
                for (record in allHistory) {
                    if (record.date_string == todayStr) {
                        localTodayFocusMs += record.total_focus_ms
                    }
                }

                val adoptedTodayMs = maxOf(0L, maxOtherTodayFocusMs - localTodayFocusMs)
                val appPrefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                appPrefs.edit()
                    .putLong("adopted_today_ms_${sanitizedEmail}", adoptedTodayMs)
                    .apply()

                com.example.util.FocusTimerManager.setAdoptedTodayMs(adoptedTodayMs)
                Log.d(TAG, "Adopted higher today's focus time recalculation: $adoptedTodayMs ms (maxOtherToday: $maxOtherTodayFocusMs, localToday: $localTodayFocusMs)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adopting highest today focus time from other devices", e)
        }
    }

    /**
     * Calculates user focus metrics (today, past 7 days, past 30 days, all time) from the local database
     * and uploads them under the active device node in Firebase.
     */
    suspend fun updateDeviceFocusStats(context: Context, email: String) {
        if (email.isBlank()) return
        
        try {
            adoptHighestTodayFocusMsFromOtherDevices(context, email)
            val db = com.example.data.AppDatabase.getInstance(context)
            val allHistory = db.localHistoryVaultDao().getAllHistoryDirect()
            
            val nowMs = System.currentTimeMillis()
            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            val todayStr = sdf.format(Date(nowMs))

            var todayFocusMs = 0L
            var past7DaysFocusMs = 0L
            var past30DaysFocusMs = 0L
            var past50DaysFocusMs = 0L
            var allTimeFocusMs = 0L

            for (record in allHistory) {
                val duration = record.total_focus_ms
                allTimeFocusMs += duration
                
                if (record.date_string == todayStr) {
                    todayFocusMs += duration
                }
                
                if (record.start_time_ms >= nowMs - (7L * 24 * 60 * 60 * 1000)) {
                    past7DaysFocusMs += duration
                }
                
                if (record.start_time_ms >= nowMs - (30L * 24 * 60 * 60 * 1000)) {
                    past30DaysFocusMs += duration
                }

                if (record.start_time_ms >= nowMs - (50L * 24 * 60 * 60 * 1000)) {
                    past50DaysFocusMs += duration
                }
            }

            // Add currently active session's focus time accumulated so far
            val isTimerRunning = com.example.util.FocusTimerManager.isTimerRunning.value
            val isStopwatchActive = com.example.util.FocusTimerManager.isStopwatchActive.value
            val accumulatedMs = com.example.util.FocusTimerManager.accumulatedSessionTimeMs.value
            val isPendingReviewNull = com.example.util.FocusTimerManager.pendingFocusReview.value == null
            if ((isTimerRunning || isStopwatchActive || accumulatedMs > 0L) && isPendingReviewNull) {
                val isFocusPhase = com.example.util.FocusTimerManager.isFocusPhase.value
                val isPaused = com.example.util.FocusTimerManager.isPaused.value
                val activeSessionMs = if (isFocusPhase && !isPaused) {
                    accumulatedMs + com.example.util.FocusTimerManager.getCurrentChunkMs()
                } else {
                    accumulatedMs
                }
                
                if (activeSessionMs > 0) {
                    todayFocusMs += activeSessionMs
                    past7DaysFocusMs += activeSessionMs
                    past30DaysFocusMs += activeSessionMs
                    past50DaysFocusMs += activeSessionMs
                    allTimeFocusMs += activeSessionMs
                }
            }

            val dbUrl = FirebaseConfig.getDatabaseUrl(context)
            if (dbUrl.isNotEmpty()) {
                val database = FirebaseDatabase.getInstance(dbUrl)
                val sanitizedEmail = sanitizeEmail(email)
                val deviceKey = getDeviceKey(context)
                
                // Retrieve adopted statistics to accumulate on top
                val appPrefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                val adoptedAllTimeMs = appPrefs.getLong("adopted_all_time_ms_${sanitizedEmail}", 0L)
                val adoptedPast50Ms = appPrefs.getLong("adopted_past_50_ms_${sanitizedEmail}", 0L)
                val adoptedPast30Ms = appPrefs.getLong("adopted_past_30_ms_${sanitizedEmail}", 0L)
                val adoptedPast7Ms = appPrefs.getLong("adopted_past_7_ms_${sanitizedEmail}", 0L)
                val adoptedTodayMs = appPrefs.getLong("adopted_today_ms_${sanitizedEmail}", 0L)

                val finalTodayFocusMs = todayFocusMs + adoptedTodayMs
                val finalPast7DaysFocusMs = past7DaysFocusMs + adoptedPast7Ms
                val finalPast30DaysFocusMs = past30DaysFocusMs + adoptedPast30Ms
                val finalPast50DaysFocusMs = past50DaysFocusMs + adoptedPast50Ms
                val finalAllTimeFocusMs = allTimeFocusMs + adoptedAllTimeMs

                val deviceRef = database.getReference("FOCUS_TIMMER")
                    .child("USER")
                    .child(sanitizedEmail)
                    .child("DEVICES_LOGGED_IN")
                    .child(deviceKey)

                val statsUpdates = mapOf(
                    "Todays_Focus_Ms" to finalTodayFocusMs,
                    "Past_7_Days_Focus_Ms" to finalPast7DaysFocusMs,
                    "Past_30_Days_Focus_Ms" to finalPast30DaysFocusMs,
                    "Past_50_Days_Focus_Ms" to finalPast50DaysFocusMs,
                    "All_Time_Focus_Ms" to finalAllTimeFocusMs,
                    "Last_Stats_Updated" to SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(nowMs))
                )
                
                deviceRef.updateChildren(statsUpdates)
                Log.d(TAG, "Successfully updated device focus timings in Firebase")

                // Check for 10-hr Focus Achievement to grant a shield
                val tenHoursMs = 10L * 3600000L
                val shieldGrantedKey = "shield_granted_${email}_${todayStr}"
                if (todayFocusMs >= tenHoursMs && !appPrefs.getBoolean(shieldGrantedKey, false)) {
                    val myShieldsRef = database.getReference("FOCUS_TIMMER")
                        .child("USER")
                        .child(sanitizedEmail)
                        .child("SHIELDS")

                    val uuid = java.util.UUID.randomUUID().toString()
                    val trueTime = TimeEngine.getTrueTimeMs()
                    val shieldPayload = mapOf(
                        "Donor_Email" to "system@focussphere.com",
                        "Donor_Name" to "Focus Sphere System (10-hr Achievement)",
                        "Granted_Timestamp" to trueTime,
                        "Is_Consumed" to false,
                        "Consumed_Date" to null
                    )

                    myShieldsRef.child(uuid).setValue(shieldPayload).addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            Log.d(TAG, "Shield granted in RTDB for 10-hr focus!")
                            appPrefs.edit().putBoolean(shieldGrantedKey, true).apply()
                            com.example.api.FocusLogManager.logEvent(context, "Granted 1 Streak Shield for achieving 10-hour daily focus!")
                        }
                    }

                    // Also save locally
                    val localShield = com.example.data.LocalShieldsVault(
                        uuid = uuid,
                        donor_email = "system@focussphere.com",
                        donor_name = "Focus Sphere System (10-hr Achievement)",
                        granted_timestamp = trueTime,
                        is_consumed = false,
                        consumed_date = null
                    )
                    db.localShieldsVaultDao().insertShield(localShield)
                }

                // Keep local focus records and today's stats 100% in sync
                com.example.util.FocusTimerManager.reloadFocusRecordsFromDb(context)
            }
        } catch (e: java.lang.Exception) {
            Log.e(TAG, "Error updating device focus timings in Firebase", e)
        }
    }

    /**
     * Active Evaluation Function: Create a function isUserActivelyLoggedIn(devicesMap).
     * Enforce these rules:
     * - Web devices (WEB_USER_) are deemed logged out if Last_Update_Time_and_Date is older than 48 hours.
     * - Native devices must have Login_status == true AND an update timestamp within 12 hours.
     */
    fun isUserActivelyLoggedIn(devicesMap: Map<String, Any>?): Boolean {
        if (devicesMap == null) return false
        
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val trueTime = TimeEngine.getTrueTimeMs()
        val fortyEightHoursMs = 48L * 60 * 60 * 1000
        val twelveHoursMs = 12L * 60 * 60 * 1000

        for ((deviceKey, deviceData) in devicesMap) {
            try {
                val data = deviceData as? Map<*, *> ?: continue
                
                // Get Last_Update_Time_and_Date
                val lastUpdateStr = data["Last_Update_Time_and_Date"] as? String ?: continue
                val lastUpdateDate = sdf.parse(lastUpdateStr) ?: continue
                val lastUpdateTimeMs = lastUpdateDate.time
                val ageMs = trueTime - lastUpdateTimeMs

                if (deviceKey.startsWith("WEB_USER_")) {
                    // Web devices: logged out if older than 48 hours
                    if (ageMs <= fortyEightHoursMs) {
                        return true
                    }
                } else {
                    // Native devices: Login_status == true AND within 12 hours
                    val loginStatus = data["Login_status"] as? Boolean ?: false
                    if (loginStatus && ageMs <= twelveHoursMs) {
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error evaluating presence for device $deviceKey", e)
            }
        }
        return false
    }
}
