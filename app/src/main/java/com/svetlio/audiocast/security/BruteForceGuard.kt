package com.svetlio.audiocast.security

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-source-IP brute-force protection.
 *
 * After [MAX_FAILS] failed auth attempts in quick succession, that IP is locked
 * out for [LOCKOUT_MS] (rejected without processing). Sparse failures decay
 * (reset after [RESET_MS] of quiet), so a rare hiccup never locks a good client.
 * A success clears the record.
 */
class BruteForceGuard {

    private class Entry {
        var fails = 0
        var lastFail = 0L
        var lockUntil = 0L
    }

    private val map = ConcurrentHashMap<String, Entry>()

    @Synchronized
    fun isLockedOut(ip: String): Boolean {
        val e = map[ip] ?: return false
        val now = System.currentTimeMillis()
        if (now < e.lockUntil) return true
        if (e.lockUntil > 0L) map.remove(ip) // lockout expired -> fresh start
        return false
    }

    @Synchronized
    fun recordFailure(ip: String) {
        val now = System.currentTimeMillis()
        val e = map.getOrPut(ip) { Entry() }
        if (now - e.lastFail > RESET_MS) e.fails = 0
        e.fails++
        e.lastFail = now
        if (e.fails >= MAX_FAILS) e.lockUntil = now + LOCKOUT_MS
    }

    @Synchronized
    fun recordSuccess(ip: String) {
        map.remove(ip)
    }

    companion object {
        private const val MAX_FAILS = 5
        private const val LOCKOUT_MS = 30_000L
        private const val RESET_MS = 60_000L
    }
}
