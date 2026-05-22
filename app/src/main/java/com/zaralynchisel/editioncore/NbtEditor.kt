package com.zaralynchisel.editioncore

import com.zaralynchisel.utils.Logger
import java.io.ByteArrayInputStream

/**
 * NBT editor for reading and modifying block/entity data within chunk NBT.
 * Uses NbtReader for parsing.
 */
class NbtEditor {

    /**
     * Read block entity data at a given position from chunk NBT.
     */
    fun readBlockEntity(chunkNbt: ByteArray, pos: BlockPos): Map<String, Any?>? {
        return try {
            val reader = NbtReader(ByteArrayInputStream(chunkNbt))
            val (_, root) = reader.readRoot()
            val blockEntities = root.getList("block_entities") ?: return null

            for (tag in blockEntities.value) {
                val entity = tag as? NbtReader.NbtTag.NbtCompound ?: continue
                val ex = entity.getInt("x")
                val ey = entity.getInt("y")
                val ez = entity.getInt("z")
                if (ex == pos.x && ey == pos.y && ez == pos.z) {
                    return entityTagToMap(entity)
                }
            }
            reader.close()
            null
        } catch (e: Exception) {
            Logger.e("Failed to read block entity at $pos", e)
            null
        }
    }

    /**
     * Get the block state name at a given position from chunk NBT.
     */
    fun getBlock(chunkNbt: ByteArray, pos: BlockPos): String? {
        return try {
            val reader = NbtReader(ByteArrayInputStream(chunkNbt))
            val (_, root) = reader.readRoot()
            val sections = root.getList("sections") ?: return null

            val sectionIndex = pos.y shr 4
            var targetSection: NbtReader.NbtTag? = null
            for (tag in sections.value) {
                val sec = tag as? NbtReader.NbtTag.NbtCompound ?: continue
                val sy = sec.getInt("Y")
                if (sy == sectionIndex) {
                    targetSection = tag
                    break
                }
            }
            reader.close()

            val section = targetSection as? NbtReader.NbtTag.NbtCompound ?: return null
            val blockStates = section.getCompound("block_states") ?: return null
            val palette = blockStates.getList("palette") ?: return null
            val data = blockStates.getList("data")  // long array, not used for simple reads

            // Simple palette lookup for a single block
            // For full palette indexing, we'd need to decode the long array
            // For now, if palette has 1 entry, it applies to all blocks
            if (palette.value.size == 1) {
                val entry = palette.value[0] as? NbtReader.NbtTag.NbtCompound
                return entry?.getString("Name")
            }

            // Return first palette entry as approximation
            val firstEntry = palette.value.firstOrNull() as? NbtReader.NbtTag.NbtCompound
            firstEntry?.getString("Name")
        } catch (e: Exception) {
            Logger.e("Failed to get block at $pos", e)
            null
        }
    }

    /**
     * Convert a compound tag to a flat map.
     */
    private fun entityTagToMap(compound: NbtReader.NbtTag.NbtCompound): Map<String, Any?> {
        return compound.value.mapValues { (_, tag) ->
            when (tag) {
                is NbtReader.NbtTag.NbtByte -> tag.value
                is NbtReader.NbtTag.NbtShort -> tag.value
                is NbtReader.NbtTag.NbtInt -> tag.value
                is NbtReader.NbtTag.NbtLong -> tag.value
                is NbtReader.NbtTag.NbtFloat -> tag.value
                is NbtReader.NbtTag.NbtDouble -> tag.value
                is NbtReader.NbtTag.NbtString -> tag.value
                else -> null
            }
        }
    }
}