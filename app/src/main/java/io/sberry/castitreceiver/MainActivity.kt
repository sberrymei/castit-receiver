package io.sberry.castitreceiver

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.annotation.OptIn
import androidx.compose.foundation.Image
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import master.flame.danmaku.controller.DrawHandler
import master.flame.danmaku.danmaku.model.BaseDanmaku
import master.flame.danmaku.danmaku.model.IDisplayer
import master.flame.danmaku.danmaku.model.IDanmakus
import master.flame.danmaku.danmaku.model.android.DanmakuContext
import master.flame.danmaku.danmaku.model.android.Danmakus
import master.flame.danmaku.danmaku.parser.BaseDanmakuParser
import master.flame.danmaku.ui.widget.DanmakuView
import org.json.JSONArray
import org.json.JSONObject
import java.net.NetworkInterface
import java.util.Collections
import io.sberry.castitreceiver.airplay.AirPlayReceiver
import io.sberry.castitreceiver.airplay.AirPlaySurfaceView
import io.sberry.castitreceiver.airplay.ProtocolState

data class DanmakuItemData(
    val text: String,
    val timeMs: Long,
    val color: Int = android.graphics.Color.WHITE,
    val type: Int = BaseDanmaku.TYPE_SCROLL_RL
)

data class DanmakuWarning(
    val type: String,
    val startTimeSec: Double,
    val endTimeSec: Double,
    val text: String
)

class SimpleDanmakuParser(private val items: List<DanmakuItemData>) : BaseDanmakuParser() {
    override fun parse(): IDanmakus {
        val danmakus = Danmakus()
        val context = mContext ?: return danmakus
        val densityFactor = if (mDispDensity <= 0.6f) 1.2f else mDispDensity
        for (item in items) {
            val danmaku = context.mDanmakuFactory.createDanmaku(item.type, context) ?: continue
            danmaku.flags = context.mGlobalFlagValues
            danmaku.text = item.text
            danmaku.padding = 8
            danmaku.priority = 1
            danmaku.isLive = false
            danmaku.setTime(item.timeMs)
            danmaku.textSize = 24f * densityFactor
            danmaku.textColor = item.color
            danmaku.textShadowColor = android.graphics.Color.BLACK
            if (mTimer != null) {
                danmaku.timer = mTimer
            }
            danmakus.addItem(danmaku)
        }
        return danmakus
    }
}

class MainActivity : ComponentActivity(), ReceiverServer.Listener {

    private var exoPlayer: ExoPlayer? = null
    private var server: ReceiverServer? = null
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var airPlayReceiver: AirPlayReceiver? = null
    private var airPlaySurfaceView: AirPlaySurfaceView? = null

    // Compose state variables
    private var isAirPlayActive by mutableStateOf(false)
    private var airPlaySenderName by mutableStateOf<String?>(null)
    private var activeVideoUrl by mutableStateOf<String?>(null)
    private var activeTitle by mutableStateOf("")
    private var ipAddress by mutableStateOf("Locating IP...")
    private var isPlayingState by mutableStateOf(false)
    private var isBufferingState by mutableStateOf(false)
    private var errorMessage by mutableStateOf<String?>(null)
    private var speedState by mutableStateOf(1.0f)
    private var isEndedState by mutableStateOf(false)
    private var danmakuMode by mutableStateOf(2)
    private var danmakuList by mutableStateOf<List<DanmakuItemData>>(emptyList())
    private var warningList by mutableStateOf<List<DanmakuWarning>>(emptyList())
    private var activeWarning by mutableStateOf<DanmakuWarning?>(null)
    private var adRemainingSeconds by mutableStateOf<Int?>(null)
    private var adCountdownJob: kotlinx.coroutines.Job? = null

    private var danmakuContext: DanmakuContext? = null
    private var danmakuView: DanmakuView? = null
    private var pendingSeekPositionMs: Long? = null

    // Thread-safe player variables (accessed from background thread)
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    @Volatile private var currentPositionMs: Long = 0
    @Volatile private var anchorPositionMs: Long = 0
    @Volatile private var anchorUptimeMs: Long = 0
    @Volatile private var durationMs: Long = 0
    @Volatile private var volumeState: Float = 1.0f

    // Thread-safe backing variables for status endpoint (read from background thread)
    @Volatile private var isPlayingBacking: Boolean = false
    @Volatile private var activeTitleBacking: String = ""
    @Volatile private var errorMessageBacking: String? = null
    @Volatile private var speedBacking: Float = 1.0f
    @Volatile private var isEndedBacking: Boolean = false
    @Volatile private var videoUrlBacking: String? = null
    @Volatile private var refererBacking: String? = null

