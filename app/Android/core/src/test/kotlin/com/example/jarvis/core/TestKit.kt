package com.example.jarvis.core

import com.example.jarvis.core.brain.ContextRetriever
import com.example.jarvis.core.brain.JarvisBrain
import com.example.jarvis.core.brain.LlmClient
import com.example.jarvis.core.brain.Persona
import com.example.jarvis.core.brain.PrivacySettings
import com.example.jarvis.core.business.LatLon
import com.example.jarvis.core.business.ProspectService
import com.example.jarvis.core.intent.IntentDetector
import com.example.jarvis.core.memory.MemoryService
import com.example.jarvis.core.net.BackendClient
import com.example.jarvis.core.net.BackendConfig
import com.example.jarvis.core.notes.NoteSearchService
import com.example.jarvis.core.skills.InMemoryRecordStore
import com.example.jarvis.core.skills.JarvisSkills
import com.example.jarvis.core.skills.SkillEngine
import com.example.jarvis.core.skills.SkillEnvironment
import com.example.jarvis.core.skills.SkillLlm
import com.example.jarvis.core.skills.SkillRegistry
import com.example.jarvis.core.skills.SkillRouter
import com.example.jarvis.core.skills.SkillServices
import com.example.jarvis.core.tasks.TaskService
import com.example.jarvis.core.tools.ConfirmationGate
import com.example.jarvis.core.tools.PermissionGate
import com.example.jarvis.core.tools.ToolCatalog
import com.example.jarvis.core.tools.ToolExecutor
import com.example.jarvis.core.tools.ToolLogEntry
import com.example.jarvis.core.tools.ToolRegistry
import com.example.jarvis.core.toolset.CoreToolset
import com.example.jarvis.core.toolset.ToolDeps
import com.example.jarvis.core.util.FixedTimeSource
import com.example.jarvis.core.weather.Place
import com.example.jarvis.core.weather.WeatherClient
import java.time.Instant

/** Ensambla el sistema completo de :core con dobles de prueba (como hace AppContainer en Android). */
class TestKit(
    backendUrl: String = "http://127.0.0.1:9/",
    token: String = "test-client-token-0123456789abcdef",
    var llm: LlmClient? = null,
    var confirm: Boolean = true,
    var permissionGranted: Boolean = true,
    weatherClient: WeatherClient = WeatherClient(),
) {
    val time = FixedTimeSource(Instant.parse("2025-09-29T13:42:00Z")) // 07:42 en Costa Rica (UTC-6)
    val memoryRepo = InMemoryMemoryRepo()
    val memory = MemoryService(memoryRepo) { time.now() }
    val taskRepo = InMemoryTaskRepo()
    val scheduler = FakeScheduler()
    val tasks = TaskService(taskRepo, scheduler, time)
    val prospectRepo = InMemoryProspectRepo()
    val prospects = ProspectService(prospectRepo) { time.now() }
    val noteIndex = InMemoryNoteIndex()
    val notes = NoteSearchService(noteIndex)
    var notesEnabled = true
    val device = FakeDevice()
    val ui = RecordingUi()
    val conversation = InMemoryConversation()
    val logs = mutableListOf<ToolLogEntry>()
    var location: LatLon? = LatLon(9.9333, -84.0833)
    var config = BackendConfig(backendUrl, token)
    val backend = BackendClient({ config })
    val registry = ToolRegistry(ToolCatalog.loadFromResources())
    var privacy = PrivacySettings()

    val deps = ToolDeps(
        backend = backend, memory = memory, notes = { if (notesEnabled) notes else null }, tasks = tasks, prospects = prospects,
        device = device, weather = weatherClient, weatherPlace = { Place("Acosta", "San José", "Costa Rica", 9.80, -84.17) },
        currentLocation = { location }, defaultLocation = { null }, time = time,
    )

    init {
        CoreToolset(deps).registerAll(registry)
    }

    val executor = ToolExecutor(
        registry,
        PermissionGate { permissionGranted },
        ConfirmationGate { _, _ -> confirm },
        { logs += it },
        time,
    )
    // ---- habilidades ----
    val store = InMemoryRecordStore()
    val media = FakeMedia()
    var serverConfigured: Boolean = backendUrl != "http://127.0.0.1:9/"
    var vaultLinked = true
    var skillLlm: SkillLlm? = null
    val env = SkillEnvironment(serverConfigured = { serverConfigured }, vaultLinked = { vaultLinked })
    val skillServices = SkillServices(
        store = store, tasks = tasks, memory = memory, notes = { if (notesEnabled) notes else null }, noteStats = { noteIndex.stats() },
        prospects = prospects, device = device, media = media, weather = weatherClient,
        weatherPlace = { Place("Acosta", "San José", "Costa Rica", 9.80, -84.17) }, llm = { skillLlm }, time = time,
    )
    val skills = SkillRegistry(registry)
    val engine = SkillEngine(skills, executor, skillServices, env, PermissionGate { permissionGranted }, ConfirmationGate { _, _ -> confirm }, { logs += it })

    init {
        JarvisSkills.install(registry, skills, engine, env)
    }

    val detector = IntentDetector(time)
    val context = ContextRetriever(memory, { if (notesEnabled) notes else null }, tasks, time, { privacy }) { q -> skills.briefCatalog(env, q) }
    val skillRouter = SkillRouter(skills, engine, env)
    val brain = JarvisBrain(registry, executor, detector, { llm }, conversation, context, Persona.load(), time, skillRouter)
}
