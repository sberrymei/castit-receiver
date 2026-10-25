package io.sberry.castitreceiver.airplay

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import io.sberry.castitreceiver.airplay.util.Logger

class AirPlaySurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    private val textureView = TextureView(context).apply { isOpaque = false }
    @Volatile private var surface: Surface? = null
    private var lastDimensions: List<Int> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            applyAspectFit()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        addView(textureView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                surface = Surface(texture)
                lastDimensions = emptyList()
                applyAspectFit()
                Logger.d("AirPlaySurfaceView: texture available ${width}x${height}")
            }
            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                lastDimensions = emptyList()
                applyAspectFit()
            }
            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                val oldSurface = surface
                surface = null
                oldSurface?.release()
                Logger.d("AirPlaySurfaceView: texture destroyed")
                return true
            }
            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
                StreamStats.videoTextureUpdates.incrementAndGet()
            }
        }
    }

    fun getSurface(): Surface? = surface

    private fun applyAspectFit() {
        StreamStats.displayRefreshHz = display?.refreshRate ?: 0f
        if (width <= 0 || height <= 0) return
        val dimensions = listOf(StreamStats.videoWidth, StreamStats.videoHeight, width, height)
        if (dimensions == lastDimensions) return
        lastDimensions = dimensions
        val (sx, sy) = VideoAspectFit.scale(dimensions[0], dimensions[1], width, height)
        textureView.setTransform(Matrix().apply {
            setScale(sx, sy, width / 2f, height / 2f)
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(tick)
    }
    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }
    companion object { private const val REFRESH_MS = 200L }
}
