package com.example.jarvis.device

import com.example.jarvis.ai.agents.DefaultAgentRegistry
import com.example.jarvis.ai.agents.DefaultIntentDetector
import com.example.jarvis.ai.agents.DefaultToolRegistry
import com.example.jarvis.ai.agents.DeviceAgentImpl
import com.example.jarvis.ai.context.DefaultContextManager
import com.example.jarvis.ai.memory.DefaultAiMemoryManager
import com.example.jarvis.ai.orchestrator.DefaultAiOrchestrator
import com.example.jarvis.ai.orchestrator.models.IntentType
import com.example.jarvis.ai.orchestrator.models.OrchestratorRequest
import com.example.jarvis.ai.tools.GetDeviceInfoTool
import com.example.jarvis.ai.tools.OpenSettingsTool
import com.example.jarvis.core.device.AppManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DeviceControlPhase8BTest {

    private class FakeAppManager(
        var isInstalledResult: Boolean = true,
        var launchAppResult: Result<Unit> = Result.success(Unit),
        var openSettingsResult: Result<Unit> = Result.success(Unit),
        var modelName: String = "Google Pixel 8",
        var versionRelease: String = "Android 14 (API 34)",
        var batteryLevelResult: Result<Int> = Result.success(85)
    ) : AppManager {
        val openedSettingsList = mutableListOf<String>()
        val launchedAppsList = mutableListOf<String>()

        override fun isAppInstalled(packageName: String): Boolean = isInstalledResult

        override fun launchApp(packageName: String): Result<Unit> {
            launchedAppsList.add(packageName)
            return launchAppResult
        }

        override fun openSettings(settingType: String): Result<Unit> {
            openedSettingsList.add(settingType)
            return openSettingsResult
        }

        override fun getDeviceModel(): String = modelName

        override fun getAndroidVersion(): String = versionRelease

        override fun getBatteryLevel(): Result<Int> = batteryLevelResult
    }

    private lateinit var fakeAppManager: FakeAppManager
    private lateinit var deviceAgent: DeviceAgentImpl

    @Before
    fun setUp() {
        fakeAppManager = FakeAppManager()
        deviceAgent = DeviceAgentImpl(appManagerProvider = { fakeAppManager })
    }

    @Test
    fun testOpenSettingsGeneral() = runBlocking {
        val result = deviceAgent.process("abre ajustes")
        assertTrue(result.contains("Abriendo ajustes de general"))
        assertEquals(listOf("general"), fakeAppManager.openedSettingsList)
    }

    @Test
    fun testOpenSettingsWifi() = runBlocking {
        val result = deviceAgent.process("abre ajustes de wifi")
        assertTrue(result.contains("Abriendo ajustes de wifi"))
        assertEquals(listOf("wifi"), fakeAppManager.openedSettingsList)
    }

    @Test
    fun testOpenSettingsBluetooth() = runBlocking {
        val result = deviceAgent.process("abrir ajustes de bluetooth")
        assertTrue(result.contains("Abriendo ajustes de bluetooth"))
        assertEquals(listOf("bluetooth"), fakeAppManager.openedSettingsList)
    }

    @Test
    fun testOpenSettingsSound() = runBlocking {
        val result = deviceAgent.process("ajustes de sonido")
        assertTrue(result.contains("Abriendo ajustes de sound"))
        assertEquals(listOf("sound"), fakeAppManager.openedSettingsList)
    }

    @Test
    fun testGetDeviceInfoAndModel() = runBlocking {
        val info = deviceAgent.getDeviceInfo()
        assertTrue(info.contains("Google Pixel 8"))
        assertTrue(info.contains("Android 14"))

        val response = deviceAgent.process("cuál es el modelo del dispositivo")
        assertTrue(response.contains("Google Pixel 8"))
    }

    @Test
    fun testGetBatteryLevelSuccess() = runBlocking {
        val response = deviceAgent.process("cuánta batería tengo")
        assertEquals("Nivel de batería: 85%", response)
    }

    @Test
    fun testGetBatteryLevelError() = runBlocking {
        fakeAppManager.batteryLevelResult = Result.failure(IllegalStateException("No disponible"))
        val response = deviceAgent.process("batería")
        assertEquals("No disponible", response)
    }

    @Test
    fun testOpenSettingsToolExecution() = runBlocking {
        val tool = OpenSettingsTool(appManagerProvider = { fakeAppManager })
        assertEquals("open_settings", tool.name)

        val result = tool.execute(mapOf("settingType" to "wifi"))
        assertTrue(result.contains("wifi"))
        assertEquals(listOf("wifi"), fakeAppManager.openedSettingsList)
    }

    @Test
    fun testGetDeviceInfoToolExecution() = runBlocking {
        val tool = GetDeviceInfoTool(appManagerProvider = { fakeAppManager })
        assertEquals("get_device_info", tool.name)

        val resultBattery = tool.execute(mapOf("queryType" to "battery"))
        assertTrue(resultBattery.contains("85%"))

        val resultModel = tool.execute(mapOf("queryType" to "model"))
        assertTrue(resultModel.contains("Google Pixel 8"))
    }

    @Test
    fun testPermissionSecurityExceptionMessage() = runBlocking {
        fakeAppManager.openSettingsResult = Result.failure(
            SecurityException("Esta acción requiere autorización del usuario o un permiso especial no concedido.")
        )
        val response = deviceAgent.process("abre ajustes de wifi")
        assertTrue(response.contains("requiere autorización del usuario"))
    }

    @Test
    fun testOrchestratorDeviceIntentFlow() = runBlocking {
        val agentRegistry = DefaultAgentRegistry().apply {
            registerAgent(DeviceAgentImpl(appManagerProvider = { fakeAppManager }))
        }
        val toolRegistry = DefaultToolRegistry()
        val orchestrator = DefaultAiOrchestrator(
            intentDetector = DefaultIntentDetector(),
            contextManager = DefaultContextManager(),
            memoryManager = DefaultAiMemoryManager(),
            agentRegistry = agentRegistry,
            toolRegistry = toolRegistry
        )

        val request = OrchestratorRequest(input = "abre ajustes de wifi")
        val response = orchestrator.process(request)

        assertTrue(response.isSuccess)
        assertEquals(IntentType.DEVICE, response.detectedIntent?.type)
        assertTrue(response.output.contains("wifi"))
    }
}
