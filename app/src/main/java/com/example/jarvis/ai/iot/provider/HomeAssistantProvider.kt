package com.example.jarvis.ai.iot.provider

import com.example.jarvis.domain.iot.*
import com.example.jarvis.domain.tools.RiskLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class HomeAssistantConfig(
    val baseUrl: String = "http://localhost:8123",
    val token: String = "",
    val connectTimeoutSeconds: Long = 10,
    val readTimeoutSeconds: Long = 10,
    val writeTimeoutSeconds: Long = 10
)

data class HomeAssistantStateDto(
    val entityId: String,
    val state: String,
    val attributes: Map<String, Any>,
    val lastChanged: String
)

class HomeAssistantProvider(
    private val config: HomeAssistantConfig,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(config.connectTimeoutSeconds, TimeUnit.SECONDS)
        .readTimeout(config.readTimeoutSeconds, TimeUnit.SECONDS)
        .writeTimeout(config.writeTimeoutSeconds, TimeUnit.SECONDS)
        .build()
) : IoTProvider {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private fun buildRequest(path: String): Request.Builder {
        val cleanBase = config.baseUrl.removeSuffix("/")
        return Request.Builder()
            .url("$cleanBase/api/$path")
            .addHeader("Authorization", "Bearer ${config.token}")
            .addHeader("Content-Type", "application/json")
    }

    override suspend fun testConnection(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            if (config.token.isBlank() || config.baseUrl.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("Home Assistant URL or Token is blank"))
            }
            val request = buildRequest("").get().build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Result.success(true)
                } else {
                    Result.failure(Exception("Home Assistant connection failed with status code: ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getDevices(): Result<List<IoTDevice>> = withContext(Dispatchers.IO) {
        try {
            val request = buildRequest("states").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Failed to fetch states: HTTP ${response.code}"))
                }
                val bodyStr = response.body?.string() ?: "[]"
                val jsonArray = JSONArray(bodyStr)
                val devices = mutableListOf<IoTDevice>()

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    devices.add(parseDevice(obj))
                }
                Result.success(devices)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getDevice(entityId: String): Result<IoTDevice?> = withContext(Dispatchers.IO) {
        try {
            val request = buildRequest("states/$entityId").get().build()
            client.newCall(request).execute().use { response ->
                if (response.code == 404) {
                    return@withContext Result.success(null)
                }
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Failed to fetch state for $entityId: HTTP ${response.code}"))
                }
                val bodyStr = response.body?.string() ?: "{}"
                val obj = JSONObject(bodyStr)
                Result.success(parseDevice(obj))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getState(entityId: String): Result<String> = withContext(Dispatchers.IO) {
        val deviceResult = getDevice(entityId)
        if (deviceResult.isSuccess) {
            val dev = deviceResult.getOrNull()
            Result.success(dev?.state ?: "unknown")
        } else {
            Result.failure(deviceResult.exceptionOrNull() ?: Exception("Unknown error getting state"))
        }
    }

    override suspend fun executeOperation(entityId: String, operation: IoTOperation): Result<String> = withContext(Dispatchers.IO) {
        try {
            val domain = operation.domain
            val service = operation.service
            val payload = JSONObject().apply {
                put("entity_id", entityId)
                for ((k, v) in operation.parameters) {
                    put(k, v)
                }
            }

            val requestBody = payload.toString().toRequestBody(jsonMediaType)
            val request = buildRequest("services/$domain/$service").post(requestBody).build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: "success"
                    Result.success("Operation executed successfully: $bodyStr")
                } else {
                    Result.failure(Exception("Service execution failed: HTTP ${response.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseDevice(obj: JSONObject): IoTDevice {
        val entityId = obj.optString("entity_id", "")
        val state = obj.optString("state", "unknown")
        val attributesObj = obj.optJSONObject("attributes") ?: JSONObject()
        val friendlyName = attributesObj.optString("friendly_name", entityId)

        val attributes = mutableMapOf<String, Any>()
        val keys = attributesObj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            attributes[key] = attributesObj.opt(key) ?: ""
        }

        val capability = when {
            entityId.startsWith("light.") -> IoTCapability.LIGHT
            entityId.startsWith("switch.") -> IoTCapability.SWITCH
            entityId.startsWith("fan.") -> IoTCapability.FAN
            entityId.startsWith("climate.") -> IoTCapability.CLIMATE
            entityId.startsWith("lock.") -> IoTCapability.LOCK
            entityId.startsWith("sensor.") -> IoTCapability.SENSOR
            entityId.startsWith("media_player.") -> IoTCapability.MEDIA_PLAYER
            else -> IoTCapability.GENERIC
        }

        return IoTDevice(
            id = entityId,
            name = friendlyName,
            type = capability,
            entityId = entityId,
            state = state,
            room = attributesObj.optString("area", null),
            provider = "home_assistant",
            isAvailable = state != "unavailable",
            attributes = attributes
        )
    }

    companion object {
        fun mapOperationToRisk(operation: IoTOperation): RiskLevel {
            return when (operation.type) {
                IoTOperationType.READ_STATE -> RiskLevel.READ
                IoTOperationType.TURN_ON, IoTOperationType.TURN_OFF, IoTOperationType.TOGGLE,
                IoTOperationType.SET_VALUE, IoTOperationType.SET_BRIGHTNESS, IoTOperationType.SET_TEMPERATURE -> RiskLevel.WRITE
                IoTOperationType.EXECUTE_SERVICE -> {
                    if (operation.domain == "lock" || operation.domain == "alarm_control_panel") {
                        RiskLevel.DESTRUCTIVE
                    } else {
                        RiskLevel.EXECUTE
                    }
                }
            }
        }
    }
}
