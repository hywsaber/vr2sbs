package com.vr2sbs.render

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.util.Log
import android.view.Surface
import com.vr2sbs.render.gl.GlUtil
import com.vr2sbs.tracking.PoseState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.tan
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class VrSbsRenderer(
    private val listener: Listener? = null
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {
    private val frameAvailable = AtomicBoolean(false)
    private var program: Int = 0
    private var positionHandle: Int = -1
    private var texCoordHandle: Int = -1
    private var texScaleHandle: Int = -1
    private var texOffsetHandle: Int = -1
    private var samplerHandle: Int = -1
    private var projectionModeHandle: Int = -1
    private var headYawHandle: Int = -1
    private var headPitchHandle: Int = -1
    private var eyeYawOffsetHandle: Int = -1
    private var tanHalfFovXHandle: Int = -1
    private var tanHalfFovYHandle: Int = -1
    private var fisheyeHalfFovHandle: Int = -1

    private var imageProgram: Int = 0
    private var imagePositionHandle: Int = -1
    private var imageTexCoordHandle: Int = -1
    private var imageTexScaleHandle: Int = -1
    private var imageTexOffsetHandle: Int = -1
    private var imageSamplerHandle: Int = -1
    private var imageProjectionModeHandle: Int = -1
    private var imageHeadYawHandle: Int = -1
    private var imageHeadPitchHandle: Int = -1
    private var imageEyeYawOffsetHandle: Int = -1
    private var imageTanHalfFovXHandle: Int = -1
    private var imageTanHalfFovYHandle: Int = -1
    private var imageFisheyeHalfFovHandle: Int = -1

    private var outputWidth: Int = 1
    private var outputHeight: Int = 1
    private var externalTextureId: Int = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private val imageLock = Any()
    private var pendingImage: Bitmap? = null
    private var pendingImageSideBySide: Boolean = false
    private var imageSideBySide: Boolean = false
    private var imageGeneration: Long = 0L
    private var uploadedImageGeneration: Long = -1L
    private var imageTextureId: Int = 0
    @Volatile
    private var latestPose: PoseState = PoseState()
    @Volatile
    private var latestRenderMode: RenderMode = RenderMode()

    private val quadVertices: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 2 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(
                floatArrayOf(
                    -1f, -1f,
                    1f, -1f,
                    -1f, 1f,
                    1f, 1f
                )
            )
            position(0)
        }

    private val quadTexCoords: FloatBuffer = ByteBuffer
        .allocateDirect(4 * 2 * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(
                floatArrayOf(
                    0f, 1f,
                    1f, 1f,
                    0f, 0f,
                    1f, 0f
                )
            )
            position(0)
        }

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        onSurfaceCreated()
    }

    fun onSurfaceCreated() {
        // A lost EGL context invalidates programs and textures; recreate them.
        imageProgram = 0
        imageTextureId = 0
        imageSideBySide = false
        uploadedImageGeneration = -1L

        program = GlUtil.createProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        texScaleHandle = GLES20.glGetUniformLocation(program, "uTexScaleX")
        texOffsetHandle = GLES20.glGetUniformLocation(program, "uTexOffsetX")
        samplerHandle = GLES20.glGetUniformLocation(program, "uTexture")
        projectionModeHandle = GLES20.glGetUniformLocation(program, "uProjectionMode")
        headYawHandle = GLES20.glGetUniformLocation(program, "uHeadYawRad")
        headPitchHandle = GLES20.glGetUniformLocation(program, "uHeadPitchRad")
        eyeYawOffsetHandle = GLES20.glGetUniformLocation(program, "uEyeYawOffsetRad")
        tanHalfFovXHandle = GLES20.glGetUniformLocation(program, "uTanHalfFovX")
        tanHalfFovYHandle = GLES20.glGetUniformLocation(program, "uTanHalfFovY")
        fisheyeHalfFovHandle = GLES20.glGetUniformLocation(program, "uFisheyeHalfFovRad")

        createImageProgramIfAvailable()

        externalTextureId = GlUtil.createExternalTexture()
        surfaceTexture = SurfaceTexture(externalTextureId).apply {
            setOnFrameAvailableListener(this@VrSbsRenderer)
        }
        surface = Surface(surfaceTexture).also { listener?.onVideoSurfaceReady(it) }

        GLES20.glClearColor(0f, 0f, 0f, 1f)
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        onSurfaceChanged(width, height)
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        outputWidth = width.coerceAtLeast(1)
        outputHeight = height.coerceAtLeast(1)
        GLES20.glViewport(0, 0, outputWidth, outputHeight)
    }

    override fun onDrawFrame(unused: GL10?) {
        uploadPendingImageIfNeeded()
        if (imageTextureId != 0) {
            renderImageFrame(latestPose, latestRenderMode)
        } else {
            renderFrame(externalTextureId, latestPose, latestRenderMode)
        }
    }

    fun renderFrame(frameTexId: Int, pose: PoseState, mode: RenderMode) {
        if (frameAvailable.compareAndSet(true, false)) {
            surfaceTexture?.updateTexImage()
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, quadVertices)
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, quadTexCoords)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, frameTexId)
        GLES20.glUniform1i(samplerHandle, 0)
        GLES20.glUniform1i(projectionModeHandle, mode.projectionMode.toShaderValue())
        GLES20.glUniform1f(headYawHandle, pose.yaw)
        GLES20.glUniform1f(headPitchHandle, pose.pitch)

        val halfWidth = outputWidth / 2
        val leftEyeWidth = halfWidth.coerceAtLeast(1)
        val rightEyeWidth = (outputWidth - halfWidth).coerceAtLeast(1)
        val safeHeight = outputHeight.coerceAtLeast(1)
        // Half-SBS packs each eye into half the frame width and the glasses stretch it back.
        // Keep the full per-eye aspect for the projection so the un-squeeze preserves geometry.
        val leftAspectWidth = if (mode.packedHalfSbs) outputWidth else leftEyeWidth
        val rightAspectWidth = if (mode.packedHalfSbs) outputWidth else rightEyeWidth
        val halfConvergenceRadians = Math.toRadians(mode.convergenceDegrees.toDouble()).toFloat() * 0.5f
        val tanHalfFovY = tan(Math.toRadians((mode.perEyeFovDegrees * 0.5f).toDouble())).toFloat()
        val fisheyeSourceFovDegrees = if (mode.fisheyeSourceFovDegrees.isFinite()) {
            mode.fisheyeSourceFovDegrees.coerceIn(1f, 360f)
        } else {
            DEFAULT_FISHEYE_SOURCE_FOV_DEGREES
        }
        val fisheyeHalfFovRadians = Math.toRadians((fisheyeSourceFovDegrees * 0.5f).toDouble()).toFloat()

        // Left eye from left half of source texture.
        GLES20.glViewport(0, 0, halfWidth, outputHeight)
        GLES20.glUniform1f(texScaleHandle, 0.5f)
        GLES20.glUniform1f(texOffsetHandle, if (mode.swapEyes) 0.5f else 0f)
        GLES20.glUniform1f(eyeYawOffsetHandle, halfConvergenceRadians)
        GLES20.glUniform1f(tanHalfFovYHandle, tanHalfFovY)
        GLES20.glUniform1f(tanHalfFovXHandle, tanHalfFovY * (leftAspectWidth.toFloat() / safeHeight.toFloat()))
        GLES20.glUniform1f(fisheyeHalfFovHandle, fisheyeHalfFovRadians)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        // Right eye from right half of source texture.
        GLES20.glViewport(halfWidth, 0, outputWidth - halfWidth, outputHeight)
        GLES20.glUniform1f(texScaleHandle, 0.5f)
        GLES20.glUniform1f(texOffsetHandle, if (mode.swapEyes) 0f else 0.5f)
        GLES20.glUniform1f(eyeYawOffsetHandle, -halfConvergenceRadians)
        GLES20.glUniform1f(tanHalfFovYHandle, tanHalfFovY)
        GLES20.glUniform1f(tanHalfFovXHandle, tanHalfFovY * (rightAspectWidth.toFloat() / safeHeight.toFloat()))
        GLES20.glUniform1f(fisheyeHalfFovHandle, fisheyeHalfFovRadians)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
    }

    /**
     * Shows [bitmap] instead of the video stream until cleared. A stereo
     * side-by-side photo is split between the eyes like a video frame; a
     * monoscopic photo is sampled in full by both eyes.
     */
    fun setStaticImage(bitmap: Bitmap?, sideBySide: Boolean = false) {
        synchronized(imageLock) {
            pendingImage = bitmap
            pendingImageSideBySide = sideBySide
            imageGeneration += 1L
        }
    }

    private fun uploadPendingImageIfNeeded() {
        val generation: Long
        val bitmap: Bitmap?
        val sideBySide: Boolean
        synchronized(imageLock) {
            generation = imageGeneration
            bitmap = pendingImage
            sideBySide = pendingImageSideBySide
        }
        if (generation == uploadedImageGeneration) return
        uploadedImageGeneration = generation

        if (bitmap == null) {
            imageSideBySide = false
            releaseImageTexture()
            return
        }
        imageSideBySide = sideBySide
        if (imageTextureId == 0) {
            val textureIds = IntArray(1)
            GLES20.glGenTextures(1, textureIds, 0)
            imageTextureId = textureIds[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTextureId)
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MIN_FILTER,
                GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_MAG_FILTER,
                GLES20.GL_LINEAR
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_S,
                GLES20.GL_CLAMP_TO_EDGE
            )
            GLES20.glTexParameteri(
                GLES20.GL_TEXTURE_2D,
                GLES20.GL_TEXTURE_WRAP_T,
                GLES20.GL_CLAMP_TO_EDGE
            )
        } else {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTextureId)
        }
        try {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to upload image texture", error)
            releaseImageTexture()
        } finally {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
        }
    }

    private fun releaseImageTexture() {
        if (imageTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(imageTextureId), 0)
            imageTextureId = 0
        }
    }

    private fun createImageProgramIfAvailable() {
        if (imageProgram != 0) return
        val created = runCatching {
            GlUtil.createProgram(VERTEX_SHADER, IMAGE_FRAGMENT_SHADER)
        }.onFailure {
            Log.e(TAG, "Unable to create the image shader", it)
        }.getOrNull() ?: return
        imageProgram = created
        imagePositionHandle = GLES20.glGetAttribLocation(created, "aPosition")
        imageTexCoordHandle = GLES20.glGetAttribLocation(created, "aTexCoord")
        imageTexScaleHandle = GLES20.glGetUniformLocation(created, "uTexScaleX")
        imageTexOffsetHandle = GLES20.glGetUniformLocation(created, "uTexOffsetX")
        imageSamplerHandle = GLES20.glGetUniformLocation(created, "uTexture")
        imageProjectionModeHandle = GLES20.glGetUniformLocation(created, "uProjectionMode")
        imageHeadYawHandle = GLES20.glGetUniformLocation(created, "uHeadYawRad")
        imageHeadPitchHandle = GLES20.glGetUniformLocation(created, "uHeadPitchRad")
        imageEyeYawOffsetHandle = GLES20.glGetUniformLocation(created, "uEyeYawOffsetRad")
        imageTanHalfFovXHandle = GLES20.glGetUniformLocation(created, "uTanHalfFovX")
        imageTanHalfFovYHandle = GLES20.glGetUniformLocation(created, "uTanHalfFovY")
        imageFisheyeHalfFovHandle = GLES20.glGetUniformLocation(created, "uFisheyeHalfFovRad")
    }

    private fun renderImageFrame(pose: PoseState, mode: RenderMode) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (imageProgram == 0) return
        GLES20.glUseProgram(imageProgram)

        GLES20.glEnableVertexAttribArray(imagePositionHandle)
        GLES20.glVertexAttribPointer(
            imagePositionHandle,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            quadVertices
        )
        GLES20.glEnableVertexAttribArray(imageTexCoordHandle)
        GLES20.glVertexAttribPointer(
            imageTexCoordHandle,
            2,
            GLES20.GL_FLOAT,
            false,
            0,
            quadTexCoords
        )

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, imageTextureId)
        GLES20.glUniform1i(imageSamplerHandle, 0)
        GLES20.glUniform1i(imageProjectionModeHandle, mode.projectionMode.toShaderValue())
        GLES20.glUniform1f(imageHeadYawHandle, pose.yaw)
        GLES20.glUniform1f(imageHeadPitchHandle, pose.pitch)

        val halfWidth = outputWidth / 2
        val leftEyeWidth = halfWidth.coerceAtLeast(1)
        val rightEyeWidth = (outputWidth - halfWidth).coerceAtLeast(1)
        val safeHeight = outputHeight.coerceAtLeast(1)
        val leftAspectWidth = if (mode.packedHalfSbs) outputWidth else leftEyeWidth
        val rightAspectWidth = if (mode.packedHalfSbs) outputWidth else rightEyeWidth
        val halfConvergenceRadians =
            Math.toRadians(mode.convergenceDegrees.toDouble()).toFloat() * 0.5f
        val tanHalfFovY =
            tan(Math.toRadians((mode.perEyeFovDegrees * 0.5f).toDouble())).toFloat()
        val fisheyeSourceFovDegrees = if (mode.fisheyeSourceFovDegrees.isFinite()) {
            mode.fisheyeSourceFovDegrees.coerceIn(1f, 360f)
        } else {
            DEFAULT_FISHEYE_SOURCE_FOV_DEGREES
        }
        val fisheyeHalfFovRadians =
            Math.toRadians((fisheyeSourceFovDegrees * 0.5f).toDouble()).toFloat()

        val imageTexScale = if (imageSideBySide) 0.5f else 1f
        val leftImageTexOffset = if (imageSideBySide && mode.swapEyes) 0.5f else 0f
        val rightImageTexOffset = if (imageSideBySide && !mode.swapEyes) 0.5f else 0f

        GLES20.glViewport(0, 0, halfWidth, outputHeight)
        GLES20.glUniform1f(imageTexScaleHandle, imageTexScale)
        GLES20.glUniform1f(imageTexOffsetHandle, leftImageTexOffset)
        GLES20.glUniform1f(imageEyeYawOffsetHandle, halfConvergenceRadians)
        GLES20.glUniform1f(imageTanHalfFovYHandle, tanHalfFovY)
        GLES20.glUniform1f(
            imageTanHalfFovXHandle,
            tanHalfFovY * (leftAspectWidth.toFloat() / safeHeight.toFloat())
        )
        GLES20.glUniform1f(imageFisheyeHalfFovHandle, fisheyeHalfFovRadians)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glViewport(halfWidth, 0, outputWidth - halfWidth, outputHeight)
        GLES20.glUniform1f(imageTexScaleHandle, imageTexScale)
        GLES20.glUniform1f(imageTexOffsetHandle, rightImageTexOffset)
        GLES20.glUniform1f(imageEyeYawOffsetHandle, -halfConvergenceRadians)
        GLES20.glUniform1f(imageTanHalfFovYHandle, tanHalfFovY)
        GLES20.glUniform1f(
            imageTanHalfFovXHandle,
            tanHalfFovY * (rightAspectWidth.toFloat() / safeHeight.toFloat())
        )
        GLES20.glUniform1f(imageFisheyeHalfFovHandle, fisheyeHalfFovRadians)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(imagePositionHandle)
        GLES20.glDisableVertexAttribArray(imageTexCoordHandle)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        frameAvailable.set(true)
    }

    fun setPose(pose: PoseState) {
        latestPose = pose
    }

    fun setRenderMode(mode: RenderMode) {
        latestRenderMode = mode
    }

    fun release() {
        listener?.onVideoSurfaceDestroyed()
        surface?.release()
        surface = null
        surfaceTexture?.release()
        surfaceTexture = null
        synchronized(imageLock) {
            pendingImage = null
            pendingImageSideBySide = false
            imageGeneration += 1L
        }
        imageSideBySide = false
        imageTextureId = 0
        uploadedImageGeneration = -1L
    }

    interface Listener {
        fun onVideoSurfaceReady(surface: Surface)
        fun onVideoSurfaceDestroyed()
    }
}

