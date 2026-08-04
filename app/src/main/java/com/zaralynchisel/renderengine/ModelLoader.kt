package com.zaralynchisel.renderengine

import com.zaralynchisel.utils.Logger
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipFile

/**
 * BlueMap-style block model loader: parses the vanilla client jar's
 * blockstates/*.json and models/*.json (parent chains, texture variables,
 * multipart parts, variant matching, model-level x/y rotations) into flat
 * per-face geometry that PlayerRenderer emits directly. Blocks without a
 * model (or an unreadable jar) fall back to the cube/cross approximations.
 *
 * Face directions: 0 down, 1 up, 2 north, 3 south, 4 west, 5 east.
 * All model coordinates (from/to/uv) are in 0..16 units and converted to
 * 0..1 here; texture v already runs top→bottom, matching the atlas layout.
 */
object ModelLoader {

    class ModelFace(
        val dir: Int,            // 0 down, 1 up, 2 north, 3 south, 4 west, 5 east
        val u0: Float, val v0: Float, val u1: Float, val v1: Float,
        val texPath: String,
        val cullDir: Int,        // -1 = no cullface
        val tint: Int            // -1 = no tint
    )

    class ModelElement(
        val x0: Float, val y0: Float, val z0: Float,
        val x1: Float, val y1: Float, val z1: Float,
        val faces: List<ModelFace>
    )

    /** Empty [elements] = block has no renderable model (caller falls back). */
    class ResolvedModel(val elements: List<ModelElement>)

    private val DIR_IDS = mapOf(
        "down" to 0, "up" to 1, "north" to 2, "south" to 3, "west" to 4, "east" to 5
    )

    @Volatile
    private var jar: ZipFile? = null

    private val modelCache = ConcurrentHashMap<String, ResolvedModel>()
    private val jsonCache = ConcurrentHashMap<String, JSONObject>()
    private val emptyModel = ResolvedModel(emptyList())

    /** Open the version jar (idempotent). Call from a background thread. */
    fun init(minecraftDir: String, version: String) {
        if (jar != null) return
        synchronized(this) {
            if (jar != null) return
            try {
                val versionsDir = File(minecraftDir, "versions")
                val dirs = versionsDir.listFiles { f -> f.isDirectory }?.toList() ?: emptyList()
                val preferred = dirs.firstOrNull { it.name == version }
                    ?: dirs.firstOrNull { it.name.startsWith(version) }
                    ?: dirs.firstOrNull()
                if (preferred == null) {
                    Logger.w("ModelLoader: no version directories under ${versionsDir.absolutePath}")
                    return
                }
                val jarFile = File(preferred, "${preferred.name}.jar")
                if (!jarFile.exists()) {
                    Logger.w("ModelLoader: no client jar at ${jarFile.absolutePath}")
                    return
                }
                jar = ZipFile(jarFile)
                Logger.d("ModelLoader: opened ${jarFile.absolutePath}")
            } catch (e: Exception) {
                Logger.w("ModelLoader.init failed: ${e.message}")
            }
        }
    }

    /** Close the cached jar (safe to call on teardown). */
    fun close() {
        synchronized(this) {
            try {
                jar?.close()
            } catch (_: Exception) { }
            jar = null
            modelCache.clear()
            jsonCache.clear()
        }
    }

    /** Resolve a block's model for the given state properties; null = unavailable. */
    fun getModel(blockId: String, props: Map<String, String>): ResolvedModel? {
        if (jar == null) return null
        val key = blockId + "|" + props.entries.sortedBy { it.key }
            .joinToString(",") { "${it.key}=${it.value}" }
        modelCache[key]?.let { return it }
        val m = load(blockId, props) ?: emptyModel
        modelCache.putIfAbsent(key, m)
        return modelCache[key]
    }

    /** Load a cached raw JSON from the jar. */
    private fun json(path: String): JSONObject? {
        jsonCache[path]?.let { return it }
        val z = jar ?: return null
        return try {
            val entry = z.getEntry(path) ?: return null
            val bytes = z.getInputStream(entry).use { it.readBytes() }
            val obj = JSONObject(String(bytes, Charsets.UTF_8))
            jsonCache.putIfAbsent(path, obj)
            obj
        } catch (e: Exception) {
            null
        }
    }

    private fun load(blockId: String, props: Map<String, String>): ResolvedModel? {
        val bs = json("assets/minecraft/blockstates/$blockId.json") ?: return null
        val elements = ArrayList<ModelElement>()
        val variants = bs.optJSONObject("variants")
        if (variants != null) {
            val key = props.entries.sortedBy { it.key }
                .joinToString(",") { "${it.key}=${it.value}" }
            val v = variants.optJSONObject(key) ?: variants.optJSONObject("")
            if (v != null) addVariant(elements, v)
            return ResolvedModel(elements)
        }
        val multi = bs.optJSONArray("multipart")
        if (multi != null) {
            for (i in 0 until multi.length()) {
                val part = multi.optJSONObject(i) ?: continue
                val whenObj = part.optJSONObject("when")
                if (whenObj != null && !matchesWhen(whenObj, props)) continue
                val apply = part.optJSONObject("apply") ?: continue
                addVariant(elements, apply)
            }
            return ResolvedModel(elements)
        }
        return null
    }

