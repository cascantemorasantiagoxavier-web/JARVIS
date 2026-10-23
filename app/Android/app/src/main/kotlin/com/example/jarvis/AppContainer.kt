package com.example.jarvis

import android.app.Application
import com.example.jarvis.core.brain.BackendLlmClient
import com.example.jarvis.core.brain.ContextRetriever
import com.example.jarvis.core.brain.JarvisBrain
import com.example.jarvis.core.brain.Persona
import com.example.jarvis.core.business.ProspectService
import com.example.jarvis.core.intent.IntentDetector
import com.example.jarvis.core.memory.MemoryService
import com.example.jarvis.core.model.AssistantMode
import com.example.jarvis.core.model.ResultCard
import com.example.jarvis.core.model.ToolRun
import com.example.jarvis.core.model.UiEvent
import com.example.jarvis.core.model.UiEventSink
import com.example.jarvis.core.net.BackendClient
import com.example.jarvis.core.net.BackendConfig
import com.example.jarvis.core.notes.NoteSearchService
import com.example.jarvis.core.orchestrator.JarvisOrchestrator
import com.example.jarvis.core.realtime.RealtimeSessionController
import com.example.jarvis.core.realtime.VoiceEventSink
import com.example.jarvis.core.skills.BackendSkillLlm
import com.example.jarvis.core.skills.JarvisSkills
import com.example.jarvis.core.skills.SkillEngine
import com.example.jarvis.core.skills.SkillEnvironment
import com.example.jarvis.core.skills.SkillRegistry
import com.example.jarvis.core.skills.SkillRouter
import com.example.jarvis.core.skills.SkillServices
import com.example.jarvis.core.tasks.TaskService
import com.example.jarvis.core.tools.ToolCatalog
import com.example.jarvis.core.tools.ToolExecutor
import com.example.jarvis.core.tools.ToolRegistry
import com.example.jarvis.core.tools.WorkingContext
import com.example.jarvis.core.toolset.CoreToolset
import com.example.jarvis.core.toolset.ToolDeps
import com.example.jarvis.core.util.SystemTimeSource
import com.example.jarvis.core.util.TimeSource
import com.example.jarvis.core.weather.WeatherClient
import com.example.jarvis.data.db.JarvisDatabase
import com.example.jarvis.data.repo.RoomConversationStore
import com.example.jarvis.data.repo.RoomMemoryRepository
import com.example.jarvis.data.repo.RoomNoteIndex
import com.example.jarvis.data.repo.RoomProspectRepository
import com.example.jarvis.data.repo.RoomRecordStore
import com.example.jarvis.data.repo.RoomTaskRepository
import com.example.jarvis.data.repo.RoomToolLogSink
import com.example.jarvis.data.settings.SecretStore
import com.example.jarvis.data.settings.SettingsRepository
import com.example.jarvis.device.AndroidDeviceGateway
import com.example.jarvis.device.ConfirmationBroker
import com.example.jarvis.device.ImageTools
import com.example.jarvis.device.MediaBroker
import com.example.jarvis.device.PermissionBroker
import com.example.jarvis.notes.VaultIndexer
import com.example.jarvis.reminders.AlarmReminderScheduler
import com.example.jarvis.voice.AudioLevels
import com.example.jarvis.voice.AudioRouting
import com.example.jarvis.voice.LocalSpeech
import com.example.jarvis.voice.VoiceManager
import com.example.jarvis.voice.WebRtcRealtimeConnector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.OkHttpClient

/**
 * Inyección de dependencias manual (sin frameworks): un único grafo para toda la app.
 * Chat, voz, botones y automatizaciones usan la MISMA instancia de cerebro, herramientas y memoria.
 */
