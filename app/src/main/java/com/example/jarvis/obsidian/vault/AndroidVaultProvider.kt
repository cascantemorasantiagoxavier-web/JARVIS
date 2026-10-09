package com.example.jarvis.obsidian.vault

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.example.jarvis.obsidian.reader.VaultReader
import com.example.jarvis.obsidian.search.VaultSearch
import com.example.jarvis.obsidian.search.VaultSearcher
import com.example.jarvis.obsidian.search.VaultSnippet
import com.example.jarvis.obsidian.writer.VaultWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class AndroidVaultProvider(private val context: Context) : VaultProvider, VaultManager, VaultReader, VaultWriter, VaultSearch, VaultSearcher {

    private val prefs = context.getSharedPreferences("jarvis_obsidian", Context.MODE_PRIVATE)

    override val vaultPath: String?
        get() = prefs.getString("vault_uri", null)

    fun setVaultUri(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(uri, flags)
            prefs.edit().putString("vault_uri", uri.toString()).apply()
        } catch (e: Exception) {
            Log.e("AndroidVaultProvider", "Failed to take persistable URI permission", e)
        }
    }

    override suspend fun selectVault(): Boolean {
        return vaultPath != null
    }

    override suspend fun validateVault(): Boolean = withContext(Dispatchers.IO) {
        val uriStr = vaultPath ?: return@withContext false
        try {
            val uri = Uri.parse(uriStr)
            val persisted = context.contentResolver.persistedUriPermissions
            val hasPermission = persisted.any { it.uri == uri && it.isReadPermission && it.isWritePermission }
            if (!hasPermission) return@withContext false

            val docId = DocumentsContract.getTreeDocumentId(uri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(uri, docId)
            
            context.contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { true } ?: false
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun syncVault(): Boolean = validateVault()

    override suspend fun fileExists(path: String): Boolean = withContext(Dispatchers.IO) {
        val treeUriStr = vaultPath ?: return@withContext false
        val treeUri = Uri.parse(treeUriStr)
        findFileUriByPath(treeUri, path) != null
    }

    override suspend fun deleteFile(path: String): Boolean = withContext(Dispatchers.IO) {
        val treeUriStr = vaultPath ?: return@withContext false
        val treeUri = Uri.parse(treeUriStr)
        val fileUri = findFileUriByPath(treeUri, path) ?: return@withContext false
        try {
            DocumentsContract.deleteDocument(context.contentResolver, fileUri)
        } catch (e: Exception) {
            Log.e("AndroidVaultProvider", "Failed to delete file: $path", e)
            false
        }
    }

    override suspend fun readFile(path: String): String? = withContext(Dispatchers.IO) {
        try {
            val treeUri = Uri.parse(vaultPath ?: return@withContext null)
            val fileUri = findFileUriByPath(treeUri, path) ?: return@withContext null
            context.contentResolver.openInputStream(fileUri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).use { reader ->
                    reader.readText()
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun writeFile(path: String, content: String) = withContext(Dispatchers.IO) {
        val treeUri = Uri.parse(vaultPath ?: return@withContext)
        val fileUri = findFileUriByPath(treeUri, path) ?: createMarkdownFile(treeUri, path)
        if (fileUri != null) {
            try {
                context.contentResolver.openOutputStream(fileUri, "w")?.use { outputStream ->
                    outputStream.write(content.toByteArray())
                }
            } catch (e: Exception) {
                Log.e("AndroidVaultProvider", "Failed to write file: $path", e)
            }
        }
    }

    override suspend fun listFiles(): List<String> = withContext(Dispatchers.IO) {
        val fileList = mutableListOf<String>()
        val uriStr = vaultPath ?: return@withContext emptyList()
        try {
            val treeUri = Uri.parse(uriStr)
            val docId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
            
            context.contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE),
                null, null, null
            )?.use { cursor ->
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameCol)
                    val mime = cursor.getString(mimeCol)
                    if (mime != DocumentsContract.Document.MIME_TYPE_DIR && name.endsWith(".md", ignoreCase = true)) {
                        fileList.add(name)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("AndroidVaultProvider", "Failed to list files", e)
        }
        fileList
    }

    override suspend fun search(query: String): List<String> = searchNotes(query)

    override suspend fun readNote(path: String): String? = readFile(path)

    override suspend fun writeNote(path: String, content: String) = writeFile(path, content)

    override suspend fun searchNotes(query: String): List<String> = withContext(Dispatchers.IO) {
        val matches = mutableListOf<String>()
        val files = listFiles()
        for (file in files) {
            val content = readFile(file) ?: ""
            if (file.contains(query, ignoreCase = true) || content.contains(query, ignoreCase = true)) {
                matches.add(file)
            }
        }
        matches
    }

    override suspend fun searchFiles(query: String): List<String> = searchNotes(query)

    override suspend fun searchSnippets(query: String, maxResults: Int): List<VaultSnippet> = withContext(Dispatchers.IO) {
        val snippets = mutableListOf<VaultSnippet>()
        val files = listFiles()
        for (file in files) {
            val content = readFile(file) ?: continue
            val lines = content.lines()
            lines.forEachIndexed { index, line ->
                if (line.contains(query, ignoreCase = true)) {
                    snippets.add(VaultSnippet(filePath = file, lineContent = line, lineNumber = index + 1))
                    if (snippets.size >= maxResults) return@withContext snippets
                }
            }
        }
        snippets
    }

    private fun findFileUriByPath(treeUri: Uri, path: String): Uri? {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        context.contentResolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { cursor ->
            val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameCol)
                if (name.equals(path, ignoreCase = true)) {
                    val fileId = cursor.getString(idCol)
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, fileId)
                }
            }
        }
        return null
    }

    private fun createMarkdownFile(treeUri: Uri, path: String): Uri? {
        val docId = DocumentsContract.getTreeDocumentId(treeUri)
        val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
        return try {
            DocumentsContract.createDocument(
                context.contentResolver,
                parentDocUri,
                "text/markdown",
                path
            )
        } catch (e: Exception) {
            null
        }
    }
}
