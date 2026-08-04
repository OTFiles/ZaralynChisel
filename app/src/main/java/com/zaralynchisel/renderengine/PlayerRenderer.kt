package com.zaralynchisel.renderengine

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import com.zaralynchisel.editioncore.DimensionType
import com.zaralynchisel.fileaccess.WorldSelector
import com.zaralynchisel.utils.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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

    /** CPU-side chunk data kept alongside [meshes]: heights for ground-collision
     *  (PlayerModeScreen asks for the surface height under the player) and the
     *  surface blocks + below-surface stacks for real face-exposure tests. */
    private val chunkDataCache = java.util.concurrent.ConcurrentHashMap<Long, ChunkSurfaceReader.SurfaceData>()

    /** Surface height (absolute Y) of the column containing (x,z), or null when
     *  that chunk hasn't loaded (or has no surface). Cross plants at the top are
     *  skipped so the player stands on the real ground. Thread-safe. */
    fun groundHeightAt(x: Float, z: Float): Float? {
        val cx = kotlin.math.floor(x / 16f).toInt()
        val cz = kotlin.math.floor(z / 16f).toInt()
        val data = chunkDataCache[chunkKey(cx, cz)] ?: return null
        val colX = Math.floorMod(kotlin.math.floor(x).toInt(), 16)
        val colZ = Math.floorMod(kotlin.math.floor(z).toInt(), 16)
        var y = data.heights[colX][colZ]
        if (y == Int.MIN_VALUE) return null
        // Walk down past plants and air until the first real ground block.
        while (true) {
            val top = data.heights[colX][colZ]
            val info = if (y == top) data.surfaceBlocks[colX][colZ]
                       else data.belowSurface[colX][colZ].getOrNull(top - y - 1)
            val id = info?.name?.substringAfter(':') ?: break
            if (!id.endsWith("air") && id !in CROSS_BLOCKS) return y.toFloat()
            y--
            if (y < top - 60) break
        }
        return y.toFloat()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    /** Decoded chunk data whose textures hadn't resolved when the renderer started;
     *  processed once a texture resolver is attached. */
    private val pendingData = java.util.concurrent.ConcurrentHashMap<Long, ChunkSurfaceReader.SurfaceData>()

    /** Number of textures that failed to load from every source (local, mods, network). */
    @Volatile
    var textureErrorCount = 0
        private set

    @Volatile
    private var nanLogged = false

    @Volatile
    private var textureResolver: com.zaralynchisel.fileaccess.TextureResolver? = null

    private val meshLogCount = java.util.concurrent.atomic.AtomicInteger()

    private val atlas = TextureAtlas()

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
            // Reversed-Z depth: far→0, near→1. Depth is tested with GL_GREATER
            // and cleared to 0. Near the far plane the depth value sits near 0,
            // where IEEE floats have the most mantissa density, so distant
            // geometry keeps far better depth separation than the classic
            // near→0 mapping (matters once the view distance grows).
            GLES30.glDepthRangef(1f, 0f)
            GLES30.glDepthFunc(GLES30.GL_GREATER)
            GLES30.glClearDepthf(0f)
            shaderProgram = createProgram(TERRAIN_VERTEX_SHADER, TERRAIN_FRAGMENT_SHADER)
            glReady = shaderProgram != 0
            atlas.uploadPending() // create + upload the missing-texture layer
            // Chunk loading is driven by onDrawFrame's ensureChunksAround() using the
            // current camera, so it always loads around the (spawn-updated) position.
        } catch (e: Exception) {
            Logger.e("PlayerRenderer.onSurfaceCreated failed", e)
        }
    }

    private var viewportWidth = 1
    private var viewportHeight = 1

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        recomputeProjection()
    }

    /** Rebuild the perspective matrix from the current fov/size (also called by updateFov). */
    private fun recomputeProjection() {
        GLES30.glViewport(0, 0, viewportWidth, viewportHeight)
        val aspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        val fovRad = Math.toRadians(viewConfig.fov.toDouble())
        val near = 0.05f
        val far = (viewConfig.renderDistance * 16 + 64).toFloat()
        // frustumM expects the NEAR-PLANE extents, not a unitless angle ratio:
        // half-height = near * tan(fov/2). Passing 1/tan(fov/2) directly made the
        // actual FOV ~176° — a fisheye stretch on every block.
        val top = (near * Math.tan(fovRad / 2.0)).toFloat()
        val bottom = -top
        val left = bottom * aspect
        val right = top * aspect
        android.opengl.Matrix.frustumM(projectionMatrix, 0, left, right, bottom, top, near, far)
    }

    override fun onDrawFrame(gl: GL10?) {
        try {
            // Reversed-Z depth state is GL state that must survive per frame.
            GLES30.glDepthRangef(1f, 0f)
            GLES30.glClearDepthf(0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            // Camera sanity: NaN coordinates would collapse the chunk set to
            // (0,0) and cause load/drop cycling — log it once if it happens.
            if (viewConfig.cameraX.isNaN() || viewConfig.cameraY.isNaN() || viewConfig.cameraZ.isNaN()) {
                if (!nanLogged) {
                    nanLogged = true
                    Logger.e("Camera coordinate is NaN: ${viewConfig.cameraX},${viewConfig.cameraY},${viewConfig.cameraZ}")
                }
            } else if (nanLogged) {
                nanLogged = false
            }
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
            atlas.bind()
            GLES30.glUniform1i(GLES30.glGetUniformLocation(shaderProgram, "uTex"), 0)

            for ((key, handles) in meshes) {
                drawMesh(key, handles, translucent = false)
            }
            // Translucent pass: alpha blend, no depth write, far chunks first so
            // nearer water/glass/plants blend correctly over farther ones.
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glDepthMask(false)
            val camX = viewConfig.cameraX
            val camZ = viewConfig.cameraZ
            val trans = meshes.entries
                .filter { it.value.size >= 6 && it.value[4] > 0 }
                .sortedByDescending { entry ->
                    val cx = (entry.key shr 32).toInt() * 16 + 8 - camX
                    val cz = (entry.key.toInt()) * 16 + 8 - camZ
                    cx * cx + cz * cz
                }
            for ((key, handles) in trans) {
                drawMesh(key, handles, translucent = true)
            }
            GLES30.glDepthMask(true)
            GLES30.glDisable(GLES30.GL_BLEND)
        } catch (e: Exception) {
            Logger.e("PlayerRenderer.onDrawFrame failed", e)
        }
    }

    private fun drawMesh(key: Long, handles: IntArray, translucent: Boolean) {
        val vao = if (translucent) handles[3] else handles[0]
        val vertexCount = if (translucent) handles[4] else handles[1]
        if (vertexCount == 0) return
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, vertexCount)
        GLES30.glBindVertexArray(0)
    }

    /** Drain CPU meshes → GL VAOs. Called on the GL thread. */
    private fun uploadPending() {
        atlas.uploadPending() // newly registered texture layers first
        while (true) {
            val entry = pendingUpload.poll() ?: break
            val (key, mesh) = entry
            // Replace an existing mesh for this chunk.
            meshes.remove(key)?.let { deleteHandles(it) }
            val vao = IntArray(1)
            val vbo = IntArray(1)
            GLES30.glGenVertexArrays(1, vao, 0)
            GLES30.glGenBuffers(1, vbo, 0)
            uploadBuffer(vbo[0], mesh.vertexBuffer)
            GLES30.glBindVertexArray(vao[0])
            setupAttribs()
            GLES30.glBindVertexArray(0)

            // Translucent geometry (if any) gets its own VAO/VBO.
            var tvao = 0
            var tvbo = 0
            var tcount = 0
            val tbuf = mesh.transBuffer
            if (tbuf != null && mesh.transCount > 0) {
                tvao = IntArray(1).also { GLES30.glGenVertexArrays(1, it, 0) }[0]
                tvbo = IntArray(1).also { GLES30.glGenBuffers(1, it, 0) }[0]
                uploadBuffer(tvbo, tbuf)
                GLES30.glBindVertexArray(tvao)
                setupAttribs()
                GLES30.glBindVertexArray(0)
                tcount = mesh.transCount
            }
            meshes[key] = intArrayOf(vao[0], mesh.vertexCount, vbo[0], tvao, tcount, tvbo)
        }
    }

    private fun uploadBuffer(vbo: Int, buf: FloatBuffer) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(
            GLES30.GL_ARRAY_BUFFER, buf.capacity() * 4,
            buf, GLES30.GL_STATIC_DRAW
        )
    }

    /** pos(3) + normal(3) + uv(2) + color(4 floats RGBA), stride 48. */
    private fun setupAttribs() {
        val stride = 48
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 3 * 4)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, stride, 6 * 4)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(3, 4, GLES30.GL_FLOAT, false, stride, 8 * 4)
        GLES30.glEnableVertexAttribArray(3)
    }

    /** Enqueue loads for chunks within render distance; drop far chunks. */
    private fun ensureChunksAround() {
        // floor(), not truncation: negative camera coords (e.g. -0.5) must map to
        // chunk -1, otherwise the player's own chunk never loads in negative regions.
        val cx = Math.floor(viewConfig.cameraX.toDouble() / 16.0).toInt()
        val cz = Math.floor(viewConfig.cameraZ.toDouble() / 16.0).toInt()
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
        if (drop.isNotEmpty()) {
            Logger.d("ensureChunksAround: dropping ${drop.size} chunks (cx=$cx,cz=$cz, cam=${viewConfig.cameraX},${viewConfig.cameraZ})")
        }
        for (key in drop) {
            meshes.remove(key)?.let { deleteHandles(it) }
            chunkDataCache.remove(key)
        }
    }

    private fun deleteHandles(handles: IntArray) {
        try {
            if (handles.size >= 6 && handles[5] != 0) {
                GLES30.glDeleteBuffers(1, intArrayOf(handles[5]), 0) // translucent vbo
            }
            if (handles.size >= 3) {
                GLES30.glDeleteBuffers(1, intArrayOf(handles[2]), 0) // vbo
            }
            GLES30.glDeleteVertexArrays(1, intArrayOf(handles[0]), 0)
            if (handles.size >= 4 && handles[3] != 0) {
                GLES30.glDeleteVertexArrays(1, intArrayOf(handles[3]), 0)
            }
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
            processChunkData(key, chunkX, chunkZ, data)
        } catch (e: Exception) {
            Logger.e("PlayerRenderer: loadChunk ($chunkX,$chunkZ) failed", e)
        } finally {
            loading.remove(key)
        }
    }

    /** Build (or rebuild) the mesh for a chunk and fetch any missing textures.
     *  The first mesh uses the placeholder layer so rendering never blocks on the
     *  network; once textures arrive the chunk is rebuilt with real tiles. */
    private suspend fun processChunkData(key: Long, chunkX: Int, chunkZ: Int, data: ChunkSurfaceReader.SurfaceData) {
        val resolver = textureResolver
        if (resolver == null) {
            // Resolver not initialised yet (screen setup race) — reprocess later.
            pendingData[key] = data
            return
        }
        val mesh = buildChunkMesh(chunkX, chunkZ, data)
        pendingUpload.add(key to mesh)
        chunkDataCache[key] = data

        val missing = mesh.paths.filter { !atlas.has(it) }
        if (missing.isEmpty()) return

        // Resolve all missing textures (parallel network/disk fetches).
        val resolved = kotlinx.coroutines.coroutineScope {
            missing.map { path ->
                async(kotlinx.coroutines.Dispatchers.IO) { path to resolver.resolveBlockTexture(path) }
            }.map { it.await() }
        }
        var added = false
        for ((path, bytes) in resolved) {
            if (bytes != null) {
                if (atlas.register(path, bytes)) added = true
            } else {
                textureErrorCount++
                Logger.e("Texture unavailable for '$path' — local assets, mods and network all failed")
            }
        }
        if (added) {
            // Rebuild with the real tiles in place of the placeholder layer.
            val mesh2 = buildChunkMesh(chunkX, chunkZ, data)
            pendingUpload.add(key to mesh2)
        }
    }

    /** Attach the texture resolver (once the screen has initialised it) and
     *  reprocess any chunks that were built without textures. */
    fun setTextureResolver(resolver: com.zaralynchisel.fileaccess.TextureResolver) {
        textureResolver = resolver
        resolver.modelSource()?.let { (dir, ver) -> ModelLoader.init(dir, ver) }
        val keys = pendingData.keys.toList()
        pendingData.clear()
        for (k in keys) {
            val data = pendingData[k] ?: continue
            val cx = (k shr 32).toInt()
            val cz = k.toInt()
            scope.launch { processChunkData(k, cx, cz, data) }
        }
    }

    /**
     * Build a 3D block mesh for one chunk. Each column renders the solid blocks
     * between min(4-neighbour surface) and the surface, but only faces that are
     * actually visible get geometry:
     *  - top face only on the surface block (air above it),
     *  - a side face only when the neighbour column's surface is lower than the
     *    block's top (otherwise the neighbour's ground covers it),
     *  - never bottom faces.
     * The block at the surface uses its own block name; blocks below come from the
     * pre-scanned stack (air gaps in caves are skipped; anything below the scanned
     * stack is approximated with stone). Column faces are textured via the atlas
     * (top texture on top faces, side texture on side faces).
     */
    private fun buildChunkMesh(
        chunkX: Int, chunkZ: Int,
        data: ChunkSurfaceReader.SurfaceData
    ): ChunkMesh {
        val heights = data.heights
        val verts = ArrayList<Float>(2048)
        val transVerts = ArrayList<Float>(256)
        val paths = LinkedHashSet<String>()
        val baseX = chunkX * 16
        val baseZ = chunkZ * 16
        val WHITE = floatArrayOf(1f, 1f, 1f, 1f, 1f)
        var grassTint: FloatArray = WHITE

        /** Is the column (colX, colZ) of chunk (nx, nz) solid at height y?
         *  Beyond the 40-block stack depth, or missing neighbor data, counts as
         *  AIR so exposed cliff walls keep rendering; cross-model plants are not
         *  solid (they're thin and must not occlude cubes behind them).
         *  [excludeWater] treats water as air — used when culling TOP faces so
         *  the riverbed under water stays visible. */
        fun solidAt(nx: Int, nz: Int, colX: Int, colZ: Int, y: Int, excludeWater: Boolean = false): Boolean {
            val d = if (nx == chunkX && nz == chunkZ) data else chunkDataCache[chunkKey(nx, nz)]
            if (d == null) {
                // Neighbour chunk not loaded: mirror our edge column so no fake
                // wall is invented along the chunk border.
                val eh = heights[colX.coerceIn(0, 15)][colZ.coerceIn(0, 15)]
                return eh != Int.MIN_VALUE && y <= eh
            }
            val top = d.heights[colX][colZ]
            if (top == Int.MIN_VALUE || y > top) return false
            val info = if (y == top) d.surfaceBlocks[colX][colZ]
                       else d.belowSurface[colX][colZ].getOrNull(top - y - 1)
            if (info == null) return false
            val id = info.name.substringAfter(':')
            if (id.endsWith("air")) return false
            if (id in CROSS_BLOCKS) return false
            if (excludeWater && id == "water") return false
            return true
        }

        fun neighbourSolid(x: Int, z: Int, y: Int, excludeWater: Boolean = false): Boolean = when {
            x in 0..15 && z in 0..15 -> solidAt(chunkX, chunkZ, x, z, y, excludeWater)
            x < 0 -> solidAt(chunkX - 1, chunkZ, x + 16, z, y, excludeWater)
            x > 15 -> solidAt(chunkX + 1, chunkZ, x - 16, z, y, excludeWater)
            z < 0 -> solidAt(chunkX, chunkZ - 1, x, z + 16, y, excludeWater)
            else -> solidAt(chunkX, chunkZ + 1, x, z - 16, y, excludeWater)
        }

        /** Block info at (x, z) of this chunk at height y; null = air (or beyond scan). */
        fun blockAt(x: Int, z: Int, y: Int, top: Int): ChunkSurfaceReader.BlockInfo? {
            if (y == top) return data.surfaceBlocks[x][z]
            val stack = data.belowSurface[x][z]
            return stack.getOrNull(top - y - 1)
        }

        /** Emit one rectangle (axis-aligned, on the X/Y/Z plane through the block at
         *  (bx,by,bz)) into the opaque or translucent vertex list. UVs span the
         *  given sub-rectangle of the tile (default: the whole tile). */
        fun emitRect(
            bx: Float, by: Float, bz: Float,
            nx: Float, ny: Float, nz: Float,
            x0: Float, y0: Float, z0: Float,
            x1: Float, y1: Float, z1: Float,
            tile: Int, color: FloatArray?, alpha: Float,
            u0: Float = 0f, v0: Float = 0f, u1: Float = 1f, v1: Float = 1f,
            sideGrassStrip: Boolean = false,
            trans: Boolean = false
        ) {
            val c = arrayOf(
                floatArrayOf(x0, y0, z0), floatArrayOf(x1, y0, z0),
                floatArrayOf(x1, y0, z1), floatArrayOf(x0, y0, z1)
            )
            if (nx != 0f) { // x-plane: corners at x0, z spans y0..y1
                c[0] = floatArrayOf(x0, y0, z0); c[1] = floatArrayOf(x0, y0, z1)
                c[2] = floatArrayOf(x0, y1, z1); c[3] = floatArrayOf(x0, y1, z0)
            } else if (nz != 0f) { // z-plane
                c[0] = floatArrayOf(x0, y0, z0); c[1] = floatArrayOf(x1, y0, z0)
                c[2] = floatArrayOf(x1, y1, z0); c[3] = floatArrayOf(x0, y1, z0)
            }
            // Wind the quad so BOTH triangles face (nx,ny,nz): if the first
            // triangle is backwards, reverse the whole corner array (a plain
            // c[1]/c[2] swap would flip the second triangle's winding and get
            // culled — every face degenerated into a single triangle).
            val e1x = c[1][0]-c[0][0]; val e1y = c[1][1]-c[0][1]; val e1z = c[1][2]-c[0][2]
            val e2x = c[2][0]-c[0][0]; val e2y = c[2][1]-c[0][1]; val e2z = c[2][2]-c[0][2]
            val gx = e1y*e2z-e1z*e2y; val gy = e1z*e2x-e1x*e2z; val gz = e1x*e2y-e1y*e2x
            if (gx*nx+gy*ny+gz*nz < 0) c.reverse()
            // UV axes: u along the horizontal in-plane axis, v vertical (or z on tops).
            val axA: Int; val axB: Int
            when {
                ny != 0f -> { axA = 0; axB = 2 }
                nx != 0f -> { axA = 2; axB = 1 }
                else -> { axA = 0; axB = 1 }
            }
            var minA = Float.MAX_VALUE; var maxA = -Float.MAX_VALUE
            var minB = Float.MAX_VALUE; var maxB = -Float.MAX_VALUE
            for (p in c) {
                if (p[axA] < minA) minA = p[axA]; if (p[axA] > maxA) maxA = p[axA]
                if (p[axB] < minB) minB = p[axB]; if (p[axB] > maxB) maxB = p[axB]
            }
            val uv = atlas.uvOrigin(tile)
            val u0 = uv[0]; val v0 = uv[1]
            val target = if (trans) transVerts else verts
            val a = alpha
            for (k in intArrayOf(0, 1, 2, 0, 2, 3)) {
                val p = c[k]
                val u = if (maxA > minA) u0 + (p[axA]-minA)/(maxA-minA)*(u1-u0) else u0
                val t = if (maxB > minB) v0 + (p[axB]-minB)/(maxB-minB)*(v1-v0) else v0
                target.add(bx+p[0]); target.add(by+p[1]); target.add(bz+p[2])
                target.add(nx); target.add(ny); target.add(nz)
                target.add(u0 + u*TextureAtlas.TILE_UV); target.add(v0 + t*TextureAtlas.TILE_UV)
                val cc = when {
                    // Grass-block side: only the top strip (t >= 0.75) is grass-coloured.
                    sideGrassStrip && ny == 0f && t >= 0.75f -> grassTint
                    color != null -> color
                    else -> WHITE
                }
                target.add(cc[0]); target.add(cc[1]); target.add(cc[2]); target.add(a)
            }
        }

        /** Emit an axis-aligned box with per-face enable flags. All coords block-local 0..1. */
        fun emitBox(
            bx: Float, by: Float, bz: Float,
            x0: Float, y0: Float, z0: Float,
            x1: Float, y1: Float, z1: Float,
            tile: Int, color: FloatArray?, alpha: Float,
            top: Boolean, bottom: Boolean,
            north: Boolean, south: Boolean, east: Boolean, west: Boolean,
            sideGrass: Boolean = false,
            trans: Boolean = false,
            topTile: Int = tile
        ) {
            if (top) emitRect(bx,by,bz, 0f,1f,0f, x0,y1,z0, x1,y1,z1, topTile, color, alpha, trans = trans)
            if (bottom) emitRect(bx,by,bz, 0f,-1f,0f, x0,y0,z0, x1,y0,z1, tile, color, alpha, trans = trans)
            if (north) emitRect(bx,by,bz, 0f,0f,-1f, x0,y0,z0, x1,y1,z0, tile, color, alpha, sideGrassStrip = sideGrass, trans = trans)
            if (south) emitRect(bx,by,bz, 0f,0f,1f, x0,y0,z1, x1,y1,z1, tile, color, alpha, sideGrassStrip = sideGrass, trans = trans)
            if (west) emitRect(bx,by,bz, -1f,0f,0f, x0,y0,z0, x0,y1,z1, tile, color, alpha, sideGrassStrip = sideGrass, trans = trans)
            if (east) emitRect(bx,by,bz, 1f,0f,0f, x1,y0,z0, x1,y1,z1, tile, color, alpha, sideGrassStrip = sideGrass, trans = trans)
        }

        /** Cross-model plants: two X-shaped double-sided quads (vanilla cross model). */
        fun emitCross(bx: Float, by: Float, bz: Float, tile: Int, tint: FloatArray?) {
            val uv = atlas.uvOrigin(tile)
            val u0 = uv[0]; val v0 = uv[1]
            for ((fi, face) in arrayOf(CROSS_1, CROSS_2).withIndex()) {
                for (back in 0..1) {
                    val v = face.vertices
                    for (k in intArrayOf(0, 1, 2, 0, 2, 3)) {
                        val i = if (back == 0) k else 3 - k
                        val px = v[i*3]; val py = v[i*3+1]; val pz = v[i*3+2]
                        transVerts.add(bx+px); transVerts.add(by+py); transVerts.add(bz+pz)
                        val n = if (back == 0) 1f else -1f
                        transVerts.add(face.nx*n); transVerts.add(face.ny*n); transVerts.add(face.nz*n)
                        // UV axes must be orthogonal in the quad plane: u runs along
                        // the face's horizontal diagonal, v along y. Using (px,pz)
                        // directly made u == v (both along the same diagonal), so the
                        // whole quad sampled one diagonal line — the "stretched pixel".
                        val u = if (fi == 0) (px + pz) * 0.5f else (1f - px + pz) * 0.5f
                        transVerts.add(u0 + u*TextureAtlas.TILE_UV); transVerts.add(v0 + py*TextureAtlas.TILE_UV)
                        val c = tint ?: WHITE
                        val a = c.getOrElse(3) { 1f }
                        transVerts.add(c[0]); transVerts.add(c[1]); transVerts.add(c[2]); transVerts.add(a)
                    }
                }
            }
        }

        /** Emit a BlueMap-style model: every element face with cullface checks. */
        fun emitModel(
            bx: Float, by: Float, bz: Float,
            model: ModelLoader.ResolvedModel,
            blockId: String, biome: String?,
            alpha: Float, trans: Boolean, solidAbove: Boolean,
            x: Int, z: Int, y: Int
        ) {
            // Tint colour by block family; applied ONLY to faces whose model
            // declares tintindex (grass top, leaves, plants… — vanilla behaviour).
            val familyTint = when (blockId) {
                "grass_block", "mycelium", "podzol" -> grassTint
                "oak_leaves", "birch_leaves", "spruce_leaves", "jungle_leaves",
                "acacia_leaves", "dark_oak_leaves", "mangrove_leaves", "azalea_leaves",
                "flowering_azalea_leaves", "cherry_leaves" -> foliageTintOf(biome)
                else -> if (blockId in CROSS_BLOCKS) crossTintOf(blockId, biome) else null
            }
            for (el in model.elements) {
                for (f in el.faces) {
                    val occluded = when (f.cullDir) {
                        -1 -> false
                        0 -> false
                        1 -> solidAbove
                        2 -> neighbourSolid(x, z - 1, y)
                        3 -> neighbourSolid(x, z + 1, y)
                        4 -> neighbourSolid(x - 1, z, y)
                        5 -> neighbourSolid(x + 1, z, y)
                        else -> false
                    }
                    if (occluded) continue
                    val nx: Float; val ny: Float; val nz: Float
                    when (f.dir) {
                        0 -> { nx = 0f; ny = -1f; nz = 0f }
                        1 -> { nx = 0f; ny = 1f; nz = 0f }
                        2 -> { nx = 0f; ny = 0f; nz = -1f }
                        3 -> { nx = 0f; ny = 0f; nz = 1f }
                        4 -> { nx = -1f; ny = 0f; nz = 0f }
                        else -> { nx = 1f; ny = 0f; nz = 0f }
                    }
                    paths.add(f.texPath)
                    val tile = atlas.tileFor(f.texPath)
                    val tint = if (f.tint >= 0) familyTint else null
                    emitRect(bx, by, bz, nx, ny, nz, el.x0, el.y0, el.z0, el.x1, el.y1, el.z1,
                        tile, tint, alpha, f.u0, f.v0, f.u1, f.v1, trans = trans)
                    if (f.cullDir < 0) {
                        // No cullface: visible from both sides (cross plants, thin panels).
                        emitRect(bx, by, bz, -nx, -ny, -nz, el.x0, el.y0, el.z0, el.x1, el.y1, el.z1,
                            tile, tint, alpha, f.u0, f.v0, f.u1, f.v1, trans = trans)
                    }
                }
            }
        }

        for (x in 0 until 16) {
            for (z in 0 until 16) {
                val top = heights[x][z]
                if (top == Int.MIN_VALUE) continue
                val biome = data.biomes[x][z]
                grassTint = grassTintOf(biome)
                // Walk down from the surface: emit faces where a neighbour column
                // has NO solid block at this height (real exposure — caves,
                // overhangs and cliffs all work). Stop once all four sides are
                // buried (or we hit the 40-block scan depth).
                val maxDepth = 40
                var y = top
                while (y >= top - maxDepth) {
                    val info = blockAt(x, z, y, top)
                    // Air (real air entries recorded in the below-surface stack, or
                    // beyond the scan) must not emit geometry — a non-null air
                    // BlockInfo would otherwise render as a placeholder-textured cube.
                    if (info == null || info.name.endsWith("air")) { y--; continue }
                    val block = info.name
                    val blockId = block.substringAfter(':')
                    val props = info.props
                    val bx = (baseX + x).toFloat()
                    val bz = (baseZ + z).toFloat()
                    val by = y.toFloat()
                    // Water never occludes a TOP face (the riverbed under water must
                    // stay visible through the translucent water above it).
                    val solidAbove = neighbourSolid(x, z, y + 1, excludeWater = true)

                    val isWater = blockId == "water"
                    val alpha = when {
                        isWater -> WATER_ALPHA
                        blockId.endsWith("_door") || blockId.endsWith("_trapdoor") -> DOOR_ALPHA
                        else -> 1f
                    }
                    // BlueMap-style: the vanilla model wins when one exists. Water is
                    // handled specially below (its model is a plain cube).
                    val model = if (!isWater) ModelLoader.getModel(blockId, props) else null
                    val trans = isWater || blockId.endsWith("_door") || blockId.endsWith("_trapdoor") ||
                        isTranslucent(blockId) || (model != null && modelHasAlpha(model))
                    if (model != null && model.elements.isNotEmpty()) {
                        emitModel(bx, by, bz, model, blockId, biome, alpha, trans, solidAbove, x, z, y)
                        // All four sides buried → nothing below can be visible.
                        if (y != top && neighbourSolid(x-1, z, y) && neighbourSolid(x+1, z, y) &&
                            neighbourSolid(x, z-1, y) && neighbourSolid(x, z+1, y)) break
                        y--
                        continue
                    }
                    // Cross-model plants without a model file (modded) still render
                    // as X quads (translucent pass — their textures have real alpha).
                    if (blockId in CROSS_BLOCKS) {
                        val p = "minecraft:block/$blockId"
                        paths.add(p)
                        emitCross(bx, by, bz, atlas.tileFor(p), crossTintOf(blockId, biome))
                        y--
                        continue
                    }

                    val waterTint = if (isWater) BiomeColors.toFloatRgba(BiomeColors.waterColor(biome), WATER_ALPHA) else null
                    // Water surface sits 1/8 below the block top (vanilla look).
                    val waterDrop = if (isWater && y == top) 0.125f else 0f
                    val wby = by - waterDrop
                    val isGrassSide = blockId in setOf("grass_block", "mycelium", "podzol")
                    val sideTint = when (blockId) {
                        "grass_block", "mycelium", "podzol" -> null // strip tint below
                        "oak_leaves", "birch_leaves", "spruce_leaves", "jungle_leaves",
                        "acacia_leaves", "dark_oak_leaves", "mangrove_leaves", "azalea_leaves",
                        "flowering_azalea_leaves", "cherry_leaves" -> foliageTintOf(biome)
                        else -> waterTint
                    }
                    val topTint = when (blockId) {
                        "grass_block", "mycelium", "podzol" -> grassTint
                        "oak_leaves", "birch_leaves", "spruce_leaves", "jungle_leaves",
                        "acacia_leaves", "dark_oak_leaves", "mangrove_leaves", "azalea_leaves",
                        "flowering_azalea_leaves", "cherry_leaves" -> foliageTintOf(biome)
                        else -> waterTint
                    }

                    val sideP = when {
                        isWater -> "minecraft:block/water_still"
                        blockId == "grass_block" -> "minecraft:block/grass_block_side"
                        blockId == "mycelium" -> "minecraft:block/mycelium_side"
                        blockId == "podzol" -> "minecraft:block/podzol_side"
                        blockId == "dirt_path" -> "minecraft:block/dirt_path_side"
                        else -> "minecraft:block/$blockId"
                    }
                    val topP = when {
                        isWater -> sideP
                        blockId == "grass_block" -> "minecraft:block/grass_block_top"
                        blockId == "mycelium" -> "minecraft:block/mycelium_top"
                        blockId == "podzol" -> "minecraft:block/podzol_top"
                        blockId == "dirt_path" -> "minecraft:block/dirt_path_top"
                        else -> sideP
                    }
                    paths.add(sideP); paths.add(topP)
                    val tile = atlas.tileFor(sideP)
                    val topTile = atlas.tileFor(topP)
                    when {
                        blockId.endsWith("_slab") -> {
                            val type = props["type"] ?: "bottom"
                            if (type == "double") {
                                emitBox(bx, wby, bz, 0f,0f,0f, 1f,1f,1f, tile, topTint, alpha, topTile = topTile,
                                    top = !solidAbove, bottom = false,
                                    north = !neighbourSolid(x, z-1, y), south = !neighbourSolid(x, z+1, y),
                                    west = !neighbourSolid(x-1, z, y), east = !neighbourSolid(x+1, z, y),
                                    sideGrass = isGrassSide, trans = trans)
                            } else {
                                val y0 = if (type == "bottom") 0f else 0.5f
                                emitBox(bx, wby, bz, 0f,y0,0f, 1f,y0+0.5f,1f, tile, topTint, alpha, topTile = topTile,
                                    top = !solidAbove, bottom = false,
                                    north = !neighbourSolid(x, z-1, y), south = !neighbourSolid(x, z+1, y),
                                    west = !neighbourSolid(x-1, z, y), east = !neighbourSolid(x+1, z, y),
                                    sideGrass = isGrassSide, trans = trans)
                            }
                        }
                        blockId.endsWith("_stairs") -> {
                            // Vanilla stairs = full bottom half + back half on top
                            // (back = away from the facing direction).
                            val facing = props["facing"] ?: "north"
                            // back = filled half (away from facing); front = step top.
                            val (bx0, bz0, bx1, bz1) = when (facing) {
                                "north" -> floatArrayOf(0f, 0.5f, 1f, 1f)   // south half
                                "south" -> floatArrayOf(0f, 0f, 1f, 0.5f)   // north half
                                "west" -> floatArrayOf(0f, 0f, 0.5f, 1f)    // west half
                                else -> floatArrayOf(0.5f, 0f, 1f, 1f)      // east half
                            }
                            val (fx0, fz0, fx1, fz1) = when (facing) {
                                "north" -> floatArrayOf(0f, 0f, 1f, 0.5f)
                                "south" -> floatArrayOf(0f, 0.5f, 1f, 1f)
                                "west" -> floatArrayOf(0.5f, 0f, 1f, 1f)
                                else -> floatArrayOf(0f, 0f, 0.5f, 1f)
                            }
                            val sN = !neighbourSolid(x, z-1, y); val sS = !neighbourSolid(x, z+1, y)
                            val sW = !neighbourSolid(x-1, z, y); val sE = !neighbourSolid(x+1, z, y)
                            // Bottom box: full footprint, y..y+0.5.
                            emitBox(bx, by, bz, 0f,0f,0f, 1f,0.5f,1f, tile, topTint, alpha, topTile = topTile,
                                top = false, bottom = false,
                                north = sN, south = sS, west = sW, east = sE,
                                sideGrass = isGrassSide, trans = trans)
                            // Step top (front half at y+0.5) — exposed where the top box is absent.
                            emitRect(bx, by, bz, 0f,1f,0f, fx0,0.5f,fz0, fx1,0.5f,fz1, topTile, topTint, alpha, trans = trans)
                            // Top box: back half, y+0.5..y+1.
                            emitBox(bx, by, bz, bx0,0.5f,bz0, bx1,1f,bz1, tile, topTint, alpha, topTile = topTile,
                                top = !solidAbove, bottom = true,
                                north = sN, south = sS, west = sW, east = sE,
                                sideGrass = isGrassSide, trans = trans)
                        }
                        blockId.endsWith("_door") -> {
                            // 3px panel (0.1875); half from block-state properties.
                            val facing = props["facing"] ?: "north"
                            val open = props["open"] == "true"
                            val hinge = props["hinge"] ?: "right"
                            val t = 0.1875f
                            val (dx, dz) = when (facing) {
                                "south" -> 0f to 1f; "east" -> 1f to 0f; "west" -> -1f to 0f
                                else -> 0f to -1f
                            }
                            // When open the leaf lies against the wall on the hinge side.
                            var lx = dz; var lz = -dx
                            if (hinge != "left") { lx = -lx; lz = -lz }
                            val px0: Float; val pz0: Float
                            val px1: Float; val pz1: Float
                            if (open) {
                                px0 = if (lx > 0) 1f - t else 0f; px1 = if (lx > 0) 1f else t
                                pz0 = if (lz > 0) 1f - t else 0f; pz1 = if (lz > 0) 1f else t
                            } else {
                                px0 = if (dx > 0) 1f - t else 0f; px1 = if (dx > 0) 1f else t
                                pz0 = if (dz > 0) 1f - t else 0f; pz1 = if (dz > 0) 1f else t
                            }
                            val doorTile = atlas.tileFor(if (props["half"] == "upper") "minecraft:block/${blockId}_top" else "minecraft:block/${blockId}_bottom")
                            paths.add("minecraft:block/${blockId}_top"); paths.add("minecraft:block/${blockId}_bottom")
                            val sN = !neighbourSolid(x, z-1, y); val sS = !neighbourSolid(x, z+1, y)
                            val sW = !neighbourSolid(x-1, z, y); val sE = !neighbourSolid(x+1, z, y)
                            emitBox(bx, by, bz, px0,0f,pz0, px1,1f,pz1, doorTile, null, alpha, topTile = topTile,
                                top = !solidAbove, bottom = false,
                                north = sN, south = sS, west = sW, east = sE, trans = trans)
                        }
                        blockId.endsWith("_trapdoor") -> {
                            val half = props["half"] ?: "bottom"
                            val open = props["open"] == "true"
                            val facing = props["facing"] ?: "north"
                            val t = 0.1875f
                            val sN = !neighbourSolid(x, z-1, y); val sS = !neighbourSolid(x, z+1, y)
                            val sW = !neighbourSolid(x-1, z, y); val sE = !neighbourSolid(x+1, z, y)
                            if (open) {
                                // Vertical panel at the facing edge (like a closed door).
                                val (dx, dz) = when (facing) {
                                    "south" -> 0f to 1f; "east" -> 1f to 0f; "west" -> -1f to 0f
                                    else -> 0f to -1f
                                }
                                val px0 = if (dx > 0) 1f - t else 0f; val px1 = if (dx > 0) 1f else t
                                val pz0 = if (dz > 0) 1f - t else 0f; val pz1 = if (dz > 0) 1f else t
                                emitBox(bx, by, bz, px0,0f,pz0, px1,1f,pz1, tile, topTint, alpha, topTile = topTile,
                                    top = !solidAbove, bottom = false,
                                    north = sN, south = sS, west = sW, east = sE, trans = trans)
                            } else {
                                val y0 = if (half == "bottom") 0f else 1f - t
                                emitBox(bx, by, bz, 0f,y0,0f, 1f,y0+t,1f, tile, topTint, alpha, topTile = topTile,
                                    top = !solidAbove, bottom = false,
                                    north = sN, south = sS, west = sW, east = sE, trans = trans)
                            }
                        }
                        blockId.endsWith("_pane") || blockId.contains("glass") || blockId == "iron_bars" -> {
                            if (blockId.endsWith("_pane") || blockId == "iron_bars") {
                                // 2px panel(s); connections come from block-state props.
                                val t = 0.125f
                                val sN = !neighbourSolid(x, z-1, y); val sS = !neighbourSolid(x, z+1, y)
                                val sW = !neighbourSolid(x-1, z, y); val sE = !neighbourSolid(x+1, z, y)
                                val e = props["east"] == "true"; val w = props["west"] == "true"
                                val n = props["north"] == "true"; val so = props["south"] == "true"
                                if (e || w || (!n && !so)) {
                                    emitBox(bx, by, bz, 0.4375f,0f,0f, 0.5625f,1f,1f, tile, topTint, 1f, topTile = topTile,
                                        top = !solidAbove, bottom = false,
                                        north = sN, south = sS, west = sW, east = sE, trans = true)
                                }
                                if (n || so) {
                                    emitBox(bx, by, bz, 0f,0f,0.4375f, 1f,1f,0.5625f, tile, topTint, 1f, topTile = topTile,
                                        top = !solidAbove, bottom = false,
                                        north = sN, south = sS, west = sW, east = sE, trans = true)
                                }
                            } else {
                                // Full glass cube (cutout look via texture alpha).
                                emitBox(bx, by, bz, 0f,0f,0f, 1f,1f,1f, tile, topTint, 1f,
                                    top = !solidAbove, bottom = false,
                                    north = !neighbourSolid(x, z-1, y), south = !neighbourSolid(x, z+1, y),
                                    west = !neighbourSolid(x-1, z, y), east = !neighbourSolid(x+1, z, y),
                                    sideGrass = isGrassSide, trans = true)
                            }
                        }
                        else -> {
                            // Full cube.
                            emitBox(bx, wby, bz, 0f,0f,0f, 1f,1f,1f, tile, topTint, alpha, topTile = topTile,
                                top = !solidAbove, bottom = false,
                                north = !neighbourSolid(x, z-1, y), south = !neighbourSolid(x, z+1, y),
                                west = !neighbourSolid(x-1, z, y), east = !neighbourSolid(x+1, z, y),
                                sideGrass = isGrassSide, trans = trans)
                        }
                    }
                    // All four sides buried → nothing below can be visible.
                    if (y != top && neighbourSolid(x-1, z, y) && neighbourSolid(x+1, z, y) &&
                        neighbourSolid(x, z-1, y) && neighbourSolid(x, z+1, y)) break
                    y--
                }
            }
        }
        val n = verts.size / 12
        val tn = transVerts.size / 12
        if (meshLogCount.incrementAndGet() <= 30) {
            val surf = heights.sumOf { row -> row.count { it != Int.MIN_VALUE } }
            Logger.d("mesh($chunkX,$chunkZ): surface=$surf/256 verts=$n trans=$tn paths=${paths.size}")
        }
        return ChunkMesh(
            toFloatBuffer(verts), n,
            if (tn > 0) toFloatBuffer(transVerts) else null, tn,
            paths
        )
    }

    /** Blocks whose textures have transparent pixels: must draw in the translucent
     *  pass (otherwise the alpha=0 pixels render black). Covers all cross plants
     *  plus the common cutout families. */
    private fun isTranslucent(id: String): Boolean =
        id in CROSS_BLOCKS || id.contains("glass") || id.contains("pane") ||
        id == "ice" || id == "frosted_ice" || id == "chain" || id == "vine" ||
        id == "lily_pad" || id == "cactus" || id == "ladder" || id == "lever" ||
        id == "cobweb" || id == "scaffolding" || id == "flower_pot" ||
        id == "kelp" || id == "seagrass" || id == "tall_seagrass" ||
        id.contains("torch") || id.contains("lantern") || id.contains("campfire") ||
        id.contains("candle") || id.contains("rail") || id.contains("sign") ||
        id.contains("fungus") || id.contains("roots") || id.contains("sapling") ||
        id == "nether_sprouts" || id == "mangrove_propagule" || id == "moss_carpet" ||
        id == "spore_blossom" || id == "glow_lichen" || id == "hanging_roots" ||
        id == "pointed_dripstone" || id == "amethyst_cluster" ||
        id.endsWith("_bud") || id.endsWith("_coral") || id == "bubble_column" ||
        id.endsWith("_button") || id.endsWith("_pressure_plate") || id.endsWith("_fence") ||
        id.endsWith("_fence_gate")

    /** True when any texture referenced by [model] has transparent pixels. */
    private fun modelHasAlpha(model: ModelLoader.ResolvedModel): Boolean {
        for (el in model.elements) {
            for (f in el.faces) {
                if (atlas.hasAlpha(f.texPath)) return true
            }
        }
        return false
    }

    private fun grassTintOf(biome: String?): FloatArray = BiomeColors.toFloatRgba(BiomeColors.grassColor(biome), 1f)

    private fun foliageTintOf(biome: String?): FloatArray = BiomeColors.toFloatRgba(BiomeColors.foliageColor(biome), 1f)

    /** Blocks rendered as double-sided X-shaped quads (vanilla cross model). */
    /** Tint for cross plants: foliage colour for leaves-ish plants, grass colour
     *  for grass/ferns, white for flowers/saplings/torches (vanilla). */
    private fun crossTintOf(id: String, biome: String?): FloatArray? = when (id) {
        "short_grass", "tall_grass", "fern", "large_fern", "vine", "lily_pad",
        "sugar_cane", "wheat", "carrots", "potatoes", "beetroots", "sweet_berry_bush",
        "crimson_roots", "warped_roots", "nether_sprouts" -> grassTintOf(biome)
        "sunflower", "lilac", "rose_bush", "peony", "tall_grass" -> foliageTintOf(biome)
        else -> null
    }

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

    /** Change the field of view and rebuild the projection matrix. */
    fun updateFov(fov: Float) {
        viewConfig.fov = fov
        recomputeProjection()
    }

    fun cleanup() {
        scope.cancel()
        try {
            for ((_, handles) in meshes) deleteHandles(handles)
            meshes.clear()
            chunkDataCache.clear()
            pendingData.clear()
            atlas.deleteOnGl()
            if (shaderProgram != 0) GLES30.glDeleteProgram(shaderProgram)
            shaderProgram = 0
        } catch (e: Exception) {
            // GL context may already be gone (surface torn down) — nothing to do.
            Logger.w("PlayerRenderer.cleanup: ${e.message}")
        }
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

    /** One cube face: 4 corner offsets (CCW, outward normal) + the face normal. */
    private class Face(val vertices: FloatArray, val nx: Float, val ny: Float, val nz: Float)

    data class ChunkMesh(
        val vertexBuffer: FloatBuffer,
        val vertexCount: Int,
        /** Translucent geometry (water, glass, plants, doors): drawn after the
         *  opaque pass with alpha blending, far→near. */
        val transBuffer: FloatBuffer?,
        val transCount: Int,
        /** Texture paths referenced by this mesh (for resolving missing textures). */
        val paths: Set<String>
    )

    companion object {
        private const val WATER_ALPHA = 0.65f
        private const val DOOR_ALPHA = 0.85f

        /** Blocks rendered as double-sided X-shaped quads (vanilla cross model). */
        private val CROSS_BLOCKS = setOf(
            "short_grass", "tall_grass", "fern", "large_fern", "vine", "lily_pad",
            "sugar_cane", "bamboo", "wheat", "carrots", "potatoes", "beetroots",
            "poppy", "dandelion", "blue_orchid", "allium", "azure_bluet",
            "red_tulip", "orange_tulip", "white_tulip", "pink_tulip", "oxeye_daisy",
            "cornflower", "lily_of_the_valley", "wither_rose", "sunflower", "lilac",
            "rose_bush", "peony", "dead_bush", "sweet_berry_bush", "torch", "soul_torch",
            "oak_sapling", "spruce_sapling", "birch_sapling", "jungle_sapling",
            "acacia_sapling", "dark_oak_sapling", "cherry_sapling", "mangrove_propagule",
            "brown_mushroom", "red_mushroom", "crimson_fungus", "warped_fungus",
            "crimson_roots", "warped_roots", "nether_sprouts"
        )

        // Faces wound so the normal (cross product of the first triangle) points outward.
        private val TOP_FACE = Face(
            floatArrayOf(0f,1f,1f, 1f,1f,1f, 1f,1f,0f, 0f,1f,0f), 0f, 1f, 0f)
        private val BOTTOM_FACE = Face(
            floatArrayOf(0f,0f,0f, 1f,0f,0f, 1f,0f,1f, 0f,0f,1f), 0f, -1f, 0f)
        private val EAST_FACE = Face(
            floatArrayOf(1f,0f,0f, 1f,1f,0f, 1f,1f,1f, 1f,0f,1f), 1f, 0f, 0f)
        private val WEST_FACE = Face(
            floatArrayOf(0f,0f,1f, 0f,1f,1f, 0f,1f,0f, 0f,0f,0f), -1f, 0f, 0f)
        private val SOUTH_FACE = Face(
            floatArrayOf(0f,0f,1f, 1f,0f,1f, 1f,1f,1f, 0f,1f,1f), 0f, 0f, 1f)
        private val NORTH_FACE = Face(
            floatArrayOf(1f,0f,0f, 0f,0f,0f, 0f,1f,0f, 1f,1f,0f), 0f, 0f, -1f)

        // Cross-plant faces (X shape, like vanilla crops/flowers). Normals point
        // diagonally; faces are emitted twice (back side) so they show from both sides.
        private val CROSS_1 = Face(
            floatArrayOf(0f,0f,0f, 1f,0f,1f, 1f,1f,1f, 0f,1f,0f), -0.7071f, 0f, 0.7071f)
        private val CROSS_2 = Face(
            floatArrayOf(1f,0f,0f, 0f,0f,1f, 0f,1f,1f, 1f,1f,0f), -0.7071f, 0f, -0.7071f)

        // Terrain shader: textured blocks, lit by a fixed world-space directional
        // light. Pixels stay crisp: NEAREST filtering + mipmaps (atlas side).
        private const val TERRAIN_VERTEX_SHADER = """
            #version 300 es
            layout(location = 0) in vec3 aPosition;
            layout(location = 1) in vec3 aNormal;
            layout(location = 2) in vec2 aUV;
            layout(location = 3) in vec4 aColor;
            uniform mat4 uVP;
            out vec2 vUV;
            out vec3 vNormal;
            out vec4 vColor;
            void main() {
                gl_Position = uVP * vec4(aPosition, 1.0);
                vUV = aUV;
                vNormal = aNormal;
                vColor = aColor;
            }
        """

        private const val TERRAIN_FRAGMENT_SHADER = """
            #version 300 es
            precision mediump float;
            uniform sampler2D uTex;
            in vec2 vUV;
            in vec3 vNormal;
            in vec4 vColor;
            out vec4 fragColor;
            void main() {
                vec4 tex = texture(uTex, vUV);
                vec3 normal = normalize(vNormal);
                vec3 lightDir = normalize(vec3(0.35, 1.0, 0.25));
                float diff = max(dot(normal, lightDir), 0.0);
                float light = 0.5 + 0.5 * diff;
                fragColor = vec4(tex.rgb * vColor.rgb * light, tex.a * vColor.a);
            }
        """
    }
}
