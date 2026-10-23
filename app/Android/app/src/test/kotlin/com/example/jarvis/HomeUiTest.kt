package com.example.jarvis

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Pruebas de interfaz (Robolectric). Sin servidor configurado: verifican el arranque, la navegación
 * y la ruta local del cerebro; las funciones remotas deben informar del motivo, no inventar.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w900dp-h600dp-land")
class HomeUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun dismissWelcome() {
        if (rule.onAllNodes(hasText("Más tarde")).fetchSemanticsNodes().isNotEmpty()) rule.onNodeWithText("Más tarde").performClick()
    }

    @Test fun startsOnHolographicCoreNotOnMap() {
        dismissWelcome()
        rule.onNodeWithTag("home").assertIsDisplayed()
        rule.onNodeWithTag("core").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithTag("map").fetchSemanticsNodes().size)
        rule.onAllNodes(hasText("JARVIS")).onFirst().assertIsDisplayed()
        rule.onNodeWithContentDescription("Hablar con JARVIS").assertIsDisplayed()
    }

    @Test fun chatAnswersLocallyWithoutServer() {
        dismissWelcome()
        rule.onNodeWithContentDescription("Chat").performClick()
        rule.onNodeWithTag("chat_input").performTextInput("¿Qué hora es?")
        rule.onNodeWithTag("chat_send").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Son las", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun businessSearchDoesNotOpenMapAndReportsMissingServer() {
        dismissWelcome()
        rule.onNodeWithContentDescription("Chat").performClick()
        rule.onNodeWithTag("chat_input").performTextInput("Busca supermercados en San José, Costa Rica")
        rule.onNodeWithTag("chat_send").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("servidor", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(0, rule.onAllNodesWithTag("map").fetchSemanticsNodes().size)
    }

    @Test fun mapOpensOnlyWhenRequested() {
        dismissWelcome()
        assertEquals(0, rule.onAllNodesWithTag("map").fetchSemanticsNodes().size)
        rule.onNodeWithContentDescription("Mapa").performClick()
        rule.onNodeWithTag("map").assertIsDisplayed()
    }

    @Test fun settingsScreenOpens() {
        dismissWelcome()
        rule.onNodeWithContentDescription("Ajustes").performClick()
        rule.onNodeWithText("SERVIDOR DE JARVIS").assertIsDisplayed()
    }
}
