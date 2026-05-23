package com.zaralynchisel.fileaccess

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.zaralynchisel.utils.Logger
import java.io.InputStream

/**
 * Access files under a SAF document tree URI using ContentResolver.
 * Provides fallback to direct File access if SAF URI is not available.
 */
class SafFileAccess(private val context: Context) {

    private var treeUri: Uri? = null
    private var treeDocId: String? = null

    /**
     * Set the SAF tree URI obtained from OpenDocumentTree.
     */
    fun setTreeUri(uri: Uri) {
        treeUri = uri
        treeDocId = try {
            DocumentsContract.getTreeDocumentId(uri)
        } catch (e: Exception) {
            Logger.e("Failed to get tree document ID", e)
            null
        }
        Logger.d("SafFileAccess setTreeUri: docId=$treeDocId")
    }

    /**
     * Open an InputStream for a file relative to the tree root.
     * Falls back to direct File access if no SAF URI.
     */
    fun openInputStream(relativePath: String, directFile: java.io.File? = null): InputStream? {
        // Try SAF first
        treeUri?.let { tree ->
            try {
                val childUri = buildChildUri(tree, treeDocId ?: "", relativePath)
                context.contentResolver.openInputStream(childUri)?.let { stream ->
                    Logger.d("SAF openInputStream OK: $relativePath")
                    return stream
                }
            } catch (e: Exception) {
                Logger.d("SAF openInputStream failed for $relativePath: ${e.message}")
            }
        }

        // Fallback to direct file access
        if (directFile != null && directFile.exists() && directFile.canRead()) {
            try {
                Logger.d("Direct file access: $directFile")
                return java.io.FileInputStream(directFile)
            } catch (e: Exception) {
                Logger.e("Direct file access failed: $directFile", e)
            }
        }

        Logger.w("Cannot open: $relativePath")
        return null
    }

    /**
     * Check if a file exists under the tree.
     */
    fun exists(relativePath: String, directFile: java.io.File? = null): Boolean {
        treeUri?.let { tree ->
            try {
                val childUri = buildChildUri(tree, treeDocId ?: "", relativePath)
                context.contentResolver.openInputStream(childUri)?.use { return true }
            } catch (_: Exception) { }
        }
        return directFile?.exists() == true
    }

    /**
     * List child entries in a directory under the tree.
     * Returns pairs of (relativePath, displayName).
     */
    fun listChildren(relativePath: String, directDir: java.io.File? = null): List<Pair<String, String>> {
        treeUri?.let { tree ->
            try {
                val dirUri = buildChildUri(tree, treeDocId ?: "", relativePath)
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    tree, DocumentsContract.getDocumentId(dirUri)
                )
                val cursor = context.contentResolver.query(
                    childrenUri,
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null, null, null
                )
                cursor?.use {
                    val results = mutableListOf<Pair<String, String>>()
                    while (it.moveToNext()) {
                        val docId = it.getString(0)
                        val name = it.getString(1)
                        val relPath = if (relativePath.isEmpty()) name else "$relativePath/$name"
                        results.add(relPath to name)
                    }
                    return results
                }
            } catch (e: Exception) {
                Logger.d("SAF listChildren failed for $relativePath: ${e.message}")
            }
        }

        // Fallback to direct directory listing
        if (directDir != null && directDir.exists() && directDir.isDirectory) {
            return directDir.listFiles()?.map { f ->
                f.name to f.name
            } ?: emptyList()
        }
        return emptyList()
    }

    val isAvailable: Boolean get() = treeUri != null

    private fun buildChildUri(tree: Uri, treeDocId: String, relativePath: String): Uri {
        return if (relativePath.isEmpty()) {
            DocumentsContract.buildDocumentUriUsingTree(tree, treeDocId)
        } else {
            // Build path segments
            val parentSegments = relativePath.split("/").dropLast(1)
            val childName = relativePath.split("/").last()

            if (parentSegments.isEmpty()) {
                DocumentsContract.buildDocumentUriUsingTree(
                    tree,
                    "$treeDocId/document/$childName"
                )
            } else {
                val parentDocPath = parentSegments.joinToString("/") { "$it/document" }
                DocumentsContract.buildDocumentUriUsingTree(
                    tree,
                    "$treeDocId/document/$parentDocPath/$childName"
                )
            }
        }
    }
}