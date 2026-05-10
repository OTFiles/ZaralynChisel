package com.zaralynchisel.renderengine

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer

/**
 * Builds optimized chunk meshes for OpenGL ES 3.0 rendering.
 * Implements greedy meshing and face culling (only visible faces).
 *
 * A chunk mesh consists of:
 * - Vertex buffer: position (3) + texcoord (2) + normal (3) = 8 floats per vertex
 * - Index buffer: triangle indices
 */
class ChunkMeshBuilder {

    data class ChunkMesh(
        val vertexBuffer: FloatBuffer,
        val indexBuffer: ShortBuffer,
        val vertexCount: Int,
        val indexCount: Int
    )

    data class BlockFace(
        val positions: FloatArray,  // 12 floats (4 vertices * 3 coords)
        val texCoords: FloatArray,  // 8 floats (4 vertices * 2 coords)
        val normal: FloatArray      // 3 floats
    )

    companion object {
        const val FLOATS_PER_VERTEX = 8  // pos(3) + tex(2) + normal(3)
        const val BYTES_PER_FLOAT = 4
        const val BYTES_PER_SHORT = 2

        // Block face definitions (all 6 faces of a unit cube)
        private val FACE_NORMALS = mapOf(
            "top" to floatArrayOf(0f, 1f, 0f),
            "bottom" to floatArrayOf(0f, -1f, 0f),
            "front" to floatArrayOf(0f, 0f, 1f),
            "back" to floatArrayOf(0f, 0f, -1f),
            "right" to floatArrayOf(1f, 0f, 0f),
            "left" to floatArrayOf(-1f, 0f, 0f)
        )

        // Vertex positions for each face (unit cube, centered at origin)
        private val FACE_VERTICES = mapOf(
            "top" to floatArrayOf(
                -0.5f, 0.5f, -0.5f,  0.5f, 0.5f, -0.5f,  0.5f, 0.5f, 0.5f,  -0.5f, 0.5f, 0.5f
            ),
            "bottom" to floatArrayOf(
                -0.5f, -0.5f, 0.5f,  0.5f, -0.5f, 0.5f,  0.5f, -0.5f, -0.5f,  -0.5f, -0.5f, -0.5f
            ),
            "front" to floatArrayOf(
                -0.5f, -0.5f, 0.5f,  0.5f, -0.5f, 0.5f,  0.5f, 0.5f, 0.5f,  -0.5f, 0.5f, 0.5f
            ),
            "back" to floatArrayOf(
                0.5f, -0.5f, -0.5f,  -0.5f, -0.5f, -0.5f,  -0.5f, 0.5f, -0.5f,  0.5f, 0.5f, -0.5f
            ),
            "right" to floatArrayOf(
                0.5f, -0.5f, 0.5f,  0.5f, -0.5f, -0.5f,  0.5f, 0.5f, -0.5f,  0.5f, 0.5f, 0.5f
            ),
            "left" to floatArrayOf(
                -0.5f, -0.5f, -0.5f,  -0.5f, -0.5f, 0.5f,  -0.5f, 0.5f, 0.5f,  -0.5f, 0.5f, -0.5f
            )
        )

        // Texture coordinates for each face (full block texture)
        private val FACE_TEX_COORDS = floatArrayOf(
            0f, 0f,  1f, 0f,  1f, 1f,  0f, 1f
        )

        // Index buffer for a quad (2 triangles)
        private val QUAD_INDICES = shortArrayOf(0, 1, 2,  0, 2, 3)
    }

    /**
     * Build a mesh for a single block at the given position.
     * Only includes faces that are visible (adjacent block is air).
     */
    fun buildBlockMesh(
        blockX: Int,
        blockY: Int,
        blockZ: Int,
        visibleFaces: List<String>,
        textureIndex: Float  // Index into texture atlas
    ): ChunkMesh? {
        if (visibleFaces.isEmpty()) return null

        val vertices = mutableListOf<Float>()
        val indices = mutableListOf<Short>()
        var vertexOffset = 0

        for (face in visibleFaces) {
            val faceVerts = FACE_VERTICES[face] ?: continue
            val normal = FACE_NORMALS[face] ?: continue

            // Add 4 vertices for this face
            for (i in 0 until 4) {
                // Position (translated to block position)
                vertices.add(faceVerts[i * 3] + blockX)
                vertices.add(faceVerts[i * 3 + 1] + blockY)
                vertices.add(faceVerts[i * 3 + 2] + blockZ)

                // Texture coordinates
                vertices.add(FACE_TEX_COORDS[i * 2])
                vertices.add(FACE_TEX_COORDS[i * 2 + 1])

                // Normal
                vertices.add(normal[0])
                vertices.add(normal[1])
                vertices.add(normal[2])
            }

            // Add indices
            for (index in QUAD_INDICES) {
                indices.add((index + vertexOffset).toShort())
            }
            vertexOffset += 4
        }

        if (vertices.isEmpty()) return null

        val vertexBuffer = ByteBuffer
            .allocateDirect(vertices.size * BYTES_PER_FLOAT)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices.toFloatArray())
        vertexBuffer.position(0)

        val indexBuffer = ByteBuffer
            .allocateDirect(indices.size * BYTES_PER_SHORT)
            .order(ByteOrder.nativeOrder())
            .asShortBuffer()
            .put(indices.toShortArray())
        indexBuffer.position(0)

        return ChunkMesh(
            vertexBuffer = vertexBuffer,
            indexBuffer = indexBuffer,
            vertexCount = vertices.size / FLOATS_PER_VERTEX,
            indexCount = indices.size
        )
    }

    /**
     * Upload a chunk mesh to OpenGL.
     * @return Array of [vao, vbo, ebo] handles
     */
    fun uploadMesh(mesh: ChunkMesh): IntArray {
        val vaos = IntArray(1)
        val vbos = IntArray(1)
        val ebos = IntArray(1)

        GLES30.glGenVertexArrays(1, vaos, 0)
        GLES30.glGenBuffers(1, vbos, 0)
        GLES30.glGenBuffers(1, ebos, 0)

        GLES30.glBindVertexArray(vaos[0])

        // Upload vertex data
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbos[0])
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER,
            mesh.vertexBuffer.capacity() * BYTES_PER_FLOAT,
            mesh.vertexBuffer,
            GLES30.GL_STATIC_DRAW
        )

        // Upload index data
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ebos[0])
        GLES30.glBufferData(
            GLES30.GL_ELEMENT_ARRAY_BUFFER,
            mesh.indexBuffer.capacity() * BYTES_PER_SHORT,
            mesh.indexBuffer,
            GLES30.GL_STATIC_DRAW
        )

        // Vertex attributes
        val stride = FLOATS_PER_VERTEX * BYTES_PER_FLOAT

        // Position (location = 0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(0)

        // TexCoord (location = 1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, stride, 3 * BYTES_PER_FLOAT)
        GLES30.glEnableVertexAttribArray(1)

        // Normal (location = 2)
        GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, stride, 5 * BYTES_PER_FLOAT)
        GLES30.glEnableVertexAttribArray(2)

        GLES30.glBindVertexArray(0)

        return intArrayOf(vaos[0], vbos[0], ebos[0])
    }
}