data class RenderMode(
    val projectionMode: ProjectionMode = ProjectionMode.VR180,
    val swapEyes: Boolean = false,
    val convergenceDegrees: Float = 0f,
    val perEyeFovDegrees: Float = 58f,
    val fisheyeSourceFovDegrees: Float = DEFAULT_FISHEYE_SOURCE_FOV_DEGREES,
    val packedHalfSbs: Boolean = false
)

private const val DEFAULT_FISHEYE_SOURCE_FOV_DEGREES = 180f

private fun ProjectionMode.toShaderValue(): Int {
    return when (this) {
        ProjectionMode.VR180 -> 0
        ProjectionMode.VR360 -> 1
        ProjectionMode.FLAT -> 2
        ProjectionMode.VR180_FISHEYE_EQUIDISTANT -> 3
        ProjectionMode.VR180_FISHEYE_EQUISOLID -> 4
    }
}

private const val TAG = "VrSbsRenderer"

/**
 * The still-image variant of the fragment shader. Sampling a regular 2D
 * texture is the only difference; projection, head tracking, and eye
 * viewports stay identical.
 */
private val IMAGE_FRAGMENT_SHADER: String =
    FRAGMENT_SHADER.replace("samplerExternalOES", "sampler2D")

private const val VERTEX_SHADER = """
attribute vec2 aPosition;
attribute vec2 aTexCoord;
varying highp vec2 vRawCoord;

void main() {
    gl_Position = vec4(aPosition, 0.0, 1.0);
    vRawCoord = aTexCoord;
}
"""

