package com.zaralynchisel.editioncore

import com.zaralynchisel.utils.Logger
import java.util.LinkedList

/**
 * Manages undo/redo history for all editing operations.
 * Supports smart merging of consecutive same-type operations.
 */
class HistoryManager(private val maxEntries: Int = 50) {

    private val undoStack = LinkedList<HistoryEntry>()
    private val redoStack = LinkedList<HistoryEntry>()
    private var nextId: Long = 0

    /**
     * Current state of the history manager.
     */
    data class HistoryState(
        val canUndo: Boolean,
        val canRedo: Boolean,
        val undoDescription: String?,
        val redoDescription: String?,
        val entries: List<HistoryEntry>
    )

    /**
     * Record a new operation.
     * @param mergeWithPrevious If true, merges with the last entry if same operation type.
     */
    fun record(
        operationType: BatchOperationType,
        description: String,
        affectedChunks: List<ChunkPos>,
        undoData: ByteArray? = null,
        mergeWithPrevious: Boolean = false
    ): HistoryEntry {
        // Smart merge: consecutive same-type operations
        if (mergeWithPrevious && undoStack.isNotEmpty()) {
            val last = undoStack.last
            if (last.operationType == operationType) {
                // Merge: update the existing entry
                val merged = last.copy(
                    description = "$description (merged)",
                    affectedChunks = (last.affectedChunks + affectedChunks).distinct()
                )
                undoStack.removeLast()
                undoStack.add(merged)
                redoStack.clear()
                Logger.d("Merged history entry: $description")
                return merged
            }
        }

        val entry = HistoryEntry(
            id = nextId++,
            operationType = operationType,
            description = description,
            timestamp = System.currentTimeMillis(),
            affectedChunks = affectedChunks,
            undoData = undoData
        )

        undoStack.add(entry)
        redoStack.clear()

        // Enforce limit
        while (undoStack.size > maxEntries) {
            undoStack.removeFirst()
        }

        Logger.d("Recorded history: $description")
        return entry
    }

    /**
     * Undo the last operation.
     * @return The entry that was undone, or null if nothing to undo.
     */
    fun undo(): HistoryEntry? {
        val entry = undoStack.pollLast() ?: return null
        redoStack.add(entry)
        Logger.d("Undo: ${entry.description}")
        return entry
    }

    /**
     * Redo the last undone operation.
     * @return The entry that was redone, or null if nothing to redo.
     */
    fun redo(): HistoryEntry? {
        val entry = redoStack.pollLast() ?: return null
        undoStack.add(entry)
        Logger.d("Redo: ${entry.description}")
        return entry
    }

    /**
     * Get the current history state.
     */
    fun getState(): HistoryState {
        return HistoryState(
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty(),
            undoDescription = undoStack.lastOrNull()?.description,
            redoDescription = redoStack.lastOrNull()?.description,
            entries = undoStack.toList()
        )
    }

    /**
     * Clear all history.
     */
    fun clear() {
        undoStack.clear()
        redoStack.clear()
        Logger.d("History cleared")
    }

    /**
     * Change the maximum number of history entries.
     */
    fun setMaxEntries(max: Int) {
        // Trim if needed
        while (undoStack.size > max) {
            undoStack.removeFirst()
        }
    }
}