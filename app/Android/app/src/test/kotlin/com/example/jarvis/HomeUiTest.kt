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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Pruebas de interfaz (Robolectric). Sin servidor configurado: verifican el arranque, la navegación
 * y la ruta local del cerebro; las funciones remotas deben informar del motivo, no inventar.
 *
 * La pantalla principal no queda nunca "quieta" a propósito (globo animado, reloj, estado que se
 * actualiza): con el reloj de Compose en avance automático la prueba esperaría para siempre. Por eso
 * se pausa y se avanza a mano tras cada acción (patrón recomendado para animaciones infinitas).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w900dp-h600dp-land")
class HomeUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Before fun pauseClock() {
        rule.mainClock.autoAdvance = false
        settle()
    }

    /** Avanza el reloj de Compose (animaciones, transiciones, efectos) y procesa lo pendiente. */
    private fun settle(ms: Long = 800) {
        rule.mainClock.advanceTimeBy(ms)
        rule.waitForIdle()
    }

    /** Espera una condición avanzando el reloj entre comprobaciones (trabajo en segundo plano real). */
    private fun waitFor(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        rule.waitUntil(timeoutMs) {
            settle(50)
            condition()
        }
    }

    private fun exists(text: String, substring: Boolean = false) =
        rule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()

    /** La bienvenida aparece cuando se leen los ajustes del disco (asíncrono). */
    private fun dismissWelcome() {
        val shown = runCatching { waitFor(3_000) { exists("Más tarde") } }.isSuccess
        if (shown) {
            rule.onNodeWithText("Más tarde").performClick()
            settle()
        }
    }

    private fun tap(description: String) {
        rule.onNodeWithContentDescription(description).performClick()
        settle(1_000) // transición de navegación
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
        tap("Chat")
        rule.onNodeWithTag("chat_input").performTextInput("¿Qué hora es?")
        settle()
        rule.onNodeWithTag("chat_send").performClick()
        waitFor { exists("Son las", substring = true) }
    }

    @Test fun businessSearchDoesNotOpenMapAndReportsMissingServer() {
        dismissWelcome()
        tap("Chat")
        rule.onNodeWithTag("chat_input").performTextInput("Busca supermercados en San José, Costa Rica")
        settle()
        rule.onNodeWithTag("chat_send").performClick()
        waitFor { exists("servidor", substring = true) }
        assertEquals(0, rule.onAllNodesWithTag("map").fetchSemanticsNodes().size)
    }

    @Test fun mapOpensOnlyWhenRequested() {
        dismissWelcome()
        assertEquals(0, rule.onAllNodesWithTag("map").fetchSemanticsNodes().size)
        tap("Mapa")
        rule.onNodeWithTag("map").assertIsDisplayed()
    }

    @Test fun settingsScreenOpens() {
        dismissWelcome()
        tap("Ajustes")
        rule.onNodeWithText("SERVIDOR DE JARVIS").assertIsDisplayed()
    }
}