private const val FRAGMENT_SHADER = """
#extension GL_OES_EGL_image_external : require
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying highp vec2 vRawCoord;
uniform float uTexScaleX;
uniform float uTexOffsetX;
uniform int uProjectionMode;
uniform float uHeadYawRad;
uniform float uHeadPitchRad;
uniform float uEyeYawOffsetRad;
uniform float uTanHalfFovX;
uniform float uTanHalfFovY;
uniform float uFisheyeHalfFovRad;
uniform samplerExternalOES uTexture;

const float PI = 3.1415926535897932384626433832795;
const float HALF_PI = 1.5707963267948966192313216916398;
const float TWO_PI = 6.283185307179586476925286766559;

vec3 rotateYaw(vec3 v, float angle) {
    float c = cos(angle);
    float s = sin(angle);
    return vec3(
        (c * v.x) + (s * v.z),
        v.y,
        (-s * v.x) + (c * v.z)
    );
}

vec3 rotatePitch(vec3 v, float angle) {
    float c = cos(angle);
    float s = sin(angle);
    return vec3(
        v.x,
        (c * v.y) - (s * v.z),
        (s * v.y) + (c * v.z)
    );
}

vec3 makeViewRay(vec2 eyeUv) {
    float ndcX = (eyeUv.x * 2.0) - 1.0;
    float ndcY = 1.0 - (eyeUv.y * 2.0);
    vec3 ray = normalize(vec3(ndcX * uTanHalfFovX, ndcY * uTanHalfFovY, 1.0));
    ray = rotatePitch(ray, uHeadPitchRad);
    ray = rotateYaw(ray, uHeadYawRad + uEyeYawOffsetRad);
    return ray;
}

vec2 mapVr180(vec3 ray) {
    float yaw = atan(ray.x, ray.z);
    float pitch = asin(clamp(ray.y, -1.0, 1.0));

    if (abs(yaw) > HALF_PI) {
        return vec2(-1.0, -1.0);
    }
    return vec2((yaw / PI) + 0.5, 0.5 - (pitch / PI));
}

vec2 mapVr360(vec3 ray) {
    float yaw = atan(ray.x, ray.z);
    float pitch = asin(clamp(ray.y, -1.0, 1.0));
    float wrappedYaw = mod((yaw / TWO_PI) + 0.5, 1.0);
    if (wrappedYaw < 0.0) wrappedYaw += 1.0;
    return vec2(wrappedYaw, 0.5 - (pitch / PI));
}

vec2 mapVr180FisheyeEquidistant(vec3 ray) {
    float theta = acos(clamp(ray.z, -1.0, 1.0));
    if (theta > uFisheyeHalfFovRad) {
        return vec2(-1.0, -1.0);
    }
    float xyLength = length(ray.xy);
    if (xyLength < 0.00001) {
        return vec2(0.5, 0.5);
    }
    float radius = (theta / max(uFisheyeHalfFovRad, 0.00001)) * 0.5;
    vec2 direction = vec2(ray.x, -ray.y) / xyLength;
    return vec2(0.5, 0.5) + (direction * radius);
}

vec2 mapVr180FisheyeEquisolid(vec3 ray) {
    float theta = acos(clamp(ray.z, -1.0, 1.0));
    if (theta > uFisheyeHalfFovRad) {
        return vec2(-1.0, -1.0);
    }
    float xyLength = length(ray.xy);
    if (xyLength < 0.00001) {
        return vec2(0.5, 0.5);
    }
    float radius = (sin(theta * 0.5) / max(sin(uFisheyeHalfFovRad * 0.5), 0.00001)) * 0.5;
    vec2 direction = vec2(ray.x, -ray.y) / xyLength;
    return vec2(0.5, 0.5) + (direction * radius);
}

void main() {
    vec2 eyeUv = vRawCoord;
    vec3 ray = makeViewRay(vRawCoord);
    if (uProjectionMode == 0) {
        eyeUv = mapVr180(ray);
    } else if (uProjectionMode == 1) {
        eyeUv = mapVr360(ray);
    } else if (uProjectionMode == 3) {
        eyeUv = mapVr180FisheyeEquidistant(ray);
    } else if (uProjectionMode == 4) {
        eyeUv = mapVr180FisheyeEquisolid(ray);
    }

    if (eyeUv.x < 0.0 || eyeUv.y < 0.0 || eyeUv.x > 1.0 || eyeUv.y > 1.0) {
        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    eyeUv = clamp(eyeUv, 0.0, 1.0);
    vec2 sampleUv = vec2((eyeUv.x * uTexScaleX) + uTexOffsetX, eyeUv.y);
    gl_FragColor = texture2D(uTexture, sampleUv);
}
"""