    private val positionUpdater = object : Runnable {
        override fun run() {
            exoPlayer?.let { player ->
                if (player.isPlaying) {
                    val pos = player.currentPosition
                    currentPositionMs = pos
                    durationMs = player.duration.let { if (it < 0) 0 else it }

                    // Recalibrate anchor if drift between extrapolated position and player position exceeds 150ms
                    val now = android.os.SystemClock.uptimeMillis()
                    val estimated = if (anchorUptimeMs > 0L) {
                        anchorPositionMs + ((now - anchorUptimeMs) * speedState).toLong()
                    } else {
                        pos
                    }
                    if (Math.abs(pos - estimated) > 150L) {
                        anchorPositionMs = pos
                        anchorUptimeMs = now
                    }
                    
                    // Update active warning
                    val currentSec = currentPositionMs / 1000.0
                    val active = warningList.firstOrNull { warning ->
                        currentSec >= warning.startTimeSec && currentSec <= warning.endTimeSec
                    }
                    if (activeWarning != active) {
                        activeWarning = active
                    }

                    // For large jumps/drifts (>3000ms), perform explicit seek
                    if (danmakuMode == 2) {
                        try {
                            danmakuView?.let { dView ->
                                if (dView.isPrepared) {
                                    val dTime = dView.currentTime
                                    val drift = Math.abs(currentPositionMs - dTime)
                                    if (drift > 3000L) {
                                        if (isPlayingState) {
                                            dView.seekTo(currentPositionMs)
                                            dView.start(currentPositionMs)
                                        } else {
                                            dView.seekTo(currentPositionMs)
                                            dView.pause()
                                        }
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            Log.e("CastItDanmaku", "Error syncing danmakuView in positionUpdater", t)
                        }
                    }
                }
            }
            mainHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("CastItReceiver", "Intercepted background exception on thread: ${thread.name}", throwable)
            if (thread.name != "DFM Cache-Building Thread" && !thread.name.contains("DFM")) {
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
        
        // Keep the screen turned on (critical for TV applications)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Setup environment details
        ipAddress = getLocalIpAddress()

        // Handle DIAL startup intents
        handleDialIntent(intent)

        // Initialize ExoPlayer with browser User-Agent
        exoPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(
                        androidx.media3.datasource.DefaultHttpDataSource.Factory()
                            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                            .setAllowCrossProtocolRedirects(true)
                    )
            )
            .build().apply {
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        isPlayingState = isPlaying
                        isPlayingBacking = isPlaying
                        if (isPlaying) {
                            errorMessage = null
                            errorMessageBacking = null
                            val pos = exoPlayer?.currentPosition ?: currentPositionMs
                            currentPositionMs = pos
                            anchorPositionMs = pos
                            anchorUptimeMs = android.os.SystemClock.uptimeMillis()
                            mainHandler.post(positionUpdater)
                            if (danmakuMode == 2) {
                                try {
                                    danmakuView?.let { dView ->
                                        if (dView.isPrepared) {
                                            dView.show()
                                            if (dView.isPaused) {
                                                dView.resume()
                                            } else {
                                                dView.start(pos)
                                            }
                                        }
                                    }
                                } catch (t: Throwable) {
                                    Log.e("CastItDanmaku", "Error starting danmakuView onIsPlayingChanged", t)
                                }
                            }
                        } else {
                            mainHandler.removeCallbacks(positionUpdater)
                            exoPlayer?.let {
                                currentPositionMs = it.currentPosition
                            }
                            anchorPositionMs = currentPositionMs
                            anchorUptimeMs = android.os.SystemClock.uptimeMillis()
                            try {
                                danmakuView?.let { dView ->
                                    if (dView.isPrepared) {
                                        dView.pause()
                                    }
                                }
                            } catch (t: Throwable) {
                                Log.e("CastItDanmaku", "Error pausing danmakuView", t)
                            }
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        isBufferingState = (playbackState == Player.STATE_BUFFERING)
                        val isEnded = (playbackState == Player.STATE_ENDED)
                        isEndedState = isEnded
                        isEndedBacking = isEnded
                        if (playbackState == Player.STATE_READY) {
                            errorMessage = null
                            errorMessageBacking = null
                            if (isPlayingState) {
                                val pos = exoPlayer?.currentPosition ?: currentPositionMs
                                currentPositionMs = pos
                                anchorPositionMs = pos
                                anchorUptimeMs = android.os.SystemClock.uptimeMillis()
                            }
                        }
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Log.e("CastItReceiver", "ExoPlayer Error: ${error.message}", error)
                        val errorStr = "Error: ${error.localizedMessage ?: "Playback failed"}"
                        errorMessage = errorStr
                        errorMessageBacking = errorStr
                        isPlayingState = false
                        isPlayingBacking = false
                    }
                })
            }

        // Start HTTP Server on port 8080
        try {
            server = ReceiverServer(8080, this)
            server?.start()
            Log.i("CastItReceiver", "HTTP Server started on port 8080")
        } catch (e: Exception) {
            Log.e("CastItReceiver", "Failed to start HTTP server", e)
        }

        // Register Bonjour Service
        registerNsdService(8080)

        // Initialize AirPlay SurfaceView and Receiver
        airPlaySurfaceView = AirPlaySurfaceView(this)
        startAirPlayReceiver()

        // Render screen
        setContent {
            ReceiverScreen()
        }
    }

    @Composable
    fun ReceiverScreen() {
        val currentVideoUrl = activeVideoUrl
        val isMirroring = isAirPlayActive
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0A0A0C)) // Premium Dark
        ) {
            if (isMirroring) {
                // AirPlay Screen Mirroring View
                AirPlayMirrorView()
            } else if (currentVideoUrl != null) {
                if (currentVideoUrl == "pending_ad") {
                    PendingAdScreen()
                } else {
                    // Playback State: Fullscreen Video Player
                    VideoPlayerView()
                    
                    // Warning Banner Overlay
                    val warning = activeWarning
                    if (warning != null && danmakuMode > 0) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = 48.dp),
                            contentAlignment = Alignment.TopCenter
                        ) {
                            val isScary = warning.type.lowercase() == "scary"
                            val glowColor = if (isScary) Color(0xFFFF334B) else Color(0xFFFFD300)
                            val titleText = if (isScary) "前方高能预警，注意避让！" else "前方名场面预警，精彩来袭！"
                            val iconText = if (isScary) "⚠️" else "🔔"
                            
                            Row(
                                modifier = Modifier
                                    .background(
                                        color = Color(0xDD0C0C0E),
                                        shape = RoundedCornerShape(16.dp)
                                    )
                                    .border(
                                        width = 1.5.dp,
                                        color = glowColor.copy(alpha = 0.5f),
                                        shape = RoundedCornerShape(16.dp)
                                    )
                                    .padding(horizontal = 24.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    text = iconText,
                                    color = Color.White,
                                    fontSize = 20.sp
                                )
                                Text(
                                    text = titleText,
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                            }
                        }
                    }
                    
                    // Overlay when paused, buffering, or error
                    if (!isPlayingState || isBufferingState || errorMessage != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.5f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                                when {
                                    errorMessage != null -> {
                                        Text(
                                            text = "PLAYBACK ERROR",
                                            color = Color(0xFFE57373),
                                            fontSize = 24.sp,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 2.sp
                                        )
                                    }
                                    isBufferingState -> {
                                        CircularProgressIndicator(
                                            color = Color.White,
                                            strokeWidth = 4.dp,
                                            modifier = Modifier.size(40.dp)
                                        )
                                    }
                                    else -> {
                                        PlayIcon()
                                    }
                                }
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(
                                    text = errorMessage ?: activeTitle,
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }
                }
            } else {
                // Standby State: Premium Dark TV Dashboard
                StandbyDashboard()
            }
        }
    }

    @Composable
    private fun PlayIcon(color: Color = Color.White.copy(alpha = 0.8f)) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(32.dp)) {
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, size.height / 2f)
                lineTo(0f, size.height)
                close()
            }
            drawPath(path, color = color)
        }
    }

    @Composable
    fun PendingAdScreen() {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .size(400.dp)
                    .align(Alignment.TopStart)
                    .offset(x = (-100).dp, y = (-100).dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFF8B263E).copy(alpha = 0.15f), Color.Transparent)
                        ),
                        shape = RoundedCornerShape(200.dp)
                    )
            )
            Box(
                modifier = Modifier
                    .size(400.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = 100.dp, y = 100.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFF008B99).copy(alpha = 0.15f), Color.Transparent)
                        ),
                        shape = RoundedCornerShape(200.dp)
                    )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(48.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = R.drawable.logo_filled),
                contentDescription = "CastIt Logo",
                modifier = Modifier
                    .size(120.dp)
                    .padding(bottom = 16.dp)
            )

            Text(
                text = "CastIt - Receiver",
                color = Color.White,
                fontSize = 48.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = "WAITING FOR ADS ON PHONE",
                color = Color(0xFFFF8C00), // Glowing Neon Orange
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp
            )
            
            val remaining = adRemainingSeconds
            if (remaining != null && remaining > 0) {
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .background(Color(0xFFFF8C00).copy(alpha = 0.15f), shape = RoundedCornerShape(12.dp))
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "⏱",
                        fontSize = 18.sp
                    )
                    Text(
                        text = "${remaining}s remaining",
                        color = Color(0xFFFF8C00),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(28.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .background(Color.White.copy(alpha = 0.05f), shape = RoundedCornerShape(16.dp))
                    .padding(horizontal = 32.dp, vertical = 24.dp)
            ) {
                if (activeTitle.isNotEmpty() && activeTitle != "Pending on ads to finish on app" && activeTitle != "Video Stream") {
                    Text(
                        text = activeTitle,
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }
                
                Text(
                    text = "Playback will begin automatically after ads finish.",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Normal
                )
            }
        }
    }

    @Composable
    fun StandbyDashboard() {
        Box(modifier = Modifier.fillMaxSize()) {
            // Neon Night background accents
            Box(
                modifier = Modifier
                    .size(400.dp)
                    .align(Alignment.TopStart)
                    .offset(x = (-100).dp, y = (-100).dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFF8B263E).copy(alpha = 0.15f), Color.Transparent)
                        ),
                        shape = RoundedCornerShape(200.dp)
                    )
            )
            Box(
                modifier = Modifier
                    .size(400.dp)
                    .align(Alignment.BottomEnd)
                    .offset(x = 100.dp, y = 100.dp)
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(Color(0xFF008B99).copy(alpha = 0.15f), Color.Transparent)
                        ),
                        shape = RoundedCornerShape(200.dp)
                    )
            )

            // Center Content
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(48.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painter = painterResource(id = R.drawable.logo_filled),
                    contentDescription = "CastIt Logo",
                    modifier = Modifier
                        .size(120.dp)
                        .padding(bottom = 16.dp)
                )
                
                Text(
                    text = "CastIt - Receiver",
                    color = Color.White,
                    fontSize = 48.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 1.sp
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = "READY TO CAST",
                    color = Color(0xFF00F0FF), // Neon Cyan
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 4.sp
                )
                
                Spacer(modifier = Modifier.height(36.dp))

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.05f), shape = RoundedCornerShape(16.dp))
                        .padding(horizontal = 32.dp, vertical = 24.dp)
                ) {
                    Text(
                        text = "Open CastIt on your phone to connect",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium
                    )
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    val deviceName = remember { getDeviceName() }
                    Text(
                        text = "Device Name: $deviceName",
                        color = Color(0xFF00F0FF), // Neon Cyan
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "IP Address: $ipAddress",
                        color = Color(0xFFD300C5), // Neon Purple
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
            }

            // Version info at Bottom-Right
            val versionString = remember { getAppVersionString() }
            Text(
                text = versionString,
                color = Color.White.copy(alpha = 0.35f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.sp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
            )
        }
    }

