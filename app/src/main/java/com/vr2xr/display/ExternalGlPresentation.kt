package com.vr2xr.display

import android.app.Presentation
import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.Display
import android.view.Surface
import android.widget.FrameLayout
import com.vr2xr.render.RenderMode
import com.vr2xr.render.VrSbsRenderer
import com.vr2xr.tracking.PoseState

class ExternalGlPresentation(
    context: Context,
    display: Display,
    private val listener: Listener,
    private val preferredDisplayModeId: Int? = null,
    private val forcedSurfaceWidthPixels: Int? = null,
    private val forcedSurfaceHeightPixels: Int? = null
) : Presentation(context, display) {
    private lateinit var surfaceView: GLSurfaceView
    private lateinit var renderer: VrSbsRenderer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyPreferredDisplayMode()
        surfaceView = GLSurfaceView(context).apply {
            setEGLContextClientVersion(2)
            // A fixed-size buffer is scaled by SurfaceView to fill the window, so the
            // renderer emits a true 1920x1080 Half-SBS frame even when the glasses
            // report a 3840x1080 display mode.
            val forcedWidth = forcedSurfaceWidthPixels
            val forcedHeight = forcedSurfaceHeightPixels
            if (forcedWidth != null && forcedHeight != null) {
                holder.setFixedSize(forcedWidth, forcedHeight)
            }
        }
        renderer = VrSbsRenderer(
            listener = object : VrSbsRenderer.Listener {
                override fun onVideoSurfaceReady(surface: Surface) {
                    listener.onVideoSurfaceReady(surface)
                }

                override fun onVideoSurfaceDestroyed() {
                    listener.onVideoSurfaceDestroyed()
                }
            }
        )
        surfaceView.setRenderer(renderer)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        setContentView(
            FrameLayout(context).apply {
                addView(
                    surfaceView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                )
            }
        )
    }

    private fun applyPreferredDisplayMode() {
        val modeId = preferredDisplayModeId ?: return
        val currentWindow = window ?: return
        val attributes = currentWindow.attributes
        attributes.preferredDisplayModeId = modeId
        currentWindow.attributes = attributes
    }

    override fun onStart() {
        super.onStart()
        surfaceView.onResume()
    }

    override fun onStop() {
        surfaceView.onPause()
        renderer.release()
        super.onStop()
    }

    fun setPose(pose: PoseState) {
        if (!::renderer.isInitialized) return
        renderer.setPose(pose)
    }

    fun setRenderMode(mode: RenderMode) {
        if (!::renderer.isInitialized) return
        renderer.setRenderMode(mode)
    }

    interface Listener {
        fun onVideoSurfaceReady(surface: Surface)
        fun onVideoSurfaceDestroyed()
    }
}
