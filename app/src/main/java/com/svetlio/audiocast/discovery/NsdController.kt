package com.svetlio.audiocast.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.svetlio.audiocast.network.Protocol
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wraps NsdManager for both roles:
 *  - Receiver calls [registerReceiver] to advertise "_audiocast._tcp." on [port].
 *  - Sender calls [startDiscovery] to find receivers and resolve them to host:port.
 *
 * NsdManager is notoriously touchy, so this class:
 *  - keeps a single registration + discovery listener instance (required to
 *    unregister/stop cleanly),
 *  - serializes resolveService() calls (concurrent resolves throw
 *    FAILURE_ALREADY_ACTIVE on many devices),
 *  - holds a Wi-Fi multicast lock while discovering (some ROMs drop mDNS
 *    packets otherwise — relevant on cheap TV boxes / hotspots).
 *
 * Callbacks may arrive on binder threads; keep handlers light and thread-safe.
 */
class NsdController(context: Context) {

    private val appContext = context.applicationContext
    private val nsdManager =
        appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager =
        appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private var multicastLock: WifiManager.MulticastLock? = null

    // ---- Registration (receiver) -------------------------------------------

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var registeredName: String? = null

    fun registerReceiver(
        port: Int = Protocol.DEFAULT_PORT,
        onRegistered: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (registrationListener != null) return // already advertising

        val info = NsdServiceInfo().apply {
            serviceName = "${Protocol.SERVICE_NAME_PREFIX}-${Build.MODEL}".replace(' ', '_')
            serviceType = Protocol.SERVICE_TYPE
            setPort(port)
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(nsdServiceInfo: NsdServiceInfo) {
                // Android may append a suffix (e.g. " (1)") to avoid name clashes.
                registeredName = nsdServiceInfo.serviceName
                onRegistered(nsdServiceInfo.serviceName)
            }

            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Registration failed: $errorCode")
                onError("Registration failed (code $errorCode)")
            }

            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Unregistration failed: $errorCode")
            }
        }
        registrationListener = listener

        try {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            registrationListener = null
            onError("registerService threw: ${e.message}")
        }
    }

    fun unregister() {
        val listener = registrationListener ?: return
        try {
            nsdManager.unregisterService(listener)
        } catch (e: Exception) {
            Log.w(TAG, "unregister ignored: ${e.message}")
        }
        registrationListener = null
        registeredName = null
    }

    // ---- Discovery (sender) -------------------------------------------------

    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private val resolving = AtomicBoolean(false)
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()

    fun startDiscovery(
        onFound: (DiscoveredReceiver) -> Unit,
        onLost: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        if (discoveryListener != null) return // already discovering

        acquireMulticastLock()

        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType.trimEnd('.') !=
                    Protocol.SERVICE_TYPE.trimEnd('.')
                ) return
                // Don't resolve our own service if this device also advertises.
                if (serviceInfo.serviceName == registeredName) return
                enqueueResolve(serviceInfo, onFound)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                onLost(serviceInfo.serviceName)
            }

            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Start discovery failed: $errorCode")
                onError("Discovery failed (code $errorCode)")
                safeStopDiscovery()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "Stop discovery failed: $errorCode")
            }
        }
        discoveryListener = listener

        try {
            nsdManager.discoverServices(
                Protocol.SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                listener,
            )
        } catch (e: Exception) {
            discoveryListener = null
            releaseMulticastLock()
            onError("discoverServices threw: ${e.message}")
        }
    }

    fun stopDiscovery() {
        safeStopDiscovery()
        releaseMulticastLock()
        resolveQueue.clear()
        resolving.set(false)
    }

    private fun safeStopDiscovery() {
        val listener = discoveryListener ?: return
        try {
            nsdManager.stopServiceDiscovery(listener)
        } catch (e: Exception) {
            Log.w(TAG, "stopDiscovery ignored: ${e.message}")
        }
        discoveryListener = null
    }

    // Serialize resolves: concurrent resolveService() calls fail on many devices.
    // resolveService + NsdServiceInfo.host are deprecated on API 34 in favour of
    // registerServiceInfoCallback, but they work across our whole 29+ range and
    // the newer API isn't available below 34 — so we keep them deliberately.
    private fun enqueueResolve(
        info: NsdServiceInfo,
        onFound: (DiscoveredReceiver) -> Unit,
    ) {
        resolveQueue.add(info)
        pumpResolveQueue(onFound)
    }

    @Suppress("DEPRECATION")
    private fun pumpResolveQueue(onFound: (DiscoveredReceiver) -> Unit) {
        if (!resolving.compareAndSet(false, true)) return
        val next = resolveQueue.poll()
        if (next == null) {
            resolving.set(false)
            return
        }
        nsdManager.resolveService(next, object : NsdManager.ResolveListener {
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                val host = resolved.host?.hostAddress
                if (host != null) {
                    onFound(
                        DiscoveredReceiver(
                            serviceName = resolved.serviceName,
                            host = host,
                            port = resolved.port,
                        )
                    )
                }
                resolving.set(false)
                pumpResolveQueue(onFound)
            }

            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed for ${info.serviceName}: $errorCode")
                resolving.set(false)
                pumpResolveQueue(onFound)
            }
        })
    }

    // ---- Multicast lock -----------------------------------------------------

    private fun acquireMulticastLock() {
        if (multicastLock != null) return
        multicastLock = wifiManager.createMulticastLock("audiocast-nsd").apply {
            setReferenceCounted(false)
            try {
                acquire()
            } catch (e: Exception) {
                Log.w(TAG, "multicast lock acquire failed: ${e.message}")
            }
        }
    }

    private fun releaseMulticastLock() {
        multicastLock?.let {
            try {
                if (it.isHeld) it.release()
            } catch (e: Exception) {
                Log.w(TAG, "multicast lock release failed: ${e.message}")
            }
        }
        multicastLock = null
    }

    companion object {
        private const val TAG = "NsdController"
    }
}
