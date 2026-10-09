package com.example.jarvis.ai.iot

import com.example.jarvis.domain.iot.*
import java.util.concurrent.TimeoutException

class FakeIoTProvider(
    var shouldFailConnection: Boolean = false,
    var unauthorized: Boolean = false,
    var timeout: Boolean = false,
    var customDevices: List<IoTDevice> = listOf(
        IoTDevice(
            id = "light.living_room",
            name = "Living Room Light",
            type = IoTCapability.LIGHT,
            entityId = "light.living_room",
            state = "off",
            room = "Living Room"
        ),
        IoTDevice(
            id = "lock.front_door",
            name = "Front Door Lock",
            type = IoTCapability.LOCK,
            entityId = "lock.front_door",
            state = "locked",
            room = "Entrance"
        )
    )
) : IoTProvider {

    val executedOperations = mutableListOf<Pair<String, IoTOperation>>()

    override suspend fun testConnection(): Result<Boolean> {
        if (timeout) return Result.failure(TimeoutException("Timeout connecting to Home Assistant"))
        if (unauthorized) return Result.failure(Exception("Unauthorized (401)"))
        if (shouldFailConnection) return Result.failure(Exception("Connection refused"))
        return Result.success(true)
    }

    override suspend fun getDevices(): Result<List<IoTDevice>> {
        if (timeout) return Result.failure(TimeoutException("Timeout"))
        if (unauthorized) return Result.failure(Exception("Unauthorized"))
        return Result.success(customDevices)
    }

    override suspend fun getDevice(entityId: String): Result<IoTDevice?> {
        if (timeout) return Result.failure(TimeoutException("Timeout"))
        val device = customDevices.find { it.entityId == entityId }
        return Result.success(device)
    }

    override suspend fun getState(entityId: String): Result<String> {
        if (timeout) return Result.failure(TimeoutException("Timeout"))
        val device = customDevices.find { it.entityId == entityId } ?: return Result.failure(Exception("Entity not found"))
        return Result.success(device.state)
    }

    override suspend fun executeOperation(entityId: String, operation: IoTOperation): Result<String> {
        if (timeout) return Result.failure(TimeoutException("Timeout"))
        if (unauthorized) return Result.failure(Exception("Unauthorized"))
        executedOperations.add(entityId to operation)
        return Result.success("Successfully executed ${operation.service} on $entityId")
    }
}
