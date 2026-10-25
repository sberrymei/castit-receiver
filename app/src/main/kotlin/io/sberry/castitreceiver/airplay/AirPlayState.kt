package io.sberry.castitreceiver.airplay

/**
 * ProtocolState represents the lifecycle state of the AirPlay receiver protocol.
 */
enum class ProtocolState {
    DISABLED,
    ADVERTISING,
    CONNECTED,
    ERROR
}

enum class Protocol {
    AIRPLAY
}

data class ActiveConnection(
    val senderName: String,
    val protocol: Protocol = Protocol.AIRPLAY,
    val startedAt: Long = System.currentTimeMillis()
) {
    val durationSeconds: Long
        get() = (System.currentTimeMillis() - startedAt) / 1000L
}