    private fun addVariant(elements: MutableList<ModelElement>, v: JSONObject) {
        val modelPath = v.optString("model", "").substringAfter(":")
        if (modelPath.isEmpty()) return
        val xRot = v.optInt("x", 0)
        val yRot = v.optInt("y", 0)
        for (e in modelElements(modelPath)) {
            elements.add(transform(e, xRot, yRot))
        }
    }

    /** Multipart "when" condition: AND of prop matches, or an OR array. */
    private fun matchesWhen(whenObj: JSONObject, props: Map<String, String>): Boolean {
        val or = whenObj.optJSONArray("OR")
        if (or != null) {
            for (i in 0 until or.length()) {
                val o = or.optJSONObject(i) ?: continue
                if (matchesWhen(o, props)) return true
            }
            return false
        }
        val it = whenObj.keys()
        while (it.hasNext()) {
            val k = it.next()
            if (props[k] != whenObj.optString(k)) return false
        }
        return true
    }

    /** Resolve a model path (with parent chain) to a flat element list. */
    private fun modelElements(path: String): List<ModelElement> {
        val raw = json("assets/minecraft/models/$path.json") ?: return emptyList()
        // Parent chain, child first.
        val chain = ArrayList<JSONObject>()
        var cur: JSONObject? = raw
        var depth = 0
        while (cur != null && depth < 16) {
            chain.add(cur)
            val parent = cur.optString("parent", "").substringAfter(":")
            cur = if (parent.isEmpty()) null else json("assets/minecraft/models/$parent.json")
            depth++
        }
        // Textures merge parent → child (child wins).
        val textures = HashMap<String, String>()
        for (i in chain.indices.reversed()) {
            val t = chain[i].optJSONObject("textures") ?: continue
            val it = t.keys()
            while (it.hasNext()) {
                val k = it.next()
                if (!textures.containsKey(k)) textures[k] = t.optString(k, "")
            }
        }
        // First model in the chain that defines elements wins.
        for (c in chain) {
            val els = c.optJSONArray("elements")
            if (els == null) continue
            val out = ArrayList<ModelElement>(els.length())
            for (i in 0 until els.length()) {
                val e = els.optJSONObject(i) ?: continue
                parseElement(e, textures)?.let { out.add(it) }
            }
            return out
        }
        return emptyList()
    }

    private fun parseElement(e: JSONObject, textures: Map<String, String>): ModelElement? {
        val from = e.optJSONArray("from") ?: return null
        val to = e.optJSONArray("to") ?: return null
        val x0 = from.optDouble(0, 0.0).toFloat() / 16f
        val y0 = from.optDouble(1, 0.0).toFloat() / 16f
        val z0 = from.optDouble(2, 0.0).toFloat() / 16f
        val x1 = to.optDouble(0, 16.0).toFloat() / 16f
        val y1 = to.optDouble(1, 16.0).toFloat() / 16f
        val z1 = to.optDouble(2, 16.0).toFloat() / 16f
        val faces = ArrayList<ModelFace>()
        val facesObj = e.optJSONObject("faces")
        if (facesObj != null) {
            val it = facesObj.keys()
            while (it.hasNext()) {
                val name = it.next()
                val dir = DIR_IDS[name] ?: continue
                val f = facesObj.optJSONObject(name) ?: continue
                val texPath = resolveTexture(f.optString("texture", ""), textures) ?: continue
                var u0: Float; var v0: Float; var u1: Float; var v1: Float
                val uvArr = f.optJSONArray("uv")
                if (uvArr != null) {
                    u0 = uvArr.optDouble(0, 0.0).toFloat() / 16f
                    v0 = uvArr.optDouble(1, 0.0).toFloat() / 16f
                    u1 = uvArr.optDouble(2, 16.0).toFloat() / 16f
                    v1 = uvArr.optDouble(3, 16.0).toFloat() / 16f
                } else {
                    // Default uv = the face's own rect (v runs top→bottom).
                    when (dir) {
                        0, 1 -> { u0 = x0; v0 = 1f - z1; u1 = x1; v1 = 1f - z0 }
                        2, 3 -> { u0 = x0; v0 = 1f - y1; u1 = x1; v1 = 1f - y0 }
                        else -> { u0 = z0; v0 = 1f - y1; u1 = z1; v1 = 1f - y0 }
                    }
                }
                // UV rotation (clockwise): rotate the whole uv rectangle.
                when (f.optInt("rotation", 0)) {
                    90 -> { val nu0 = 1f - v1; val nv0 = u0; val nu1 = 1f - v0; val nv1 = u1
                            u0 = nu0; v0 = nv0; u1 = nu1; v1 = nv1 }
                    180 -> { val nu0 = 1f - u1; val nv0 = 1f - v1; val nu1 = 1f - u0; val nv1 = 1f - v0
                             u0 = nu0; v0 = nv0; u1 = nu1; v1 = nv1 }
                    270 -> { val nu0 = v0; val nv0 = 1f - u1; val nu1 = v1; val nv1 = 1f - u0
                             u0 = nu0; v0 = nv0; u1 = nu1; v1 = nv1 }
                }
                val cullName = f.optString("cullface", "")
                val cullDir = DIR_IDS[cullName] ?: -1
                val tint = if (f.has("tintindex")) f.optInt("tintindex", -1) else -1
                faces.add(ModelFace(dir, u0, v0, u1, v1, texPath, cullDir, tint))
            }
        }
        return ModelElement(x0, y0, z0, x1, y1, z1, faces)
    }

