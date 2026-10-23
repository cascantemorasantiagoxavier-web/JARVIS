package com.example.jarvis.core.skills

import com.example.jarvis.core.skills.impl.DevelopmentCopilotSkill
import com.example.jarvis.core.skills.impl.DocumentReaderSkill
import com.example.jarvis.core.skills.impl.HomeInventorySkill
import com.example.jarvis.core.skills.impl.ReportGeneratorSkill
import com.example.jarvis.core.skills.impl.SalesCoachSkill
import com.example.jarvis.core.skills.impl.SpreadsheetAnalystSkill
import com.example.jarvis.core.skills.impl.StockControlSkill
import com.example.jarvis.core.skills.impl.WritingEditorSkill
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
        StockControlSkill(), HomeInventorySkill(), SalesCoachSkill(),
    )

    fun install(tools: ToolRegistry, registry: SkillRegistry, engine: SkillEngine, env: SkillEnvironment) {
        all().forEach { registry.register(it) }
        SkillToolset(registry, engine, env).registerAll(tools)
    }
}
