package com.svetlio.audiocast.discovery

/** A receiver found on the local network via NSD, resolved to a reachable address. */
data class DiscoveredReceiver(
    val serviceName: String,
    val host: String,
    val port: Int,
)
