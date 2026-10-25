package io.sberry.castitreceiver

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.IOException

class ReceiverServer(
    port: Int,
    private val listener: Listener
) : NanoHTTPD(port) {

    interface Listener {
        fun onCast(url: String, title: String, positionMs: Long, referer: String?, userAgent: String?, adDurationSec: Double = 0.0)
        fun onPlay()
        fun onRemotePause()
        fun onSeek(positionMs: Long)
        fun onVolume(volume: Float)
        fun onSpeed(speed: Float)
        fun onDanmakuConfig(mode: Int)
        fun onDanmakuData(itemsJson: String)
        fun onDanmakuWarnings(warningsJson: String)
        fun getPlaybackStatus(): PlaybackStatus
    }

    data class PlaybackStatus(
        val isPlaying: Boolean,
        val currentTimeMs: Long,
        val durationMs: Long,
        val volume: Float,
        val activeTitle: String,
        val speed: Float,
        val isEnded: Boolean,
        val videoUrl: String?,
        val referer: String?
    )

    override fun serve(session: IHTTPSession): Response {
        val method = session.method
        val uri = session.uri

        return try {
            when {
                uri == "/cast" && method == Method.POST -> handleCast(session)
                uri == "/play" && method == Method.POST -> handlePlay()
                uri == "/pause" && method == Method.POST -> handlePause()
                uri == "/seek" && method == Method.POST -> handleSeek(session)
                uri == "/volume" && method == Method.POST -> handleVolume(session)
                uri == "/speed" && method == Method.POST -> handleSpeed(session)
                uri == "/danmaku/config" && method == Method.POST -> handleDanmakuConfig(session)
                uri == "/danmaku/data" && method == Method.POST -> handleDanmakuData(session)
                uri == "/danmaku/warnings" && method == Method.POST -> handleDanmakuWarnings(session)
                uri == "/status" && method == Method.GET -> handleStatus()
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error: ${e.message}")
        }
    }

    private fun handleCast(session: IHTTPSession): Response {
        val body = parseRequestBody(session)
        val url = body.optString("url", "")
        val title = body.optString("title", "Video Stream")
        val positionSec = body.optDouble("position", 0.0)
        val positionMs = (positionSec * 1000).toLong()
        val referer = body.optString("referer", "").takeIf { it.isNotEmpty() }
        val userAgent = body.optString("userAgent", "").takeIf { it.isNotEmpty() }
        val adDurationSec = body.optDouble("adDuration", 0.0)
        if (url.isEmpty()) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing 'url'")
        }
        listener.onCast(url, title, positionMs, referer, userAgent, adDurationSec)
        return jsonResponse(JSONObject().put("status", "success"))
    }

    private fun handlePlay(): Response {
        listener.onPlay()
        return jsonResponse(JSONObject().put("status", "success"))
    }

    private fun handlePause(): Response {
        listener.onRemotePause()
        return jsonResponse(JSONObject().put("status", "success"))
    }

    private fun handleSeek(session: IHTTPSession): Response {
        val body = parseRequestBody(session)
        val position = body.optDouble("position", -1.0)
        if (position < 0) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing or invalid 'position'")
        }
        val positionMs = (position * 1000).toLong()
        listener.onSeek(positionMs)
        return jsonResponse(JSONObject().put("status", "success"))
    }

    private fun handleVolume(session: IHTTPSession): Response {
        val body = parseRequestBody(session)
        val volume = body.optDouble("volume", -1.0)
        if (volume < 0 || volume > 1.0) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Missing or invalid 'volume'")
        }
        listener.onVolume(volume.toFloat())
        return jsonResponse(JSONObject().put("status", "success"))
    }

    private fun handleSpeed(session: IHTTPSession): Response {
        val body = parseRequestBody(session)
        val speed = body.optDouble("speed", 1.0).toFloat()
        listener.onSpeed(speed)
        return jsonResponse(JSONObject().put("status", "success"))
    }

    private fun handleDanmakuConfig(session: IHTTPSession): Response {
        val body = parseRequestBody(session)
        val mode = body.optInt("mode", 0)
        listener.onDanmakuConfig(mode)
        return jsonResponse(JSONObject().put("status", "success"))
    }

    private fun handleDanmakuWarnings(session: IHTTPSession): Response {
        val rawBody = parseRawRequestBody(session)
        listener.onDanmakuWarnings(rawBody)
        return jsonResponse(JSONObject().put("status", "success"))
    }

    private fun handleDanmakuData(session: IHTTPSession): Response {
        android.util.Log.e("CastItReceiverServer", "handleDanmakuData POST request received! Content length: ${session.headers["content-length"]}")
        val rawBody = parseRawRequestBody(session)
        android.util.Log.e("CastItReceiverServer", "handleDanmakuData parsed body character count: ${rawBody.length}")
        if (rawBody.isNotEmpty()) {
            android.util.Log.e("CastItReceiverServer", "handleDanmakuData sample text: ${rawBody.take(120)}")
        } else {
            android.util.Log.e("CastItReceiverServer", "handleDanmakuData body was completely empty!")
        }
        listener.onDanmakuData(rawBody)
        return jsonResponse(JSONObject().put("status", "success"))
    }

    private fun handleStatus(): Response {
        val status = listener.getPlaybackStatus()
        val json = JSONObject().apply {
            put("isPlaying", status.isPlaying)
            put("currentTime", status.currentTimeMs / 1000.0)
            put("duration", status.durationMs / 1000.0)
            put("volume", status.volume)
            put("title", status.activeTitle)
            put("speed", status.speed)
            put("isEnded", status.isEnded)
            put("videoUrl", status.videoUrl ?: "")
            put("referer", status.referer ?: "")
        }
        return jsonResponse(json)
    }

    private fun parseRawRequestBody(session: IHTTPSession): String {
        return try {
            val headers = session.headers
            val contentLength = headers.entries
                .firstOrNull { it.key.equals("content-length", ignoreCase = true) }
                ?.value?.toIntOrNull() ?: -1

            val input = session.inputStream
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            
            if (contentLength > 0) {
                var bytesRead = 0
                while (bytesRead < contentLength) {
                    val toRead = Math.min(buffer.size, contentLength - bytesRead)
                    val read = input.read(buffer, 0, toRead)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    bytesRead += read
                }
            } else {
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                }
            }
            output.toString("UTF-8")
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }

    private fun parseRequestBody(session: IHTTPSession): JSONObject {
        val bodyString = parseRawRequestBody(session)
        if (bodyString.isEmpty()) return JSONObject()
        return try {
            JSONObject(bodyString)
        } catch (e: Exception) {
            e.printStackTrace()
            JSONObject()
        }
    }

    private fun jsonResponse(json: JSONObject): Response {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val response = newFixedLengthResponse(
            Response.Status.OK, 
            "application/json; charset=utf-8", 
            java.io.ByteArrayInputStream(bytes), 
            bytes.size.toLong()
        )
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }
}
