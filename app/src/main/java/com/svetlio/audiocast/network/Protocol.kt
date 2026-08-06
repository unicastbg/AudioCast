package com.svetlio.audiocast.network

/**
 * Shared network constants for both roles.
 *
 * The wire framing (FILE_META / FILE_DATA / FILE_END, and later PCM_* for
 * Phase 2) is added alongside the transport in the next step. This file holds
 * only what discovery needs right now.
 */
object Protocol {

    /** NSD service type. Must be of the form "_name._tcp." */
    const val SERVICE_TYPE = "_audiocast._tcp."

    /** TCP port the receiver listens on and advertises via NSD. */
    const val DEFAULT_PORT = 8787

    /** Prefix for the advertised service name, e.g. "AudioCast-Pixel7". */
    const val SERVICE_NAME_PREFIX = "AudioCast"
}
