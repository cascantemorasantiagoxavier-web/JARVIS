package com.example.jarvis.core.skills.impl

import com.example.jarvis.core.skills.SkillContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Datos estructurados que comparten varias habilidades, con su colección en el almacén local.
 * Una sola definición por concepto: p. ej. los movimientos financieros los escriben
 * expense_capture e invoice_quote_creator y los leen personal_finance, cashflow_forecast y
 * report_generator.
 */
object Collections {
    const val TRANSACTIONS = "finance.transactions"
    const val BUDGETS = "finance.budgets"
    const val RECURRING = "finance.recurring"
    const val QUOTES = "finance.quotes"
    const val INVENTORY = "inventory.items"
    const val MOVEMENTS = "inventory.movements"
    const val INTERACTIONS = "crm.interactions"
    const val FLASHCARDS = "learning.cards"
    const val ROUTINES = "automation.routines"
    const val INBOX = "inbox.items"
    const val DRAFTS = "communication.drafts"
    const val RENEWALS = "renewals.items"
    const val MEALS = "meals.plans"
    const val GROCERIES = "meals.groceries"
}

@Serializable
data class Transaction(
    val type: String, // income | expense
    val amount: String, // decimal exacto como texto
    val currency: String,
    val category: String,
    val description: String,
    val date: String, // AAAA-MM-DD
    val source: String = "manual", // manual | receipt | invoice
    val reference: String? = null,
)

@Serializable data class Budget(val category: String, val monthlyLimit: String, val currency: String)

@Serializable
data class RecurringItem(val name: String, val type: String, val amount: String, val currency: String, val dayOfMonth: Int, val category: String = "fijo")

@Serializable data class QuoteLine(val description: String, val quantity: String, val unitPrice: String)

@Serializable
data class Quote(
    val number: String,
    val kind: String, // quote | proforma
    val client: String,
    val clientEmail: String? = null,
    val lines: List<QuoteLine>,
    val taxRate: String,
    val currency: String,
    val date: String,
    val validUntil: String,
    val notes: String = "",
    val status: String = "draft", // draft | sent | accepted | rejected | paid
)

@Serializable
data class InventoryItem(
    val scope: String, // business | home
    val name: String,
    val quantity: Double = 0.0,
    val unit: String = "unidades",
    val minQuantity: Double? = null,
    val unitCost: String? = null,
    val currency: String? = null,
    val barcode: String? = null,
    val location: String? = null,
    val purchaseDate: String? = null,
    val warrantyUntil: String? = null,
    val serial: String? = null,
    val notes: String = "",
)

@Serializable data class StockMovement(val itemId: String, val delta: Double, val reason: String, val date: String, val resultingQuantity: Double)

@Serializable
data class Interaction(val prospectId: Long?, val contactName: String, val kind: String, val summary: String, val date: String, val followUp: String? = null, val followUpTaskId: Long? = null)

@Serializable
data class Flashcard(
    val deck: String,
    val front: String,
    val back: String,
    val ease: Double = 2.5,
    val intervalDays: Int = 0,
    val repetitions: Int = 0,
    val due: String, // AAAA-MM-DD
    val lapses: Int = 0,
)

@Serializable data class RoutineStep(val skill: String, val input: JsonObject)

@Serializable
data class Routine(val name: String, val triggers: List<String>, val steps: List<RoutineStep>, val authorized: Boolean, val createdAt: String, val lastRun: String? = null)

@Serializable
data class InboxItem(val text: String, val source: String, val createdAt: String, val status: String = "open", val suggestion: String? = null, val convertedTo: String? = null)

@Serializable
data class Draft(val channel: String, val recipient: String?, val subject: String?, val body: String, val createdAt: String, val status: String = "draft", val scheduledFor: String? = null, val platform: String? = null)

@Serializable
data class Renewal(val name: String, val kind: String, val expiresOn: String, val remindDaysBefore: Int, val taskId: Long?, val notes: String = "")

@Serializable data class MealDay(val day: String, val meals: List<String>)
@Serializable data class MealPlan(val week: String, val days: List<MealDay>, val people: Int)
@Serializable data class GroceryItem(val name: String, val quantity: String = "", val checked: Boolean = false, val forMeals: List<String> = emptyList())

/** Accesos tipados a las colecciones compartidas. */
fun SkillContext.transactions() = collection(Collections.TRANSACTIONS, Transaction.serializer())
fun SkillContext.budgets() = collection(Collections.BUDGETS, Budget.serializer())
fun SkillContext.recurring() = collection(Collections.RECURRING, RecurringItem.serializer())
fun SkillContext.quotes() = collection(Collections.QUOTES, Quote.serializer())
fun SkillContext.inventory() = collection(Collections.INVENTORY, InventoryItem.serializer())
fun SkillContext.movements() = collection(Collections.MOVEMENTS, StockMovement.serializer())
fun SkillContext.interactions() = collection(Collections.INTERACTIONS, Interaction.serializer())
fun SkillContext.flashcards() = collection(Collections.FLASHCARDS, Flashcard.serializer())
fun SkillContext.routines() = collection(Collections.ROUTINES, Routine.serializer())
fun SkillContext.inbox() = collection(Collections.INBOX, InboxItem.serializer())
fun SkillContext.drafts() = collection(Collections.DRAFTS, Draft.serializer())
fun SkillContext.renewals() = collection(Collections.RENEWALS, Renewal.serializer())
fun SkillContext.mealPlans() = collection(Collections.MEALS, MealPlan.serializer())
fun SkillContext.groceries() = collection(Collections.GROCERIES, GroceryItem.serializer())