class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val time: TimeSource = SystemTimeSource
    val db: JarvisDatabase = JarvisDatabase.build(app)
    val settings = SettingsRepository(app, scope)
    val secrets = SecretStore(app)
    val http = OkHttpClient()

    fun backendConfig() = BackendConfig(settings.current.backendUrl.trim(), secrets.token())
    fun backendConfigured(): Boolean = backendConfig().isConfigured

    val backend = BackendClient({ backendConfig() }, http)
    val permissions = PermissionBroker(app)
    val confirmations = ConfirmationBroker()
    val device = AndroidDeviceGateway(app, permissions)
    val media = MediaBroker(app, permissions)
    val weather = WeatherClient(http)

    private val _uiEvents = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val uiEvents: SharedFlow<UiEvent> = _uiEvents.asSharedFlow()
    val uiSink = UiEventSink { _uiEvents.tryEmit(it) }

    val memory = MemoryService(RoomMemoryRepository(db)) { time.now() }
    val noteIndex = RoomNoteIndex(db)
    val noteSearch = NoteSearchService(noteIndex)
    val vaultIndexer = VaultIndexer(app, db, noteIndex)
    val reminders = AlarmReminderScheduler(app)
    val tasks = TaskService(RoomTaskRepository(db), reminders, time)
    val prospects = ProspectService(RoomProspectRepository(db)) { time.now() }
    val conversation = RoomConversationStore(db)
    val working = WorkingContext()

    private fun notesIfLinked(): NoteSearchService? = if (settings.current.vaultUri != null) noteSearch else null

    val toolLog = RoomToolLogSink(db, scope)

    val registry: ToolRegistry = ToolRegistry(ToolCatalog.loadFromResources()).also { reg ->
        CoreToolset(
            ToolDeps(
                backend = backend,
                memory = memory,
                notes = ::notesIfLinked,
                tasks = tasks,
                prospects = prospects,
                device = device,
                weather = weather,
                weatherPlace = { settings.current.weatherPlace },
                currentLocation = { device.currentLocation() },
                defaultLocation = { settings.current.defaultLocation },
                time = time,
            ),
        ).registerAll(reg)
    }

    val executor = ToolExecutor(registry, permissions, confirmations, toolLog, time)

    // ---- Habilidades: mismo ejecutor, permisos y confirmaciones que el resto de JARVIS ----
    val skillEnv = SkillEnvironment(serverConfigured = ::backendConfigured, vaultLinked = { settings.current.vaultUri != null })
    val skillServices = SkillServices(
        store = RoomRecordStore(db),
        tasks = tasks,
        memory = memory,
        notes = ::notesIfLinked,
        noteStats = { if (settings.current.vaultUri != null) noteIndex.stats() else null },
        prospects = prospects,
        device = device,
        media = media,
        weather = weather,
        weatherPlace = { settings.current.weatherPlace },
        llm = { if (backendConfigured()) BackendSkillLlm(backend) else null },
        time = time,
        pixels = ImageTools.pixelDecoder,
    )
    val skills = SkillRegistry(registry)
    val skillEngine = SkillEngine(skills, executor, skillServices, skillEnv, permissions, confirmations, toolLog)

    init {
        // Registra las habilidades y las meta-herramientas (list_skills, describe_skill, run_skill)
        // antes de comprobar que todas las herramientas del contrato tienen implementación.
        JarvisSkills.install(registry, skills, skillEngine, skillEnv)
        registry.verifyComplete()
    }
    val persona: Persona = Persona.load()
    val contextRetriever = ContextRetriever(memory, ::notesIfLinked, tasks, time, { settings.current.privacy() }) { q -> skills.briefCatalog(skillEnv, q) }
    val intentDetector = IntentDetector(time) { settings.current.defaultLocation }

    val brain = JarvisBrain(
        registry = registry,
        executor = executor,
        detector = intentDetector,
        llm = { if (backendConfigured()) BackendLlmClient(backend) else null },
        conversation = conversation,
        context = contextRetriever,
        persona = persona,
        time = time,
        skillRouter = SkillRouter(skills, skillEngine, skillEnv),
    )

    val orchestrator = JarvisOrchestrator(brain, conversation, scope, time, working, uiSink)

    val levels = AudioLevels()
    val localSpeech = LocalSpeech(app, levels)

    /** Rompe la dependencia circular controlador ↔ gestor de voz. */
    private val voiceSinkProxy = object : VoiceEventSink {
        override fun userSaid(text: String) = voice.userSaid(text)
        override fun assistantSaid(text: String, cards: List<ResultCard>, runs: List<ToolRun>) = voice.assistantSaid(text, cards, runs)
        override fun notice(text: String) = voice.notice(text)
        override fun sessionEnded(reason: String) = voice.sessionEnded(reason)
    }

    val realtime = RealtimeSessionController(
        connector = WebRtcRealtimeConnector(app, backend, http, { settings.current.connectMode }, levels),
        registry = registry,
        executor = executor,
        instructions = { persona.instructions(contextRetriever.build("conversación de voz", AssistantMode.BALANCED, working, voice = true)) },
        settings = { settings.current.realtime() },
        working = working,
        ui = uiSink,
        sink = voiceSinkProxy,
        time = time,
        scope = scope,
    )

    val voice: VoiceManager by lazy {
        VoiceManager(
            context = app,
            scope = scope,
            settings = settings,
            realtime = realtime,
            orchestrator = { orchestrator },
            local = localSpeech,
            routing = AudioRouting(app),
            levels = levels,
            permissions = permissions,
            realtimeReady = ::backendConfigured,
        )
    }
}
