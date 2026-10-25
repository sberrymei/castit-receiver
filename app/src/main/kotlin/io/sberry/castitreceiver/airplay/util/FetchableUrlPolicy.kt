package io.sberry.castitreceiver.airplay.util

import java.net.URI

object FetchableUrlPolicy {
    fun isFetchable(urlString: String?): Boolean {
        if (urlString.isNullOrBlank()) return false
        return try {
            val uri = URI(urlString)
            val scheme = uri.scheme?.lowercase()
            scheme == "http" || scheme == "https" || scheme == "rtsp"
        } catch (e: Exception) {
            false
        }
    }
}
