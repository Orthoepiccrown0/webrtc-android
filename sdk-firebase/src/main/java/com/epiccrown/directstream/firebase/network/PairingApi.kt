package com.epiccrown.directstream.firebase.network

import com.epiccrown.directstream.api.BackendException
import com.epiccrown.directstream.api.CallMode
import com.epiccrown.directstream.api.Device
import com.epiccrown.directstream.api.DeviceCode
import com.epiccrown.directstream.api.IceServer
import com.epiccrown.directstream.api.PairingRequest
import com.epiccrown.directstream.api.Role
import com.epiccrown.directstream.api.TurnConfiguration
import com.epiccrown.directstream.firebase.internal.FirebaseClients
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import timber.log.Timber

typealias ServiceException = BackendException

class PairingApi(private val clients: FirebaseClients, private val baseUrl: String) {
    val configured
        get() = baseUrl.startsWith("https://")

    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()

    private suspend fun post(
        path: String,
        data: JSONObject = JSONObject(),
        timeoutSeconds: Long = 20,
    ): JSONObject {
        check(configured) { "Configure the HTTPS Worker URL in stream.properties, then rebuild." }
        for (attempt in 0..1) {
            val token = clients.idToken(attempt == 1)
            val result =
                withContext(Dispatchers.IO) {
                    val started = System.nanoTime()
                    Timber.tag("PairingApi").d("POST %s (attempt %d)", path, attempt + 1)
                    val request =
                        Request.Builder()
                            .url(baseUrl.trimEnd('/') + path)
                            .header("Authorization", "Bearer $token")
                            .post(data.toString().toRequestBody("application/json".toMediaType()))
                            .build()
                    val call =
                        (if (timeoutSeconds == 20L) http
                            else
                                http
                                    .newBuilder()
                                    .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
                                    .build())
                            .newCall(request)
                    suspendCancellableCoroutine<Pair<Int, JSONObject>> { continuation ->
                        continuation.invokeOnCancellation { call.cancel() }
                        call.enqueue(
                            object : okhttp3.Callback {
                                override fun onFailure(
                                    call: okhttp3.Call,
                                    error: java.io.IOException,
                                ) {
                                    continuation.resumeWithException(error)
                                }

                                override fun onResponse(
                                    call: okhttp3.Call,
                                    response: okhttp3.Response,
                                ) {
                                    try {
                                        val result = response.use {
                                            val elapsedMs =
                                                TimeUnit.NANOSECONDS.toMillis(
                                                    System.nanoTime() - started,
                                                )
                                            Timber.tag("PairingApi")
                                                .d(
                                                    "POST %s -> HTTP %d (%d ms)",
                                                    path,
                                                    response.code,
                                                    elapsedMs,
                                                )
                                            val payload = runCatching {
                                                JSONObject(
                                                    response.body?.string().orEmpty().ifBlank {
                                                        "{}"
                                                    },
                                                )
                                            }
                                                .getOrElse {
                                                    throw ServiceException(
                                                        response.code,
                                                        "Unexpected server response. Please retry.",
                                                    )
                                                }
                                            response.code to payload
                                        }
                                        continuation.resume(result)
                                    } catch (error: Exception) {
                                        continuation.resumeWithException(error)
                                    }
                                }
                            },
                        )
                    }
                }
            if (result.first in 200..299) return result.second
            if (result.first == 401 && attempt == 0) {
                Timber.tag("PairingApi")
                    .d("Authentication rejected; retrying with a refreshed token")
                continue
            }
            Timber.tag("PairingApi").w("POST %s rejected (HTTP %d)", path, result.first)
            throw ServiceException(
                result.first,
                result.second.optString("error", "Service unavailable."),
            )
        }
        error("Authentication failed.")
    }

    private fun device(data: JSONObject) =
        Device(
            data.getString("code"),
            true,
            data.optString("activeRoomId").takeUnless { it.isBlank() || it == "null" },
            data.takeIf { it.has("relayAvailable") }?.getBoolean("relayAvailable"),
            data.optJSONArray("supportedCallModes")?.let { values ->
                (0 until values.length())
                    .mapNotNull {
                        runCatching { CallMode.valueOf(values.getString(it)) }.getOrNull()
                    }
                    .toSet()
            } ?: CallMode.entries.toSet(),
            true,
            data.optString("participantName").takeUnless { it.isBlank() || it == "null" },
        )

    private fun request(data: JSONObject) =
        PairingRequest(
            data.getString("requestId"),
            data.getString("verificationValue"),
            data.getLong("expiresAt"),
            data.getString("status"),
            data.optString("roomId").takeUnless { it.isBlank() || it == "null" },
            Role.valueOf(data.getString("role")),
            data.optString("peerCode").takeIf { it.isNotBlank() && it != "null" },
            data.optLong("createdAt").takeIf { it > 0 },
            CallMode.valueOf(data.optString("mode", "Video")),
            data.optString("peerName").takeUnless { it.isBlank() || it == "null" },
        )

    suspend fun register(participantName: String? = null): Device {
        val data =
            operation(
                "/device/register",
                JSONObject().put("participantName", participantName ?: JSONObject.NULL),
            )
        if (
            data.optInt("eventProtocolVersion", 1) != 1 ||
                data.optInt("codecProtocolVersion", 1) != 1
        )
            throw BackendException(
                426,
                "Unsupported calling protocol. Update the SDK and service together.",
            )
        return device(data)
    }

    private suspend fun operation(
        path: String,
        data: JSONObject = JSONObject(),
        timeoutSeconds: Long = 20,
    ): JSONObject = post("/v1$path", data, timeoutSeconds)

    suspend fun revoke() {
        operation("/device/revoke")
    }

    suspend fun reset(): Device = device(operation("/device/reset"))

    suspend fun pushToken(token: String) {
        operation("/device/push-token", JSONObject().put("token", token))
    }

    suspend fun create(
        code: String,
        requestId: String,
        mode: CallMode = CallMode.Video,
    ): PairingRequest {
        return request(
            operation(
                "/requests/create",
                JSONObject()
                    .put("code", DeviceCode.normalize(code))
                    .put("requestId", requestId)
                    .put("mode", mode.name),
            ),
        )
    }

    suspend fun incoming(): PairingRequest? =
        operation("/requests/incoming").optJSONObject("request")?.let(::request)

    suspend fun status(id: String, timeoutSeconds: Long = 20): PairingRequest =
        request(operation("/requests/status", JSONObject().put("requestId", id), timeoutSeconds))

    suspend fun approve(id: String): String =
        operation("/requests/approve", JSONObject().put("requestId", id)).getString("roomId")

    suspend fun reject(id: String) {
        operation("/requests/reject", JSONObject().put("requestId", id))
    }

    suspend fun cancel(id: String) {
        operation("/requests/cancel", JSONObject().put("requestId", id))
    }

    suspend fun end(roomId: String) {
        operation("/sessions/end", JSONObject().put("roomId", roomId))
    }

    suspend fun sessionActive(roomId: String) {
        operation("/sessions/status", JSONObject().put("roomId", roomId))
    }

    suspend fun iceServers(roomId: String): TurnConfiguration {
        val data = operation("/turn-credentials", JSONObject().put("roomId", roomId))
        val servers = data.getJSONArray("iceServers")
        return TurnConfiguration(
            (0 until servers.length()).map { index ->
                val item = servers.getJSONObject(index)
                val urls = item.getJSONArray("urls")
                IceServer(
                    (0 until urls.length()).map { urls.getString(it) },
                    item.optString("username"),
                    item.optString("credential"),
                )
            },
            data.getLong("expiresAt"),
        )
    }
}
