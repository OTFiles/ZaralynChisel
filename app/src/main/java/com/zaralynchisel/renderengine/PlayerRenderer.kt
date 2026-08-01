package com.zaralynchisel.renderengine

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.zaralynchisel.editioncore.DimensionType
import com.zaralynchisel.fileaccess.WorldSelector
import com.zaralynchisel.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * OpenGL ES 3.0 renderer for Player Mode.
 *
 * Renders the world as a 3D terrain surface mesh. For each chunk within render
 * distance it reads the per-column surface block (via [ChunkSurfaceReader], the same
 * corrected pipeline God Mode uses — per-long packed block_states + heightmap offset,
 * mirroring BlueMap's heightmap/`getBlockState` approach) and builds a coloured
 * heightmap mesh. Face normals are derived in the fragment shader for a shaded 3D look.
 */
class PlayerRenderer(
    private val worldPath: String = "",
    private val spawnX: Int = 0,
    private val spawnY: Int = 80,
    private val spawnZ: Int = 0,
    private val dimension: DimensionType = DimensionType.OVERWORLD,
    private val worldSelector: WorldSelector? = null
) : GLSurfaceView.Renderer {

    data class PlayerViewConfig(
        var cameraX: Float = 0f,
        var cameraY: Float = 80f,
        var cameraZ: Float = 0f,
        var yaw: Float = 0f,
        var pitch: Float = 0f,
        var renderDistance: Int = 6,
        var showChunkGrid: Boolean = false,
        var fov: Float = 70f
    )

    var viewConfig = PlayerViewConfig(
        cameraX = spawnX.toFloat() + 0.5f,
        cameraY = spawnY.toFloat() + 2f,
        cameraZ = spawnZ.toFloat() + 0.5f
    )

    /** CPU-side chunk heights kept alongside [meshes] for ground-collision
     *  queries (PlayerModeScreen asks for the surface height under the player). */
    private val surfaceHeights = java.util.concurrent.ConcurrentHashMap<Long, Array<IntArray>>()

    /** Surface height (absolute Y) of the column containing (x,z), or null when
     *  that chunk hasn't loaded (or has no surface). Thread-safe. */
    fun groundHeightAt(x: Float, z: Float): Float? {
        val cx = kotlin.math.floor(x / 16f).toInt()
        val cz = kotlin.math.floor(z / 16f).toInt()
        val heights = surfaceHeights[chunkKey(cx, cz)] ?: return null
        val colX = Math.floorMod(kotlin.math.floor(x).toInt(), 16)
        val colZ = Math.floorMod(kotlin.math.floor(z).toInt(), 16)
        val h = heights[colX][colZ]
        return if (h == Int.MIN_VALUE) null else h.toFloat()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private var shaderProgram = 0
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)

    /** CPU-side meshes awaiting GL upload (produced by background loader). */
    private val pendingUpload = java.util.concurrent.ConcurrentLinkedQueue<Pair<Long, ChunkMesh>>()
    /** Uploaded GL meshes keyed by chunk key (chunkX, chunkZ). */
    private val meshes = java.util.concurrent.ConcurrentHashMap<Long, IntArray>()
    private val loading = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    private var glReady = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            GLES30.glClearColor(0.53f, 0.81f, 0.98f, 1.0f) // sky blue
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glEnable(GLES30.GL_CULL_FACE)
            GLES30.glCullFace(GLES30.GL_BACK)
            shaderProgram = createProgram(TERRAIN_VERTEX_SHADER, TERRAIN_FRAGMENT_SHADER)
            glReady = shaderProgram != 0
            // Chunk loading is driven by onDrawFrame's ensureChunksAround() using the
            // current camera, so it always loads around the (spawn-updated) position.
        } catch (e: Exception) {
            Logger.e("PlayerRenderer.onSurfaceCreated failed", e)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.toFloat().coerceAtLeast(1f)
        val fovRad = Math.toRadians(viewConfig.fov.toDouble())
        val top = (1.0 / Math.tan(fovRad / 2.0)).toFloat()
        val bottom = -top
        val left = bottom * aspect
        val right = top * aspect
        val near = 0.1f
        val far = (viewConfig.renderDistance * 16 + 32).toFloat()
        android.opengl.Matrix.frustumM(projectionMatrix, 0, left, right, bottom, top, near, far)
    }

    override fun onDrawFrame(gl: GL10?) {
        try {
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            if (!glReady) return

            // Camera: look in the yaw/pitch direction (yaw=0 → -Z forward).
            val yawRad = Math.toRadians(viewConfig.yaw.toDouble())
            val pitchRad = Math.toRadians(viewConfig.pitch.toDouble())
            val fx = (-kotlin.math.sin(yawRad) * kotlin.math.cos(pitchRad)).toFloat()
            val fy = kotlin.math.sin(pitchRad).toFloat()
            val fz = (-kotlin.math.cos(yawRad) * kotlin.math.cos(pitchRad)).toFloat()
            android.opengl.Matrix.setLookAtM(
                viewMatrix, 0,
                viewConfig.cameraX, viewConfig.cameraY, viewConfig.cameraZ,
                viewConfig.cameraX + fx, viewConfig.cameraY + fy, viewConfig.cameraZ + fz,
                0f, 1f, 0f
            )
            android.opengl.Matrix.multiplyMM(vpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

            uploadPending()
            ensureChunksAround()

            GLES30.glUseProgram(shaderProgram)
            val vpLoc = GLES30.glGetUniformLocation(shaderProgram, "uVP")
            GLES30.glUniformMatrix4fv(vpLoc, 1, false, vpMatrix, 0)

            for ((key, handles) in meshes) {
                drawMesh(key, handles)
            }
        } catch (e: Exception) {
            Logger.e("PlayerRenderer.onDrawFrame failed", e)
        }
    }

    private fun drawMesh(key: Long, handles: IntArray) {
        val vao = handles[0]
        val vertexCount = handles[1]
        if (vertexCount == 0) return
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, vertexCount)
        GLES30.glBindVertexArray(0)
    }

    /** Drain CPU meshes → GL VAOs. Called on the GL thread. */
    private fun uploadPending() {
        while (true) {
            val entry = pendingUpload.poll() ?: break
            val (key, mesh) = entry
            // Replace an existing mesh for this chunk.
            meshes.remove(key)?.let { deleteHandles(it) }
            val vao = IntArray(1)
            val vbo = IntArray(1)
            GLES30.glGenVertexArrays(1, vao, 0)
            GLES30.glGenBuffers(1, vbo, 0)

            GLES30.glBindVertexArray(vao[0])

            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
            GLES30.glBufferData(
                GLES30.GL_ARRAY_BUFFER, mesh.vertexBuffer.capacity() * 4,
                mesh.vertexBuffer, GLES30.GL_STATIC_DRAW
            )

            // pos(3) + normal(3) + color(3)
            val stride = 9 * 4
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 3 * 4)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, stride, 6 * 4)
            GLES30.glEnableVertexAttribArray(2)

            GLES30.glBindVertexArray(0)
            meshes[key] = intArrayOf(vao[0], mesh.vertexCount, vbo[0])
        }
    }

    /** Enqueue loads for chunks within render distance; drop far chunks. */
    private fun ensureChunksAround() {
        val cx = Math.floorDiv(viewConfig.cameraX.toInt(), 16)
        val cz = Math.floorDiv(viewConfig.cameraZ.toInt(), 16)
        val rd = viewConfig.renderDistance
        val desired = mutableSetOf<Long>()
        for (dx in -rd..rd) {
            for (dz in -rd..rd) {
                if (dx * dx + dz * dz > (rd + 1) * (rd + 1)) continue
                val x = cx + dx
                val z = cz + dz
                val key = chunkKey(x, z)
                desired.add(key)
                if (!meshes.containsKey(key) && !loading.contains(key) && loading.size < 8) {
                    loading.add(key)
                    scope.launch { loadChunk(x, z, key) }
                }
            }
        }
        // Drop chunks no longer needed.
        val drop = meshes.keys.filter { it !in desired }
        for (key in drop) {
            meshes.remove(key)?.let { deleteHandles(it) }
            surfaceHeights.remove(key)
        }
    }

    private fun deleteHandles(handles: IntArray) {
        try {
            if (handles.size >= 3) {
                GLES30.glDeleteBuffers(1, intArrayOf(handles[2]), 0) // vbo
            }
            GLES30.glDeleteVertexArrays(1, intArrayOf(handles[0]), 0)
        } catch (_: Exception) { }
    }

    private suspend fun loadChunk(chunkX: Int, chunkZ: Int, key: Long) {
        try {
            val selector = worldSelector ?: return
            if (worldPath.isEmpty()) return
            val data = selector.loadChunkSurfaceAndHeight(worldPath, chunkX, chunkZ, dimension) ?: run {
                loading.remove(key)
                return
            }
            val mesh = buildTerrainMesh(chunkX, chunkZ, data)
            surfaceHeights[key] = data.heights
            pendingUpload.add(key to mesh)
        } catch (e: Exception) {
            Logger.e("PlayerRenderer: loadChunk ($chunkX,$chunkZ) failed", e)
        } finally {
            loading.remove(key)
        }
    }

    /**
     * Build a terrain mesh for one chunk: 16×16 cells → 2 triangles each, with
     * CCW winding (front faces point UP, so back-face culling keeps the surface
     * visible from above) and a real per-face normal computed on the CPU (the
     * old screen-space dFdx/dFdy normal produced distorted shading that looked
     * like curved, warped terrain).
     * Per vertex: position (world x, surface y, world z) + normal + linear RGB.
     */
    private fun buildTerrainMesh(
        chunkX: Int, chunkZ: Int,
        data: ChunkSurfaceReader.SurfaceData
    ): ChunkMesh {
        val colors = data.colors
        val heights = data.heights
        val baseX = chunkX * 16
        val baseZ = chunkZ * 16
        val verts = ArrayList<Float>(16 * 16 * 2 * 3 * 9)

        for (j in 0 until 16) {
            for (i in 0 until 16) {
                val h00 = heightAt(heights, i, j)
                val h10 = heightAt(heights, i + 1, j)
                val h01 = heightAt(heights, i, j + 1)
                val h11 = heightAt(heights, i + 1, j + 1)
                val x0 = (baseX + i).toFloat(); val x1 = (baseX + i + 1).toFloat()
                val z0 = (baseZ + j).toFloat(); val z1 = (baseZ + j + 1).toFloat()
                val c00 = colorAt(colors, i, j)
                val c10 = colorAt(colors, i + 1, j)
                val c01 = colorAt(colors, i, j + 1)
                val c11 = colorAt(colors, i + 1, j + 1)
                // Two triangles, each wound so its normal points up.
                emitTri(verts, x0, h00, z0, c00, x1, h10, z0, c10, x1, h11, z1, c11)
                emitTri(verts, x0, h00, z0, c00, x1, h11, z1, c11, x0, h01, z1, c01)
            }
        }
        return ChunkMesh(toFloatBuffer(verts), verts.size / 9)
    }

    private fun heightAt(heights: Array<IntArray>, i: Int, j: Int): Float {
        val h = heights[i.coerceAtMost(15)][j.coerceAtMost(15)]
        return if (h == Int.MIN_VALUE) 0f else h.toFloat()
    }

    private fun colorAt(colors: Array<IntArray>, i: Int, j: Int): FloatArray? {
        val cid = colors[i.coerceAtMost(15)][j.coerceAtMost(15)]
        return if (cid > 0) toRgb(MapColorPalette.getColor(cid)) else null
    }

    /** Push one triangle (3 vertices × pos+normal+color). Normal = normalised
     *  cross product; degenerate triangles fall back to straight up. */
    private fun emitTri(
        verts: MutableList<Float>,
        ax: Float, ay: Float, az: Float, ac: FloatArray?,
        bx: Float, by: Float, bz: Float, bc: FloatArray?,
        cx: Float, cy: Float, cz: Float, cc: FloatArray?
    ) {
        val ux = bx - ax; val uy = by - ay; val uz = bz - az
        val vx = cx - ax; val vy = cy - ay; val vz = cz - az
        var nx = uy * vz - uz * vy
        var ny = uz * vx - ux * vz
        var nz = ux * vy - uy * vx
        val len = kotlin.math.sqrt(nx * nx + ny * ny + nz * nz)
        if (len < 1e-6f) { nx = 0f; ny = 1f; nz = 0f }
        else { nx /= len; ny /= len; nz /= len }
        fun push(x: Float, y: Float, z: Float, c: FloatArray?) {
            verts.add(x); verts.add(y); verts.add(z)
            verts.add(nx); verts.add(ny); verts.add(nz)
            if (c != null) { verts.add(c[0]); verts.add(c[1]); verts.add(c[2]) }
            else { verts.add(0.08f); verts.add(0.08f); verts.add(0.12f) }
        }
        push(ax, ay, az, ac)
        push(bx, by, bz, bc)
        push(cx, cy, cz, cc)
    }

    private fun toRgb(argb: Int): FloatArray = floatArrayOf(
        ((argb shr 16) and 0xFF) / 255f,
        ((argb shr 8) and 0xFF) / 255f,
        (argb and 0xFF) / 255f
    )

    private fun toFloatBuffer(list: ArrayList<Float>): FloatBuffer {
        val bb = ByteBuffer.allocateDirect(list.size * 4).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        for (v in list) fb.put(v)
        fb.position(0)
        return fb
    }

    fun updateCamera(x: Float, y: Float, z: Float, yaw: Float, pitch: Float) {
        viewConfig.cameraX = x
        viewConfig.cameraY = y
        viewConfig.cameraZ = z
        viewConfig.yaw = yaw
        viewConfig.pitch = pitch
    }

    fun cleanup() {
        scope.cancel()
        for ((_, handles) in meshes) deleteHandles(handles)
        meshes.clear()
        surfaceHeights.clear()
        if (shaderProgram != 0) GLES30.glDeleteProgram(shaderProgram)
    }

    private fun chunkKey(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)

    // ── Shader helpers ──────────────────────────────────────────────

    private fun createProgram(vsh: String, fsh: String): Int {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vsh)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fsh)
        if (vs == 0 || fs == 0) return 0
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        val linkStatus = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, linkStatus, 0)
        if (linkStatus[0] != GLES30.GL_TRUE) {
            Logger.e("PlayerRenderer: link failed: ${GLES30.glGetProgramInfoLog(prog)}")
            GLES30.glDeleteProgram(prog)
            return 0
        }
        return prog
    }

    private fun compile(type: Int, src: String): Int {
        val sh = GLES30.glCreateShader(type)
        GLES30.glShaderSource(sh, src)
        GLES30.glCompileShader(sh)
        val status = IntArray(1)
        GLES30.glGetShaderiv(sh, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES30.GL_TRUE) {
            Logger.e("PlayerRenderer: shader compile failed: ${GLES30.glGetShaderInfoLog(sh)}")
            GLES30.glDeleteShader(sh)
            return 0
        }
        return sh
    }

    data class ChunkMesh(
        val vertexBuffer: FloatBuffer,
        val vertexCount: Int
    )

    companion object {
        // Terrain shader: positions are already in world space (model = identity).
        // Per-face normals come from the CPU mesh (flat shading); light is a fixed
        // world-space directional light.
        private const val TERRAIN_VERTEX_SHADER = """
            #version 300 es
            layout(location = 0) in vec3 aPosition;
            layout(location = 1) in vec3 aNormal;
            layout(location = 2) in vec3 aColor;
            uniform mat4 uVP;
            out vec3 vColor;
            out vec3 vNormal;
            void main() {
                gl_Position = uVP * vec4(aPosition, 1.0);
                vColor = aColor;
                vNormal = aNormal;
            }
        """

        private const val TERRAIN_FRAGMENT_SHADER = """
            #version 300 es
            precision mediump float;
            in vec3 vColor;
            in vec3 vNormal;
            out vec4 fragColor;
            void main() {
                vec3 normal = normalize(vNormal);
                vec3 lightDir = normalize(vec3(0.35, 1.0, 0.25));
                float diff = max(dot(normal, lightDir), 0.0);
                float light = 0.45 + 0.55 * diff;
                fragColor = vec4(vColor * light, 1.0);
            }
        """
    }
}
