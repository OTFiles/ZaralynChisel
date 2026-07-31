package com.zaralynchisel.fileaccess

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.zaralynchisel.utils.Logger
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream

/**
 * Access files under a SAF document tree URI using DocumentFile API.
 * Provides fallback to direct File access if SAF URI is not available.
 */
class SafFileAccess(private val context: Context) {

    private var treeUri: Uri? = null
    private var rootDoc: DocumentFile? = null

    fun setTreeUri(uri: Uri) {
        treeUri = uri
        rootDoc = try {
            DocumentFile.fromTreeUri(context, uri)
        } catch (e: Exception) {
            Logger.e("Failed to create DocumentFile from tree URI", e)
            null
        }
        Logger.d("SafFileAccess setTreeUri: uri=$uri, doc=${rootDoc?.name}")
    }

    /**
     * Find a child document by relative path from tree root.
     */
    private fun findChild(relativePath: String): DocumentFile? {
        val root = rootDoc ?: return null
        if (relativePath.isEmpty()) return root
        val segments = relativePath.split("/")
        var current = root
        for (seg in segments) {
            current = current.findFile(seg) ?: return null
        }
        return current
    }

    /**
     * Open an InputStream for a file relative to the tree root.
     */
    fun openInputStream(relativePath: String, directFile: File? = null): InputStream? {
        // Try SAF first
        try {
            val child = findChild(relativePath)
            if (child != null) {
                context.contentResolver.openInputStream(child.uri)?.let { stream ->
                    Logger.d("SAF openInputStream OK: $relativePath")
                    return BufferedInputStream(stream)
                }
            }
        } catch (e: Exception) {
            Logger.d("SAF openInputStream failed for $relativePath: ${e.message}")
        }

        // Fallback to direct file access
        if (directFile != null && directFile.exists() && directFile.canRead()) {
            try {
                Logger.d("Direct file access: $directFile")
                return BufferedInputStream(directFile.inputStream())
            } catch (e: Exception) {
                Logger.e("Direct file access failed: $directFile", e)
            }
        }

        Logger.w("Cannot open: $relativePath")
        return null
    }

    /**
     * Open a seekable ParcelFileDescriptor for a file under the tree (zero-copy
     * random access — avoids the temp-file copy that [openInputStream] requires).
     * Returns null when SAF is unavailable; callers can fall back to direct
     * RandomAccessFile on [directFile].  The caller owns the returned PFD and must
     * close it.
     */
    fun openParcelFileDescriptor(relativePath: String, directFile: File? = null): android.os.ParcelFileDescriptor? {
        try {
            val child = findChild(relativePath)
            if (child != null) {
                val pfd = context.contentResolver.openFileDescriptor(child.uri, "r")
                if (pfd != null) {
                    Logger.d("SAF openPFD OK: $relativePath")
                    return pfd
                }
            }
        } catch (e: Exception) {
            Logger.d("SAF openPFD failed for $relativePath: ${e.message}")
        }
        Logger.w("Cannot open pfd: $relativePath")
        return null
    }

    /**
     * Check if a file exists under the tree.
     */
    fun exists(relativePath: String, directFile: File? = null): Boolean {
        try {
            val child = findChild(relativePath)
            if (child != null && child.exists()) return true
        } catch (e: Exception) {
            Logger.d("SAF exists check failed for $relativePath: ${e.message}")
        }
        return directFile?.exists() == true
    }

    /**
     * List child entries in a directory under the tree.
     */
    fun listChildren(relativePath: String, directDir: File? = null): List<Pair<String, String>> {
        try {
            val dir = findChild(relativePath)
            if (dir != null && dir.isDirectory) {
                return dir.listFiles().mapNotNull { child ->
                    val name = child.name ?: return@mapNotNull null
                    val relPath = if (relativePath.isEmpty()) name else "$relativePath/$name"
                    relPath to name
                }
            }
        } catch (e: Exception) {
            Logger.d("SAF listChildren failed for $relativePath: ${e.message}")
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
}