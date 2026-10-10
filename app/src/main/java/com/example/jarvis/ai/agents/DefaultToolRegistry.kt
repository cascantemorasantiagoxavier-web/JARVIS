package com.example.jarvis.ai.agents

import com.example.jarvis.ai.tools.*
import com.example.jarvis.core.di.AppContainer
import com.example.jarvis.domain.tools.Tool

class DefaultToolRegistry : ToolRegistry {
    private val tools = mutableMapOf<String, Tool>()

    init {
        registerTool(SystemInfoTool())
        registerTool(MemorySearchTool())
        registerTool(TaskCreateTool())
        registerTool(TaskListTool())
        registerTool(TaskCompleteTool())
        registerTool(BusinessProspectTool())
        registerTool(ImportResearchTool())
        registerTool(DiagnosticTool())
        registerTool(SupplierComparisonTool())
        registerTool(MarketAnalysisTool())
        registerTool(FinancialCalcTool())
        registerTool(LogisticsCalculatorTool())
        registerTool(DataAnalyticsTool())
        registerTool(WorkflowAutomationTool())
        registerTool(ScriptStudioTool())
        registerTool(AppBuilderTool())
        registerTool(ImageGenerationTool())
        registerTool(ImageAnalysisTool())
        registerTool(CodeAssistanceTool())
        registerTool(BusinessSystemBuilderTool())
        registerTool(ProductCatalogTool())
        registerTool(InventoryControlTool())
        registerTool(SalesPosTool())
        registerTool(BusinessDashboardTool())
        registerTool(ObsidianSearchTool())
        registerTool(OpenSettingsTool())
        registerTool(GetDeviceInfoTool())
        registerTool(CheckAppInstalledTool())
        registerTool(LaunchAppTool())
        registerTool(ResearchTool())
        registerTool(WebSearchTool())
        registerTool(OpenWebPageTool())
        registerTool(CalendarTool())
        registerTool(EmailTool())
        registerTool(CommunicationSearchTool())
        registerTool(CommunicationDraftTool())
        registerTool(CommunicationSendTool())
        registerTool(IoTTool())
        registerTool(MapsTool { AppContainer.appContext })
        registerTool(ContactSearchTool { AppContainer.appContext })
        registerTool(DailyBriefingTool { AppContainer.appContext })
        registerTool(OpenWebTool())
        registerTool(DeviceControlTool())
        registerTool(GetDateTimeTool())
        registerTool(CalculatorTool())
        registerTool(BatteryTool())
        registerTool(CheckNetworkTool())
        registerTool(MemorySaveTool())
        registerTool(MemoryDeleteTool())
        registerTool(MemoryClearAllTool())
        registerTool(PreferenceUpdateTool())

        try {
            val ctx = AppContainer.appContext
            if (ctx != null) {
                registerTool(CreateShortTool(ctx))
                registerTool(RenderShortTool(ctx))
                registerTool(PublishShortTool(ctx))
                registerTool(ListShortsTool(ctx))
            }
        } catch (_: Exception) {}
    }

    override fun registerTool(tool: Tool) {
        tools[tool.name] = tool
    }

    override fun getTool(name: String): Tool? {
        val tool = tools[name]
        if (tool == null) {
            when (name) {
                "maps_tool" -> {
                    val t = MapsTool { AppContainer.appContext }
                    registerTool(t)
                    return t
                }
                "contact_search" -> {
                    val t = ContactSearchTool { AppContainer.appContext }
                    registerTool(t)
                    return t
                }
                "daily_briefing" -> {
                    val t = DailyBriefingTool { AppContainer.appContext }
                    registerTool(t)
                    return t
                }
                "task_list" -> {
                    val t = TaskListTool()
                    registerTool(t)
                    return t
                }
                "task_complete" -> {
                    val t = TaskCompleteTool()
                    registerTool(t)
                    return t
                }
                "business_prospect" -> {
                    val t = BusinessProspectTool()
                    registerTool(t)
                    return t
                }
                "import_research" -> {
                    val t = ImportResearchTool()
                    registerTool(t)
                    return t
                }
                "diagnostic_tool" -> {
                    val t = DiagnosticTool()
                    registerTool(t)
                    return t
                }
                "supplier_comparison" -> {
                    val t = SupplierComparisonTool()
                    registerTool(t)
                    return t
                }
                "market_analysis" -> {
                    val t = MarketAnalysisTool()
                    registerTool(t)
                    return t
                }
                "financial_calc" -> {
                    val t = FinancialCalcTool()
                    registerTool(t)
                    return t
                }
                "logistics_calculator" -> {
                    val t = LogisticsCalculatorTool()
                    registerTool(t)
                    return t
                }
                "data_analytics" -> {
                    val t = DataAnalyticsTool()
                    registerTool(t)
                    return t
                }
                "workflow_automation" -> {
                    val t = WorkflowAutomationTool()
                    registerTool(t)
                    return t
                }
                "script_studio" -> {
                    val t = ScriptStudioTool()
                    registerTool(t)
                    return t
                }
                "app_builder" -> {
                    val t = AppBuilderTool()
                    registerTool(t)
                    return t
                }
                "image_generation" -> {
                    val t = ImageGenerationTool()
                    registerTool(t)
                    return t
                }
                "image_analysis" -> {
                    val t = ImageAnalysisTool()
                    registerTool(t)
                    return t
                }
                "code_assistance" -> {
                    val t = CodeAssistanceTool()
                    registerTool(t)
                    return t
                }
                "business_system_builder" -> {
                    val t = BusinessSystemBuilderTool()
                    registerTool(t)
                    return t
                }
                "product_catalog" -> {
                    val t = ProductCatalogTool()
                    registerTool(t)
                    return t
                }
                "inventory_control" -> {
                    val t = InventoryControlTool()
                    registerTool(t)
                    return t
                }
                "sales_pos" -> {
                    val t = SalesPosTool()
                    registerTool(t)
                    return t
                }
                "business_dashboard" -> {
                    val t = BusinessDashboardTool()
                    registerTool(t)
                    return t
                }
            }
        }
        if (tool == null && AppContainer.appContext != null) {
            val ctx = AppContainer.appContext!!
            try {
                when (name) {
                    "create_short" -> {
                        val t = CreateShortTool(ctx)
                        registerTool(t)
                        return t
                    }
                    "render_short" -> {
                        val t = RenderShortTool(ctx)
                        registerTool(t)
                        return t
                    }
                    "publish_short" -> {
                        val t = PublishShortTool(ctx)
                        registerTool(t)
                        return t
                    }
                    "list_shorts" -> {
                        val t = ListShortsTool(ctx)
                        registerTool(t)
                        return t
                    }
                }
            } catch (_: Exception) {}
        }
        return tool
    }

    override fun getRegisteredTools(): List<Tool> {
        return tools.values.toList()
    }
}
