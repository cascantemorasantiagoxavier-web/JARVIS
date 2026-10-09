package com.example.jarvis.features.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.jarvis.features.chat.JarvisChatScreen
import com.example.jarvis.features.home.JarvisHomeScreen
import com.example.jarvis.features.memory.JarvisMemoryScreen
import com.example.jarvis.features.projects.JarvisProjectsScreen
import com.example.jarvis.features.settings.JarvisSettingsScreen
import com.example.jarvis.features.shorts.ShortsScreen
import com.example.jarvis.features.tasks.JarvisTasksScreen
import com.example.jarvis.features.voice.JarvisVoiceScreen
import com.example.jarvis.ui.hud.AssistantState
import com.example.jarvis.ui.hud.JarvisControlBar
import com.example.jarvis.ui.hud.JarvisTopBar
import com.example.jarvis.ui.theme.HudBackground

@Composable
fun JarvisMainScreen() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route ?: Screen.Home.route

    Scaffold(
        topBar = {
            JarvisTopBar(state = AssistantState.IDLE)
        },
        bottomBar = {
            JarvisControlBar(
                currentRoute = currentRoute,
                onNavigate = { route ->
                    if (currentRoute != route) {
                        navController.navigate(route) {
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(HudBackground)
                .padding(innerPadding)
        ) {
            NavHost(
                navController = navController,
                startDestination = Screen.Home.route,
                modifier = Modifier.fillMaxSize()
            ) {
                composable(Screen.Home.route) {
                    JarvisHomeScreen(onNavigate = { route -> navController.navigate(route) })
                }
                composable(Screen.Chat.route) {
                    JarvisChatScreen()
                }
                composable(Screen.Voice.route) {
                    JarvisVoiceScreen()
                }
                composable(Screen.Shorts.route) {
                    ShortsScreen()
                }
                composable(Screen.Tasks.route) {
                    JarvisTasksScreen()
                }
                composable(Screen.Memory.route) {
                    JarvisMemoryScreen()
                }
                composable(Screen.Projects.route) {
                    JarvisProjectsScreen()
                }
                composable(Screen.Settings.route) {
                    JarvisSettingsScreen()
                }
            }
        }
    }
}
