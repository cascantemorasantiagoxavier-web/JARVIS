package com.example.jarvis.core.skills

import com.example.jarvis.core.skills.impl.*
import com.example.jarvis.core.tools.ToolRegistry

/**
 * Lista única de habilidades implementadas. Añadir una habilidad = manifiesto en
 * shared/skills/<id>.json + clase que implemente Skill + una línea aquí (ver docs/SKILLS.md).
 */
object JarvisSkills {
    fun all(): List<Skill> = listOf(
        // Productividad
        WritingEditorSkill(), SpreadsheetAnalystSkill(), DocumentReaderSkill(), ReportGeneratorSkill(), DevelopmentCopilotSkill(),
        // Negocio e inventario
        StockControlSkill(), HomeInventorySkill(), SalesCoachSkill(), BusinessProspectingSkill(),
        // Asistente personal
        DailyBriefingSkill(), TaskManagerSkill(), ConversationMemorySkill(), DeviceDiagnosticsSkill(), MapsNavigationSkill(),
        ObsidianMemorySkill(), MeetingAssistantSkill(), RoutineAutomationSkill(),
        // Investigación
        DeepResearchSkill(), ImportExportResearchSkill(), PriceComparisonSkill(),
        // Comunicación y marketing
        MessageDrafterSkill(), EmailAssistantSkill(), MessagingAssistantSkill(), SocialMediaManagerSkill(), MarketingOptimizerSkill(), WebsiteBuilderSkill(),
        // Finanzas
        ExpenseCaptureSkill(), PersonalFinanceSkill(), CashflowForecastSkill(), InvoiceQuoteCreatorSkill(),
        // Clientes, negociación y mercado
        CustomerRelationshipsSkill(), NegotiationCoachSkill(), ContractReviewSkill(), CompetitorIntelligenceSkill(), SupplierVerifierSkill(),
        // Planificación y hogar
        ScheduleOptimizerSkill(), RenewalMonitorSkill(), TravelPlannerSkill(), MealAndGroceryPlannerSkill(),
        // Aprendizaje
        PersonalTutorSkill(), LanguageCoachSkill(),
        // Voz, imagen y escáner
        VoiceMemoInboxSkill(), VisualSceneAssistantSkill(), BarcodeBusinessCardScannerSkill(),
    )

    fun install(tools: ToolRegistry, registry: SkillRegistry, engine: SkillEngine, env: SkillEnvironment) {
        all().forEach { registry.register(it) }
        SkillToolset(registry, engine, env).registerAll(tools)
    }
}
