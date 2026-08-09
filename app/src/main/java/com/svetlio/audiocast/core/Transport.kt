package com.svetlio.audiocast.core

/**
 * Network transport for the LIVE capture stream.
 *
 * TCP: reliable, ordered — a lost packet stalls the stream until resent.
 * UDP: no delivery guarantee — a lost packet is just a tiny gap, no stall.
 *
 * This choice applies to live casting only. File transfers always use TCP,
 * because a compressed file can't tolerate a single dropped byte.
 */
enum class Transport { TCP, UDP }
