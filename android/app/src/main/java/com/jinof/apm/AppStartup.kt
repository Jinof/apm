package com.jinof.apm

import android.animation.ValueAnimator
import android.provider.Settings
import android.view.Choreographer
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.core.view.doOnPreDraw

internal class AppStartup(private val activity: ComponentActivity, content: @Composable () -> Unit) {
    private val root = FrameLayout(activity)
    private val splash = activity.layoutInflater.inflate(R.layout.app_startup, root, false)
    private var firstFrameDrawn = false
    private var catalogReady = false
    private var page: (@Composable () -> Unit)? = content

    init {
        root.addView(splash)
        activity.setContentView(root)
        // Give the native indicator a frame on RenderThread before composing the album.
        splash.doOnPreDraw {
            splash.post {
                firstFrameDrawn = true
                showPageIfReady()
            }
        }
    }

    fun onCatalogReady() {
        catalogReady = true
        showPageIfReady()
    }

    private fun showPageIfReady() {
        val content = page ?: return
        if (!firstFrameDrawn || !catalogReady || activity.isFinishing || activity.isDestroyed) return
        page = null
        val album = ComposeView(activity).apply {
            id = R.id.apm_page
            setContent(content = content)
        }
        root.addView(album, 0)
        album.doOnPreDraw {
            // Initial Compose layout can use up a frame. Start the fade on a fresh vsync
            // after the first frame with both layers has been submitted to the renderer.
            if (album.isHardwareAccelerated) {
                album.viewTreeObserver.registerFrameCommitCallback { splash.postOnAnimation(::fadeSplash) }
            } else {
                splash.postOnAnimation(::fadeSplash)
            }
        }
    }

    private fun fadeSplash() {
        val scale = Settings.Global.getFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val durationNanos = 180_000_000L * scale
        if (!ValueAnimator.areAnimatorsEnabled() || durationNanos <= 0f) {
            finishHandoff()
            return
        }
        val clock = Choreographer.getInstance()
        val easing = AccelerateDecelerateInterpolator()
        var previousFrame = 0L
        var progress = 0f
        clock.postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!splash.isAttachedToWindow) return
                if (previousFrame != 0L) {
                    // Thumbnail uploads may miss a vsync. Keep intermediate opacities visible
                    // instead of letting one late frame consume the entire fade.
                    val step = ((frameTimeNanos - previousFrame) / durationNanos).coerceAtMost(0.125f)
                    progress = (progress + step).coerceAtMost(1f)
                    splash.alpha = 1f - easing.getInterpolation(progress)
                }
                previousFrame = frameTimeNanos
                when {
                    progress >= 1f -> finishHandoff()
                    progress > 0f && splash.isHardwareAccelerated ->
                        splash.viewTreeObserver.registerFrameCommitCallback { clock.postFrameCallback(this) }
                    else -> clock.postFrameCallback(this)
                }
            }
        })
    }

    private fun finishHandoff() {
        root.removeView(splash)
        activity.reportFullyDrawn()
    }
}
