package com.zaralynchisel.fileaccess

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.zaralynchisel.utils.Logger
import java.io.BufferedInputStream
import java.io.InputStream

/**
 * Access files under a SAF document tree URI using ContentResolver.
 * Uses DocumentsContract to build URIs for child documents.
 */
class SafFileAccess(private val context: Context) {

    private var treeUri: Uri? = null
    private var treeDocId: String? = null

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
     * Build a URI for a child document under the tree.
     * relativePath is relative to tree root, e.g. "level.dat" or "region/r.0.0.mca".
     */
    fun buildChildUri(relativePath: String): Uri? {
        val tree = treeUri ?: return null
        val baseId = treeDocId ?: return null

        // For direct child: <treeDocId>/document/<name>
        // For nested: each path segment becomes /document/
        val segments = relativePath.split("/")
        val childDocId = buildString {
            append(baseId)
            for (seg in segments) {
                append("/document/")
                append(seg)
            }
        }
        return DocumentsContract.buildDocumentUriUsingTree(tree, childDocId)
    }

    /**
     * Open an InputStream for a file relative to the tree root.
     */
    fun openInputStream(relativePath: String, directFile: java.io.File? = null): InputStream? {
        // Try SAF first
        val childUri = buildChildUri(relativePath)
        if (childUri != null) {
            try {
                context.contentResolver.openInputStream(childUri)?.let { stream ->
                    Logger.d("SAF openInputStream OK: $relativePath")
                    return BufferedInputStream(stream)
                }
            } catch (e: Exception) {
                Logger.d("SAF openInputStream failed for $relativePath: ${e.message}")
            }
        }

        // Fallback to direct file access
        if (directFile != null && directFile.exists() && directFile.canRead()) {
            try {
                Logger.d("Direct file access: $directFile")
                return BufferedInputStream(java.io.FileInputStream(directFile))
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
                val childUri = buildChildUri(relativePath) ?: return@let null
                // Query to check existence
                context.contentResolver.query(
                    childUri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                    null, null, null
                )?.use { cursor ->
                    if (cursor.count > 0) return true
                }
            } catch (e: Exception) {
                Logger.d("SAF exists check failed for $relativePath: ${e.message}")
            }
        }
        return directFile?.exists() == true
    }

    /**
     * List child entries in a directory under the tree.
     */
    fun listChildren(relativePath: String, directDir: java.io.File? = null): List<Pair<String, String>> {
        treeUri?.let { tree ->
            try {
                val dirUri = buildChildUri(relativePath) ?: return@let null
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    tree, DocumentsContract.getDocumentId(dirUri)
                )
                val cursor = context.contentResolver.query(
                    childrenUri,
                    arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                    null, null, null
                )
                cursor?.use { c ->
                    val results = mutableListOf<Pair<String, String>>()
                    while (c.moveToNext()) {
                        val name = c.getString(1)
                        val relPath = if (relativePath.isEmpty()) name else "$relativePath/$name"
                        results.add(relPath to name)
                    }
                    return results
                }
            } catch (e: Exception) {
                Logger.d("SAF listChildren failed for $relativePath: ${e.message}")
            }
        }

        if (directDir != null && directDir.exists() && directDir.isDirectory) {
            return directDir.listFiles()?.map { f ->
                f.name to f.name
            } ?: emptyList()
        }
        return emptyList()
    }

    val isAvailable: Boolean get() = treeUri != null
}