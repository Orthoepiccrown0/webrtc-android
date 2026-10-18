package com.epiccrown.directstream.showcase.ui

import android.content.Context
import com.epiccrown.directstream.api.CallAttempt
import com.epiccrown.directstream.api.CallDirection
import com.epiccrown.directstream.api.CallOutcome
import com.epiccrown.directstream.api.DeviceCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Private device storage; Android backup/transfer is already excluded for all shared preferences.
 */
class RecentCallStore(context: Context, preferenceName: String = "recent-calls") {
    private val prefs = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val _rows = MutableStateFlow(load())
    val rows = _rows.asStateFlow()

    init {
        runCatching {
            val data = JSONArray(prefs.getString("rows", "[]"))
            var migrated = false
            for (i in 0 until data.length()) {
                val row = data.optJSONObject(i) ?: continue
                if (row.has("alias")) {
                    row.remove("alias")
                    migrated = true
                }
            }
            if (migrated) prefs.edit().putString("rows", data.toString()).apply()
        }
    }

    private fun load(): List<RecentDevice> = runCatching {
        val data = JSONArray(prefs.getString("rows", "[]"))
        (0 until data.length())
            .mapNotNull { i ->
                runCatching {
                    val item = data.getJSONObject(i)
                    RecentDevice(
                        item.getString("id"),
                        item
                            .optString("code")
                            .takeIf { it.isNotBlank() }
                            ?.let(DeviceCode::normalize),
                        item.optString("peerUid").takeIf { it.isNotBlank() },
                        item
                            .optString("peerName")
                            .takeIf { it.isNotBlank() }
                            ?.let(com.epiccrown.directstream.api.ParticipantName::normalize),
                        item.getString("requestId"),
                        item.getLong("at"),
                        CallDirection.valueOf(item.getString("direction")),
                        CallOutcome.valueOf(item.getString("outcome")),
                    )
                }
                    .getOrNull()
            }
            .sortedByDescending { it.at }
            .take(50)
    }
        .getOrDefault(emptyList())

    private suspend fun update(transform: (List<RecentDevice>) -> List<RecentDevice>) =
        mutex.withLock {
            val next = transform(_rows.value)
            if (next == _rows.value) return@withLock
            val data = JSONArray()
            next.forEach { row ->
                data.put(
                    JSONObject()
                        .put("id", row.id)
                        .put("code", row.code ?: "")
                        .put("peerUid", row.peerUid ?: "")
                        .put("peerName", row.peerName ?: "")
                        .put("requestId", row.requestId)
                        .put("at", row.at)
                        .put("direction", row.direction.name)
                        .put("outcome", row.outcome.name),
                )
            }
            withContext(Dispatchers.IO) {
                check(prefs.edit().putString("rows", data.toString()).commit()) {
                    "Could not save recent calls."
                }
            }
            _rows.value = next
        }

    suspend fun record(attempt: CallAttempt) = update { RecentCalls.record(it, attempt) }

    suspend fun remove(id: String) = update { rows -> rows.filterNot { it.id == id } }

    suspend fun clear() = update { emptyList() }
}
