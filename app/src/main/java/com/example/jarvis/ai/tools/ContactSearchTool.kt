package com.example.jarvis.ai.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.example.jarvis.domain.tools.RiskLevel
import com.example.jarvis.domain.tools.Tool

class ContactSearchTool(
    private val contextProvider: () -> Context? = { null }
) : Tool {

    override val name = "contact_search"
    override val description = "Busca contactos en la agenda del teléfono por nombre o número"
    override val riskLevel = RiskLevel.READ

    override suspend fun execute(params: Map<String, Any>): String {
        val query = params["query"]?.toString() ?: params["name"]?.toString() ?: params["contact"]?.toString() ?: ""
        if (query.isBlank()) {
            return "Por favor, especifica el nombre del contacto que deseas buscar."
        }

        val ctx = contextProvider() ?: return "No hay contexto disponible para acceder a los contactos."

        val hasPermission = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) {
            return "Jefe, necesito permiso para consultar sus contactos y encontrar a '$query'. Por favor, concédalo en Ajustes."
        }

        try {
            val results = mutableListOf<String>()
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$query%")

            ctx.contentResolver.query(uri, projection, selection, selectionArgs, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (cursor.moveToNext()) {
                    val name = if (nameIdx >= 0) cursor.getString(nameIdx) ?: "" else ""
                    val number = if (numberIdx >= 0) cursor.getString(numberIdx) ?: "" else ""
                    if (name.isNotBlank()) {
                        results.add("• $name: $number")
                    }
                }
            }

            if (results.isEmpty()) {
                return "No encontré ningún contacto que coincida con '$query'."
            }

            return "Contactos encontrados para '$query':\n" + results.take(5).joinToString("\n")
        } catch (e: Exception) {
            return "Error al buscar en los contactos: ${e.message}"
        }
    }
}
