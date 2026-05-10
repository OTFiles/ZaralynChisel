package com.zaralynchisel.renderengine

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * OpenGL ES 3.0 renderer for Player Mode.
 * Renders a first-person view of the Minecraft world with:
 * - Frustum culling (only visible faces)
 * - Smooth lighting (optional)
 * - Chunk mesh batching
 * - Texture atlas support
 *
 * This is a simplified voxel engine — not a full Minecraft clone.
 */
class PlayerRenderer : GLSurfaceView.Renderer {

    data class PlayerViewConfig(
        var cameraX: Float = 0f,
        var cameraY: Float = 64f,
        var cameraZ: Float = 0f,
        var yaw: Float = 0f,
        var pitch: Float = 0f,
        var renderDistance: Int = 12,
        var showChunkGrid: Boolean = false,
        var smoothLighting: Boolean = true,
        var fov: Float = 70f
    )

    var viewConfig = PlayerViewConfig()

    // Shader program IDs
    private var blockShaderProgram = 0
    private var lineShaderProgram = 0

    // Matrices
    private val projectionMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)

    // VBOs and VAOs
    private var chunkVao = 0
    private var chunkVbo = 0
    private var chunkEbo = 0

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.53f, 0.81f, 0.98f, 1.0f)  // Sky blue
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)

        // TODO: Compile shaders, create VAOs/VBOs
        // blockShaderProgram = compileShader(BLOCK_VERTEX_SHADER, BLOCK_FRAGMENT_SHADER)
        // lineShaderProgram = compileShader(LINE_VERTEX_SHADER, LINE_FRAGMENT_SHADER)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.toFloat()

        // Perspective projection
        val fovRad = Math.toRadians(viewConfig.fov.toDouble())
        val top = (1.0 / Math.tan(fovRad / 2.0)).toFloat()
        val bottom = -top
        val left = bottom * aspect
        val right = top * aspect
        val near = 0.1f
        val far = viewConfig.renderDistance * 16f

        android.opengl.Matrix.frustumM(projectionMatrix, 0, left, right, bottom, top, near, far)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        // Update view matrix from camera
        android.opengl.Matrix.setLookAtM(
            viewMatrix, 0,
            viewConfig.cameraX, viewConfig.cameraY, viewConfig.cameraZ,
            viewConfig.cameraX + kotlin.math.sin(Math.toRadians(viewConfig.yaw.toDouble())).toFloat(),
            viewConfig.cameraY + kotlin.math.sin(Math.toRadians(viewConfig.pitch.toDouble())).toFloat(),
            viewConfig.cameraZ + kotlin.math.cos(Math.toRadians(viewConfig.yaw.toDouble())).toFloat(),
            0f, 1f, 0f
        )

        // TODO: Render visible chunks
        // 1. Frustum culling
        // 2. Build chunk meshes (greedy meshing)
        // 3. Bind texture atlas
        // 4. Draw elements
    }

    /**
     * Update the camera position.
     */
    fun updateCamera(x: Float, y: Float, z: Float, yaw: Float, pitch: Float) {
        viewConfig.cameraX = x
        viewConfig.cameraY = y
        viewConfig.cameraZ = z
        viewConfig.yaw = yaw
        viewConfig.pitch = pitch
    }

    /**
     * Clean up OpenGL resources.
     */
    fun cleanup() {
        if (chunkVao != 0) {
            GLES30.glDeleteVertexArrays(1, intArrayOf(chunkVao), 0)
        }
        if (chunkVbo != 0) {
            GLES30.glDeleteBuffers(1, intArrayOf(chunkVbo), 0)
        }
        if (chunkEbo != 0) {
            GLES30.glDeleteBuffers(1, intArrayOf(chunkEbo), 0)
        }
        if (blockShaderProgram != 0) {
            GLES30.glDeleteProgram(blockShaderProgram)
        }
    }

    companion object {
        // Vertex shader for blocks
        const val BLOCK_VERTEX_SHADER = """
            #version 300 es
            layout(location = 0) in vec3 aPosition;
            layout(location = 1) in vec2 aTexCoord;
            layout(location = 2) in vec3 aNormal;
            uniform mat4 uModelViewProjection;
            out vec2 vTexCoord;
            out vec3 vNormal;
            void main() {
                gl_Position = uModelViewProjection * vec4(aPosition, 1.0);
                vTexCoord = aTexCoord;
                vNormal = aNormal;
            }
        """

        // Fragment shader for blocks
        const val BLOCK_FRAGMENT_SHADER = """
            #version 300 es
            precision mediump float;
            in vec2 vTexCoord;
            in vec3 vNormal;
            uniform sampler2D uTexture;
            uniform vec3 uLightDirection;
            out vec4 fragColor;
            void main() {
                vec4 texColor = texture(uTexture, vTexCoord);
                float light = max(dot(vNormal, normalize(uLightDirection)), 0.3);
                fragColor = vec4(texColor.rgb * light, texColor.a);
            }
        """
    }
}