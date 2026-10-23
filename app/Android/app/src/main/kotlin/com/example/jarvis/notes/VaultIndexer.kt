package com.example.jarvis.notes

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.jarvis.JarvisApplication
import com.example.jarvis.core.notes.MarkdownChunker
import com.example.jarvis.core.notes.NotePrivacy
import com.example.jarvis.data.db.JarvisDatabase
import com.example.jarvis.data.repo.RoomNoteIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class IndexReport(val scanned: Int, val indexed: Int, val unchanged: Int, val skipped: Int, val errors: Int)

/**
 * Indexa una bóveda de Obsidian elegida por el usuario mediante el selector de carpetas de
 * Android (Storage Access Framework). Solo lectura; incremental por fecha de modificación.
 * La bóveda de un PC no es accesible desde el teléfono salvo que esté sincronizada en él
 * (Obsidian Sync, Syncthing, carpeta compartida…).
 */
class VaultIndexer(
    private val context: Context,
    private val db: JarvisDatabase,
    private val index: RoomNoteIndex,
    private val privacy: NotePrivacy = NotePrivacy(),
) {
    fun persistAccess(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun releaseAccess(uri: Uri) {
        runCatching { context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    fun hasAccess(uri: Uri): Boolean = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    suspend fun index(treeUri: Uri, onProgress: (Int) -> Unit = {}): IndexReport = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: error("No se pudo abrir la carpeta de la bóveda.")
        if (!root.canRead()) error("Sin permiso de lectura sobre la bóveda. Vuelve a vincularla.")
        val stamps = db.notes().stamps().associate { it.notePath to it.modifiedAt }
        val seen = mutableSetOf<String>()
        var scanned = 0; var indexed = 0; var unchanged = 0; var skipped = 0; var errors = 0

        suspend fun walk(dir: DocumentFile, prefix: String) {
            for (f in dir.listFiles()) {
                coroutineContext.ensureActive()
                val name = f.name ?: continue
                if (name.startsWith(".")) continue // .obsidian, .trash, ocultos
                val path = if (prefix.isEmpty()) name else "$prefix/$name"
                if (f.isDirectory) {
                    walk(f, path)
                } else if (name.endsWith(".md", ignoreCase = true)) {
                    scanned++
                    if (scanned > MAX_FILES) { skipped++; continue }
                    seen += path
                    val modified = f.lastModified()
                    if (stamps[path] == modified) { unchanged++; continue }
                    if (f.length() > MAX_BYTES) { skipped++; continue }
                    try {
                        val text = context.contentResolver.openInputStream(f.uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: continue
                        index.replaceNote(path, MarkdownChunker.chunk(path, text, modified, privacy))
                        indexed++
                    } catch (e: Exception) {
                        errors++
                    }
                    if (scanned % 25 == 0) onProgress(scanned)
                }
            }
        }
        walk(root, "")
        index.removeNotesNotIn(seen)
        onProgress(scanned)
        IndexReport(scanned, indexed, unchanged, skipped, errors)
    }

    companion object {
        const val MAX_FILES = 5000
        const val MAX_BYTES = 2_000_000L
        private const val WORK = "jarvis_vault_reindex"

        /** Reindexado periódico (cada 12 h, sin batería baja) si hay bóveda vinculada. */
        fun schedulePeriodic(context: Context) {
            val req = PeriodicWorkRequestBuilder<VaultIndexWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun cancelPeriodic(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK)
        }
    }
}

class VaultIndexWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as? JarvisApplication ?: return Result.failure()
        // Proceso recién creado por WorkManager: hay que esperar a leer los ajustes del disco.
        val uri = app.container.settings.awaitLoaded().vaultUri?.let(Uri::parse) ?: return Result.success()
        if (!app.container.vaultIndexer.hasAccess(uri)) return Result.failure()
        return runCatching { app.container.vaultIndexer.index(uri) }.fold({ Result.success() }, { Result.retry() })
    }
}
