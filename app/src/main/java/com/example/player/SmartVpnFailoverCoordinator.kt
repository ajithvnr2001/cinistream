package com.example.player

/**
 * Pure state machine governing Smart Auto-VPN Failover.
 *
 * Plays streams on fast Direct ISP / Cloudflare 1.1.1.1 by default, and automatically triggers
 * embedded NordLynx (WireGuard) failover ONLY when:
 * 1. The user explicitly enabled Smart Auto-VPN Failover and has saved NordVPN credentials.
 * 2. The stream is remote (not local storage) and NordVPN is NOT already active.
 * 3. Direct ISP experiences sustained low-bandwidth stalls (>= 2 rebuffers below 2.0 Mbps)
 *    OR an unrecoverable upstream HTTP/network error after CDN mirrors are exhausted.
 * 4. Per-session cooldown: fires at most ONCE per media URI to prevent connect/disconnect loops.
 */
class SmartVpnFailoverCoordinator(
    private val stallCountThreshold: Int = DEFAULT_STALL_COUNT_THRESHOLD,
    private val lowBandwidthThresholdBps: Long = DEFAULT_LOW_BANDWIDTH_THRESHOLD_BPS
) {
    companion object {
        const val DEFAULT_STALL_COUNT_THRESHOLD = 2
        const val DEFAULT_LOW_BANDWIDTH_THRESHOLD_BPS = 2_000_000L // 2.0 Mbps
    }

    private val lock = Any()
    private var currentMediaUri: String = ""
    private var lowBandwidthStallCount: Int = 0
    private var failoverTriggeredForCurrentMedia: Boolean = false
    private var manuallySuppressedForCurrentMedia: Boolean = false

    fun onNewMediaSession(mediaUri: String) {
        synchronized(lock) {
            if (currentMediaUri != mediaUri) {
                currentMediaUri = mediaUri
                lowBandwidthStallCount = 0
                failoverTriggeredForCurrentMedia = false
                manuallySuppressedForCurrentMedia = false
            }
        }
    }

    fun suppressForCurrentSession() {
        synchronized(lock) {
            manuallySuppressedForCurrentMedia = true
        }
    }

    fun recordRebufferStall(
        isFeatureEnabled: Boolean,
        hasNordCredentials: Boolean,
        isNordAlreadyActive: Boolean,
        isLocalMedia: Boolean,
        estimatedBandwidthBps: Long
    ): Boolean {
        synchronized(lock) {
            if (!canTriggerLocked(isFeatureEnabled, hasNordCredentials, isNordAlreadyActive, isLocalMedia)) {
                return false
            }
            if (estimatedBandwidthBps in 1L until lowBandwidthThresholdBps || estimatedBandwidthBps <= 0L) {
                lowBandwidthStallCount++
            }
            if (lowBandwidthStallCount >= stallCountThreshold) {
                failoverTriggeredForCurrentMedia = true
                return true
            }
            return false
        }
    }

    fun recordFatalNetworkError(
        isFeatureEnabled: Boolean,
        hasNordCredentials: Boolean,
        isNordAlreadyActive: Boolean,
        isLocalMedia: Boolean
    ): Boolean {
        synchronized(lock) {
            if (!canTriggerLocked(isFeatureEnabled, hasNordCredentials, isNordAlreadyActive, isLocalMedia)) {
                return false
            }
            failoverTriggeredForCurrentMedia = true
            return true
        }
    }

    fun hasTriggeredForCurrentSession(): Boolean = synchronized(lock) {
        failoverTriggeredForCurrentMedia
    }

    private fun canTriggerLocked(
        isFeatureEnabled: Boolean,
        hasNordCredentials: Boolean,
        isNordAlreadyActive: Boolean,
        isLocalMedia: Boolean
    ): Boolean {
        return isFeatureEnabled &&
            hasNordCredentials &&
            !isNordAlreadyActive &&
            !isLocalMedia &&
            !failoverTriggeredForCurrentMedia &&
            !manuallySuppressedForCurrentMedia
    }
}
