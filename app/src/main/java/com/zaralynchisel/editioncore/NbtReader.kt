package com.zaralynchisel.editioncore

import com.zaralynchisel.utils.Logger
import java.io.DataInputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * Lightweight Minecraft NBT (Named Binary Tag) reader.
 * Supports the subset of NBT needed for Anvil region file parsing.
 *
 * NBT specification: https://wiki.vg/NBT
 */
class NbtReader(inputStream: InputStream) {

    private val dis: DataInputStream

    /**
     * NBT tag types.
     */
    enum class TagType(val id: Byte) {
        TAG_END(0),
        TAG_BYTE(1),
        TAG_SHORT(2),
        TAG_INT(3),
        TAG_LONG(4),
        TAG_FLOAT(5),
        TAG_DOUBLE(6),
        TAG_BYTE_ARRAY(7),
        TAG_STRING(8),
        TAG_LIST(9),
        TAG_COMPOUND(10),
        TAG_INT_ARRAY(11),
        TAG_LONG_ARRAY(12);

        companion object {
            private val map = entries.associateBy { it.id }
            fun fromId(id: Byte): TagType = map[id] ?: throw IllegalArgumentException("Unknown NBT tag type: $id")
        }
    }

    /**
     * Represents a parsed NBT tag.
     */
    sealed class NbtTag {
        data class NbtByte(val value: Byte) : NbtTag()
        data class NbtShort(val value: Short) : NbtTag()
        data class NbtInt(val value: Int) : NbtTag()
        data class NbtLong(val value: Long) : NbtTag()
        data class NbtFloat(val value: Float) : NbtTag()
        data class NbtDouble(val value: Double) : NbtTag()
        data class NbtString(val value: String) : NbtTag()
        data class NbtByteArray(val value: ByteArray) : NbtTag()
        data class NbtIntArray(val value: IntArray) : NbtTag()
        data class NbtLongArray(val value: LongArray) : NbtTag()
        data class NbtList(val value: List<NbtTag>, val elementType: TagType) : NbtTag()
        data class NbtCompound(val value: Map<String, NbtTag>) : NbtTag()
        data object NbtEnd : NbtTag()
    }

    init {
        // Detect compression: GZip or Zlib (deflate)
        val magicBytes = ByteArray(2)
        inputStream.mark(2)
        inputStream.read(magicBytes)
        inputStream.reset()

        dis = when {
            magicBytes[0] == 0x1F.toByte() && magicBytes[1] == 0x8B.toByte() -> {
                DataInputStream(GZIPInputStream(inputStream))
            }
            magicBytes[0] == 0x78.toByte() && (magicBytes[1].toInt() and 0xF0) == 0x80 -> {
                DataInputStream(InflaterInputStream(inputStream))
            }
            else -> {
                DataInputStream(inputStream)
            }
        }
    }

    /**
     * Read the root compound tag.
     */
    fun readRoot(): Pair<String, NbtTag.NbtCompound> {
        val type = TagType.fromId(dis.readByte())
        if (type != TagType.TAG_COMPOUND) {
            throw IllegalStateException("Root NBT tag must be COMPOUND, got $type")
        }
        val name = readString()
        val compound = readCompound()
        return Pair(name, compound)
    }

    /**
     * Read a named tag (type + name + payload).
     */
    private fun readNamedTag(): Pair<String, NbtTag> {
        val type = TagType.fromId(dis.readByte())
        if (type == TagType.TAG_END) {
            return Pair("", NbtTag.NbtEnd)
        }
        val name = readString()
        val tag = readTagPayload(type)
        return Pair(name, tag)
    }

    /**
     * Read tag payload by type.
     */
    private fun readTagPayload(type: TagType): NbtTag {
        return when (type) {
            TagType.TAG_BYTE -> NbtTag.NbtByte(dis.readByte())
            TagType.TAG_SHORT -> NbtTag.NbtShort(dis.readShort())
            TagType.TAG_INT -> NbtTag.NbtInt(dis.readInt())
            TagType.TAG_LONG -> NbtTag.NbtLong(dis.readLong())
            TagType.TAG_FLOAT -> NbtTag.NbtFloat(dis.readFloat())
            TagType.TAG_DOUBLE -> NbtTag.NbtDouble(dis.readDouble())
            TagType.TAG_BYTE_ARRAY -> {
                val len = dis.readInt()
                val arr = ByteArray(len)
                dis.readFully(arr)
                NbtTag.NbtByteArray(arr)
            }
            TagType.TAG_STRING -> NbtTag.NbtString(readString())
            TagType.TAG_LIST -> readList()
            TagType.TAG_COMPOUND -> readCompound()
            TagType.TAG_INT_ARRAY -> {
                val len = dis.readInt()
                val arr = IntArray(len) { dis.readInt() }
                NbtTag.NbtIntArray(arr)
            }
            TagType.TAG_LONG_ARRAY -> {
                val len = dis.readInt()
                val arr = LongArray(len) { dis.readLong() }
                NbtTag.NbtLongArray(arr)
            }
            TagType.TAG_END -> NbtTag.NbtEnd
        }
    }

    /**
     * Read a compound tag (sequence of named tags until TAG_END).
     */
    private fun readCompound(): NbtTag.NbtCompound {
        val map = mutableMapOf<String, NbtTag>()
        while (true) {
            val (name, tag) = readNamedTag()
            if (tag is NbtTag.NbtEnd) break
            map[name] = tag
        }
        return NbtTag.NbtCompound(map)
    }

    /**
     * Read a list tag.
     */
    private fun readList(): NbtTag.NbtList {
        val elementType = TagType.fromId(dis.readByte())
        val length = dis.readInt()
        val elements = mutableListOf<NbtTag>()
        for (i in 0 until length) {
            elements.add(readTagPayload(elementType))
        }
        return NbtTag.NbtList(elements, elementType)
    }

    /**
     * Read a UTF-8 string (prefixed with unsigned short length).
     */
    private fun readString(): String {
        val length = dis.readUnsignedShort()
        val bytes = ByteArray(length)
        dis.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    fun close() {
        try {
            dis.close()
        } catch (_: Exception) { }
    }

    /**
     * Convenience: get an int value from a compound by key path.
     */
    fun NbtTag.NbtCompound.getInt(path: String, default: Int = 0): Int {
        val tag = resolvePath(path) ?: return default
        return (tag as? NbtTag.NbtInt)?.value ?: default
    }

    fun NbtTag.NbtCompound.getLong(path: String, default: Long = 0L): Long {
        val tag = resolvePath(path) ?: return default
        return (tag as? NbtTag.NbtLong)?.value ?: default
    }

    fun NbtTag.NbtCompound.getString(path: String, default: String = ""): String {
        val tag = resolvePath(path) ?: return default
        return (tag as? NbtTag.NbtString)?.value ?: default
    }

    fun NbtTag.NbtCompound.getCompound(path: String): NbtTag.NbtCompound? {
        val tag = resolvePath(path) ?: return null
        return tag as? NbtTag.NbtCompound
    }

    fun NbtTag.NbtCompound.getList(path: String): NbtTag.NbtList? {
        val tag = resolvePath(path) ?: return null
        return tag as? NbtTag.NbtList
    }

    private fun NbtTag.NbtCompound.resolvePath(path: String): NbtTag? {
        val parts = path.split("/")
        var current: NbtTag = this
        for (part in parts) {
            current = when (current) {
                is NbtTag.NbtCompound -> current.value[part] ?: return null
                else -> return null
            }
        }
        return current
    }
}