class SafeDanmakuView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null,
    defStyleAttr: Int = 0
) : DanmakuView(context, attrs, defStyleAttr) {

    fun enableExternalSync() {
        handler?.let { h ->
            h.enableNonBlockMode(true)
            h.setIdleSleep(false)
        }
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        try {
            super.onDraw(canvas)
        } catch (t: Throwable) {
            Log.e("SafeDanmakuView", "Caught exception during DanmakuView.onDraw", t)
        }
    }
}

    @Composable
    fun AirPlayMirrorView() {
        var showBadge by remember { mutableStateOf(true) }

        LaunchedEffect(Unit) {
            delay(4000)
            showBadge = false
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            airPlaySurfaceView?.let { sView ->
                AndroidView(
                    factory = {
                        (sView.parent as? android.view.ViewGroup)?.removeView(sView)
                        sView
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
            
            // Subtle sender badge overlay in corner - auto-fades after 4 seconds
            AnimatedVisibility(
                visible = showBadge,
                enter = fadeIn(),
                exit = fadeOut(animationSpec = tween(800)),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(24.dp)
            ) {
                val sender = airPlaySenderName
                val hasDistinctSender = !sender.isNullOrBlank() && !sender.equals("AirPlay", ignoreCase = true)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.65f), shape = RoundedCornerShape(12.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(
                        text = "AirPlay",
                        color = Color(0xFF00F0FF),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    if (hasDistinctSender) {
                        Text(
                            text = sender!!,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    }

    @OptIn(UnstableApi::class)
    @Composable
    fun VideoPlayerView() {
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { context ->
                    val pView = android.view.LayoutInflater.from(context).inflate(R.layout.player_view, null) as PlayerView
                    pView.apply {
                        useController = false
                        player = exoPlayer
                        keepScreenOn = true
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            key(activeVideoUrl) {
                if (danmakuMode == 2) {
                    AndroidView(
                        factory = { context ->
                            SafeDanmakuView(context).apply {
                                show()
                                bringToFront()
                                enableDanmakuDrawingCache(false)
                                val maxLinesPair = HashMap<Int, Int>()
                                maxLinesPair[BaseDanmaku.TYPE_SCROLL_RL] = 6
                                val overlappingEnablePair = HashMap<Int, Boolean>()
                                overlappingEnablePair[BaseDanmaku.TYPE_SCROLL_RL] = true
                                overlappingEnablePair[BaseDanmaku.TYPE_FIX_TOP] = true

                                val dContext = DanmakuContext.create().apply {
                                    setDanmakuStyle(IDisplayer.DANMAKU_STYLE_STROKEN, 3f)
                                    setDuplicateMergingEnabled(false)
                                    setScrollSpeedFactor(1.2f)
                                    setScaleTextSize(1.2f)
                                    setMaximumLines(maxLinesPair)
                                    preventOverlapping(overlappingEnablePair)
                                }
                                danmakuContext = dContext
                                danmakuView = this
                                applyDanmakuListToView()
                            }
                        },
                        modifier = Modifier.fillMaxSize().padding(top = 24.dp)
                    )
                }
            }
        }
    }

    // MARK: - Server Listener callbacks (invoked from background threads)

    @OptIn(UnstableApi::class)
    override fun onCast(url: String, title: String, positionMs: Long, referer: String?, userAgent: String?, adDurationSec: Double) {
        runOnUiThread {
            try {
                Log.e("CastItReceiver", "Casting URL: $url with title: $title at position: $positionMs ms (referer: $referer, userAgent: $userAgent, adDuration: $adDurationSec)")
                activeVideoUrl = url
                activeTitle = title
                activeTitleBacking = title
                errorMessage = null
                errorMessageBacking = null
                isEndedState = false
                isEndedBacking = false
                currentPositionMs = positionMs
                pendingSeekPositionMs = if (positionMs > 0) positionMs else null
                durationMs = 0
                videoUrlBacking = url
                refererBacking = referer
                danmakuList = emptyList()
                warningList = emptyList()
                activeWarning = null
                try {
                    danmakuView?.stop()
                    danmakuView?.clearDanmakusOnScreen()
                    danmakuView?.removeAllDanmakus(true)
                } catch (e: Exception) {
                    Log.e("CastItDanmaku", "Error clearing danmakuView onCast", e)
                }
                
                if (url == "pending_ad") {
                    isBufferingState = false
                    isPlayingState = false
                    isPlayingBacking = false
                    exoPlayer?.let { player ->
                        player.stop()
                        player.clearMediaItems()
                    }
                    adCountdownJob?.cancel()
                    if (adDurationSec > 0.0) {
                        val totalSec = Math.round(adDurationSec).toInt()
                        adRemainingSeconds = totalSec
                        adCountdownJob = lifecycleScope.launch {
                            for (sec in totalSec downTo 1) {
                                adRemainingSeconds = sec
                                kotlinx.coroutines.delay(1000)
                            }
                            adRemainingSeconds = 0
                        }
                    } else {
                        adRemainingSeconds = null
                    }
                } else {
                    adCountdownJob?.cancel()
                    adRemainingSeconds = null
                    isBufferingState = true
                    exoPlayer?.let { player ->
                        player.stop()
                        player.clearMediaItems()
                        
                        val httpDataSourceFactory = androidx.media3.datasource.DefaultHttpDataSource.Factory()
                            .setAllowCrossProtocolRedirects(true)
                        
                        if (!userAgent.isNullOrEmpty()) {
                            httpDataSourceFactory.setUserAgent(userAgent)
                        } else {
                            httpDataSourceFactory.setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                        }
                        
                        val isYouTube = url.contains("googlevideo.com") || url.contains("youtube.com")
                        val isVimeo = url.contains("vimeocdn.com") || url.contains("vimeo.com")
                        val headers = mutableMapOf<String, String>()
                        
                        if (isYouTube) {
                            headers["Referer"] = "https://www.youtube.com/"
                            headers["Origin"] = "https://www.youtube.com"
                        } else if (isVimeo) {
                            if (!referer.isNullOrEmpty()) {
                                headers["Referer"] = referer
                                val origin = try {
                                    val uri = android.net.Uri.parse(referer)
                                    if (uri.scheme != null && uri.host != null) {
                                        "${uri.scheme}://${uri.host}"
                                    } else {
                                        referer
                                    }
                                } catch (e: Exception) {
                                    referer
                                }
                                headers["Origin"] = origin
                            }
                        } else {
                            if (!referer.isNullOrEmpty()) {
                                headers["Referer"] = referer
                                val origin = try {
                                    val uri = android.net.Uri.parse(referer)
                                    if (uri.scheme != null && uri.host != null) {
                                        "${uri.scheme}://${uri.host}"
                                    } else {
                                        referer
                                    }
                                } catch (e: Exception) {
                                    referer
                                }
                                headers["Origin"] = origin
                            }
                        }
                        if (headers.isNotEmpty()) {
                            httpDataSourceFactory.setDefaultRequestProperties(headers)
                        }
                        
                        val mediaSourceFactory = androidx.media3.exoplayer.source.DefaultMediaSourceFactory(this@MainActivity)
                            .setDataSourceFactory(httpDataSourceFactory)
                        val mediaSource = mediaSourceFactory.createMediaSource(MediaItem.fromUri(url))
                        
                        player.setMediaSource(mediaSource)
                        player.setPlaybackSpeed(speedState)
                        player.volume = toPerceptualVolume(volumeState)
                        player.prepare()
                        if (positionMs > 0) {
                            player.seekTo(positionMs)
                        }
                        player.play()
                    }
                }
            } catch (e: Exception) {
                Log.e("CastItReceiver", "Error in onCast execution", e)
                errorMessage = "Playback error: ${e.localizedMessage ?: "Failed to play media"}"
                errorMessageBacking = errorMessage
            }
        }
    }

    override fun onPlay() {
        runOnUiThread {
            Log.i("CastItReceiver", "Play requested")
            exoPlayer?.play()
            val pos = exoPlayer?.currentPosition ?: currentPositionMs
            currentPositionMs = pos
            anchorPositionMs = pos
            anchorUptimeMs = android.os.SystemClock.uptimeMillis()
            if (danmakuMode == 2) {
                try {
                    danmakuView?.let { dView ->
                        if (dView.isPrepared) {
                            dView.show()
                            if (dView.isPaused) {
                                dView.resume()
                            } else {
                                dView.start(pos)
                            }
                        }
                    }
                } catch (t: Throwable) {
                    Log.e("CastItDanmaku", "Error starting danmakuView onPlay", t)
                }
            }
        }
    }

    override fun onRemotePause() {
        runOnUiThread {
            Log.i("CastItReceiver", "Pause requested")
            exoPlayer?.pause()
            val pos = exoPlayer?.currentPosition ?: currentPositionMs
            currentPositionMs = pos
            anchorPositionMs = pos
            anchorUptimeMs = android.os.SystemClock.uptimeMillis()
            try {
                danmakuView?.let { dView ->
                    if (dView.isPrepared) {
                        dView.pause()
                    }
                }
            } catch (t: Throwable) {
                Log.e("CastItDanmaku", "Error pausing danmakuView onRemotePause", t)
            }
        }
    }

    override fun onSeek(positionMs: Long) {
        runOnUiThread {
            Log.i("CastItReceiver", "Seek requested to: $positionMs ms")
            currentPositionMs = positionMs
            anchorPositionMs = positionMs
            anchorUptimeMs = android.os.SystemClock.uptimeMillis()
            pendingSeekPositionMs = positionMs
            exoPlayer?.seekTo(positionMs)
            try {
                danmakuView?.let { dView ->
                    if (dView.isPrepared) {
                        if (isPlayingState && danmakuMode == 2) {
                            dView.seekTo(positionMs)
                            dView.start(positionMs)
                        } else {
                            dView.seekTo(positionMs)
                            dView.pause()
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.e("CastItDanmaku", "Error seeking danmakuView onSeek", t)
            }
        }
    }

    private fun toPerceptualVolume(linear: Float): Float {
        val clamped = linear.coerceIn(0f, 1f)
        // Convert linear UI slider [0..1] to logarithmic/perceptual audio volume.
        // Human ear perceives sound logarithmically (Weber-Fechner law).
        // A quadratic curve (x^2) maps 50% slider to 0.25 amplitude (-12 dB, roughly half perceived loudness),
        // giving a smooth, natural volume change across the entire slider range.
        return clamped * clamped
    }

    override fun onVolume(volume: Float) {
        runOnUiThread {
            Log.i("CastItReceiver", "Volume requested: $volume")
            volumeState = volume
            exoPlayer?.volume = toPerceptualVolume(volume)
        }
    }

    override fun onSpeed(speed: Float) {
        runOnUiThread {
            Log.i("CastItReceiver", "Speed requested: $speed")
            speedState = speed
            speedBacking = speed
            exoPlayer?.setPlaybackSpeed(speed)
            val currentPos = exoPlayer?.currentPosition ?: currentPositionMs
            currentPositionMs = currentPos
            anchorPositionMs = currentPos
            anchorUptimeMs = android.os.SystemClock.uptimeMillis()
        }
    }

    override fun onDanmakuConfig(mode: Int) {
        runOnUiThread {
            Log.i("CastItReceiver", "Danmaku config requested: mode=$mode")
            danmakuMode = mode
            try {
                if (mode == 2) {
                    danmakuView?.let { dView ->
                        if (dView.isPrepared) {
                            dView.show()
                            if (isPlayingState) {
                                if (dView.isPaused) {
                                    dView.resume()
                                } else {
                                    dView.start(exoPlayer?.currentPosition ?: currentPositionMs)
                                }
                            } else {
                                dView.pause()
                            }
                        }
                    }
                } else {
                    danmakuView?.hide()
                }
            } catch (e: Exception) {
                Log.e("CastItDanmaku", "Error toggling danmakuView config", e)
            }
        }
    }

    override fun onDanmakuWarnings(warningsJson: String) {
        runOnUiThread {
            Log.i("CastItReceiver", "Received warnings JSON: ${warningsJson.length} chars")
            try {
                val list = ArrayList<DanmakuWarning>()
                val array = JSONArray(warningsJson)
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    list.add(
                        DanmakuWarning(
                            type = obj.getString("type"),
                            startTimeSec = obj.getDouble("startTimeSec"),
                            endTimeSec = obj.getDouble("endTimeSec"),
                            text = obj.getString("text")
                        )
                    )
                }
                warningList = list
                Log.i("CastItReceiver", "Parsed ${list.size} warning intervals")
            } catch (e: Exception) {
                Log.e("CastItReceiver", "Failed to parse warnings JSON", e)
            }
        }
    }

    private fun applyDanmakuListToView() {
        val dView = danmakuView ?: return
        val currentList = danmakuList ?: return
        if (currentList.isEmpty()) return

        Log.e("CastItDanmaku", "applyDanmakuListToView: Applying current list of ${currentList.size} items to danmakuView")
        try {
            if (dView.isPrepared) {
                dView.release()
            }
        } catch (e: Exception) {
            Log.e("CastItDanmaku", "Error releasing/clearing dView in applyDanmakuListToView", e)
        }

        dView.setCallback(object : DrawHandler.Callback {
            override fun prepared() {
                runOnUiThread {
                    try {
                        (dView as? SafeDanmakuView)?.enableExternalSync()
                        dView.show()
                        val pos = pendingSeekPositionMs ?: (exoPlayer?.currentPosition ?: currentPositionMs)
                        pendingSeekPositionMs = null
                        Log.e("CastItDanmaku", "DanmakuView prepared & started at ${pos}ms with ${currentList.size} items")
                        if (isPlayingState && danmakuMode == 2) {
                            dView.start(pos)
                        } else {
                            dView.seekTo(pos)
                            dView.pause()
                        }
                    } catch (t: Throwable) {
                        Log.e("CastItDanmaku", "Error starting DanmakuView in prepared callback", t)
                    }
                }
            }
            override fun updateTimer(timer: master.flame.danmaku.danmaku.model.DanmakuTimer?) {
                if (timer == null) return
                val basePos = anchorPositionMs
                val baseUptime = anchorUptimeMs
                val spd = speedBacking
                val playing = isPlayingBacking
                val currentVideoPos = if (playing && baseUptime > 0L) {
                    val elapsed = android.os.SystemClock.uptimeMillis() - baseUptime
                    basePos + (elapsed * spd).toLong()
                } else {
                    basePos
                }
                timer.update(currentVideoPos)
            }
            override fun danmakuShown(danmaku: BaseDanmaku?) {}
            override fun drawingFinished() {}
        })

        val parser = SimpleDanmakuParser(currentList)
        val dContext = danmakuContext ?: DanmakuContext.create().apply {
            setDanmakuStyle(IDisplayer.DANMAKU_STYLE_STROKEN, 3f)
            setDuplicateMergingEnabled(false)
            setScrollSpeedFactor(1.2f)
            setScaleTextSize(1.2f)
            val maxLinesPair = HashMap<Int, Int>()
            maxLinesPair[BaseDanmaku.TYPE_SCROLL_RL] = 6
            val overlappingEnablePair = HashMap<Int, Boolean>()
            overlappingEnablePair[BaseDanmaku.TYPE_SCROLL_RL] = true
            overlappingEnablePair[BaseDanmaku.TYPE_FIX_TOP] = true
            setMaximumLines(maxLinesPair)
            preventOverlapping(overlappingEnablePair)
        }.also {
            danmakuContext = it
        }

        dView.prepare(parser, dContext)
        (dView as? SafeDanmakuView)?.enableExternalSync()
    }

    override fun onDanmakuData(itemsJson: String) {
        runOnUiThread {
            Log.e("CastItDanmaku", "Danmaku data received in UI thread. Payload length=${itemsJson.length}")
            try {
                val jsonArray = JSONArray(itemsJson)
                val newList = ArrayList<DanmakuItemData>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val text = obj.optString("text", "")
                    if (text.isEmpty()) continue
                    val timeMs = obj.optLong("timeMs", 0L)
                    val colorStr = obj.optString("color", "#FFFFFF")
                    val colorInt = try {
                        android.graphics.Color.parseColor(colorStr)
                    } catch (e: Exception) {
                        android.graphics.Color.WHITE
                    }
                    val typeStr = obj.optString("type", "scroll")
                    val typeInt = when (typeStr) {
                        "top" -> BaseDanmaku.TYPE_FIX_TOP
                        "bottom" -> BaseDanmaku.TYPE_FIX_BOTTOM
                        else -> BaseDanmaku.TYPE_SCROLL_RL
                    }
                    newList.add(DanmakuItemData(text, timeMs, colorInt, typeInt))
                }

                danmakuList = newList
                Log.e("CastItDanmaku", "Parsed ${newList.size} Danmaku items into list")
                
                applyDanmakuListToView()
            } catch (e: Exception) {
                Log.e("CastItDanmaku", "Error parsing Danmaku JSON data", e)
            }
        }
    }

    override fun getPlaybackStatus(): ReceiverServer.PlaybackStatus {
        return ReceiverServer.PlaybackStatus(
            isPlaying = isPlayingBacking,
            currentTimeMs = currentPositionMs,
            durationMs = durationMs,
            volume = volumeState,
            activeTitle = errorMessageBacking ?: activeTitleBacking,
            speed = speedBacking,
            isEnded = isEndedBacking,
            videoUrl = videoUrlBacking,
            referer = refererBacking
        )
    }

    // MARK: - Key Event Handling (TV Remote)

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (activeVideoUrl != null) {
            when (keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    // Back button exits playback state and stops ExoPlayer
                    stopPlayback()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    // Toggle play/pause
                    exoPlayer?.let {
                        if (it.isPlaying) it.pause() else it.play()
                    }
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun stopPlayback() {
        exoPlayer?.stop()
        activeVideoUrl = null
        activeTitle = ""
        activeTitleBacking = ""
        isPlayingBacking = false
        isEndedBacking = false
        errorMessageBacking = null
        videoUrlBacking = null
        refererBacking = null
        danmakuList = emptyList()
        warningList = emptyList()
        activeWarning = null
        pendingSeekPositionMs = null
        try {
            danmakuView?.stop()
            danmakuView?.clearDanmakusOnScreen()
            danmakuView?.removeAllDanmakus(true)
        } catch (e: Exception) {
            Log.e("CastItDanmaku", "Error clearing danmakuView on stopPlayback", e)
        }
    }

    // MARK: - mDNS Network Service Discovery

    private fun getDeviceName(): String {
        var name: String? = null
        try {
            name = android.provider.Settings.Global.getString(contentResolver, "device_name")
        } catch (_: Exception) {}
        if (name.isNullOrEmpty()) {
            try {
                name = android.provider.Settings.Secure.getString(contentResolver, "bluetooth_name")
            } catch (_: Exception) {}
        }
        
        val model = android.os.Build.MODEL
        val friendlyModelName = when (model) {
            "AFTSS" -> "Fire TV Stick HD"
            "AFTSSS" -> "Fire TV Stick"
            "AFTKA" -> "Fire TV Stick 4K Max"
            "AFTKM" -> "Fire TV Stick 4K"
            "AFTKRT", "AFTSO" -> "Fire TV Stick 4K Max"
            "AFTMM" -> "Fire TV Stick 4K"
            "AFTT" -> "Fire TV Stick"
            "AFTM" -> "Fire TV Stick"
            else -> model
        }

        if (name.isNullOrEmpty() || name.equals(model, ignoreCase = true) || name.contains("AFTSS", ignoreCase = true)) {
            name = friendlyModelName
        } else {
            // Clean up any CastIt TV prefix or suffix model codes
            name = name.replace("CastIt TV - ", "", ignoreCase = true)
            name = name.replace("CastIt TV", "", ignoreCase = true)
            name = name.replace(" - $model", "", ignoreCase = true)
            name = name.replace(model, friendlyModelName, ignoreCase = true)
            name = name.trim()
            if (name.isEmpty()) {
                name = friendlyModelName
            }
        }
        
        return name
    }

    private fun getAppVersionString(): String {
        return try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val version = pInfo.versionName ?: "1.3.2"
            val code = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                pInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode.toLong()
            }
            "v$version (Build $code)"
        } catch (e: Exception) {
            "v1.3.2 (Build 18)"
        }
    }

    private fun registerNsdService(port: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = getDeviceName()
            serviceType = "_castit._tcp"
            setPort(port)
        }

        nsdManager = getSystemService(Context.NSD_SERVICE) as NsdManager
        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i("CastItReceiver", "Service registered: ${info.serviceName}")
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, err: Int) {
                Log.e("CastItReceiver", "Bonjour registration failed: $err")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i("CastItReceiver", "Service unregistered")
            }

            override fun onUnregistrationFailed(info: NsdServiceInfo, err: Int) {
                Log.e("CastItReceiver", "Bonjour unregistration failed: $err")
            }
        }

        nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    // MARK: - Helper Utilities

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (intf in interfaces) {
                val addrs = Collections.list(intf.inetAddresses)
                for (addr in addrs) {
                    if (!addr.isLoopbackAddress) {
                        val sAddr = addr.hostAddress ?: ""
                        val isIPv4 = sAddr.indexOf(':') < 0
                        if (isIPv4) return sAddr
                    }
                }
            }
        } catch (ex: Exception) {
            ex.printStackTrace()
        }
        return "Unknown IP"
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDialIntent(intent)
    }

    private fun handleDialIntent(intent: android.content.Intent?) {
        intent?.let {
            val dialParam = it.getStringExtra("com.amazon.extra.DIAL_PARAM")
            val dialDataUrl = it.getStringExtra("com.amazon.extra.DIAL_ADDITIONAL_DATA_URL")
            if (dialParam != null || dialDataUrl != null) {
                Log.i("CastItReceiver", "Launched via DIAL. Param: $dialParam, DataURL: $dialDataUrl")
            }
        }
    }

    // MARK: - AirPlay Receiver Management

    private fun startAirPlayReceiver() {
        val deviceName = getDeviceName()
        Log.i("CastItReceiver", "Starting AirPlayReceiver with displayName='$deviceName'")
        
        airPlayReceiver = AirPlayReceiver(
            context = this,
            displayName = deviceName,
            mirrorWidth = 1920,
            mirrorHeight = 1080,
            audioEnabled = true,
            pinAuthEnabled = false,
            videoSurfaceProvider = { airPlaySurfaceView?.getSurface() },
            onStateChanged = { state ->
                runOnUiThread {
                    Log.i("CastItReceiver", "AirPlay state changed: $state")
                    when (state) {
                        ProtocolState.CONNECTED -> {
                            // Pause local video if actively playing
                            exoPlayer?.pause()
                            isAirPlayActive = true
                        }
                        ProtocolState.ADVERTISING, ProtocolState.DISABLED -> {
                            isAirPlayActive = false
                            airPlaySenderName = null
                        }
                        ProtocolState.ERROR -> {
                            isAirPlayActive = false
                        }
                    }
                }
            },
            onSenderNameChanged = { name ->
                runOnUiThread {
                    airPlaySenderName = name
                }
            },
            onVideoSessionChanged = { hasVideo ->
                runOnUiThread {
                    if (hasVideo) {
                        exoPlayer?.pause()
                        isAirPlayActive = true
                    }
                }
            }
        )
        airPlayReceiver?.start()
    }

    private fun stopAirPlayReceiver() {
        try {
            airPlayReceiver?.stop()
            airPlayReceiver = null
        } catch (e: Exception) {
            Log.e("CastItReceiver", "Error stopping AirPlay receiver", e)
        }
    }

    // MARK: - Cleanup

    override fun onDestroy() {
        super.onDestroy()
        stopAirPlayReceiver()
        mainHandler.removeCallbacks(positionUpdater)
        server?.stop()
        exoPlayer?.release()
        exoPlayer = null
        
        try {
            registrationListener?.let {
                nsdManager?.unregisterService(it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
