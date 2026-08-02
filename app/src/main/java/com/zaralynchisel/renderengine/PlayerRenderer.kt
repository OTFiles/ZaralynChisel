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

    /** Chunks waiting on a neighbour's heightmap to rebuild their border walls.
     *  key = neighbour chunk key, value = chunk keys waiting for it. */
    private val waitingForNeighbor = java.util.concurrent.ConcurrentHashMap<Long, MutableSet<Long>>()

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
        val top = (1.0 / Math.tan(fovRad / 2.0)).toFloat()
        val bottom = -top
        val left = bottom * aspect
        val right = top * aspect
        val near = 0.05f
        val far = (viewConfig.renderDistance * 16 + 64).toFloat()
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

            GLES30.glBindVertexArray(vao[0])

            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
            GLES30.glBufferData(
                GLES30.GL_ARRAY_BUFFER, mesh.vertexBuffer.capacity() * 4,
                mesh.vertexBuffer, GLES30.GL_STATIC_DRAW
            )

            // pos(3) + normal(3) + uv(2)
            val stride = 8 * 4
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 3 * 4)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, stride, 6 * 4)
            GLES30.glEnableVertexAttribArray(2)

            GLES30.glBindVertexArray(0)
            meshes[key] = intArrayOf(vao[0], mesh.vertexCount, vbo[0])
        }
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
            processChunkData(key, chunkX, chunkZ, data)
        } catch (e: Exception) {
            Logger.e("PlayerRenderer: loadChunk ($chunkX,$chunkZ) failed", e)
        } finally {
            loading.remove(key)
            // Chunks that were waiting for this chunk's heightmap can now rebuild
            // their border walls (cross-chunk cliffs).
            val waiters = waitingForNeighbor.remove(key) ?: emptyList()
            if (waiters.isNotEmpty()) {
                Logger.d("loadChunk ($chunkX,$chunkZ): notifying ${waiters.size} waiting chunk(s)")
            }
            for (wk in waiters) {
                if (meshes.containsKey(wk) && !loading.contains(wk)) {
                    val wx = (wk and 0xFFFFFFFFL).toInt()
                    val wz = ((wk ushr 32) and 0xFFFFFFFFL).toInt()
                    loading.add(wk)
                    Logger.d("loadChunk: rebuilding neighbour ($wx,$wz) for cross-chunk walls")
                    scope.launch { loadChunk(wx, wz, wk) }
                }
            }
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
        // If a neighbour chunk isn't loaded yet, our border walls are missing.
        // Register so that neighbour's arrival rebuilds this chunk (cross-chunk cliffs).
        for ((dx, dz) in listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)) {
            val nk = chunkKey(chunkX + dx, chunkZ + dz)
            if (!surfaceHeights.containsKey(nk)) {
                waitingForNeighbor.computeIfAbsent(nk) { java.util.concurrent.ConcurrentHashMap.newKeySet() }.add(key)
            }
        }
        val mesh = buildChunkMesh(chunkX, chunkZ, data)
        pendingUpload.add(key to mesh)
        surfaceHeights[key] = data.heights

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
        val paths = LinkedHashSet<String>()
        val baseX = chunkX * 16
        val baseZ = chunkZ * 16

        fun neighborHeight(nx: Int, nz: Int, colX: Int, colZ: Int): Int {
            val nh = surfaceHeights[chunkKey(nx, nz)] ?: return Int.MIN_VALUE
            return nh[colX][colZ]
        }

        fun surfaceAt(x: Int, z: Int): Int {
            // Columns of this chunk first; outside columns consult the already-
            // loaded neighbour chunk heightmaps (cross-chunk cliff walls). If a
            // neighbour isn't loaded yet, fall back to the edge column so no
            // fake wall is invented — the neighbour triggers a rebuild when it
            // arrives (see processChunkData).
            val h = when {
                x in 0..15 && z in 0..15 -> heights[x][z]
                x < 0 -> neighborHeight(chunkX - 1, chunkZ, x + 16, z)
                x > 15 -> neighborHeight(chunkX + 1, chunkZ, x - 16, z)
                z < 0 -> neighborHeight(chunkX, chunkZ - 1, x, z + 16)
                else -> neighborHeight(chunkX, chunkZ + 1, x, z - 16)
            }
            if (h != Int.MIN_VALUE) return h
            val eh = heights[x.coerceIn(0, 15)][z.coerceIn(0, 15)]
            return if (eh == Int.MIN_VALUE) Int.MIN_VALUE else eh
        }

        fun blockAt(x: Int, z: Int, y: Int, top: Int): String {
            if (y == top) return data.surfaceBlocks[x][z] ?: "minecraft:stone"
            val stack = data.belowSurface[x][z]
            val idx = top - y - 1
            return stack.getOrNull(idx) ?: "minecraft:stone"
        }

        fun emitFace(bx: Float, by: Float, bz: Float, face: Face, tile: Int) {
            val v = face.vertices
            val uv = atlas.uvOrigin(tile)
            val u0 = uv[0]
            val v0 = uv[1]
            // Each face emits 6 vertices (two triangles) so glDrawArrays(GL_TRIANGLES)
            // never stitches across faces. Emitting only 4 made every 2nd triangle
            // span two faces — the visible "triangle" artifacts.
            for (k in intArrayOf(0, 1, 2, 0, 2, 3)) {
                verts.add(bx + v[k * 3]); verts.add(by + v[k * 3 + 1]); verts.add(bz + v[k * 3 + 2])
                verts.add(face.nx); verts.add(face.ny); verts.add(face.nz)
                verts.add(u0 + (if (k == 1 || k == 2) 1f else 0f) * TextureAtlas.TILE_UV)
                verts.add(v0 + (if (k == 2 || k == 3) 1f else 0f) * TextureAtlas.TILE_UV)
            }
        }

        for (x in 0 until 16) {
            for (z in 0 until 16) {
                val top = heights[x][z]
                if (top == Int.MIN_VALUE) continue
                // Bottom of the visible column = min of the 4 neighbour surfaces.
                var bottom = Int.MAX_VALUE
                for ((nx, nz) in SURFACE_NEIGHBOURS) {
                    val nh = surfaceAt(x + nx, z + nz)
                    if (nh != Int.MIN_VALUE && nh < bottom) bottom = nh
                }
                if (bottom == Int.MAX_VALUE) bottom = top - 1
                if (bottom > top - 1) bottom = top - 1 // at least the surface block

                for (y in (bottom + 1)..top) {
                    val block = blockAt(x, z, y, top)
                    val bx = (baseX + x).toFloat()
                    val bz = (baseZ + z).toFloat()
                    val by = y.toFloat()
                    if (y == top) {
                        val p = topTexturePath(block)
                        paths.add(p)
                        emitFace(bx, by, bz, TOP_FACE, atlas.tileFor(p))
                    }
                    if (surfaceAt(x - 1, z) < y) {
                        val p = sideTexturePath(block); paths.add(p)
                        emitFace(bx, by, bz, WEST_FACE, atlas.tileFor(p))
                    }
                    if (surfaceAt(x + 1, z) < y) {
                        val p = sideTexturePath(block); paths.add(p)
                        emitFace(bx, by, bz, EAST_FACE, atlas.tileFor(p))
                    }
                    if (surfaceAt(x, z - 1) < y) {
                        val p = sideTexturePath(block); paths.add(p)
                        emitFace(bx, by, bz, NORTH_FACE, atlas.tileFor(p))
                    }
                    if (surfaceAt(x, z + 1) < y) {
                        val p = sideTexturePath(block); paths.add(p)
                        emitFace(bx, by, bz, SOUTH_FACE, atlas.tileFor(p))
                    }
                }
            }
        }
        return ChunkMesh(toFloatBuffer(verts), verts.size / 9, paths)
    }

    private fun topTexturePath(block: String): String {
        val id = block.substringAfter(':')
        return when (id) {
            "grass_block" -> "minecraft:block/grass_block_top"
            "mycelium" -> "minecraft:block/mycelium_top"
            "podzol" -> "minecraft:block/podzol_top"
            "dirt_path" -> "minecraft:block/dirt_path_top"
            "farmland" -> "minecraft:block/farmland"
            "water" -> "minecraft:block/water_still"
            "lava" -> "minecraft:block/lava_still"
            "snow" -> "minecraft:block/snow"
            else -> if (id.endsWith("_log") || id.endsWith("_stem")) {
                "minecraft:block/${id}_top"
            } else {
                "minecraft:block/$id"
            }
        }
    }

    private fun sideTexturePath(block: String): String {
        val id = block.substringAfter(':')
        return when (id) {
            "grass_block" -> "minecraft:block/grass_block_side"
            "mycelium" -> "minecraft:block/mycelium_side"
            "podzol" -> "minecraft:block/podzol_side"
            "dirt_path" -> "minecraft:block/dirt_path_side"
            "water" -> "minecraft:block/water_still"
            "lava" -> "minecraft:block/lava_still"
            "snow" -> "minecraft:block/snow"
            else -> "minecraft:block/$id"
        }
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
            surfaceHeights.clear()
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
        /** Texture paths referenced by this mesh (for resolving missing textures). */
        val paths: Set<String>
    )

    companion object {
        private val SURFACE_NEIGHBOURS = arrayOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)

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

        // Terrain shader: textured blocks, lit by a fixed world-space directional
        // light. Pixels stay crisp: NEAREST filtering + mipmaps (atlas side).
        private const val TERRAIN_VERTEX_SHADER = """
            #version 300 es
            layout(location = 0) in vec3 aPosition;
            layout(location = 1) in vec3 aNormal;
            layout(location = 2) in vec2 aUV;
            uniform mat4 uVP;
            out vec2 vUV;
            out vec3 vNormal;
            void main() {
                gl_Position = uVP * vec4(aPosition, 1.0);
                vUV = aUV;
                vNormal = aNormal;
            }
        """

        private const val TERRAIN_FRAGMENT_SHADER = """
            #version 300 es
            precision mediump float;
            uniform sampler2D uTex;
            in vec2 vUV;
            in vec3 vNormal;
            out vec4 fragColor;
            void main() {
                vec4 tex = texture(uTex, vUV);
                vec3 normal = normalize(vNormal);
                vec3 lightDir = normalize(vec3(0.35, 1.0, 0.25));
                float diff = max(dot(normal, lightDir), 0.0);
                float light = 0.5 + 0.5 * diff;
                fragColor = vec4(tex.rgb * light, 1.0);
            }
        """
    }
}
