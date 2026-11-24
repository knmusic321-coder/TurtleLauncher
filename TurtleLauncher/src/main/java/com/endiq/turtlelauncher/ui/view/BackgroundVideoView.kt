package com.endiq.turtlelauncher.ui.view

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import java.io.File

/**
 * Looping, muted-nothing background video player built on [TextureView].
 *
 * A plain `VideoView` is a SurfaceView: the surface is composited by the system, so
 * `setAlpha()` and `setScaleX/Y()` do not affect the picture. A TextureView is drawn
 * like any other view, which is what lets the launcher fade the clip to the same
 * translucency as the wallpaper and crop it to fill the screen instead of stretching
 * a portrait clip.
 *
 * The clip's own audio track is played unless [setMuted] was called; the caller pauses
 * playback when the launcher is not in the foreground. Muting is a per-view flag, so the
 * main-menu clip and the launch-panel clip can be silenced independently.
 */
class BackgroundVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : TextureView(context, attrs) {

    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var prepared = false
    private var pendingFile: File? = null
    private var pendingLoop = true
    private var muted = false
    private var videoWidth = 0
    private var videoHeight = 0

    private val coverMatrix = Matrix()

    init {
        surfaceTextureListener = object : SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                surface = Surface(texture)
                pendingFile?.let { file -> play(file, pendingLoop) }
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                updateCoverTransform()
            }

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                releasePlayer()
                surface?.release()
                surface = null
                return true
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        }
    }

    /** Starts [file] from the beginning, looping when [loop] is true. */
    fun play(file: File, loop: Boolean) {
        pendingFile = file
        pendingLoop = loop
        releasePlayer()
        if (!isAvailable) return

        runCatching {
            val mediaPlayer = MediaPlayer()
            player = mediaPlayer
            mediaPlayer.setDataSource(file.absolutePath)
            mediaPlayer.setSurface(surface ?: return@runCatching)
            mediaPlayer.isLooping = loop
            val volume = if (muted) 0f else 1f
            mediaPlayer.setVolume(volume, volume)
            mediaPlayer.setOnVideoSizeChangedListener { _, width, height ->
                updateCoverTransform(width, height)
            }
            mediaPlayer.setOnPreparedListener { preparedPlayer ->
                prepared = true
                preparedPlayer.start()
            }
            mediaPlayer.prepareAsync()
        }.onFailure {
            releasePlayer()
        }
    }

    /** Turns this view's soundtrack off (or back on) without restarting the clip. */
    fun setMuted(muted: Boolean) {
        this.muted = muted
        runCatching {
            val volume = if (muted) 0f else 1f
            player?.setVolume(volume, volume)
        }
    }

    fun isMuted(): Boolean = muted

    fun pausePlayback() {
        runCatching {
            if (player?.isPlaying == true) player?.pause()
        }
    }

    fun resumePlayback() {
        runCatching {
            if (prepared && player?.isPlaying != true) player?.start()
        }
    }

    fun stopPlayback() {
        pendingFile = null
        releasePlayer()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateCoverTransform()
    }

    /** Scales the texture so it covers the view, cropping the overflowing edges. */
    private fun updateCoverTransform(width: Int = videoWidth, height: Int = videoHeight) {
        if (width <= 0 || height <= 0 || this.width <= 0 || this.height <= 0) return
        videoWidth = width
        videoHeight = height

        val viewRatio = this.width.toFloat() / this.height
        val videoRatio = width.toFloat() / height
        val scaleX: Float
        val scaleY: Float
        if (videoRatio > viewRatio) {
            scaleX = videoRatio / viewRatio
            scaleY = 1f
        } else {
            scaleX = 1f
            scaleY = viewRatio / videoRatio
        }

        coverMatrix.setScale(scaleX, scaleY)
        coverMatrix.postTranslate(
            (this.width - this.width * scaleX) / 2f,
            (this.height - this.height * scaleY) / 2f
        )
        setTransform(coverMatrix)
    }

    private fun releasePlayer() {
        prepared = false
        runCatching { player?.stop() }
        runCatching { player?.release() }
        player = null
    }

    override fun onDetachedFromWindow() {
        releasePlayer()
        super.onDetachedFromWindow()
    }
}
