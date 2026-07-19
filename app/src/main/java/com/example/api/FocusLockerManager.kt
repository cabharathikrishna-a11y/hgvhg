package com.example.api

import android.content.Context
import android.util.Log
import com.example.util.TimeEngine
import com.example.util.FocusTimerManager
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ParticipantInfo(
    val email: String,
    val sanitizedEmail: String,
    val displayName: String,
    val joinTimestamp: Long
)

data class FocusLockerUiModel(
    val roomId: String = "",
    val roomName: String = "",
    val hostEmail: String = "",
    val participants: List<ParticipantInfo> = emptyList(),
    val isHost: Boolean = false
)

object FocusLockerManager {
    private const val TAG = "FocusLockerManager"

    private val _uiState = MutableStateFlow(FocusLockerUiModel())
    val uiState: StateFlow<FocusLockerUiModel> = _uiState.asStateFlow()

    private var roomListener: ValueEventListener? = null
    private var roomRef: com.google.firebase.database.DatabaseReference? = null
    


    fun getFallbackDisplayName(email: String): String {
        val clean = email.substringBefore("@").replace(".", "_")
        val prefix = clean.substringBefore("_")
        return prefix.replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(java.util.Locale.getDefault()) else it.toString()
        }
    }

    fun createRoom(
        context: Context,
        myEmail: String,
        roomName: String,
        onSuccess: (String) -> Unit,
        onFailure: (Exception) -> Unit
    ) {
        if (myEmail.isBlank()) {
            onFailure(IllegalArgumentException("Email cannot be blank"))
            return
        }

        try {
            val dbUrl = FirebaseConfig.getDatabaseUrl(context)
            if (dbUrl.isEmpty()) {
                onFailure(IllegalStateException("Database URL is empty"))
                return
            }

            val database = FirebaseDatabase.getInstance(dbUrl)
            val roomId = "ROOM_${System.currentTimeMillis()}"
            val sanitizedMyEmail = DevicePresenceManager.sanitizeEmail(myEmail)
            val trueTime = TimeEngine.getTrueTimeMs()

            val roomRef = database.getReference("FOCUS_TIMMER")
                .child("SHARED_ROOMS")
                .child(roomId)

            val payload = mapOf(
                "Host_Email" to myEmail,
                "Room_Name" to roomName,
                "Participants" to mapOf(sanitizedMyEmail to trueTime)
            )

            roomRef.setValue(payload).addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "Successfully created shared room: $roomId")
                    joinRoom(context, myEmail, roomId)
                    onSuccess(roomId)
                } else {
                    onFailure(task.exception ?: Exception("Failed to write room state"))
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in createRoom", e)
            onFailure(e)
        }
    }

    fun joinRoom(context: Context, myEmail: String, roomId: String) {
        if (myEmail.isBlank() || roomId.isBlank()) return

        try {
            val dbUrl = FirebaseConfig.getDatabaseUrl(context)
            if (dbUrl.isEmpty()) return

            val database = FirebaseDatabase.getInstance(dbUrl)
            val sanitizedMyEmail = DevicePresenceManager.sanitizeEmail(myEmail)
            val trueTime = TimeEngine.getTrueTimeMs()

            // Save joined roomId in SharedPreferences for low-data reconnect queries
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("last_joined_room_id_$sanitizedMyEmail", roomId).apply()

            // Update participant list first
            database.getReference("FOCUS_TIMMER")
                .child("SHARED_ROOMS")
                .child(roomId)
                .child("Participants")
                .child(sanitizedMyEmail)
                .setValue(trueTime)

            // Start listening to the room
            listenToRoom(context, myEmail, roomId)

        } catch (e: Exception) {
            Log.e(TAG, "Error joining room", e)
        }
    }

    fun leaveRoom(context: Context, myEmail: String) {
        val currentRoomId = _uiState.value.roomId
        if (currentRoomId.isBlank() || myEmail.isBlank()) return

        try {
            val dbUrl = FirebaseConfig.getDatabaseUrl(context)
            if (dbUrl.isEmpty()) return

            val database = FirebaseDatabase.getInstance(dbUrl)
            val sanitizedMyEmail = DevicePresenceManager.sanitizeEmail(myEmail)

            // Clear joined roomId from SharedPreferences
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            prefs.edit().remove("last_joined_room_id_$sanitizedMyEmail").apply()

            // Remove participant node
            database.getReference("FOCUS_TIMMER")
                .child("SHARED_ROOMS")
                .child(currentRoomId)
                .child("Participants")
                .child(sanitizedMyEmail)
                .removeValue()

            // If the leaving user is the host, end the room entirely
            if (_uiState.value.isHost) {
                database.getReference("FOCUS_TIMMER")
                    .child("SHARED_ROOMS")
                    .child(currentRoomId)
                    .removeValue()
            }

            // Cleanup local state
            stopListening()

        } catch (e: Exception) {
            Log.e(TAG, "Error leaving room", e)
        }
    }

    private fun listenToRoom(context: Context, myEmail: String, roomId: String) {
        stopListening()

        try {
            val dbUrl = FirebaseConfig.getDatabaseUrl(context)
            if (dbUrl.isEmpty()) return

            val database = FirebaseDatabase.getInstance(dbUrl)
            val ref = database.getReference("FOCUS_TIMMER")
                .child("SHARED_ROOMS")
                .child(roomId)

            roomRef = ref

            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.exists()) {
                        // Room deleted remotely (e.g. host deleted/left)
                        stopListening()
                        return
                    }

                    val hostEmail = snapshot.child("Host_Email").getValue(String::class.java) ?: ""
                    val roomName = snapshot.child("Room_Name").getValue(String::class.java) ?: ""

                    val participantsList = mutableListOf<ParticipantInfo>()
                    val participantsSnapshot = snapshot.child("Participants")
                    if (participantsSnapshot.exists()) {
                        for (child in participantsSnapshot.children) {
                            val sanitized = child.key ?: continue
                            val joinTs = child.getValue(Long::class.java) ?: 0L
                            
                            // Reconstruct plain email or approximate it
                            val rawEmail = sanitized.replace("_dot_", ".").replace("_at_", "@")
                            val displayName = getFallbackDisplayName(rawEmail)
                            participantsList.add(
                                ParticipantInfo(
                                    email = rawEmail,
                                    sanitizedEmail = sanitized,
                                    displayName = displayName,
                                    joinTimestamp = joinTs
                                )
                            )
                        }
                    }

                    val isHost = (myEmail == hostEmail)

                    _uiState.value = FocusLockerUiModel(
                        roomId = roomId,
                        roomName = roomName,
                        hostEmail = hostEmail,
                        participants = participantsList,
                        isHost = isHost
                    )
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Room listener cancelled", error.toException())
                }
            }

            ref.addValueEventListener(listener)
            roomListener = listener

        } catch (e: Exception) {
            Log.e(TAG, "Error starting room listener", e)
        }
    }

    fun stopListening() {
        roomListener?.let { listener ->
            roomRef?.removeEventListener(listener)
        }
        roomListener = null
        roomRef = null
        _uiState.value = FocusLockerUiModel()
    }

    fun checkForExistingRoomsAndReconnect(context: Context, myEmail: String) {
        if (myEmail.isBlank()) return
        Log.d(TAG, "Checking for existing rooms to reconnect for: $myEmail")
        try {
            val dbUrl = FirebaseConfig.getDatabaseUrl(context)
            if (dbUrl.isEmpty()) return

            val database = FirebaseDatabase.getInstance(dbUrl)
            val sanitizedMyEmail = DevicePresenceManager.sanitizeEmail(myEmail)
            val prefs = context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

            // Step 1: Attempt to check using saved roomId from SharedPreferences first (extremely low-data single-node lookup)
            val savedRoomId = prefs.getString("last_joined_room_id_$sanitizedMyEmail", "") ?: ""
            if (savedRoomId.isNotBlank()) {
                val roomRef = database.getReference("FOCUS_TIMMER")
                    .child("SHARED_ROOMS")
                    .child(savedRoomId)
                roomRef.addListenerForSingleValueEvent(object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        if (snapshot.exists()) {
                            val participantsSnapshot = snapshot.child("Participants")
                            if (participantsSnapshot.exists() && participantsSnapshot.hasChild(sanitizedMyEmail)) {
                                Log.i(TAG, "Auto-reconnect (Saved Room): Found active participant entry in roomId: $savedRoomId. Reconnecting...")
                                joinRoom(context, myEmail, savedRoomId)
                                return
                            }
                        }
                        // If room doesn't exist or we are not in it, fall back to the indexed query
                        queryExistingRooms(context, myEmail, database, sanitizedMyEmail, prefs)
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Log.e(TAG, "Auto-reconnect (Saved Room): check cancelled", error.toException())
                        queryExistingRooms(context, myEmail, database, sanitizedMyEmail, prefs)
                    }
                })
            } else {
                queryExistingRooms(context, myEmail, database, sanitizedMyEmail, prefs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in checkForExistingRoomsAndReconnect", e)
        }
    }

    private fun queryExistingRooms(
        context: Context,
        myEmail: String,
        database: FirebaseDatabase,
        sanitizedMyEmail: String,
        prefs: android.content.SharedPreferences
    ) {
        try {
            val roomsRef = database.getReference("FOCUS_TIMMER").child("SHARED_ROOMS")
            // Step 2: Use highly selective query filter instead of reading entire SHARED_ROOMS branch
            val query = roomsRef.orderByChild("Participants/$sanitizedMyEmail").startAt(1.0)
            query.addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.exists()) {
                        for (roomSnapshot in snapshot.children) {
                            val participantsSnapshot = roomSnapshot.child("Participants")
                            if (participantsSnapshot.exists() && participantsSnapshot.hasChild(sanitizedMyEmail)) {
                                val roomId = roomSnapshot.key ?: continue
                                Log.i(TAG, "Auto-reconnect (Query): Found existing focus group participant entry in roomId: $roomId. Reconnecting...")
                                joinRoom(context, myEmail, roomId)
                                break // Only reconnect to the first found room
                            }
                        }
                    }
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e(TAG, "Auto-reconnect (Query): selective query check cancelled", error.toException())
                }
            })
        } catch (ex: Exception) {
            Log.e(TAG, "Error performing selective room query reconnection", ex)
        }
    }
}
