package com.example.jarvis.domain.iot

import com.example.jarvis.domain.tools.RiskLevel

enum class IoTCapability {
    LIGHT,
    SWITCH,
    FAN,
    CLIMATE,
    LOCK,
    SENSOR,
    MEDIA_PLAYER,
    GENERIC
}

data class IoTDevice(
    val id: String,
    val name: String,
    val type: IoTCapability,
    val entityId: String,
    val state: String,
    val room: String? = null,
    val provider: String = "home_assistant",
    val isAvailable: Boolean = true,
    val attributes: Map<String, Any> = emptyMap(),
    val metadata: Map<String, String> = emptyMap()
)

enum class IoTOperationType {
    READ_STATE,
    TURN_ON,
    TURN_OFF,
    TOGGLE,
    SET_VALUE,
    SET_BRIGHTNESS,
    SET_TEMPERATURE,
    EXECUTE_SERVICE
}

data class IoTOperation(
    val type: IoTOperationType,
    val domain: String,
    val service: String,
    val parameters: Map<String, Any> = emptyMap(),
    val riskLevel: RiskLevel = RiskLevel.WRITE
)

interface IoTProvider {
    suspend fun getDevices(): Result<List<IoTDevice>>
    suspend fun getDevice(entityId: String): Result<IoTDevice?>
    suspend fun getState(entityId: String): Result<String>
    suspend fun executeOperation(entityId: String, operation: IoTOperation): Result<String>
    suspend fun testConnection(): Result<Boolean>
}
