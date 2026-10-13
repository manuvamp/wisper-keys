package app.wisperkeys

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

/**
 * The link to wisper on the PC (see wisper/service/remote.py for the protocol).
 *
 * Latency: the connection is opened while the keyboard is on screen, so pressing the mic costs no handshake; audio
 * streams while you talk (the PC transcribes at your pauses), so only the last moment is left when you let go.
 * Every paired address (home Wi-Fi, Tailscale) is tried at once and the first to answer wins.
 * Reliability: the current dictation's audio is kept until its text arrives; if the link drops, it is replayed on the
 * next connection, so nothing you said is lost to a Wi-Fi hiccup.
 * Pairing is once: if the PC comes back at a new address (restart, new Wi-Fi address), it is found again by itself.
 */
class VoiceClient(
    private val listener: Listener,
    /** Looks for the PC on the network when every saved address fails (see [PcFinder]). */
    private val find: ((token: String, onFound: (String) -> Unit) -> Unit)? = null,
    /** The PC was found at a new address: save the list so the next connection goes straight there. */
    private val onAddresses: ((List<String>) -> Unit)? = null,
) {

    enum class Status { NOT_PAIRED, CONNECTING, READY, OFFLINE }

    interface Listener {
        fun onStatus(status: Status, detail: String)
        fun onPartial(id: Int, text: String)
        fun onFinal(id: Int, text: String)
        fun onFailed(id: Int, reason: String)
    }

    private class Dictation(val id: Int, val before: String) {
        val audio = ByteArrayOutputStream()
        var stopped = false
    }

    private val main = Handler(Looper.getMainLooper())
    private val http = OkHttpClient.Builder()
        .socketFactory(NoDelaySocketFactory())
        .connectTimeout(4, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
        .build()
    private val lock = Any()
    private var urls = listOf<String>()
    private var token = ""
    private var rendezvous = ""
    private var lastLookupAt = 0L
    private var live: WebSocket? = null
    private val attempts = mutableListOf<WebSocket>()
    private var dict: Dictation? = null
    private var wanted = false
    private var retryMs = 1000L
    private var lastPingAt = 0L
    private var lastFindAt = 0L
    var status = Status.OFFLINE
        private set
    var detail = ""
        private set
    var rttMs = -1
        private set

    fun configure(urls: List<String>, token: String, rendezvous: String = "") {
        synchronized(lock) {
            this.rendezvous = rendezvous
            if (urls == this.urls && token == this.token) return
            this.urls = urls
            this.token = token
            dropAll()
            if (wanted) connectLocked()
        }
    }

    /** Keyboard shown: make sure a connection is up (or coming). */
    fun connect() {
        synchronized(lock) {
            wanted = true
            main.removeCallbacks(idleClose)
            connectLocked()
            if (live != null && SystemClock.elapsedRealtime() - lastPingAt > 5000) pingLocked()
        }
    }

    /** Keyboard hidden: keep the link a little while (you often come straight back), then close it. */
    fun release() {
        synchronized(lock) {
            wanted = false
            main.removeCallbacks(idleClose)
            main.postDelayed(idleClose, 90_000)
        }
    }

    /**
     * The phone switched networks (Wi-Fi <-> mobile data). Reconnect at once instead of waiting for the old link to
     * time out, so the direct Wi-Fi path wins at home and the tunnel takes over as soon as you leave. Never during a
     * dictation (that one finishes, or is replayed, on its own).
     */
    fun networkChanged() {
        synchronized(lock) {
            if (dict != null || !wanted) return
            live?.cancel()
            attempts.forEach { it.cancel() }
            live = null
            attempts.clear()
            main.removeCallbacks(pinger)
            retryMs = 1000
            connectLocked()
        }
    }

    /** Close now (the setup screen's connection test). */
    fun close() {
        synchronized(lock) {
            wanted = false
            main.removeCallbacks(retry)
            dropAll()
        }
    }

    private val idleClose = Runnable { synchronized(lock) { if (!wanted && dict == null) dropAll() } }
    private val retry = Runnable { synchronized(lock) { if (wanted || dict != null) connectLocked() } }
    private val pinger = object : Runnable {
        override fun run() {
            synchronized(lock) {
                if (live != null && wanted) { pingLocked(); main.postDelayed(this, 10_000) }
            }
        }
    }

    private fun connectLocked() {
        if (live != null || attempts.isNotEmpty()) return
        if (urls.isEmpty() || token.isEmpty()) return setStatus(Status.NOT_PAIRED, "")
        setStatus(Status.CONNECTING, "")
        for (u in urls) {
            val req = Request.Builder().url(u.trimEnd('/') + "/v1/stream").header("Authorization", "Bearer $token").build()
            attempts += http.newWebSocket(req, Attempt())
        }
    }

    private fun dropAll() {
        live?.close(1000, null)
        attempts.forEach { it.cancel() }
        live = null
        attempts.clear()
        main.removeCallbacks(pinger)
        setStatus(if (urls.isEmpty() || token.isEmpty()) Status.NOT_PAIRED else Status.OFFLINE, "")
    }

    private fun pingLocked() {
        lastPingAt = SystemClock.elapsedRealtime()
        live?.send(JSONObject().put("type", "ping").put("t", System.nanoTime()).toString())
    }

    private fun setStatus(s: Status, d: String) {
        status = s
        detail = d
        main.post { listener.onStatus(s, d) }
    }

    // ---------------------------------------------------------------- dictation
    fun start(id: Int, before: String) {
        synchronized(lock) {
            dict = Dictation(id, before)
            live?.send(startJson(id, before)) ?: connectLocked()
        }
    }

    fun audio(id: Int, bytes: ByteArray, len: Int) {
        synchronized(lock) {
            val d = dict ?: return
            if (d.id != id || d.stopped || d.audio.size() > 20_000_000) return
            d.audio.write(bytes, 0, len)
            live?.send(bytes.toByteString(0, len))
        }
    }

    fun stop(id: Int) {
        synchronized(lock) {
            val d = dict ?: return
            if (d.id != id) return
            d.stopped = true
            live?.send(JSONObject().put("type", "stop").put("id", id).toString()) ?: connectLocked()
            main.postDelayed({
                synchronized(lock) {
                    if (dict === d) {
                        dict = null
                        main.post { listener.onFailed(id, if (live == null) "Couldn't reach your PC" else "Your PC didn't answer") }
                    }
                }
            }, 15_000)
        }
    }

    fun cancel(id: Int) {
        synchronized(lock) {
            if (dict?.id == id) {
                live?.send(JSONObject().put("type", "cancel").put("id", id).toString())
                dict = null
            }
        }
    }

    private fun startJson(id: Int, before: String) =
        JSONObject().put("type", "start").put("id", id).put("before", before).toString()

    /** A fresh connection picks up the dictation in flight: start + everything said so far (+ stop if released). */
    private fun replayLocked(ws: WebSocket) {
        val d = dict ?: return
        ws.send(startJson(d.id, d.before))
        val a = d.audio.toByteArray()
        var i = 0
        while (i < a.size) {
            val n = minOf(32_000, a.size - i)
            ws.send(a.toByteString(i, n))
            i += n
        }
        if (d.stopped) ws.send(JSONObject().put("type", "stop").put("id", d.id).toString())
    }

    private inner class Attempt : WebSocketListener() {
        override fun onMessage(webSocket: WebSocket, text: String) {
            val m = try { JSONObject(text) } catch (e: Exception) { return }
            when (m.optString("type")) {
                "ready" -> synchronized(lock) {
                    if (live == null) {
                        live = webSocket
                        attempts.remove(webSocket)
                        attempts.forEach { it.cancel() }
                        attempts.clear()
                        retryMs = 1000
                        setStatus(Status.READY, "")
                        replayLocked(webSocket)
                        main.removeCallbacks(pinger)
                        main.post(pinger)
                    } else if (live !== webSocket) webSocket.close(1000, null)
                }
                "pong" -> {
                    rttMs = ((System.nanoTime() - m.optLong("t")) / 1_000_000).toInt()
                    setStatus(Status.READY, "$rttMs ms")
                }
                "partial" -> {
                    val id = m.optInt("id")
                    val t = m.optString("text")
                    main.post { listener.onPartial(id, t) }
                }
                "final" -> {
                    val id = m.optInt("id")
                    val t = m.optString("text")
                    synchronized(lock) { if (dict?.id == id) dict = null }
                    main.post { listener.onFinal(id, t) }
                }
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {}

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
            lost(webSocket, when (response?.code) {
                401 -> "Wrong token: pair again"
                404 -> "PC answered but isn't wisper"
                else -> "PC unreachable"
            })

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket, "PC closed the link")
    }

    private fun lost(ws: WebSocket, reason: String) {
        synchronized(lock) {
            if (ws === live) {
                live = null
                main.removeCallbacks(pinger)
            } else if (!attempts.remove(ws)) return
            if (live == null && attempts.isEmpty()) {
                setStatus(Status.OFFLINE, reason)
                if ((wanted || dict != null) && reason != "Wrong token: pair again") {
                    findLocked()
                    main.removeCallbacks(retry)
                    main.postDelayed(retry, retryMs)
                    retryMs = minOf(retryMs * 2, 15_000)
                }
            }
        }
    }

    /**
     * Every saved address failed. Look for the PC two ways at once: on this Wi-Fi (mDNS, at most every 20 s) and,
     * wherever you are, at the address it last published (the rendezvous gist, at most once a minute).
     */
    private fun findLocked() {
        val now = SystemClock.elapsedRealtime()
        val tok = token
        val f = find
        if (f != null && now - lastFindAt >= 20_000) {
            lastFindAt = now
            f(tok) { url -> adopt(tok, listOf(url)) }
        }
        val r = rendezvous
        if (r.isNotEmpty() && now - lastLookupAt >= 60_000) {
            lastLookupAt = now
            http.newCall(Request.Builder().url(r).header("Accept", "application/vnd.github+json").build())
                .enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {}
                    override fun onResponse(call: okhttp3.Call, response: Response) {
                        val urls = try {
                            response.use {
                                val gist = JSONObject(it.body?.string() ?: return)
                                val c = JSONObject(gist.getJSONObject("files").getJSONObject("wisper.json").getString("content"))
                                if (c.optString("id") != PcFinder.fingerprint(tok)) return
                                val a = c.getJSONArray("urls")
                                (0 until a.length()).map { a.getString(it) }.filter { it.startsWith("ws") }
                            }
                        } catch (e: Exception) { return }
                        main.post { adopt(tok, urls) }
                    }
                })
        }
    }

    /** New addresses for the paired PC: put them first, drop stale tunnel addresses, save, reconnect. */
    private fun adopt(tok: String, found: List<String>) {
        synchronized(lock) {
            if (tok != token || found.isEmpty()) return
            val tunnels = found.any { it.startsWith("wss://") }
            val merged = (found + urls.filter { !(tunnels && it.startsWith("wss://")) }).distinct().take(5)
            if (merged != urls) {
                urls = merged
                onAddresses?.invoke(urls)
            }
            retryMs = 1000
            main.removeCallbacks(retry)
            if (live == null && attempts.isEmpty()) connectLocked()
        }
    }

    /** Small messages (stop, the final text) go out at once instead of waiting on Nagle's algorithm. */
    private class NoDelaySocketFactory : SocketFactory() {
        private val d: SocketFactory = getDefault()
        private fun Socket.nd() = apply { tcpNoDelay = true }
        override fun createSocket(): Socket = d.createSocket().nd()
        override fun createSocket(host: String?, port: Int): Socket = d.createSocket(host, port).nd()
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
            d.createSocket(host, port, localHost, localPort).nd()
        override fun createSocket(host: InetAddress?, port: Int): Socket = d.createSocket(host, port).nd()
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            d.createSocket(address, port, localAddress, localPort).nd()
    }
}
