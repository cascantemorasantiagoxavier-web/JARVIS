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
