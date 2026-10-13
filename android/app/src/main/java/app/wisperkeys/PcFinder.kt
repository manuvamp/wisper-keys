package app.wisperkeys

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.security.MessageDigest

/**
 * Finds your PC on the network when its saved address stops answering (a new Wi-Fi address after a restart, a new
 * router...). wisper announces itself over mDNS as _wisper._tcp with a fingerprint of the pairing token; only the
 * PC you paired with matches it. Pairing stays a one-time thing.
 */
class PcFinder(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var busy = false

    /** Looks for [timeoutMs]; calls [onFound] (main thread) with ws://ip:port, or never if the PC isn't around. */
    fun find(token: String, timeoutMs: Long = 5000, onFound: (String) -> Unit) {
        if (busy || token.isEmpty()) return
        busy = true
        val want = fingerprint(token)
        var done = false
        val queue = ArrayDeque<NsdServiceInfo>()
        var resolving = false

        lateinit var discovery: NsdManager.DiscoveryListener
        fun finish() {
            if (done) return
            done = true
            busy = false
            try { nsd.stopServiceDiscovery(discovery) } catch (_: Exception) {}
        }

        fun resolveNext() {
            if (done || resolving) return
            val s = queue.removeFirstOrNull() ?: return
            resolving = true
            @Suppress("DEPRECATION")
            nsd.resolveService(s, object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    main.post { resolving = false; resolveNext() }
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    main.post {
                        resolving = false
                        val id = info.attributes["id"]?.let { String(it) }
                        @Suppress("DEPRECATION")
                        val host = info.host?.hostAddress
                        if (!done && id == want && host != null && !host.contains(':')) {
                            finish()
                            onFound("ws://$host:${info.port}")
                        } else resolveNext()
                    }
                }
            })
        }

        discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { main.post { done = true; busy = false } }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                main.post { queue.addLast(serviceInfo); resolveNext() }
            }
        }
        try {
            nsd.discoverServices("_wisper._tcp", NsdManager.PROTOCOL_DNS_SD, discovery)
        } catch (e: Exception) {
            busy = false
            return
        }
        main.postDelayed({ finish() }, timeoutMs)
    }

    companion object {
        fun fingerprint(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }.take(12)
    }
}
