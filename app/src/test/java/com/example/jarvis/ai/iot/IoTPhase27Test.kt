package com.example.jarvis.ai.iot

import com.example.jarvis.ai.agents.DefaultToolRegistry
import com.example.jarvis.ai.tools.IoTTool
import com.example.jarvis.ai.iot.provider.HomeAssistantProvider
import com.example.jarvis.domain.iot.*
import com.example.jarvis.domain.tools.RiskLevel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class IoTPhase27Test {

    private lateinit var fakeProvider: FakeIoTProvider
    private lateinit var iotTool: IoTTool
    private lateinit var toolRegistry: DefaultToolRegistry

    @Before
    fun setUp() {
        fakeProvider = FakeIoTProvider()
        iotTool = IoTTool(provider = fakeProvider)
        toolRegistry = DefaultToolRegistry()
    }

    @Test
    fun test1_DomainModelsAndCapabilities() {
        val device = IoTDevice(
            id = "light.kitchen",
            name = "Kitchen Light",
            type = IoTCapability.LIGHT,
            entityId = "light.kitchen",
            state = "on",
            room = "Kitchen"
        )
        assertEquals("light.kitchen", device.entityId)
        assertEquals(IoTCapability.LIGHT, device.type)
        assertTrue(device.isAvailable)
    }

    @Test
    fun test2_RiskMapping() {
        val readOp = IoTOperation(IoTOperationType.READ_STATE, "light", "get_state")
        assertEquals(RiskLevel.READ, HomeAssistantProvider.mapOperationToRisk(readOp))

        val writeOp = IoTOperation(IoTOperationType.TURN_ON, "light", "turn_on")
        assertEquals(RiskLevel.WRITE, HomeAssistantProvider.mapOperationToRisk(writeOp))

        val lockOp = IoTOperation(IoTOperationType.EXECUTE_SERVICE, "lock", "unlock")
        assertEquals(RiskLevel.DESTRUCTIVE, HomeAssistantProvider.mapOperationToRisk(lockOp))
    }

    @Test
    fun test3_FakeProviderConnectionAndDiscovery() = runBlocking {
        val connResult = fakeProvider.testConnection()
        assertTrue(connResult.isSuccess)
        assertTrue(connResult.getOrDefault(false))

        val devicesResult = fakeProvider.getDevices()
        assertTrue(devicesResult.isSuccess)
        assertEquals(2, devicesResult.getOrNull()?.size)
    }

    @Test
    fun test4_FakeProviderUnauthorizedAndTimeout() = runBlocking {
        fakeProvider.unauthorized = true
        val connResult = fakeProvider.testConnection()
        assertTrue(connResult.isFailure)

        fakeProvider.unauthorized = false
        fakeProvider.timeout = true
        val stateResult = fakeProvider.getState("light.living_room")
        assertTrue(stateResult.isFailure)
    }

    @Test
    fun test5_IoTToolRegistration() {
        val tool = toolRegistry.getTool("iot_tool")
        assertNotNull(tool)
        assertEquals("iot_tool", tool?.name)
    }

    @Test
    fun test6_IoTToolReadExecution() = runBlocking {
        val params = mapOf("action" to "get_state", "entity_id" to "light.living_room")
        val result = iotTool.execute(params)
        assertEquals("off", result)
    }

    @Test
    fun test7_IoTToolWriteExecution() = runBlocking {
        val params = mapOf("action" to "turn_on", "entity_id" to "light.living_room")
        val result = iotTool.execute(params)
        assertTrue(result.contains("Successfully executed"))
        assertEquals(1, fakeProvider.executedOperations.size)
    }

    @Test
    fun test8_IoTToolMissingEntityId() = runBlocking {
        val params = mapOf("action" to "turn_on")
        val result = iotTool.execute(params)
        assertTrue(result.contains("especifica el dispositivo"))
    }
}