    /** Resolve a "#variable" texture reference to a full resource path. */
    private fun resolveTexture(ref: String, textures: Map<String, String>): String? {
        var cur = ref
        var depth = 0
        while (cur.startsWith("#") && depth < 8) {
            cur = textures[cur.substring(1)] ?: return null
            depth++
        }
        if (cur.isEmpty()) return null
        return "minecraft:" + cur.substringAfter(":")
    }

    /** Apply model-level x/y rotations (vanilla: x first, then y; CCW from above). */
    private fun transform(e: ModelElement, xRot: Int, yRot: Int): ModelElement {
        if (xRot == 0 && yRot == 0) return e
        var fx0 = e.x0 * 16f; var fy0 = e.y0 * 16f; var fz0 = e.z0 * 16f
        var fx1 = e.x1 * 16f; var fy1 = e.y1 * 16f; var fz1 = e.z1 * 16f
        when (xRot) {
            90 -> { val ny0 = 16f - fz1; val ny1 = 16f - fz0; val nz0 = fy0; val nz1 = fy1
                    fy0 = ny0; fy1 = ny1; fz0 = nz0; fz1 = nz1 }
            180 -> { val ny0 = 16f - fy1; val ny1 = 16f - fy0; val nz0 = 16f - fz1; val nz1 = 16f - fz0
                     fy0 = ny0; fy1 = ny1; fz0 = nz0; fz1 = nz1 }
            270 -> { val ny0 = fz0; val ny1 = fz1; val nz0 = 16f - fy1; val nz1 = 16f - fy0
                     fy0 = ny0; fy1 = ny1; fz0 = nz0; fz1 = nz1 }
        }
        when (yRot) {
            90 -> { val nx0 = fz0; val nx1 = fz1; val nz0 = 16f - fx1; val nz1 = 16f - fx0
                    fx0 = nx0; fx1 = nx1; fz0 = nz0; fz1 = nz1 }
            180 -> { val nx0 = 16f - fx1; val nx1 = 16f - fx0; val nz0 = 16f - fz1; val nz1 = 16f - fz0
                     fx0 = nx0; fx1 = nx1; fz0 = nz0; fz1 = nz1 }
            270 -> { val nx0 = 16f - fz1; val nx1 = 16f - fz0; val nz0 = fx0; val nz1 = fx1
                     fx0 = nx0; fx1 = nx1; fz0 = nz0; fz1 = nz1 }
        }
        var dirMap = intArrayOf(0, 1, 2, 3, 4, 5)
        if (xRot != 0) {
            dirMap = when (xRot) {
                90 -> intArrayOf(2, 3, 0, 1, 4, 5)
                180 -> intArrayOf(1, 0, 3, 2, 4, 5)
                else -> intArrayOf(3, 2, 1, 0, 4, 5)
            }
        }
        if (yRot != 0) {
            val d = dirMap
            dirMap = IntArray(6)
            for (i in 0..5) dirMap[i] = when (yRot) {
                90 -> when (d[i]) { 2 -> 4; 3 -> 5; 4 -> 3; 5 -> 2; else -> d[i] }
                180 -> when (d[i]) { 2 -> 3; 3 -> 2; 4 -> 5; 5 -> 4; else -> d[i] }
                else -> when (d[i]) { 2 -> 5; 3 -> 4; 4 -> 2; 5 -> 3; else -> d[i] }
            }
        }
        val faces = ArrayList<ModelFace>(e.faces.size)
        for (f in e.faces) {
            faces.add(ModelFace(
                dirMap[f.dir], f.u0, f.v0, f.u1, f.v1, f.texPath,
                if (f.cullDir >= 0) dirMap[f.cullDir] else -1, f.tint
            ))
        }
        return ModelElement(fx0 / 16f, fy0 / 16f, fz0 / 16f, fx1 / 16f, fy1 / 16f, fz1 / 16f, faces)
    }
}
