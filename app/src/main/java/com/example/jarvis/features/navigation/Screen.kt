package com.example.jarvis.features.navigation

sealed class Screen(val route: String, val title: String) {
    data object Home : Screen("home", "Inicio")
    data object Chat : Screen("chat", "Chat")
    data object Voice : Screen("voice", "Voz")
    data object Shorts : Screen("shorts", "Shorts")
    data object Tasks : Screen("tasks", "Tareas")
    data object Memory : Screen("memory", "Memoria")
    data object Projects : Screen("projects", "Proyectos")
    data object Settings : Screen("settings", "Configuración")
}
